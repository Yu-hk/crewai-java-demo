package io.example.crew;

import io.example.crew.core.Agent;
import io.example.crew.core.ConsoleListener;
import io.example.crew.core.Crew;
import io.example.crew.core.CrewException;
import io.example.crew.core.CrewListener;
import io.example.crew.core.CrewOutput;
import io.example.crew.core.FileRunStore;
import io.example.crew.core.Guardrails;
import io.example.crew.core.MetricsListener;
import io.example.crew.core.Process;
import io.example.crew.core.RunStore;
import io.example.crew.core.Task;
import io.example.crew.core.TaskOutput;
import io.example.crew.flow.CrewNode;
import io.example.crew.flow.DagException;
import io.example.crew.flow.DagResult;
import io.example.crew.flow.SimpleDag;
import io.example.crew.llm.LlmClient;
import io.example.crew.llm.OllamaClient;
import io.example.crew.llm.ResilientLlmClient;
import io.example.crew.mcp.McpServer;
import io.example.crew.mcp.McpServerConfig;
import io.example.crew.mem.Embedder;
import io.example.crew.mem.FileMemoryStore;
import io.example.crew.mem.HashingEmbedder;
import io.example.crew.mem.Hit;
import io.example.crew.mem.InMemoryMemoryStore;
import io.example.crew.mem.Knowledge;
import io.example.crew.mem.MemException;
import io.example.crew.mem.Memory;
import io.example.crew.mem.MemoryQuery;
import io.example.crew.mem.MemoryScope;
import io.example.crew.mem.MemoryStore;
import io.example.crew.mem.OllamaEmbedder;
import io.example.crew.schema.Field;
import io.example.crew.tool.CalculatorTool;
import io.example.crew.tool.Tool;
import io.example.crew.tool.ToolRegistry;
import io.example.crew.tool.ToolSet;
import io.example.crew.util.Console;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 示例：三智能体的"研究 → 分析 → 成文"流水线。
 *
 * <p>演示了 P0 的五项能力（结构化输出契约、原生 function calling、上下文预算、
 * Guardrails、重试与限流），P1 的三项（状态持久化与断点续跑、事件钩子、DAG 桥接），
 * P2 的 MCP 工具挂载，以及 P3 的记忆与知识库。
 *
 * <pre>
 *   ./run.sh                                      # 顺序流程
 *   CREW_PROCESS=hierarchical ./run.sh            # 叠加主管评审
 *   CREW_NATIVE_TOOLS=0 ./run.sh                  # 强制文本协议，用于对比
 *
 *   CREW_RUN_ID=demo CREW_ABORT_AT=2 ./run.sh     # 第 2 步前中断，成果落盘
 *   CREW_RUN_ID=demo ./run.sh                     # 续跑：复用第 1 步，不再花 token
 *   CREW_DAG=1 ./run.sh                           # 把 Crew 嵌进编排图，前后接代码节点
 *   CREW_MCP=demo ./run.sh                        # 把 MCP 服务端的工具挂到写作 Agent 上
 *
 *   CREW_MEMORY=1 CREW_SEED=1 ./run.sh            # 预置常驻约定与长期教训，跑一轮
 *   CREW_MEMORY=1 ./run.sh                        # 再跑：跨进程召回上一轮产出
 *   CREW_MEMORY=1 CREW_KNOWLEDGE=1 ./run.sh       # 把 README 索引进知识库
 *   CREW_SELFTEST=1 ./run.sh                      # 113 项离线断言，不需要 Ollama
 * </pre>
 */
public final class Main {

    private static final String TOPIC = "把多智能体框架引入 Java 技术栈的收益与代价";

    private Main() {
    }

    /**
     * 趋势分析任务的结构化输出契约。
     *
     * <p>这就是 {@code output_pydantic} 的 Java 等价物：字段声明即契约，
     * 反射生成 JSON Schema 注入提示词，产出按此校验并能直接绑定成对象。
     */
    public static final class TrendAnalysis {

        @Field(desc = "两条可验证的趋势判断，每条不超过 50 字")
        public List<String> judgments;

