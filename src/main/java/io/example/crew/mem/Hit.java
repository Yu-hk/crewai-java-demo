package io.example.crew.mem;

/**
 * 一条检索结果，附带打分明细。
 *
 * <p>{@code relevance} / {@code recency} / {@code importance} 三项分开暴露，是为了能回答
 * "这条为什么被召回了"——线上调检索最耗时的从来不是调权重，而是搞不清某条结果凭什么排第一。
 * {@code why} 用一句话给出主因，直接进日志。
 */
public record Hit(MemoryItem item, double score, double relevance, double recency, double importance, String why) {

    /** 用于控制台单行展示。 */
    public String line() {
        return "[%s·%.2f] %s".formatted(item.scope().label(), score, item.summary(80));
    }
}
