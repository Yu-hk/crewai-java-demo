package io.example.crew;

import io.example.crew.core.Agent;
import io.example.crew.core.AgentEvents;
import io.example.crew.core.ContextBudget;
import io.example.crew.core.Crew;
import io.example.crew.core.CrewListener;
import io.example.crew.core.CrewOutput;
import io.example.crew.core.Task;
import io.example.crew.core.TaskOutput;
import io.example.crew.llm.ChatMessage;
import io.example.crew.llm.ChatReply;
import io.example.crew.llm.LlmClient;
import io.example.crew.llm.ToolSpec;
import io.example.crew.mem.Chunker;
import io.example.crew.mem.Embedder;
import io.example.crew.mem.FileMemoryStore;
import io.example.crew.mem.HashingEmbedder;
import io.example.crew.mem.Hit;
import io.example.crew.mem.InMemoryMemoryStore;
import io.example.crew.mem.Knowledge;
import io.example.crew.mem.Memory;
import io.example.crew.mem.MemoryItem;
import io.example.crew.mem.MemoryQuery;
import io.example.crew.mem.MemoryScope;
import io.example.crew.mem.Vectors;
import io.example.crew.schema.Field;
import io.example.crew.tool.Tool;
import io.example.crew.tool.ToolRegistry;
import io.example.crew.tool.ToolSet;
import io.example.crew.util.Console;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 离线断言：验证记忆层与 Crew 集成，<b>不需要 Ollama</b>。
 *
 * <p>用 {@link HashingEmbedder} 而不是 bge-m3，理由写在那个类里：记忆层绝大多数 bug
 * 与语义质量无关，用真模型测只是让每轮多等十几秒并引入"今天答得不一样"的噪声。
 * 语义质量的结论另在 README 里用实跑数据支撑。
 *
 * <p>跑法：{@code CREW_SELFTEST=1 ./run.sh}
 */
public final class SelfTest {

    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<>();

    private SelfTest() {
    }

    public static boolean run() {
        long startedAt = System.currentTimeMillis();
        Console.title("离线断言");

        section("向量与嵌入");
        vectors();
        hashing();

        section("记忆写入与检索");
        storeBasics();
        dedup();
        scoring();
        filters();
        render();
        pinnedAndExempt();

        section("持久化");
        fileStore();

        section("分块与知识库");
        chunker();
        knowledge();

        section("Crew 集成");
        crewIntegration();
        budgetReservation();

        section("工具集与任务绑定");
        toolsets();

        long cost = System.currentTimeMillis() - startedAt;
        Console.section("结果");
        Console.bullet("通过 " + passed + " 项" + (failed == 0 ? "" : " / 失败 " + failed + " 项"));
        for (String failure : failures) {
            Console.bullet("× " + failure);
        }
        Console.last("耗时 " + Console.millis(cost));
        return failed == 0;
    }

    // ------------------------------------------------------------------ 向量

    private static void vectors() {
        float[] v = {0.1f, -0.25f, 0.333f, 0f, 1.5f};
        check("base64 往返无损", java.util.Arrays.equals(v, Vectors.decode(Vectors.encode(v))));
        check("空向量编码为空串", Vectors.encode(new float[0]).isEmpty());
        check("非法 base64 降级为空数组", Vectors.decode("!!!not-base64!!!").length == 0);

        near("相同向量余弦为 1", 1.0, Vectors.cosine(v, v), 1e-6);
        near("正交向量余弦为 0", 0.0, Vectors.cosine(new float[]{1, 0}, new float[]{0, 1}), 1e-6);
        near("反向向量余弦为 -1", -1.0, Vectors.cosine(new float[]{1, 0}, new float[]{-1, 0}), 1e-6);
        near("维度不等返回 0", 0.0, Vectors.cosine(new float[]{1, 0}, new float[]{1, 0, 0}), 1e-6);
        near("零向量返回 0", 0.0, Vectors.cosine(new float[]{0, 0}, new float[]{1, 0}), 1e-6);

        float[] normalized = Vectors.normalize(new float[]{3, 4});
        near("归一化后模长为 1", 1.0, Math.hypot(normalized[0], normalized[1]), 1e-6);
    }

    private static void hashing() {
        HashingEmbedder embedder = new HashingEmbedder();
        String text = "多智能体框架引入 Java 技术栈";
        check("同文本两次嵌入一致",
                java.util.Arrays.equals(embedder.embed(text), embedder.embed(text)));
        check("向量维度与声明一致", embedder.embed(text).length == embedder.dimension());

        float[] query = embedder.embed("多智能体框架的调试成本");
        double related = Vectors.cosine(query, embedder.embed("多智能体的框架调试代价很高"));
        double unrelated = Vectors.cosine(query, embedder.embed("番茄炒蛋要先把鸡蛋炒散"));
        check("相关文本相似度高于无关文本", related > unrelated + 0.05,
                "相关 " + fmt(related) + " / 无关 " + fmt(unrelated));
        check("无关文本相似度低于基线", unrelated <= embedder.baseline(),
                "实际 " + fmt(unrelated) + " 基线 " + fmt(embedder.baseline()));

        float[] empty = embedder.embed("");
        double sum = 0;
        for (float x : empty) {
            sum += Math.abs(x);
        }
        near("空文本嵌入为零向量", 0.0, sum, 1e-9);
    }

    // ------------------------------------------------------------------ 存储

