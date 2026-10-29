package io.example.crew.llm;

import java.util.List;

/**
 * 一次对话的结果。
 *
 * <p>关键在于它必须同时容纳两种形态：纯文本回复，或一组工具调用请求。
 * 早前的实现只返回 {@code String}，因此无法区分"模型说完了"和"模型要调工具"，
 * 只能靠正则扫描正文猜——这正是原生 function calling 要消灭的东西。
 *
 * @param content   文本正文，工具调用时可能为空
 * @param toolCalls 工具调用请求，无则为空列表
 */
public record ChatReply(String content, List<NativeToolCall> toolCalls) {

    public ChatReply {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public static ChatReply text(String content) {
        return new ChatReply(content, List.of());
    }
}
