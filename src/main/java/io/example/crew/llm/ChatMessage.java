package io.example.crew.llm;

import java.util.List;

/**
 * 一条对话消息，对应 Ollama /api/chat 的 messages 元素。
 *
 * <p>字段比"角色 + 正文"多两个，是被原生 function calling 逼出来的：
 * <ul>
 *   <li>{@code toolCalls} —— 助手消息里携带的调用请求，回灌时必须原样带回历史，
 *       否则模型不知道自己上一轮要过工具，会重复发同一个调用。</li>
 *   <li>{@code toolName} —— 工具结果消息（role=tool）必须指明它属于哪个工具。</li>
 * </ul>
 *
 * @param role       system / user / assistant / tool
 * @param content    正文
 * @param toolName   仅 role=tool 时有值
 * @param toolCalls  仅 assistant 携带工具调用时有值
 */
public record ChatMessage(String role, String content, String toolName, List<NativeToolCall> toolCalls) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null, List.of());
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null, List.of());
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content, null, List.of());
    }

    /** 带工具调用请求的助手消息。content 通常为空，但部分模型会附一段思考文字。 */
    public static ChatMessage assistantToolCalls(String content, List<NativeToolCall> calls) {
        return new ChatMessage("assistant", content, null, List.copyOf(calls));
    }

    /** 工具执行结果，作为 role=tool 回灌。 */
    public static ChatMessage tool(String toolName, String content) {
        return new ChatMessage("tool", content, toolName, List.of());
    }
}
