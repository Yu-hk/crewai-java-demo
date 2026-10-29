package io.example.crew.llm;

import java.util.List;

/**
 * 模型客户端抽象。
 *
 * <p>把 LLM 调用收敛到一个接口，是为了让它成为整个框架里唯一的外部依赖点：
 * 换成 OpenAI 兼容端点、vLLM 或云端模型时，只需换实现，Agent/Task/Crew 不动。
 * 这一点与 CrewAI 的 {@code LLM} 抽象意图一致。
 */
public interface LlmClient {

    /**
     * 发起一次对话。
     *
     * @param messages    完整对话历史
     * @param temperature 生成温度
     * @param tools       本次允许调用的工具声明；空列表表示不允许调用
     */
    ChatReply chat(List<ChatMessage> messages, double temperature, List<ToolSpec> tools);

    /** 不允许工具调用的一次对话。 */
    default ChatReply chat(List<ChatMessage> messages, double temperature) {
        return chat(messages, temperature, List.of());
    }

    default ChatReply chat(List<ChatMessage> messages) {
        return chat(messages, 0.7, List.of());
    }

    /** 模型标识，用于日志。 */
    String model();

    /** 累计用量，用于成本核算。 */
    Usage usage();

    /**
     * 是否支持原生 function calling。
     *
     * <p>返回 false 时，{@code Agent} 会自动回退到文本协议（{@code [TOOL] {...}}）。
     * 保留这条降级路径不是为了兼容，而是因为小模型 / 老版本 Ollama 上原生协议确实会失效，
     * 而没有降级就意味着整条链路直接不可用。
     */
    default boolean supportsNativeTools() {
        return false;
    }

    /**
     * 调用统计。
     *
     * @param calls            调用次数
     * @param promptTokens     累计输入 token（模型侧上报）
     * @param completionTokens 累计输出 token
     */
    record Usage(int calls, long promptTokens, long completionTokens) {

        public String report() {
            return "LLM 调用 %d 次 | 输入 %,d tok | 输出 %,d tok".formatted(calls, promptTokens, completionTokens);
        }

        public long totalTokens() {
            return promptTokens + completionTokens;
        }
    }
}