        @Field(desc = "调用次数相比单次大模型调用增长的百分比，只填数字，例如 500")
        public double increasePercent;

        @Field(desc = "一句话结论", maxChars = 40, optional = true)
        public String summary;

        @Override
        public String toString() {
            int count = judgments == null ? 0 : judgments.size();
            return "判断 " + count + " 条 · 增幅 " + trim(increasePercent) + "%"
                    + (summary == null || summary.isBlank() ? "" : " · " + summary);
        }

        private static String trim(double value) {
            return Math.abs(value - Math.rint(value)) < 1e-9
                    ? String.valueOf((long) Math.rint(value))
                    : String.valueOf(value);
        }
    }

    public static void main(String[] args) {
        // 离线断言：不连模型、不需要 Ollama，用于验证记忆层协议细节
        if ("1".equals(env("CREW_SELFTEST", "0"))
                || (args.length > 0 && "selftest".equalsIgnoreCase(args[0]))) {
            System.exit(SelfTest.run() ? 0 : 1);
        }

        String baseUrl = env("OLLAMA_HOST", "http://127.0.0.1:11434");
        String model = args.length > 0 && !args[0].isBlank() ? args[0] : env("CREW_MODEL", "qwen2.5:7b");
        Process process = "hierarchical".equalsIgnoreCase(env("CREW_PROCESS", "sequential"))
                ? Process.HIERARCHICAL
                : Process.SEQUENTIAL;
        double temperature = Double.parseDouble(env("CREW_TEMPERATURE", "0.4"));
        boolean debug = "1".equals(env("CREW_DEBUG", "0"));
        boolean nativeTools = !"0".equals(env("CREW_NATIVE_TOOLS", "1"));
        int maxRpm = Integer.parseInt(env("CREW_MAX_RPM", "60"));
        int contextTokens = Integer.parseInt(env("CREW_CONTEXT_TOKENS", "2500"));

        String runId = env("CREW_RUN_ID", null);
        int abortAt = Integer.parseInt(env("CREW_ABORT_AT", "0"));
        boolean viaDag = "1".equals(env("CREW_DAG", "0"));
        String mcpTarget = env("CREW_MCP", null);

        boolean useMemory = "1".equals(env("CREW_MEMORY", "0"));
        boolean useKnowledge = "1".equals(env("CREW_KNOWLEDGE", "0"));
        String memoryFile = env("CREW_MEMORY_FILE", "memories.jsonl");
        String embedModel = env("CREW_MEMORY_EMBED", "bge-m3");

        Console.info("连接 Ollama: " + baseUrl + " · 模型: " + model
                + " · 工具协议: " + (nativeTools ? "原生 function calling" : "文本协议")
                + " · 工具绑定: " + ("task".equalsIgnoreCase(env("CREW_TOOLSET", "agent"))
                        ? "按任务（Task.toolset）" : "按 Agent")
                + (runId == null ? "" : " · 存档: runs/" + runId + ".json"));

        // MCP 服务端是子进程，必须显式关闭，否则进程会残留
        Memory memory = null;
        try (McpServer mcp = openMcp(mcpTarget)) {
            if (useMemory || useKnowledge) {
                memory = openMemory(baseUrl, embedModel, memoryFile);
                if ("1".equals(env("CREW_SEED", "0"))) {
                    seedMemory(memory);
                }
                if (useKnowledge) {
                    indexKnowledge(memory);
                }
            }

            ToolSet mcpToolset = mcp == null ? null : mcp.toolSet("demo 服务端");
            boolean taskScopedTools = "task".equalsIgnoreCase(env("CREW_TOOLSET", "agent"));
            ResilientLlmClient llm = ResilientLlmClient.wrap(
                    new OllamaClient(baseUrl, model, nativeTools), maxRpm);
            Crew crew = researchCrew(llm, process, temperature, debug, contextTokens,
                    mcpToolset, taskScopedTools, memory);

            MetricsListener metrics = new MetricsListener();
            CrewListener listener = CrewListener.plus(
                    new ConsoleListener(), metrics, abortListener(abortAt));

            RunStore store = runId == null ? null : new FileRunStore(Path.of("runs"));

            boolean ok = viaDag
                    ? runAsDag(crew, listener, metrics, store, runId, llm)
                    : runDirectly(crew, listener, metrics, store, runId, llm);

            if (memory != null) {
                reportMemory(memory);
            }
            if (!ok) {
                System.exit(1);
            }
        } finally {
            if (memory != null) {
                memory.close();
            }
        }
    }

