package io.example.crew.mem;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 检索参数。
 *
 * <p>默认值是实测调出来的，不是拍的。三项门限各自解决不同的问题，缺一项都会漏：
 *
 * <ol>
 *   <li><b>噪声地板</b>（{@code noiseMargin}）：与 {@link Embedder#baseline()} 相加，
 *       判断"这一批候选是不是全都是噪声"。整批最高分都过不了地板就干脆不召回，
 *       避免把无关内容硬塞进提示词。</li>
 *   <li><b>相对截断</b>（{@code relativeFloor}）：只保留复合分不低于最高分 72% 的条目。
 *       这一项比任何绝对阈值都重要——实测 bge-m3 上"完全无关"的两句中文相似度最高能到
 *       <b>0.538</b>，而真正弱相关的句子只有 0.56，两者区间重叠，绝对阈值必然误杀或漏放。
 *       相对排序不受各向异性影响。</li>
 *   <li><b>topK</b>：最终的硬上限。记忆注入的边际收益在 3~5 条之后急剧归零，
 *       而每多一条都在挤压任务描述本身的预算。</li>
 * </ol>
 *
 * <p>{@code excludeRunId} 解决的是"记忆污染"：同一次运行里，第 2 个任务写入的产出
 * 对第 3 个任务本来就已经在前序上下文里了，再从记忆里捞一遍是纯粹的 token 浪费，
 * 而且会让模型把同一段内容当成两个独立证据。
 */
public final class MemoryQuery {

    private final String text;
    private final Set<MemoryScope> scopes;
    private final int topK;
    private final double wRelevance;
    private final double wRecency;
    private final double wImportance;
    private final double relativeFloor;
    private final double noiseMargin;
    private final double minRelevance;
    private final String excludeRunId;
    private final long now;
    private final double importanceExempt;
    private final boolean includePinned;

    private MemoryQuery(Builder b) {
        this.text = b.text == null ? "" : b.text;
        this.scopes = b.scopes.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.allOf(MemoryScope.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(b.scopes));
        this.topK = Math.max(1, b.topK);
        double sum = b.wRelevance + b.wRecency + b.wImportance;
        if (sum <= 0) {
            throw new IllegalArgumentException("三项权重之和必须大于 0");
        }
        // 归一化：不同作用域会把 recency 权重让给 relevance，不先归一化的话
        // 两条记忆的分数就不可比（比如知识块与短期记忆）。
        this.wRelevance = b.wRelevance / sum;
        this.wRecency = b.wRecency / sum;
        this.wImportance = b.wImportance / sum;
        this.relativeFloor = Math.max(0, Math.min(1, b.relativeFloor));
        this.noiseMargin = Math.max(0, b.noiseMargin);
        this.minRelevance = b.minRelevance;
        this.excludeRunId = b.excludeRunId;
        this.now = b.now > 0 ? b.now : System.currentTimeMillis();
        this.importanceExempt = Math.max(0, Math.min(1, b.importanceExempt));
        this.includePinned = b.includePinned;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 最常用的构造：给定查询文本，其余取默认。 */
    public static MemoryQuery of(String text, int topK) {
        return builder().text(text).topK(topK).build();
    }

    public String text() {
        return text;
    }

    public Set<MemoryScope> scopes() {
        return scopes;
    }

    public int topK() {
        return topK;
    }

    public double wRelevance() {
        return wRelevance;
    }

    public double wRecency() {
        return wRecency;
    }

    public double wImportance() {
        return wImportance;
    }

    public double relativeFloor() {
        return relativeFloor;
    }

    public double noiseMargin() {
        return noiseMargin;
    }

    /** 硬相关性地板；{@link Double#NaN} 表示不启用（默认）。 */
    public double minRelevance() {
        return minRelevance;
    }

    public String excludeRunId() {
        return excludeRunId;
    }

    public long now() {
        return now;
    }

    /** 重要性豁免阈值：重要性达到此值的条目可以越过相对截断。 */
    public double importanceExempt() {
        return importanceExempt;
    }

    /** 是否把常驻记忆也纳入检索结果。默认 false——它们由 {@code Memory.pinnedBlock} 单独注入。 */
    public boolean includePinned() {
        return includePinned;
    }

    /** 检索参数构造器。 */
    public static final class Builder {
        private String text;
        private final Set<MemoryScope> scopes = EnumSet.noneOf(MemoryScope.class);
        private int topK = 4;
        private double wRelevance = 0.70;
        private double wRecency = 0.20;
        private double wImportance = 0.10;
        private double relativeFloor = 0.72;
        private double noiseMargin = 0.15;
        private double minRelevance = Double.NaN;
        private String excludeRunId;
        private long now;
        private double importanceExempt = 0.7;
        private boolean includePinned;

        public Builder text(String v) {
            this.text = v;
            return this;
        }

        public Builder scopes(MemoryScope... v) {
            this.scopes.addAll(Set.of(v));
            return this;
        }

        public Builder topK(int v) {
            this.topK = v;
            return this;
        }

        /** 三项权重会先归一化，传任意正数比例即可。 */
        public Builder weights(double relevance, double recency, double importance) {
            this.wRelevance = relevance;
            this.wRecency = recency;
            this.wImportance = importance;
            return this;
        }

        /** 相对截断比例，保留复合分 >= 最高分 × 该值 的条目。设 0 表示不截断。 */
        public Builder relativeFloor(double v) {
            this.relativeFloor = v;
            return this;
        }

        /** 噪声地板余量，与嵌入器声明的基线相加。 */
        public Builder noiseMargin(double v) {
            this.noiseMargin = v;
            return this;
        }

        /** 启用绝对相关性硬地板。仅在确认过当前嵌入器的分布后才该用。 */
        public Builder minRelevance(double v) {
            this.minRelevance = v;
            return this;
        }

        /** 排除某次运行期间写入的记忆，防止同一链路自我污染。 */
        public Builder excludeRunId(String v) {
            this.excludeRunId = v;
            return this;
        }

        /** 指定"当前时刻"，用于测试时间衰减。 */
        public Builder now(long v) {
            this.now = v;
            return this;
        }

        /**
         * 重要性豁免阈值，默认 0.7。
         *
         * <p>解决的问题：价值高的信息常常字面相关性低。实测一条"结论先行、不要堆砌形容词"
         * 的长期偏好，与具体任务主题的相似度只有 0.37，比无关基线还低——纯语义检索永远
         * 排不到它，相对截断也必然砍掉它。但它一旦丢失，后面每一步产出都跑偏。
         *
         * <p>豁免条目仍要求 relevance 不低于嵌入器基线（不算真正的无关），
         * 且最多占 topK 的一半，防止一批高重要性条目把任务本身的信息挤出去。
         */
        public Builder importanceExempt(double v) {
            this.importanceExempt = v;
            return this;
        }

        /** 把常驻记忆也纳入检索结果。默认不纳入，避免与 {@code pinnedBlock} 重复注入。 */
        public Builder includePinned(boolean v) {
            this.includePinned = v;
            return this;
        }

        public MemoryQuery build() {
            return new MemoryQuery(this);
        }
    }
}
