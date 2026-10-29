package io.example.crew.llm;

import java.util.Map;

/**
 * 一次原生工具调用请求，来自模型返回的 {@code message.tool_calls[]}。
 *
 * <p>注意 Ollama 的 {@code arguments} 是 JSON 对象（OpenAI 是 JSON 字符串）。
 * 两者都兼容的成本很低，所以在 {@link OllamaClient} 里同时处理了。
 *
 * @param name      工具名
 * @param arguments 参数表
 */
public record NativeToolCall(String name, Map<String, Object> arguments) {
}