    private static void storeBasics() {
        Memory memory = Memory.offline();
        MemoryItem item = memory.remember("用户要求所有输出必须用中文", MemoryScope.LONG_TERM);
        check("写入后条数为 1", memory.size() == 1);
        check("返回条目作用域正确", item.scope() == MemoryScope.LONG_TERM);
        check("id 为 12 位十六进制", item.id().matches("[0-9a-f]{12}"), item.id());
        check("createdAt 已填充", item.createdAt() > 0);

        memory.remember("短期的任务产出", MemoryScope.SHORT_TERM);
        check("按作用域过滤", memory.store().byScope(MemoryScope.LONG_TERM).size() == 1);
        check("全部作用域计数", memory.size() == 2);

        memory.forget(item.id());
        check("删除后条数回落", memory.size() == 1);
        memory.clear();
        check("清空后条数为 0", memory.size() == 0);

        Memory empty = Memory.offline();
        check("空库检索返回空列表而非 null",
                empty.recall(MemoryQuery.builder().text("任意查询").build()).isEmpty());
        check("空文本写入被拒绝", throwsOn(() -> empty.remember("  ", MemoryScope.SHORT_TERM)));
    }

    private static void dedup() {
        Memory memory = Memory.offline();
        memory.remember("用户要求所有输出必须用中文，不要夹杂英文术语", MemoryScope.LONG_TERM);
        memory.remember("用户要求所有输出必须用中文，不要夹杂英文术语", MemoryScope.LONG_TERM);
        check("同文本重复写入不新增条目", memory.size() == 1);

        // 语义级去重要在 bge-m3 上才准（阈值 0.95 也按它标定）。这里用哈希嵌入验证的是
        // "机制"而不是"阈值"：把阈值放到哈希嵌入能达到的区间，确认合并路径被走到。
        Memory relaxed = Memory.builder().embedder(new HashingEmbedder())
                .store(new InMemoryMemoryStore()).dedupThreshold(0.55).build();
        relaxed.remember("用户要求所有输出必须用中文，不要夹杂英文术语", MemoryScope.LONG_TERM);
        relaxed.remember("用户要求所有输出都必须用中文，不要混入英文术语", MemoryScope.LONG_TERM);
        check("达到阈值时合并而非新增", relaxed.size() == 1,
                "实际 " + relaxed.size() + " 条（阈值 0.55）");
        List<MemoryItem> merged = relaxed.store().byScope(MemoryScope.LONG_TERM);
        check("合并后 hits 大于 0", merged.get(0).hits() > 0, "hits=" + merged.get(0).hits());

        Memory strict = Memory.offline();
        strict.remember("用户要求所有输出必须用中文，不要夹杂英文术语", MemoryScope.LONG_TERM);
        strict.remember("用户要求所有输出都必须用中文，不要混入英文术语", MemoryScope.LONG_TERM);
        check("默认阈值 0.95 下措辞改写算两条", strict.size() == 2,
                "哈希嵌入的相似度达不到 0.95，这是它的能力边界，不是 bug");

        Memory other = Memory.offline();
        other.remember("多智能体框架引入 Java 技术栈", MemoryScope.LONG_TERM);
        other.remember("番茄炒蛋要先把鸡蛋炒散", MemoryScope.LONG_TERM);
        check("不同主题不合并", other.size() == 2);

        Memory noDedup = Memory.builder().embedder(new HashingEmbedder())
                .store(new InMemoryMemoryStore()).dedupThreshold(0).build();
        noDedup.remember("完全一样的句子", MemoryScope.SHORT_TERM);
        noDedup.remember("完全一样的句子", MemoryScope.SHORT_TERM);
        check("阈值 0 时同文本仍不新增（id 相同覆盖）", noDedup.size() == 1);
    }

    private static void scoring() {
        long now = System.currentTimeMillis();
        Memory memory = Memory.offline();
        memory.remember("多智能体框架引入 Java 技术栈的收益与代价", MemoryScope.SHORT_TERM,
                Map.of(), 0.4);

        Hit fresh = memory.recall(MemoryQuery.builder()
                .text("多智能体引入 Java 技术栈").topK(1).now(now).build()).get(0);
        Hit aged = memory.recall(MemoryQuery.builder()
                .text("多智能体引入 Java 技术栈").topK(1).now(now + 24 * 3600_000L).build()).get(0);
        near("短期记忆 24h 后 recency 降到 0.5", 0.5, aged.recency(), 0.01);
        check("recency 下降导致总分下降", aged.score() < fresh.score(),
                "新 " + fmt(fresh.score()) + " / 旧 " + fmt(aged.score()));

        Memory longMem = Memory.offline();
        longMem.remember("多智能体框架引入 Java 技术栈的收益与代价", MemoryScope.LONG_TERM,
                Map.of(), 0.4);
        Hit longAged = longMem.recall(MemoryQuery.builder()
                .text("多智能体引入 Java 技术栈").topK(1).now(now + 24 * 3600_000L).build()).get(0);
        near("长期记忆 24h 后 recency 仍有 0.977", 0.977, longAged.recency(), 0.01);
        check("长期记忆比短期记忆衰减慢", longAged.recency() > aged.recency());

        Memory knowledge = Memory.offline();
        knowledge.remember("多智能体框架引入 Java 技术栈的收益与代价", MemoryScope.KNOWLEDGE);
        Hit knowledgeAged = knowledge.recall(MemoryQuery.builder()
                .text("多智能体引入 Java 技术栈").topK(1)
                .now(now + 365 * 24 * 3600_000L).build()).get(0);
        near("知识块一年后 recency 仍为 1（不衰减）", 1.0, knowledgeAged.recency(), 1e-9);

        // importance 裁决：两条相关性接近时，高重要性胜出
        Memory decide = Memory.offline();
        decide.remember("把多智能体框架引入 Java 技术栈，收益是复用现有工程能力",
                MemoryScope.SHORT_TERM, Map.of(), 0.40);
        decide.remember("把多智能体框架引入 Java 技术栈，真正难点是并发与失败语义",
                MemoryScope.LONG_TERM, Map.of(), 0.90);
        List<Hit> hits = decide.recall(MemoryQuery.builder()
                .text("多智能体框架引入 Java 技术栈").topK(2).build());
        check("相关性接近时高重要性排第一",
                hits.size() >= 2 && hits.get(0).importance() > hits.get(1).importance(),
                hits.isEmpty() ? "无结果" : "top1 imp=" + fmt(hits.get(0).importance()));

        // importance 能翻盘的相关性差值上限 = wImportance * deltaImp / wRelevance
        double gain = 0.10 * (0.90 - 0.40) / 0.70;
        near("importance 可抵消的相关性差约 7%", 0.071, gain, 0.005);

        Memory importanceOf = Memory.offline();
        check("含'必须/不要'的文本重要性更高",
                io.example.crew.mem.Importance.of("所有输出必须用中文，不要夹杂英文") > 0.5);
        check("短文本重要性被压低",
                io.example.crew.mem.Importance.of("好的") < 0.3);
    }

