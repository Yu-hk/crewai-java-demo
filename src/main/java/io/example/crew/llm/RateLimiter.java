package io.example.crew.llm;

/**
 * 令牌桶限流，对应 CrewAI 的 {@code max_rpm}。
 *
 * <p>实现取的是"匀速放行"而不是"整分钟放行"：把每分钟 N 次摊成每 {@code 60/N} 秒一次，
 * 避免突发流量把本地显卡打满（Ollama 并发两个 7B 请求就会开始排队甚至 OOM）。
 *
 * <p>等待是阻塞的，且发生在取号之后——这叫预约式限流：并发调用者各自算好自己该等多久，
 * 不会出现"同时醒来再争抢"的惊群。
 */
public final class RateLimiter {

    private final long intervalNanos;
    private long nextFreeNanos;

    /**
     * @param permitsPerMinute 每分钟允许的调用次数，<= 0 表示不限流
     */
    public RateLimiter(int permitsPerMinute) {
        this.intervalNanos = permitsPerMinute <= 0
                ? 0
                : 60_000_000_000L / permitsPerMinute;
        this.nextFreeNanos = System.nanoTime();
    }

    public static RateLimiter unlimited() {
        return new RateLimiter(0);
    }

    public boolean limited() {
        return intervalNanos > 0;
    }

    /** 取一个令牌，必要时阻塞。返回实际等待的毫秒数。 */
    public synchronized long acquire() {
        if (intervalNanos == 0) {
            return 0;
        }
        long now = System.nanoTime();
        long waitNanos = Math.max(0, nextFreeNanos - now);
        nextFreeNanos = Math.max(now, nextFreeNanos) + intervalNanos;
        if (waitNanos <= 0) {
            return 0;
        }
        long waitMillis = waitNanos / 1_000_000L;
        if (waitMillis > 0) {
            try {
                Thread.sleep(waitMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return waitMillis;
    }
}
