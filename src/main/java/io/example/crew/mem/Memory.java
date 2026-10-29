package io.example.crew.mem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 记忆门面。对应 CrewAI 里 {@code Crew(memory=True)} 背后那一整套东西，但收成一个类。
 *
 * <p><b>它和 {@code ContextBudget} 是什么关系</b>——这是理解本类的关键。
 * 两者都在"处理放不下的历史"，但方向相反：
 *
 * <pre>
 *   全量历史 ──[Memory：语义检索]──> 相关的若干条 ──[ContextBudget：压缩]──> 装进窗口
 *                 选择（丢弃无关）                    压缩（保留要点）
 * </pre>
 *
 * <p>顺序不能反。先压缩再检索，等于先把可能要用的信息压掉了再去捞；
 * 而且压缩要花一次模型调用，对本来就不该进来的内容花这次调用是纯浪费。
 * 所以正确做法是：先用记忆把候选范围从"全部历史"缩到"几条相关的"，再让预算去处理它们。
 *
 * <p><b>检索是只读的。</b>{@link #recall} 不改任何状态，连"命中次数"都不加。
 * 原因：一旦命中就刷新 {@code lastUsedAt}，被召回过的记忆永远显得"新鲜"，
 * 下一轮又更容易被召回——受欢迎的记忆越来越受欢迎，冷门的永远沉底。
 * 这个正反馈在记忆系统里是纯粹的 bug，而不是"使用频次加权"这种Feature。
 */
public final class Memory implements AutoCloseable {

    private final Embedder embedder;
    private final MemoryStore store;
    private final double dedupThreshold;
    private final boolean autoFlush;

    private Memory(Builder b) {
        this.embedder = b.embedder;
        this.store = b.store;
        this.dedupThreshold = b.dedupThreshold;
        this.autoFlush = b.autoFlush;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 全离线的一套记忆：哈希嵌入 + 内存存储。用于断言与"没有 Ollama 也要能跑"的场景。 */
    public static Memory offline() {
        return builder().embedder(new HashingEmbedder()).store(new InMemoryMemoryStore()).build();
    }

    public Embedder embedder() {
        return embedder;
    }

    public MemoryStore store() {
        return store;
    }

    // ------------------------------------------------------------------ 写入

    /** 写入一条短期记忆，重要性由启发式给出。 */
    public MemoryItem remember(String text, MemoryScope scope) {
        return remember(text, scope, Map.of(), Importance.of(text));
    }

    /**
     * 写入一条常驻记忆。
     *
     * <p>常驻记忆不参与检索，每次运行都会被直接注入。适合放"对所有输出都成立"的约定：
     * 格式规范、语气要求、绝对不能做的事。它们与任务主题天然不相关，
     * 走检索一定会被阈值筛掉——这不是阈值调得不对，而是检索这个机制本身不适用。
     */
    public MemoryItem rememberPinned(String text, MemoryScope scope, double importance) {
        return remember(text, scope, Map.of("pinned", "true"), importance, true);
    }

    public MemoryItem remember(String text, MemoryScope scope, Map<String, String> meta, double importance) {
        return remember(text, scope, meta, importance, true);
    }

    /**
     * 写入一条记忆，先做语义去重。
     *
     * <p>去重不是优化，是正确性要求。同一条"用户要求中文输出"在每次运行结束时都可能被再次写入，
     * 没有去重的话跑十轮就积十条几乎一样的记忆，检索时 topK 全被它们占满，
     * 真正的任务信息一条都进不来。
     *
     * <p>去重按<b>相似度</b>而非文本相等：措辞会变（"必须用中文" / "所有输出用中文"），
     * 语义不变。阈值 0.95 是在 bge-m3 上定的——它在无关文本上最高能到 0.538，
     * 所以 0.95 这个位置有足够的安全边际，不会把两条不同记忆合掉。
     *
     * @return 实际落库的条目（可能是合并后的旧条目）
     */
    public MemoryItem remember(String text, MemoryScope scope, Map<String, String> meta,
                               double importance, boolean flush) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("记忆内容不能为空");
        }
        float[] vector = embedder.embed(text);
        if (vector.length == 0) {
            throw new MemException("嵌入失败，无法写入记忆：" + abbreviate(text));
        }
        MemoryItem candidate = MemoryItem.create(scope, text, vector, meta, importance, System.currentTimeMillis());

        Optional<MemoryItem> dup = nearestInScope(scope, vector);
        MemoryItem stored;
        if (dup.isPresent() && Vectors.cosine(dup.get().embedding(), vector) >= dedupThreshold) {
            stored = dup.get().merge(candidate, System.currentTimeMillis());
        } else {
            stored = candidate;
        }
        // 内容指纹相同的情况（同文本重复写入）由 store 的 key 天然覆盖，
        // 但 hits 不该丢掉，所以这里显式累加一次。
        if (!dup.isPresent()) {
            MemoryItem existing = store.get(stored.id()).orElse(null);
            if (existing != null) {
                stored = existing.touched(System.currentTimeMillis());
            }
        }
        store.put(stored);
        if (flush && autoFlush) {
            store.flush();
        }
        return stored;
    }

    /**
     * 批量写入，只在结尾 flush 一次。
     *
     * <p>索引一份几百块的文档时，逐条 flush 会退化成几百次全量重写文件。
     */
    public List<MemoryItem> rememberAll(List<String> texts, MemoryScope scope) {
        List<MemoryItem> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            out.add(remember(text, scope, Map.of(), Importance.of(text), false));
        }
        store.flush();
        return out;
    }

    // ------------------------------------------------------------------ 检索

    /**
     * 检索相关记忆。
     *
     * <p>四段式过滤，前两段是硬约束，后两段是打分策略：
     * <ol>
     *   <li>作用域 / runId / 常驻标记过滤（结构性约束，与语义无关）</li>
     *   <li>噪声地板：整批最高分都低于 {@code 基线 + 余量} 时直接返回空。宁可没有记忆，
     *       也不要把无关内容塞进提示词——模型拿到无关记忆会强行往任务上套，比没有更糟。
     *       但<b>高重要性条目可以打破这个判定</b>，理由见下面的豁免逻辑。</li>
     *   <li>相对截断 + topK：见 {@link MemoryQuery} 的说明</li>
     *   <li>重要性豁免：见 {@link MemoryQuery.Builder#importanceExempt}</li>
     * </ol>
     *
     * <p>第 2 与第 4 条的张力来自实测：噪声地板按 relevance 判断，
     * 会系统性地误杀"字面不相关但价值很高"的记忆（实测相似度 0.37，
     * 而基线 0.39）。纯阈值方案在这类信息上必然失效，所以必须有单独的通道。
     */
    public List<Hit> recall(MemoryQuery query) {
        if (query == null || query.text().isBlank()) {
            return List.of();
        }
        float[] queryVector = embedder.embed(query.text());
        if (queryVector.length == 0) {
            return List.of();
        }
        double baseline = embedder.baseline();

        List<Hit> scored = new ArrayList<>();
        double bestRelevance = -1;
        boolean hasExempt = false;
        for (MemoryItem item : store.all()) {
            if (!query.scopes().contains(item.scope())) {
                continue;
            }
            if (!query.includePinned() && item.pinned()) {
                // 常驻记忆由 pinnedBlock 单独注入，纳入检索会重复
                continue;
            }
            if (query.excludeRunId() != null && query.excludeRunId().equals(item.meta("runId"))) {
                continue;
            }
            if (item.embedding() == null || item.embedding().length != queryVector.length) {
                // 维度不符 = 换过嵌入模型，旧向量已不可比。跳过而不是崩溃。
                continue;
            }
            double relevance = Math.max(0, Vectors.cosine(queryVector, item.embedding()));
            if (!Double.isNaN(query.minRelevance()) && relevance < query.minRelevance()) {
                continue;
            }
            scored.add(score(item, relevance, query));
            bestRelevance = Math.max(bestRelevance, relevance);
            if (item.importance() >= query.importanceExempt() && relevance >= baseline) {
                hasExempt = true;
            }
        }
        if (scored.isEmpty()) {
            return List.of();
        }
        if (bestRelevance < baseline + query.noiseMargin() && !hasExempt) {
            return List.of();
        }

        scored.sort(Comparator.comparingDouble(Hit::score).reversed());
        double cutoff = scored.get(0).score() * query.relativeFloor();
        int exemptLimit = Math.max(1, query.topK() / 2);

        List<Hit> picked = new ArrayList<>();
        List<Hit> exempted = new ArrayList<>();
        for (Hit hit : scored) {
            boolean exempt = hit.importance() >= query.importanceExempt() && hit.relevance() >= baseline;
            if (exempt) {
                if (exempted.size() < exemptLimit) {
                    exempted.add(hit);
                }
                continue;
            }
            if (hit.score() >= cutoff) {
                picked.add(hit);
            }
        }
        picked.addAll(exempted);
        picked.sort(Comparator.comparingDouble(Hit::score).reversed());
        if (picked.size() > query.topK()) {
            return new ArrayList<>(picked.subList(0, query.topK()));
        }
        return picked;
    }

    /** 检索并把结果渲染成可直接插入提示词的一段文本，无结果时返回空串。 */
    public String recallBlock(MemoryQuery query, int maxChars) {
        return render(recall(query), maxChars);
    }

    /**
     * 渲染全部常驻记忆，无则返回空串。
     *
     * <p>措辞与检索块刻意不同："必须遵守"而不是"可能相关"。模型对两类指令的遵守率差别很大，
     * 把约束混在"可能相关"的段落里，等于告诉模型它可以不遵守。
     */
    public String pinnedBlock(int maxChars) {
        if (maxChars <= 0) {
            return "";
        }
        List<MemoryItem> pinned = new ArrayList<>();
        for (MemoryItem item : store.all()) {
            if (item.pinned()) {
                pinned.add(item);
            }
        }
        if (pinned.isEmpty()) {
            return "";
        }
        pinned.sort(Comparator.comparingDouble(MemoryItem::importance).reversed());

        StringBuilder sb = new StringBuilder(256);
        sb.append("【必须遵守的约定】\n")
                .append("以下约定对本输出无条件生效，与任务内容无关，必须满足。\n");
        int used = sb.length();
        for (MemoryItem item : pinned) {
            String line = "- " + item.summary(200) + "\n";
            if (used + line.length() > maxChars) {
                break;
            }
            sb.append(line);
            used += line.length();
        }
        return sb.toString();
    }

    /**
     * 渲染成提示词片段。
     *
     * <p>"与当前任务无关的请直接忽略"这一句不是客套。实测小模型拿到一段标注为"相关记忆"的
     * 内容后，会默认它确实相关并想办法用上它——哪怕内容与任务八竿子打不着。
     * 必须显式给出"忽略"的许可，模型才敢不用。
     */
    public String render(List<Hit> hits, int maxChars) {
        if (hits == null || hits.isEmpty() || maxChars <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append("【相关记忆】\n")
                .append("下面是从历史运行与知识库中检索到的内容，可能与本任务有关。\n")
                .append("与当前任务无关的请直接忽略，不要为了用上它们而改变回答方向。\n");
        int used = sb.length();
        for (Hit hit : hits) {
            String line = "- [%s · %.2f] %s\n".formatted(
                    hit.item().scope().label(), hit.score(), hit.item().summary(160));
            if (used + line.length() > maxChars) {
                break;
            }
            sb.append(line);
            used += line.length();
        }
        return sb.toString();
    }

    /** 同作用域内最相似的一条，供去重与其他调用方判断"是否已有类似记忆"。 */
    public Optional<Hit> nearest(String text, MemoryScope scope) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        float[] vector = embedder.embed(text);
        Optional<MemoryItem> best = nearestInScope(scope, vector);
        if (best.isEmpty()) {
            return Optional.empty();
        }
        double relevance = Math.max(0, Vectors.cosine(vector, best.get().embedding()));
        return Optional.of(score(best.get(), relevance, MemoryQuery.of(text, 1)));
    }

    private Optional<MemoryItem> nearestInScope(MemoryScope scope, float[] vector) {
        MemoryItem best = null;
        double bestSimilarity = -2;
        for (MemoryItem item : store.byScope(scope)) {
            if (item.embedding() == null || item.embedding().length != vector.length) {
                continue;
            }
            double similarity = Vectors.cosine(vector, item.embedding());
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                best = item;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * 复合打分。
     *
     * <p>知识块的 {@code halfLifeHours == 0}，此时把 recency 的那份权重让给 relevance，
     * 保证权重和恒为 1。不这么做的话，混检"知识块 + 短期记忆"时两类条目分数不在同一量纲上：
     * 知识块永远拿满 recency 分（等于恒加 0.18），排在前面的会全是新导入的文档。
     */
    private Hit score(MemoryItem item, double relevance, MemoryQuery query) {
        double recencyWeight = item.scope().halfLifeHours() <= 0 ? 0 : query.wRecency();
        double relevanceWeight = query.wRelevance() + (query.wRecency() - recencyWeight);
        double recency = recencyWeight == 0
                ? 1.0
                : Math.pow(0.5, item.ageHours(query.now()) / item.scope().halfLifeHours());
        double value = relevanceWeight * relevance
                + recencyWeight * recency
                + query.wImportance() * item.importance();

        String why;
        if (item.importance() >= 0.65 && relevance < 0.6) {
            why = "重要性补足（语义相似度只有 " + fmt(relevance) + "）";
        } else if (recencyWeight > 0 && recency < 0.5) {
            why = "语义相关但已陈旧（recency " + fmt(recency) + "）";
        } else {
            why = "语义相关";
        }
        return new Hit(item, value, relevance, recency, item.importance(), why);
    }

    // ------------------------------------------------------------------ 维护

    public boolean forget(String id) {
        boolean removed = store.remove(id);
        if (removed) {
            store.flush();
        }
        return removed;
    }

    public void clear() {
        store.clear();
        store.flush();
    }

    public int size() {
        return store.size();
    }

    /** 常驻记忆条数。 */
    public int pinnedCount() {
        int count = 0;
        for (MemoryItem item : store.all()) {
            if (item.pinned()) {
                count++;
            }
        }
        return count;
    }

    /** 各作用域条数与平均重要性，用于控制台汇报。 */
    public Stats stats() {
        Map<MemoryScope, Integer> counts = new EnumMap<>(MemoryScope.class);
        Map<MemoryScope, Double> importanceSum = new EnumMap<>(MemoryScope.class);
        double total = 0;
        int n = 0;
        for (MemoryItem item : store.all()) {
            counts.merge(item.scope(), 1, Integer::sum);
            importanceSum.merge(item.scope(), item.importance(), Double::sum);
            total += item.importance();
            n++;
        }
        Map<MemoryScope, Double> avg = new EnumMap<>(MemoryScope.class);
        for (Map.Entry<MemoryScope, Double> e : importanceSum.entrySet()) {
            avg.put(e.getKey(), e.getValue() / counts.get(e.getKey()));
        }
        return new Stats(store.size(), Map.copyOf(counts), Map.copyOf(avg), n == 0 ? 0 : total / n);
    }

    @Override
    public void close() {
        store.flush();
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }

    private static String abbreviate(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }

    /**
     * @param total          记忆总条数
     * @param counts         各作用域条数
     * @param averageImportance 各作用域平均重要性
     * @param overallImportance 全局平均重要性
     */
    public record Stats(int total, Map<MemoryScope, Integer> counts,
                        Map<MemoryScope, Double> averageImportance, double overallImportance) {

        public String report() {
            if (total == 0) {
                return "记忆为空";
            }
            StringBuilder sb = new StringBuilder("记忆 ").append(total).append(" 条（");
            boolean first = true;
            for (MemoryScope scope : MemoryScope.values()) {
                Integer count = counts.get(scope);
                if (count == null || count == 0) {
                    continue;
                }
                if (!first) {
                    sb.append(" · ");
                }
                first = false;
                sb.append(scope.label()).append(' ').append(count);
            }
            return sb.append("）平均重要性 ").append(String.format("%.2f", overallImportance)).toString();
        }
    }

    /** Memory 构造器。 */
    public static final class Builder {
        private Embedder embedder;
        private MemoryStore store;
        private double dedupThreshold = 0.95;
        private boolean autoFlush = true;

        public Builder embedder(Embedder v) {
            this.embedder = v;
            return this;
        }

        public Builder store(MemoryStore v) {
            this.store = v;
            return this;
        }

        /** 语义去重阈值。0 表示不去重（每次写入都是新条目）。 */
        public Builder dedupThreshold(double v) {
            this.dedupThreshold = v;
            return this;
        }

        public Builder autoFlush(boolean v) {
            this.autoFlush = v;
            return this;
        }

        public Memory build() {
            if (embedder == null) {
                throw new IllegalStateException("Memory 需要 embedder");
            }
            if (store == null) {
                this.store = new InMemoryMemoryStore();
            }
            return new Memory(this);
        }
    }

    /** 调试用：把记忆库整体打印成行。 */
    public List<String> dump() {
        List<String> lines = new ArrayList<>();
        for (MemoryItem item : store.all()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scope", item.scope().name());
            row.put("importance", String.format("%.2f", item.importance()));
            row.put("hits", item.hits());
            row.put("meta", item.meta());
            row.put("text", item.summary(60));
            lines.add(row.toString());
        }
        return lines;
    }
}