    private static void filters() {
        Memory memory = Memory.offline();
        memory.remember("多智能体框架引入 Java 技术栈的成本分析", MemoryScope.SHORT_TERM,
                Map.of("runId", "r1"), 0.4);
        memory.remember("Java 技术栈迁移需要考虑团队既有能力与维护成本", MemoryScope.SHORT_TERM,
                Map.of("runId", "r0"), 0.4);
        memory.remember("番茄炒蛋要先把鸡蛋炒散", MemoryScope.LONG_TERM, Map.of(), 0.4);

        String query = "多智能体框架引入 Java 技术栈的成本分析";
        List<Hit> withR1 = memory.recall(MemoryQuery.builder()
                .text(query).topK(5).relativeFloor(0).build());
        check("不排除时结果含本次运行写入的条目",
                withR1.stream().anyMatch(h -> "r1".equals(h.item().meta("runId"))),
                "候选 " + withR1.size() + " 条");
        List<Hit> excluded = memory.recall(MemoryQuery.builder()
                .text(query).topK(5).excludeRunId("r1").build());
        check("excludeRunId 过滤掉指定运行",
                excluded.stream().noneMatch(h -> "r1".equals(h.item().meta("runId"))),
                "剩余 " + excluded.size() + " 条");

        List<Hit> all = memory.recall(MemoryQuery.builder().text(query).topK(5).relativeFloor(0).build());
        List<Hit> top1 = memory.recall(MemoryQuery.builder().text(query).topK(1).build());
        check("topK 限制生效", top1.size() == 1);

        List<Hit> floor = memory.recall(MemoryQuery.builder()
                .text(query).topK(5).relativeFloor(0.9).build());
        check("relativeFloor 截断生效",
                floor.size() < all.size() && floor.size() >= 1,
                "不截断 " + all.size() + " 条 / 截断后 " + floor.size() + " 条");

        List<Hit> noise = memory.recall(MemoryQuery.builder()
                .text("quantum chromodynamics lattice").topK(5).build());
        check("无关查询被噪声地板挡住", noise.isEmpty(), "实际 " + noise.size() + " 条");

        List<Hit> scoped = memory.recall(MemoryQuery.builder()
                .text(query).topK(5).scopes(MemoryScope.LONG_TERM).build());
        check("作用域限定生效",
                scoped.stream().allMatch(h -> h.item().scope() == MemoryScope.LONG_TERM));
    }

    private static void render() {
        Memory memory = Memory.offline();
        memory.remember("多智能体框架引入 Java 技术栈的收益与代价", MemoryScope.SHORT_TERM);
        List<Hit> hits = memory.recall(MemoryQuery.builder()
                .text("多智能体引入 Java 技术栈").topK(3).build());
        String block = memory.render(hits, 600);
        check("渲染结果非空", !block.isBlank());
        check("渲染含'相关记忆'标记", block.contains("【相关记忆】"));
        check("渲染含忽略许可", block.contains("忽略"),
                "缺少'无关请忽略'会让小模型强行套用无关记忆");
        check("渲染含作用域标签", block.contains("[短期"));

        String tiny = memory.render(hits, 60);
        check("maxChars 过小时不输出条目", tiny.length() <= 120, "实际 " + tiny.length() + " 字符");
        check("空结果渲染为空串", memory.render(List.of(), 600).isEmpty());
    }

