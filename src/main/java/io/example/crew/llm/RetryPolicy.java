package io.example.crew.llm;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 重试策略：指数退避 + 抖动。
 *
 * <p>为什么必须有抖动：多个任务同时打同一个本地 Ollama 时，固定退避会让失败请求
 * 在同一时刻再次撞车，形成"重试风暴"。抖动把重试时间打散，成本极低。
 *
 * <p>为什么要有上限：`maxAttempts` 不是越大越好。对 7B 本地模型，第 3 次重试的
 * 边际收益接近 0，而它会让一次失败的耗时从 30 秒涨到 2 分钟，掩盖真正的问题。
 *
 * @param maxAttempts     总尝试次数（含首次），建议 3
 * @param baseDelayMillis 首次退避基数
 * @param maxDelayMillis  单次退避上限
 */
public record RetryPolicy(int maxAttempts, long baseDelayMillis, long maxDelayMillis) {

    public static RetryPolicy defaults() {
        return new RetryPolicy(3, 800, 8_000);
    }

    public static RetryPolicy none() {
        return new RetryPolicy(1, 0, 0);
    }

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts 至少为 1");
        }
    }

    /** 第 {@code attempt} 次失败后应等待的毫秒数，带 ±25% 抖动。 */
    public long delayFor(int attempt) {
        long base = Math.min(maxDelayMillis, baseDelayMillis * (1L << Math.min(attempt - 1, 6)));
        double jitter = 0.75 + ThreadLocalRandom.current().nextDouble() * 0.5;
        return Math.max(0, (long) (base * jitter));
    }
}