    /**
     * 建立记忆。嵌入模型不可用时降级到离线哈希实现，而不是直接崩。
     *
     * <p>降级必须<b>说出来</b>：哈希嵌入的语义能力远弱于 bge-m3，检索质量会明显下降。
     * 静默降级会让人以为"记忆功能就这样"，去调错方向。
     */
    private static Memory openMemory(String baseUrl, String embedModel, String file) {
        Embedder embedder;
        if ("hash".equalsIgnoreCase(embedModel)) {
            embedder = new HashingEmbedder();
            Console.info("记忆嵌入：离线哈希实现（CREW_MEMORY_EMBED=hash）");
        } else {
            OllamaEmbedder ollama = new OllamaEmbedder(baseUrl, embedModel);
            try {
                // 预热一次：启动时就把"没 pull 模型"这类配置问题暴露出来，
                // 而不是跑到第三个任务才失败
                ollama.embed("预热");
                Console.info("记忆嵌入：" + embedModel + " · " + ollama.dimension() + " 维 · 基线 "
                        + String.format("%.2f", ollama.baseline()));
                embedder = ollama;
            } catch (MemException e) {
                Console.warn("嵌入模型不可用，降级为离线哈希实现：" + e.getMessage());
                embedder = new HashingEmbedder();
            }
        }

        MemoryStore store = file == null || file.isBlank()
                ? new InMemoryMemoryStore()
                : new FileMemoryStore(Path.of(file));
        Memory memory = Memory.builder().embedder(embedder).store(store).build();
        if (store instanceof FileMemoryStore fileStore) {
            Console.info("记忆存档：" + fileStore.file() + "（现有 " + fileStore.size() + " 条）");
            for (String error : fileStore.loadErrors()) {
                Console.warn("存档中有损坏记录已跳过 · " + error);
            }
        }
        return memory;
    }

    /**
     * 预置三条记忆，模拟"过去几轮沉淀下来的东西"。
     *
     * <p>前两条标为常驻、第三条留在长期作用域，正好覆盖两条不同的注入路径：
     * <ul>
     *   <li>常驻：与任务主题完全无关（相似度约 0.33，低于无关基线），但每次都注入</li>
     *   <li>长期 + 高重要性：相似度约 0.44，靠重要性豁免越过相对截断</li>
     * </ul>
     * 两者的相似度都过不了"基线 + 余量"这道地板，这正是仅靠阈值检索会全军覆没的场景。
     */
    private static void seedMemory(Memory memory) {
        memory.rememberPinned(
                "输出规范：Markdown 只用二级标题（## ），全篇不超过 200 字，正文不要出现「本文将」这类套话。",
                MemoryScope.LONG_TERM, 0.85);
        memory.rememberPinned(
                "用户偏好：结论先行，先给判断再给理由，不要罗列形容词堆砌。",
                MemoryScope.LONG_TERM, 0.80);
        memory.remember(
                "踩坑：给 7B 模型同时要求「输出 JSON」和「调用工具」时，它会直接编数字，必须把两步顺序写死。",
                MemoryScope.LONG_TERM, Map.of("kind", "lesson"), 0.85);
        Console.info("预置记忆：" + memory.size() + " 条（2 条常驻 + 1 条长期教训）");
    }