    /** 常驻记忆与重要性豁免：两类"字面不相关但必须生效"的信息。 */
    private static void pinnedAndExempt() {
        Memory memory = Memory.offline();
        memory.rememberPinned("输出规范：全篇不超过 200 字，不要写「本文将」这类套话",
                MemoryScope.LONG_TERM, 0.85);
        memory.rememberPinned("语气要求：结论先行，先给判断再给理由",
                MemoryScope.LONG_TERM, 0.75);
        check("常驻记忆条数正确", memory.pinnedCount() == 2);

        String block = memory.pinnedBlock(600);
        check("常驻块含'必须遵守'", block.contains("【必须遵守的约定】"));
        check("常驻块含条目内容", block.contains("不超过 200 字"));
        check("常驻块按重要性降序",
                block.indexOf("输出规范") < block.indexOf("语气要求"),
                "重要性 0.85 的应排在 0.75 之前");

        // 常驻记忆默认不参与检索——否则会与 pinnedBlock 重复注入
        List<Hit> recalled = memory.recall(MemoryQuery.builder()
                .text("多智能体框架引入 Java 技术栈").topK(5).build());
        check("常驻记忆默认不进检索结果", recalled.isEmpty(),
                "实际 " + recalled.size() + " 条");
        List<Hit> withPinned = memory.recall(MemoryQuery.builder()
                .text("全篇不超过多少字，要不要写套话").topK(5)
                .includePinned(true).relativeFloor(0).build());
        check("includePinned 可纳入检索", !withPinned.isEmpty(),
                "实际 " + withPinned.size() + " 条");

        // 重要性豁免：低相关但高重要性，能越过相对截断。
        // 这里把基线压到 0（见 BaselineEmbedder 的说明），是为了单独验证"豁免通道"
        // 本身是否通畅，不被哈希嵌入器的相关性能力干扰。
        Memory exempt = Memory.builder().embedder(new BaselineEmbedder(new HashingEmbedder(), 0))
                .store(new InMemoryMemoryStore()).build();
        exempt.remember("分析多智能体框架引入 Java 技术栈的成本", MemoryScope.SHORT_TERM,
                Map.of(), 0.40);
        exempt.remember("踩坑：同时要求输出 JSON 与调用工具时模型会编数字，必须把顺序写死",
                MemoryScope.LONG_TERM, Map.of(), 0.90);
        List<Hit> hits = exempt.recall(MemoryQuery.builder()
                .text("分析多智能体框架引入 Java 技术栈的成本").topK(4).build());
        boolean exemptPresent = hits.stream().anyMatch(h -> h.item().importance() >= 0.9);
        check("高重要性低相关条目被豁免召回", exemptPresent,
                "召回 " + hits.size() + " 条：" + hits.stream().map(h -> String.format("%.2f/%.2f",
                        h.relevance(), h.importance())).toList());
        check("豁免条目排在语义相关条目之后",
                hits.size() >= 2 && hits.get(hits.size() - 1).importance() >= 0.9,
                "豁免条目分数天然低，不应抢到第一位");

        // 豁免限额：不能把所有高重要性条目都塞进去
        Memory many = Memory.builder().embedder(new BaselineEmbedder(new HashingEmbedder(), 0))
                .store(new InMemoryMemoryStore()).build();
        many.remember("分析多智能体框架引入 Java 技术栈的成本", MemoryScope.SHORT_TERM,
                Map.of(), 0.40);
        for (int i = 0; i < 6; i++) {
            many.remember("重要教训第" + i + "条：这里必须注意不能乱来",
                    MemoryScope.LONG_TERM, Map.of(), 0.90);
        }
        List<Hit> limited = many.recall(MemoryQuery.builder()
                .text("分析多智能体框架引入 Java 技术栈的成本").topK(4).build());
        long exemptCount = limited.stream().filter(h -> h.item().importance() >= 0.9).count();
        check("豁免条数受 topK/2 限制", exemptCount <= 2,
                "实际豁免 " + exemptCount + " 条，topK=4 时上限为 2");
        check("豁免不会挤掉全部语义相关结果",
                limited.stream().anyMatch(h -> h.item().importance() < 0.9),
                "召回 " + limited.size() + " 条");
    }

    /**
     * 可以指定基线的嵌入器包装。
     *
     * <p>用途：把"豁免通道是否通畅"和"嵌入器能给出多高的相关性"这两件事拆开测。
     * 哈希嵌入对语义改写不敏感，低相关文本的余弦经常低于它自己的无关基线，
     * 于是豁免条件（relevance ≥ baseline）先失败了，测不到后面的逻辑。
     * 把基线压到 0，豁免路径就能被单独覆盖。
     */
    private record BaselineEmbedder(Embedder delegate, double baseline) implements Embedder {

        @Override
        public float[] embed(String text) {
            return delegate.embed(text);
        }

        @Override
        public int dimension() {
            return delegate.dimension();
        }

        @Override
        public String model() {
            return delegate.model() + "+baseline" + baseline;
        }
    }

    // ------------------------------------------------------------------ 持久化

    private static void fileStore() {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("crew-mem-test");
            Path file = dir.resolve("mem.jsonl");

            Memory first = Memory.builder().embedder(new HashingEmbedder())
                    .store(new FileMemoryStore(file)).build();
            first.remember("跨进程保留的结论：调试成本是主要代价", MemoryScope.LONG_TERM,
                    Map.of("runId", "r0"), 0.8);
            first.remember("短期产出", MemoryScope.SHORT_TERM);
            first.close();

            check("存档文件已创建", Files.exists(file));

            FileMemoryStore reopened = new FileMemoryStore(file);
            check("重开后条数一致", reopened.size() == 2, "实际 " + reopened.size());
            check("重开后无解析错误", reopened.loadErrors().isEmpty(),
                    String.join("; ", reopened.loadErrors()));

            Optional<MemoryItem> restored = reopened.all().stream()
                    .filter(i -> i.scope() == MemoryScope.LONG_TERM).findFirst();
            check("作用域还原", restored.isPresent());
            if (restored.isPresent()) {
                MemoryItem item = restored.get();
                check("文本还原", item.text().contains("调试成本"));
                check("元数据还原", "r0".equals(item.meta("runId")));
                near("重要性还原", 0.8, item.importance(), 1e-9);
                check("向量维度还原", item.embedding().length == new HashingEmbedder().dimension());
                check("向量非零（base64 无损）", Math.abs(item.embedding()[0]) >= 0);
            }

            // 用重开后的库做检索，验证"存档里的向量能直接参与打分"
            Memory again = Memory.builder().embedder(new HashingEmbedder())
                    .store(reopened).build();
            List<Hit> hits = again.recall(MemoryQuery.builder()
                    .text("调试成本是主要代价吗").topK(3).build());
            check("重开后的库能检索到旧记忆", !hits.isEmpty(),
                    "若为空说明向量没有正确往返");

            // 坏行容错
            Files.writeString(file, "{这不是合法 json}\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
            FileMemoryStore tolerant = new FileMemoryStore(file);
            check("坏行被跳过而非崩溃", tolerant.size() == 2);
            check("坏行被记录", tolerant.loadErrors().size() == 1,
                    "实际 " + tolerant.loadErrors().size());
        } catch (IOException e) {
            check("文件存储用例执行", false, e.getMessage());
        } finally {
            deleteQuietly(dir);
        }
    }

    // ------------------------------------------------------------------ 分块

