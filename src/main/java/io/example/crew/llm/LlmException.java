package io.example.crew.llm;

/**
 * 模型调用失败。
 *
 * <p>{@link #retryable()} 是重试策略的唯一依据，判断标准必须由客户端给出而不是由外层猜：
 * 429 / 5xx / 连接断开值得重试，401 / 404 / 参数错误重试一万次也不会成功，
 * 而"JSON 解析失败"这类问题重试反而有害。把可重试性做成异常属性，才能让
 * {@link ResilientLlmClient} 与具体 provider 解耦。
 */
public class LlmException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean retryable;
    private final int statusCode;

    public LlmException(String message, boolean retryable, Throwable cause) {
        this(message, 0, retryable, cause);
    }

    public LlmException(String message, int statusCode, boolean retryable, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }

    public int statusCode() {
        return statusCode;
    }
}