    /**
     * 把本仓库的 README 当作语料索引进知识库。
     *
     * <p>用真实文档而不是造一段样例：40 KB 中文技术文档切出上百块，
     * 正好能看出分块、批量嵌入、语义检索在真实体量下的表现。
     */
    private static void indexKnowledge(Memory memory) {
        Path readme = Path.of("README.md");
        if (!Files.exists(readme)) {
            Console.warn("未找到 README.md，跳过知识库索引");
            return;
        }
        long startedAt = System.currentTimeMillis();
        Knowledge knowledge = Knowledge.builder("README.md", memory).build();
        int chunks = knowledge.indexFile(readme);
        Console.info("知识库索引：README.md → " + chunks + " 块 · "
                + Console.millis(System.currentTimeMillis() - startedAt));

        List<Hit> hits = knowledge.retrieve("上下文预算不足时怎么压缩早期产出", 3);
        Console.section("知识库检索自检");
        Console.bullet("查询：上下文预算不足时怎么压缩早期产出");
        if (hits.isEmpty()) {
            Console.bullet("未命中（检查嵌入模型与阈值）");
        }
        for (Hit hit : hits) {
            Console.bullet(String.format("%.3f · 第 %s 块 · %s", hit.relevance(),
                    hit.item().meta("chunk"), hit.item().summary(70)));
        }
    }

    /** 记忆库统计与内容，让"记忆里到底有什么"可见。 */
    private static void reportMemory(Memory memory) {
        Console.section("记忆库");
        Console.bullet(memory.stats().report());
        List<Hit> sample = memory.recall(MemoryQuery.builder()
                .text(TOPIC).topK(3).build());
        if (sample.isEmpty()) {
            Console.bullet("以当前主题检索：无命中");
        }
        for (Hit hit : sample) {
            Console.bullet(String.format("[%s %.2f] %s", hit.item().scope().label(),
                    hit.score(), hit.item().summary(60)));
        }
        Console.last("来源：短期 = 本次任务产出 · 长期 = 跨运行沉淀 · 知识 = 文档分块");
    }

    /**
     * 按 {@code CREW_MCP} 打开一个 MCP 服务端。
     *
     * <p>三种取值对应三种用法：{@code demo} 走本仓库自带的纯 Java 服务端（无外部依赖，可离线复现），
     * {@code everything} 走官方测试服务端（需要 Node），其余值当作命令行原样执行。
     */
    private static McpServer openMcp(String target) {
        if (target == null) {
            return null;
        }
        McpServerConfig config = switch (target) {
            case "demo" -> McpServerConfig.builder("demo-tools").run(demoServerCommand()).build();
            case "everything" -> McpServerConfig.builder("everything")
                    .run("npx", "-y", "@modelcontextprotocol/server-everything")
                    // 这个服务端有 13 个工具，全挂上会挤掉任务描述本身
                    .maxTools(5)
                    .build();
            default -> McpServerConfig.builder("custom")
                    .run(target.split("\\s+"))
                    .build();
        };

        Console.info("启动 MCP 服务端：" + String.join(" ", config.command()));
        McpServer server = McpServer.open(config);
        Console.info("MCP 就绪：" + server.summary());
        Console.info("MCP 工具：" + server.tools().stream().map(Tool::name).toList());
        return server;
    }