    private static void chunker() {
        check("空文本切出 0 块", Chunker.split("").isEmpty());
        check("空白文本切出 0 块", Chunker.split("   \n\n  ").isEmpty());

        String shortText = "只有一句话。";
        check("短文本切出 1 块", Chunker.split(shortText, 400, 60).size() == 1);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("这是第").append(i).append("句用于测试分块的内容，它需要有足够的长度。");
        }
        String longText = sb.toString();
        List<String> chunks = Chunker.split(longText, 200, 40);
        check("长文本切出多块", chunks.size() > 5, "实际 " + chunks.size() + " 块");
        boolean withinLimit = chunks.stream().allMatch(c -> c.length() <= 200);
        check("每块不超过上限", withinLimit,
                "最长 " + chunks.stream().mapToInt(String::length).max().orElse(0));

        List<Chunker.Chunk> withOffsets = Chunker.splitWithOffsets(longText, 200, 40);
        check("块偏移量递增",
                withOffsets.get(1).start() < withOffsets.get(1).end()
                        && withOffsets.get(1).start() > withOffsets.get(0).start(),
                "相邻块应重叠且前进");
        check("重叠生效", withOffsets.get(1).start() < withOffsets.get(0).end(),
                "第二块起点 " + withOffsets.get(1).start() + " 应早于第一块终点 "
                        + withOffsets.get(0).end());

        // 无标点的长文本不能死循环
        String noPunctuation = "a".repeat(1000);
        check("无标点文本能正常切分", Chunker.split(noPunctuation, 100, 20).size() >= 10);

        // 代码围栏保护：切分不应把代码块拦腰截断
        String withFence = "说明文字。".repeat(38)
                + "\n```java\nint a = compute();\nreturn a;\n```\n"
                + "收尾文字。".repeat(38);
        List<Chunker.Chunk> fenceChunks = Chunker.splitWithOffsets(withFence, 200, 0);
        boolean fenceIntact = fenceChunks.stream().anyMatch(c -> {
            int open = c.text().indexOf("```java");
            return open >= 0 && c.text().indexOf("```", open + 7) > 0;
        });
        check("代码围栏不会被拦腰截断", fenceIntact,
                "块数 " + fenceChunks.size() + "，含围栏的块："
                        + fenceChunks.stream().filter(c -> c.text().contains("```")).count());

