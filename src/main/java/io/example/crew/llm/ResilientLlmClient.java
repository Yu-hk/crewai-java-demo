package io.example.crew.llm;

import io.example.crew.util.Console;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 给任意 {@link LlmClient} 加上限流与重试的装饰器。
 *
 * <p>用装饰器而不是在 {@link OllamaClient} 里写 if-else，是因为这两件事都属于
 * <b>与 provider 无关的运行时策略</b>：换成云端 API 时策略应当原样保留。
 * 这也让 {@link OllamaClient} 能保持"只发一次请求"的简单形态，便于单独测试。
 *
 * <p>重试的判定完全交给 {@link LlmException#retryable()}——装饰器不猜哪种失败值得重试，
 * 因为只有具体客户端知道 429 和 401 的区别。
 */
public final class ResilientLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final RetryPolicy policy;
    private final RateLimiter limiter;
    private final AtomicInteger retries = new AtomicInteger();
    private final AtomicLong throttledMillis = new AtomicLong();

    public ResilientLlmClient(LlmClient delegate, RetryPolicy policy, RateLimiter limiter) {
        this.delegate = delegate;
        this.policy = policy;
        this.limiter = limiter;
    }

    /** 按每分钟调用上限包装客户端，重试策略取默认值（3 次 / 800ms 起）。 */
    public static ResilientLlmClient wrap(LlmClient delegate, int maxRpm) {
        return new ResilientLlmClient(delegate, RetryPolicy.defaults(), new RateLimiter(maxRpm));
    }

    @Override
    public ChatReply chat(List<ChatMessage> messages, double temperature, List<ToolSpec> tools) {
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            long waited = limiter.acquire();
            if (waited > 0) {
                throttledMillis.addAndGet(waited);
            }
            try {
                return delegate.chat(messages, temperature, tools);
            } catch (LlmException e) {
                boolean lastAttempt = attempt >= policy.maxAttempts();
                if (!e.retryable() || lastAttempt) {
                    throw e;
                }
                retries.incrementAndGet();
                long delay = policy.delayFor(attempt);
                Console.warn("模型调用失败（第 " + attempt + " 次），" + delay + "ms 后重试："
                        + Console.truncate(e.getMessage(), 100));
                sleep(delay);
            }
        }
        throw new IllegalStateException("重试循环异常退出");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public int retries() {
        return retries.get();
    }

    public long throttledMillis() {
        return throttledMillis.get();
    }

    @Override
    public String model() {
        return delegate.model();
    }

    @Override
    public Usage usage() {
        return delegate.usage();
    }

    @Override
    public boolean supportsNativeTools() {
        return delegate.supportsNativeTools();
    }
}