    /** 以当前 JVM 与当前 classpath 启动自带的 demo 服务端，jar 包与 out 目录两种跑法都成立。 */
    private static String[] demoServerCommand() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return new String[]{
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Dfile.encoding=UTF-8",
                "-cp", System.getProperty("java.class.path"),
                "io.example.crew.mcp.DemoMcpServer"
        };
    }

    /** 直接调用 Crew 的常规路径。 */
    private static boolean runDirectly(Crew crew, CrewListener listener, MetricsListener metrics,
                                       RunStore store, String runId, ResilientLlmClient llm) {
        CrewOutput result;
        try {
            result = crew.kickoff(Map.of("topic", TOPIC), listener, store, runId);
        } catch (CrewException e) {
            printInterruption(e, runId);
            return false;
        }

        Console.title("最终产出");
        Console.block(result.raw(), "");

        Console.section("运行统计");
        Console.bullet("总耗时 " + Console.millis(result.millis())
                + (result.reusedTasks() > 0
                ? "（另有 " + result.reusedTasks() + " 步复用存档，不计入本次耗时）" : ""));
        Console.bullet(result.usage().report());
        Console.bullet("重试 " + llm.retries() + " 次 · 限流等待 " + llm.throttledMillis() + "ms");
        for (TaskOutput output : result.taskOutputs()) {
            Console.bullet("%s · %s · %s%s".formatted(
                    output.task().label(),
                    output.agentRole(),
                    Console.millis(output.millis()),
                    output.attempts() > 1 ? " · 重试 " + (output.attempts() - 1) + " 次" : ""));
            if (output.hasParsed()) {
                Console.bullet("  ↳ 结构化结果：" + output.parsed());
            }
            for (String note : output.notes()) {
                Console.bullet("  ↳ " + note);
            }
        }
        Console.last("流程 " + result.process() + " · 任务 " + result.taskOutputs().size() + " 个");
        metrics.report();
        return true;
    }

    /**
     * 把 Crew 嵌进编排图：前面是取数节点，后面是确定性收尾节点。
     *
     * <p>这个模式才是生产形态——Crew 只覆盖"需要模型自主协作"的那一段，
     * 其余步骤用代码写。收尾节点的存在说明一件事：格式兜底交给代码，
     * 比在提示词里反复强调"必须以 ## 开头"可靠得多。
     */
    private static boolean runAsDag(Crew crew, CrewListener listener, MetricsListener metrics,
                                    RunStore store, String runId, ResilientLlmClient llm) {
        CrewNode crewNode = CrewNode.builder("research", crew)
                .listener(listener)
                .store(store, runId)
                .build();

        SimpleDag dag = SimpleDag.builder()
                .node("prepare", ctx -> Map.of("topic", TOPIC))
                .node(crewNode)
                .node("compose", Main::composeNode)
                .edge("prepare", "research")
                .edge("research", "compose")
                .build();

        DagResult dagResult;
        try {
            dagResult = dag.run(Map.of("seed", "demo"));
        } catch (DagException e) {
            if (e.getCause() instanceof CrewException ce) {
                printInterruption(ce, runId);
            } else {
                Console.error(e.getMessage());
            }
            return false;
        }

        Map<String, Object> composed = dagResult.node("compose");
        Console.title("最终产出（编排图 · compose 节点）");
        Console.block(String.valueOf(composed.get("report")), "");

        Console.section("编排图执行");
        Console.bullet("执行顺序：" + String.join(" → ", dagResult.order()));
        Console.bullet("prepare → topic = " + dagResult.node("prepare").get("topic"));
        Map<String, Object> research = dagResult.node("research");
        Console.bullet("research → 产出 " + String.valueOf(research.get("raw")).length()
                + " 字 · " + Console.millis(((Number) research.getOrDefault("millis", 0L)).longValue()));
        Console.bullet("compose  → 正文 " + composed.get("chars") + " 字"
                + (Boolean.TRUE.equals(composed.get("titleFixedByDag"))
                ? " · 标题由编排层补齐（Crew 产出不合格但被代码兜住）" : " · 标题由模型自己给出"));
        Console.last("Crew 只是图里的一个节点，前后步骤都是确定性代码");

        metrics.report();
        Console.section("运行统计");
        Console.bullet(llm.usage().report());
        Console.bullet("重试 " + llm.retries() + " 次 · 限流等待 " + llm.throttledMillis() + "ms");
        return true;
    }

    /**
     * 确定性收尾节点：格式兜底 + 字数统计。
     *
     * <p>注意这里不抛异常而是"修补并记录"：交付物缺标题属于可修复的偏差，
     * 让整条链路失败反而更糟。但修补事实必须放进产出，供上层告警。
     */
    private static Map<String, Object> composeNode(Map<String, Object> ctx) {
        String raw = String.valueOf(ctx.getOrDefault("raw", "")).strip();
        boolean hasTitle = raw.startsWith("## ");
        String report = hasTitle
                ? raw
                : "## 技术栈引入多智能体的可行性简报（标题由编排层补齐）\n\n" + raw;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("report", report);
        out.put("chars", report.length());
        out.put("titleFixedByDag", !hasTitle);
        return out;
    }

    /** 演示用：在第 N 步开始前抛异常，模拟进程被杀或人工中止。 */
    private static CrewListener abortListener(int step) {
        if (step <= 0) {
            return new CrewListener() {
            };
        }
        return new CrewListener() {
            @Override
            public void onTaskStart(int index, int total, Task task) {
                if (index == step) {
                    throw new IllegalStateException(
                            "模拟中断：CREW_ABORT_AT=" + step + "，在第 " + step + " 步开始前强制退出");
                }
            }
        };
    }

    private static void printInterruption(CrewException e, String runId) {
        Console.title("流程中断");
        Console.bullet(e.getMessage());
        Throwable cause = e.getCause();
        if (cause != null && cause.getMessage() != null) {
            Console.bullet("原因：" + cause.getMessage());
        }
        Console.bullet("已保留 " + e.completedSteps() + "/" + e.totalSteps() + " 步成果");
        if (runId == null) {
            Console.last("未启用持久化，下次需全量重跑（设置 CREW_RUN_ID 可保留成果）");
        } else {
            Console.last("续跑命令：CREW_RUN_ID=" + runId + " ./run.sh");
        }
    }

    /** 组装示例流水线：三个角色、三个任务、一个工具、三个 guardrail。 */
    private static Crew researchCrew(LlmClient llm, Process process, double temperature,
                                     boolean debug, int contextTokens, ToolSet mcpToolset,
                                     boolean taskScopedTools, Memory memory) {
        List<Tool> mcpTools = mcpToolset == null
                ? List.of()
                : List.copyOf(mcpToolset.registry().all());

        Agent researcher = Agent.builder()
                .role("资深行业研究员")
                .goal("把模糊的话题拆成准确、可验证的事实要点")
                .backstory("你在技术咨询机构做了十年研究，习惯区分'事实'与'判断'，"
                        + "从不为了凑字数编造数据或机构名。")
                .llm(llm)
                .temperature(temperature)
                .build();

        Agent analyst = Agent.builder()
                .role("数据分析师")
                .goal("从研究要点中提炼可度量、可验证的结论")
                .backstory("你擅长把定性描述翻译成指标与比例关系，凡涉及计算一律调用工具，绝不心算。")
                .llm(llm)
                // Agent 级工具的两种挂法：绑在 Agent 上（全部任务共享），
                // 或留给任务各自绑定（见下方 taskScopedTools 分支）。
                // 后者能让同一个 Agent 在不同任务里只看到该任务的工具。
                .tools(taskScopedTools ? null : ToolRegistry.of(new CalculatorTool()))
                .temperature(temperature)
                .build();

        // MCP 工具挂在这里：远端服务端暴露什么，这个 Agent 就能用什么，
        // 本地没有任何一行业务代码知道 word_count 的存在。
        boolean hasWordCount = mcpTools.stream().anyMatch(t -> t.name().equals("word_count"));
        ToolRegistry writerTools = ToolRegistry.of().registerAll(mcpTools);

        Agent writer = Agent.builder()
                .role("技术编辑")
                .goal("把研究结论写成决策者能直接读的短文")
                .backstory("你为技术负责人写简报，习惯先给结论再给理由，删掉一切形容词堆砌。"
                        + (hasWordCount ? "篇幅有硬性上限，交稿前会用 word_count 工具核对字数，不靠感觉估算。" : ""))
                .llm(llm)
                .tools(taskScopedTools ? null : writerTools)
                .temperature(temperature)
                .build();

        // 任务级工具集：从同一个服务端的工具里挑，而不是整包给。
        // 这正是工具集存在的理由——服务端可能暴露十几个工具，某一步只需要一个。
        ToolSet mathToolset = ToolSet.of("趋势分析·计算", new CalculatorTool());
        // only() 会严格校验名字，但示例要能适配任意服务端（挂 everything 时就没有
        // word_count）。所以先探测再绑定：没有该工具时降级为"本任务无工具"，
        // 而不是让整个程序因为一个示例性的工具缺失而起不来。
        ToolSet writingToolset = mcpToolset != null && mcpToolset.registry().contains("word_count")
                ? mcpToolset.only("word_count").named("成文·字数核对")
                : ToolSet.empty("成文·无工具");

        Task factFinding = Task.builder()
                .label("事实收集")
                .description("围绕主题「{topic}」，给出 4 条关键事实要点。每条独立成行、不超过 40 字，"
                        + "只陈述可以推理或普遍成立的内容。")
                .expectedOutput("4 行编号要点，形如 1. xxx，不含开场白与总结句")
                .agent(researcher)
                .build();

        Task trendAnalysis = Task.builder()
                .label("趋势分析")                .description("""
                        基于前序要点，完成两件事：
                        (1) 给出 2 条可验证的趋势判断，每条不超过 50 字；
                        (2) 必须调用 calculator 工具计算：一个 3 智能体的流程，若每个智能体各调用模型 2 次，
                            相比单次大模型调用（1 次），调用次数增加的百分比是多少。
                            表达式为 (3*2-1)/1*100。
                        """)
                .expectedOutput("一个 JSON 对象：judgments 含 2 条以上判断，increasePercent 为计算结果的数字")
                .agent(analyst)
                .context(factFinding)
                .toolset(taskScopedTools ? mathToolset : null)
                .outputType(TrendAnalysis.class)
                .guardrails(
                        // 关键教训：结构化输出只保证"格式合法"，不保证"内容为真"。
                        // 模型完全可能不调工具、直接编一个数字塞进 increasePercent，
                        // 而 schema 校验对此毫无察觉。数值一致性必须靠 guardrail 单独兜住。
                        Guardrails.require("计算结果",
                                "increasePercent 必须是调用 calculator 工具得到的值（正确结果 500）。"
                                        + "如果还没调用工具，请先调用 (3*2-1)/1*100，再把返回值填进来。",
                                check -> check.parsed() instanceof TrendAnalysis t
                                        && Math.abs(t.increasePercent - 500) < 1e-6),
                        // 实测：写成"恰好 2 条"时 7B 会稳定给出 3 条（多出来的一条通常是总结），
                        // 连续 3 次驳回耗尽重试，106s 才降级放行。校验标准必须定在模型能做到的水平上。
                        Guardrails.require("判断条数", "judgments 至少要有 2 条判断，且整体须先通过 JSON 结构校验",
                                check -> check.parsed() instanceof TrendAnalysis t
                                        && t.judgments != null && t.judgments.size() >= 2),
                        Guardrails.require("判断长度", "每条趋势判断不得超过 50 字，请压缩",
                                check -> check.parsed() instanceof TrendAnalysis t
                                        && t.judgments != null
                                        && t.judgments.stream().allMatch(j -> j.length() <= 50)))
                .build();

        Task drafting = Task.builder()
                .label("成文")
                .description("把前序研究与分析整合成一段 Markdown 短文：一个二级标题，正文不超过 150 字，"
                        + "结尾一句结论。不要复述任务要求。"
                        + (hasWordCount
                        ? "写完必须调用 word_count 工具核对字符数（limit 传 200），超了就精简后再交。"
                        : ""))
                .expectedOutput("Markdown 片段，以 ## 开头，总长不超过 200 字")
                .agent(writer)
                .toolset(taskScopedTools ? writingToolset : null)
                .guardrails(
                        Guardrails.contains("标题格式", "必须以 Markdown 二级标题（## ）开头", "## "),
                        Guardrails.maxChars("篇幅", 200))
                .build();

        Agent manager = process == Process.HIERARCHICAL
                ? Agent.builder()
                .role("流程主管")
                .goal("确保每一步产出都能直接交付")
                .backstory("你审稿极其严格，只看产出是否达成目标，不接受空话。")
                .llm(llm)
                .temperature(0.1)
                .build()
                : null;

        if (debug) {
            Console.section("调试：分析师系统提示词（按趋势分析任务的工具集渲染）");
            Console.block(analyst.systemPrompt(trendAnalysis.effectiveTools()), Console.gray("  │ "));
        }

        return Crew.builder()
                .name("技术栈引入多智能体的可行性简报")
                .agents(researcher, analyst, writer)
                .tasks(factFinding, trendAnalysis, drafting)
                .process(process)
                .manager(manager)
                .maxReworks(1)
                .contextTokens(contextTokens)
                .memory(memory)
                .memoryTopK(4)
                .memoryMaxChars(600)
                .debugPrompts(debug)
                .build();
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