        String mdText = """
                ## 第一节
                这一节讲的是甲。它有若干句子。

                ## 第二节
                这一节讲的是乙。它也有若干句子。

                ### 2.1 子节
                子节内容在这里。
                """;
        List<String> mdChunks = Chunker.splitMarkdown(mdText, 400);
        check("Markdown 分块非空", !mdChunks.isEmpty());
        check("Markdown 块保留标题", mdChunks.get(0).startsWith("## 第一节"),
                mdChunks.isEmpty() ? "空" : mdChunks.get(0).substring(0, Math.min(20, mdChunks.get(0).length())));
        check("Markdown 分块不切碎短节", mdChunks.size() <= 2,
                "实际 " + mdChunks.size() + " 块（短节应被合并）");
    }

    private static void knowledge() {
        Memory memory = Memory.offline();
        Knowledge knowledge = Knowledge.builder("测试文档", memory).chunkSize(150).overlap(30).build();

        int chunks = knowledge.index("""
                上下文预算是指注入提示词的历史材料总量。超出预算时必须压缩。
                压缩策略是保留最近的原文，把更早的产出概括成要点。

                记忆检索与上下文预算解决的是两件事。记忆负责从全量历史里挑出相关的那几条，
                预算负责把挑出来的那几条塞进模型窗口。

                分块大小影响检索质量。块太大向量会变成平均语义，块太小则半句话无法匹配。
                """);
        check("文档被切成多块", chunks >= 2, "实际 " + chunks + " 块");
        check("chunks() 与实际一致", knowledge.chunks() == chunks);

        List<Hit> hits = knowledge.retrieve("上下文预算不足时怎么压缩早期产出", 3);
        check("知识检索有命中", !hits.isEmpty());
        if (!hits.isEmpty()) {
            check("命中块与查询语义相关",
                    hits.get(0).item().text().contains("预算")
                            || hits.get(0).item().text().contains("压缩"),
                    hits.get(0).item().summary(40));
            check("命中块带来源元数据", "测试文档".equals(hits.get(0).item().meta("source")));
        }

        String block = knowledge.block("上下文预算不足时怎么压缩早期产出", 2, 800);
        check("渲染含参考资料标记", block.contains("【参考资料】"));
        check("渲染含来源与块号", block.contains("来源 测试文档"));

        int before = knowledge.chunks();
        knowledge.index("完全换了一份内容，讲的是别的事情。");
        check("重新索引不累积旧块", knowledge.chunks() < before + 1,
                "重索引前 " + before + " 块，之后 " + knowledge.chunks() + " 块");

        Knowledge other = Knowledge.builder("另一份", memory).build();
        other.index("这是一份独立文档。");
        check("不同来源互不清理", other.chunks() == 1);
    }

    // ------------------------------------------------------------------ Crew

    private static void crewIntegration() {
        Memory memory = Memory.offline();
        RecordingLlm llm = new RecordingLlm(prompt -> "要点一。要点二。");

        String firstRun = runCrew(memory, llm, "分析多智能体框架引入 Java 技术栈的收益");
        check("首次运行：无历史记忆可召回",
                !firstRun.contains("【相关记忆】"),
                "空库不应该产出记忆段");
        check("任务产出写入记忆", memory.size() >= 1, "实际 " + memory.size() + " 条");
        check("写入的是短期记忆",
                memory.store().all().stream().anyMatch(i -> i.scope() == MemoryScope.SHORT_TERM));
        check("写入带 runId 元数据",
                memory.store().all().stream().anyMatch(i -> i.meta("runId") != null));

        llm.reset();
        String secondRun = runCrew(memory, llm, "分析多智能体框架引入 Java 技术栈的收益");
        check("第二次运行：召回历史记忆",
                secondRun.contains("【相关记忆】"),
                "同样的任务描述应该召回上一次的产出");
        check("召回内容含作用域标签", secondRun.contains("[短期"));
        check("召回内容带忽略许可", secondRun.contains("忽略"));

        // 同一次运行内不应召回自己刚写的
        Memory isolated = Memory.offline();
        RecordingLlm llm2 = new RecordingLlm(prompt -> "产出内容。");
        runTwoTasks(isolated, llm2);
        List<String> prompts = llm2.prompts();
        check("同一次运行内第二个任务能用到第一个任务的记忆",
                prompts.size() >= 2,
                "第二个任务的 prompt 数 " + prompts.size());
        if (prompts.size() >= 2) {
            check("第二个任务未把第一个任务的产出当历史记忆重复注入",
                    !prompts.get(1).contains("【相关记忆】"),
                    "本次运行的产出已在前序上下文里，重复注入是 token 浪费");
        }

        // 未挂记忆时不应出现记忆段
        RecordingLlm plain = new RecordingLlm(prompt -> "产出。");
        Agent agent = Agent.builder().role("测试").goal("产出内容").backstory("背景")
                .llm(plain).build();
        Task task = Task.builder().label("单任务").description("随便写点什么").agent(agent).build();
        Crew.builder().name("无记忆").agents(agent).tasks(task).build()
                .kickoff(Map.of(), new CrewListener() {
                });
        check("未挂记忆时 prompt 不含记忆段",
                plain.prompts().stream().noneMatch(p -> p.contains("【相关记忆】")));

        // 失败教训写入记忆
        Memory failing = Memory.offline();
        LlmClient broken = new LlmClient() {
            @Override
            public ChatReply chat(List<ChatMessage> messages, double temperature, List<ToolSpec> tools) {
                throw new IllegalStateException("模型不可用");
            }

            @Override
            public String model() {
                return "broken";
            }

            @Override
            public Usage usage() {
                return new Usage(0, 0, 0);
            }
        };
        Agent failingAgent = Agent.builder().role("测试").goal("产出").backstory("背景")
                .llm(broken).build();
        Task failingTask = Task.builder().label("会失败的任务")
                .description("这个任务注定失败").agent(failingAgent).build();
        try {
            Crew.builder().name("失败用例").agents(failingAgent).tasks(failingTask)
                    .memory(failing).build().kickoff(Map.of(), new CrewListener() {
                    });
            check("失败任务应抛异常", false);
        } catch (RuntimeException expected) {
            boolean recorded = failing.store().all().stream()
                    .anyMatch(i -> "task-failure".equals(i.meta("kind")));
            check("失败教训被写入记忆", recorded,
                    "记忆里应有 kind=task-failure 的条目");
        }

        Memory.Stats stats = memory.stats();
        check("统计总数与库一致", stats.total() == memory.size());
        check("统计报告非空", !stats.report().isBlank());
    }

    /**
     * 工具集与任务绑定。
     *
     * <p>重点不在集合运算，而在"任务绑定之后，模型实际看到的东西是否跟着变"。
     * 只改注册表而不改提示词、只改提示词而不改 tools[] 声明，都会产生
     * "模型照提示词去调一个不存在的工具"这类难查的问题。
     */
    private static void toolsets() {
        Tool alpha = new FakeTool("alpha");
        Tool beta = new FakeTool("beta");
        // 模拟 MCP：暴露名带服务端前缀，别名是原始名
        Tool read = new FakeTool("fs__read_file", "read_file", "fs/read_file");

        ToolSet base = ToolSet.of("基础", alpha, beta, read);
        check("工具集统计正确", base.size() == 3
                        && base.toolNames().equals(List.of("alpha", "beta", "fs__read_file")),
                base.toolNames().toString());

        ToolSet subset = base.only("alpha");
        check("only 精确挑选", subset.toolNames().equals(List.of("alpha")), subset.toolNames().toString());
        check("only 返回新实例，原集合不受影响", base.size() == 3, "原集合 " + base.size() + " 个");
        check("only 支持别名（配置不必知道前缀）",
                base.only("read_file").toolNames().equals(List.of("fs__read_file")),
                base.only("read_file").toolNames().toString());
        check("only 未命中直接失败",
                raises(() -> base.only("nope"), "工具集中不存在"));

        check("exclude 生效",
                base.exclude("beta").toolNames().equals(List.of("alpha", "fs__read_file")));
        check("exclude 未命中直接失败", raises(() -> base.exclude("nope"), "无法排除"));

        // 真实场景是"本地计算器 + 远端 MCP 工具"合并，来源必须能同时体现
        ToolSet remote = ToolSet.from("远端组", "mcp:demo", List.of(new FakeTool("gamma")));
        ToolSet merged = base.merge(remote);
        check("merge 合并两组", merged.size() == 4, merged.toolNames().toString());
        check("merge 记录多个来源", merged.origin().equals("local+mcp:demo"), merged.origin());
        check("同源合并不产生重复标注",
                base.merge(ToolSet.of("另一组", new FakeTool("delta"))).origin().equals("local"));
        check("merge 重名冲突失败而非覆盖",
                raises(() -> base.merge(ToolSet.of("重复", new FakeTool("alpha"))), "工具名冲突"));

        ToolSet renamed = subset.named("趋势分析·计算");
        check("named 只改展示名，内容不变",
                renamed.name().equals("趋势分析·计算") && renamed.size() == subset.size());

        ToolSet emptySet = ToolSet.empty("无工具");
        check("空工具集是合法值（不是 null 语义）", emptySet.isEmpty() && emptySet.toolNames().isEmpty());

        // 子集不得重新生成 Schema：MCP 的 Schema 由服务端拥有，重算等于换了一份契约
        String originalSchema = base.registry().specs().stream()
                .filter(s -> s.name().equals("alpha")).findFirst().orElseThrow().parametersJson();
        String subsetSchema = subset.registry().specs().get(0).parametersJson();
        check("子集沿用原 Schema，不重新生成",
                originalSchema.equals(subsetSchema), subsetSchema);
        check("子集拒绝分发集外工具",
                subset.registry().invoke("beta", Map.of()).startsWith("ERROR"),
                subset.registry().invoke("beta", Map.of()));

        // ---- 任务绑定：模型实际收到的声明 ----
        RecordingLlm llm = new RecordingLlm(prompt -> "产出。", true);
        Agent agent = Agent.builder()
                .role("测试员").goal("验证工具可见性").backstory("你只做一件事：验证。")
                .llm(llm)
                .tools(ToolRegistry.of(alpha, beta))
                .build();

        Task inherit = Task.builder().label("继承").description("第一个任务").agent(agent).build();
        Task narrowed = Task.builder().label("收窄").description("第二个任务").agent(agent)
                .toolset(ToolSet.of("收窄集", alpha)).build();
        Task noTools = Task.builder().label("无工具").description("第三个任务").agent(agent)
                .toolset(ToolSet.empty("禁用工具")).build();
        Task inheritAgain = Task.builder().label("再继承").description("第四个任务").agent(agent).build();

        Crew crew = Crew.builder()
                .name("工具集断言")
                .agents(agent)
                .tasks(inherit, narrowed, noTools, inheritAgain)
                .build();
        crew.kickoff(Map.of());

        check("四个任务各调用一次模型", llm.prompts().size() == 4,
                "实际 " + llm.prompts().size() + " 次，提示词：" + llm.prompts());
        if (llm.prompts().size() == 4) {
            check("未绑定时继承 Agent 的全部工具",
                    llm.specNamesAt(0).equals(List.of("alpha", "beta")),
                    llm.specNamesAt(0).toString());
            check("绑定时只下发任务工具集",
                    llm.specNamesAt(1).equals(List.of("alpha")),
                    llm.specNamesAt(1).toString());
            check("绑定空集时不下发任何声明",
                    llm.specNamesAt(2).isEmpty(), llm.specNamesAt(2).toString());
            check("空集不回退到 Agent 工具",
                    !llm.specNamesAt(2).contains("alpha"),
                    "空集若回退，模型会拿到它本不该用的工具");
            check("上一个任务的收窄不影响下一个任务",
                    llm.specNamesAt(3).equals(List.of("alpha", "beta")),
                    llm.specNamesAt(3).toString());

            // 三处一致：系统提示词里列的工具 == 下发的声明
            String narrowedSystem = llm.systemAt(1);
            check("提示词工具清单与声明一致（有 alpha）", narrowedSystem.contains("alpha"));
            check("提示词里不出现已被排除的 beta",
                    !narrowedSystem.contains("- beta"),
                    "提示词与声明不一致时，模型会去调一个没有声明的工具");
            String emptySystem = llm.systemAt(2);
            check("空集时提示词不提工具", !emptySystem.contains("可用工具"),
                    "不该对一个没有工具的任务讲工具协议");
            check("空集时提示词不提 [TOOL] 协议", !emptySystem.contains("[TOOL]"));
        }

        // 结构化输出 + 空工具集：提示词不能要求"先调工具再填 JSON"
        Task structured = Task.builder().label("结构化").description("输出结构").agent(agent)
                .outputType(Dummy.class)
                .toolset(ToolSet.empty("禁用工具"))
                .build();
        String structuredPrompt = structured.buildPrompt(Map.of(), "", "", null,
                io.example.crew.schema.Structure.of(Dummy.class), false);
        check("空工具集时结构化提示不要求先调工具",
                !structuredPrompt.contains("必须先调用工具"),
                "任务没有工具却要求调工具，模型只能编一个数字");

        // 同一 Agent 交替使用不同工具集，字段不应被写回
        check("工具集是运行期参数，不写回 Agent",
                agent.registry().names().equals(List.of("alpha", "beta")),
                agent.registry().names().toString());
    }

    /** 期望抛出包含指定文字的 IllegalArgumentException。 */
    private static boolean raises(Runnable action, String expectedFragment) {
        try {
            action.run();
            return false;
        } catch (IllegalArgumentException e) {
            return e.getMessage() != null && e.getMessage().contains(expectedFragment);
        }
    }

    /** 最小工具实现：只为验证集合与分发，不做真实工作。 */
    private static final class FakeTool implements Tool {

        private static final String SCHEMA =
                "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}";

        private final String name;
        private final List<String> aliases;

        private FakeTool(String name, String... aliases) {
            this.name = name;
            this.aliases = List.of(aliases);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "假工具 " + name;
        }

        @Override
        public List<String> aliases() {
            return aliases;
        }

        @Override
        public String parametersJson() {
            return SCHEMA;
        }

        @Override
        public String invoke(Map<String, Object> arguments) {
            return name + ":ok";
        }
    }

    /** 结构化输出契约的最小 POJO，仅用于验证提示词分支。 */
    public static final class Dummy {

        @Field(desc = "一句话结论")
        public String summary;
    }

    private static void budgetReservation() {
        List<TaskOutput> outputs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Agent agent = Agent.builder().role("r" + i).goal("g").backstory("b")
                    .llm(fakeLlm("x")).build();
            Task task = Task.builder().label("任务" + i).description("d" + i).agent(agent).build();
            outputs.add(new TaskOutput(task, "内容".repeat(200), "r" + i, 1L, 1, null, List.of()));
        }
        ContextBudget budget = new ContextBudget(2000);
        ContextBudget.Result full = budget.assemble(outputs, null, 0);
        // 预留 1100 后预算剩 900，recentBudget=450 仍能容纳最后一条（约 440 tok），
        // 于是走"压缩更早产出"分支而不是"截断最后一条"——两者是不同路径，都要覆盖。
        ContextBudget.Result reserved = budget.assemble(outputs, null, 1100);
        check("预留 0 时上下文未压缩", !full.compressed(),
                "总量约 " + full.tokens() + " tok");
        check("预留 1100 tok 后上下文被压缩", reserved.compressed(),
                "压缩后 " + reserved.tokens() + " tok");
        check("压缩后备注说明了预留来源",
                reserved.note() != null && reserved.note().contains("预留"),
                String.valueOf(reserved.note()));
        check("压缩后备注说明了超预算原因",
                reserved.note() != null && reserved.note().contains("上下文超预算"),
                String.valueOf(reserved.note()));

        ContextBudget.Result starved = budget.assemble(outputs, null, 1900);
        check("预留过大时退化为截断最后一条", starved.compressed());
        check("截断分支同样说明预留来源",
                starved.note() != null && starved.note().contains("预留"),
                String.valueOf(starved.note()));
    }

    // ------------------------------------------------------------------ 辅助

    private static String runCrew(Memory memory, RecordingLlm llm, String description) {
        Agent agent = Agent.builder().role("研究员").goal("产出要点").backstory("背景")
                .llm(llm).build();
        Task task = Task.builder().label("要点整理").description(description)
                .expectedOutput("三行要点").agent(agent).build();
        Crew crew = Crew.builder().name("单任务").agents(agent).tasks(task)
                .memory(memory).build();
        crew.kickoff(Map.of(), new CrewListener() {
        });
        return llm.last();
    }

    private static void runTwoTasks(Memory memory, RecordingLlm llm) {
        Agent agent = Agent.builder().role("研究员").goal("产出").backstory("背景")
                .llm(llm).build();
        Task first = Task.builder().label("第一步")
                .description("分析多智能体框架引入 Java 技术栈的收益")
                .agent(agent).build();
        Task second = Task.builder().label("第二步")
                .description("分析多智能体框架引入 Java 技术栈的代价")
                .agent(agent).build();
        Crew.builder().name("两任务").agents(agent).tasks(first, second)
                .memory(memory).build().kickoff(Map.of(), new CrewListener() {
        });
    }

    private static LlmClient fakeLlm(String reply) {
        return new LlmClient() {
            @Override
            public ChatReply chat(List<ChatMessage> messages, double temperature, List<ToolSpec> tools) {
                return ChatReply.text(reply);
            }

            @Override
            public String model() {
                return "fake";
            }

            @Override
            public Usage usage() {
                return new Usage(0, 0, 0);
            }
        };
    }

    /** 记录每次收到的用户消息，用于断言"记忆是否真的进了提示词"。 */
    private static final class RecordingLlm implements LlmClient {
        private final Function<String, String> responder;
        private final boolean nativeTools;
        private final List<String> prompts = new ArrayList<>();
        private final List<String> systems = new ArrayList<>();
        private final List<List<ToolSpec>> specCalls = new ArrayList<>();

        private RecordingLlm(Function<String, String> responder) {
            this(responder, false);
        }

        /** {@code nativeTools=true} 时 {@code Agent} 会走原生协议，从而真的下发 {@code tools[]}。 */
        private RecordingLlm(Function<String, String> responder, boolean nativeTools) {
            this.responder = responder;
            this.nativeTools = nativeTools;
        }

        @Override
        public ChatReply chat(List<ChatMessage> messages, double temperature, List<ToolSpec> tools) {
            String user = messages.stream()
                    .filter(m -> "user".equals(m.role()))
                    .reduce((a, b) -> b)
                    .map(ChatMessage::content)
                    .orElse("");
            String system = messages.stream()
                    .filter(m -> "system".equals(m.role()))
                    .map(ChatMessage::content)
                    .findFirst()
                    .orElse("");
            prompts.add(user);
            systems.add(system);
            specCalls.add(List.copyOf(tools));
            return ChatReply.text(responder.apply(user));
        }

        @Override
        public boolean supportsNativeTools() {
            return nativeTools;
        }

        @Override
        public String model() {
            return "recording";
        }

        @Override
        public Usage usage() {
            return new Usage(0, 0, 0);
        }

        String last() {
            return prompts.isEmpty() ? "" : prompts.get(prompts.size() - 1);
        }

        String lastSystem() {
            return systems.isEmpty() ? "" : systems.get(systems.size() - 1);
        }

        /** 最近一次调用实际下发的工具声明名。 */
        List<String> lastSpecNames() {
            if (specCalls.isEmpty()) {
                return List.of();
            }
            return specCalls.get(specCalls.size() - 1).stream().map(ToolSpec::name).toList();
        }

        /** 第 index 次调用下发的工具声明名；越界返回空列表。 */
        List<String> specNamesAt(int index) {
            if (index < 0 || index >= specCalls.size()) {
                return List.of();
            }
            return specCalls.get(index).stream().map(ToolSpec::name).toList();
        }

        String systemAt(int index) {
            return index < 0 || index >= systems.size() ? "" : systems.get(index);
        }

        List<String> prompts() {
            return List.copyOf(prompts);
        }

        void reset() {
            prompts.clear();
            systems.clear();
            specCalls.clear();
        }
    }

    private static void section(String name) {
        Console.section(name);
    }

    private static void check(String name, boolean ok) {
        check(name, ok, null);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println(Console.green("  ✓ ") + name);
        } else {
            failed++;
            String line = name + (detail == null ? "" : "（" + detail + "）");
            failures.add(line);
            System.out.println(Console.yellow("  ✗ ") + line);
        }
    }

    private static void near(String name, double expected, double actual, double tolerance) {
        check(name, Math.abs(expected - actual) <= tolerance,
                "期望 " + fmt(expected) + " 实际 " + fmt(actual));
    }

    private static boolean throwsOn(Runnable action) {
        try {
            action.run();
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static String fmt(double v) {
        return String.format("%.4f", v);
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响断言结论
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
