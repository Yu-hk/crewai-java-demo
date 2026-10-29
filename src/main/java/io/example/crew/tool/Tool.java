package io.example.crew.tool;

import java.util.List;
import java.util.Map;

/**
 * 工具抽象。对应 CrewAI 中挂在 Agent 上的 {@code tools=[...]}。
 *
 * <p>实现方只需声明名称、描述与调用逻辑；描述会被写进 Agent 的系统提示词，
 * 因此"描述写得清不清楚"直接决定模型能不能正确选用工具。
 */
public interface Tool {

    /** 工具名，模型调用时使用，建议 snake_case。 */
    String name();

    /** 一句话说明"能做什么、什么时候用"，会进入系统提示词。 */
    String description();

    /** 参数说明，用于提示词中的签名展示，如 {@code expression: string}。 */
    default String signature() {
        return "";
    }

    /**
     * 参数类型。声明后框架会反射生成 JSON Schema，用于两件事：
     * 原生 function calling 的 {@code tools[].function.parameters}，
     * 以及调用前对模型给出的参数做类型校验。
     *
     * <p>这是与 CrewAI {@code BaseTool.args_schema}（Pydantic 模型）对应的机制。
     * 返回 null 表示"不声明参数结构"，此时参数不做校验、Schema 为空对象。
     */
    default Class<?> parametersType() {
        return null;
    }

    /**
     * 别名，仅用于"按名挑选工具"，不参与注册与调用。
     *
     * <p>典型场景是 MCP：暴露名被加了前缀（{@code fs__read_file}）以避免多服务端冲突，
     * 但写配置时人更愿意写原始名 {@code read_file}。别名让挑选可以按原始名做，
     * 同时不会让模型看到两个名字——注册名始终只有一个。
     */
    default List<String> aliases() {
        return List.of();
    }

    /**
     * 直接给出参数 JSON Schema 文本，优先级高于 {@link #parametersType()}。
     *
     * <p>存在的理由是 MCP：远端工具的 Schema 由服务端下发给客户端，客户端既没有对应的
     * Java 类、也不应该去反推。这种情况下"反射生成 Schema"是错的方向——
     * 服务端才是契约的拥有者，客户端只做透传。
     *
     * @return 完整 JSON Schema；返回 null 表示改用 {@link #parametersType()} 反射生成
     */
    default String parametersJson() {
        return null;
    }

    /**
     * 执行工具。
     *
     * @param arguments 模型给出的参数
     * @return 返回给模型的文本结果
     * @throws ToolException 参数不合法或执行失败
     */
    String invoke(Map<String, Object> arguments) throws ToolException;

    /** 工具执行失败。消息会原样回传给模型，让它可以自行修正。 */
    class ToolException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public ToolException(String message) {
            super(message);
        }
    }
}
