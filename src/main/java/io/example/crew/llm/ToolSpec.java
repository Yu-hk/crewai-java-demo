package io.example.crew.llm;

/**
 * 发给模型的工具声明，对应 Ollama / OpenAI 的 {@code tools[]} 元素。
 *
 * <p>与项目内部 {@code Tool} 接口的分工：{@code Tool} 是可执行体，
 * {@code ToolSpec} 是它的"发行版"——只含模型需要的三样东西（名字、描述、参数 Schema）。
 * 请求体里出现的东西必须与可执行体解耦，否则参数类一改就会影响协议层。
 *
 * @param name           工具名
 * @param description    用途说明
 * @param parametersJson 参数 JSON Schema 文本
 */
public record ToolSpec(String name, String description, String parametersJson) {
}
