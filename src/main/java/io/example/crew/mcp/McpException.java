package io.example.crew.mcp;

import io.example.crew.tool.FatalToolFailure;

/**
 * MCP 通信或协议层失败。
 *
 * <p>刻意与 {@code Tool.ToolException} 区分开：ToolException 表示"工具执行失败，
 * 把原因回灌给模型让它自己修正"，而 McpException 表示"这条连接坏了"——
 * 后者回灌给模型没有意义（模型改不了进程是否活着），必须让调用方看见。
 *
 * <p>两者混淆过一次：初版把连接断开也塞进 ToolException，结果模型收到
 * "ERROR: 连接已关闭"后反复重试同一个工具，把 6 轮迭代全烧光。
 */
public class McpException extends RuntimeException implements FatalToolFailure {

    private static final long serialVersionUID = 1L;

    /** JSON-RPC 错误码；0 表示非协议错误（超时、IO、进程退出）。 */
    private final int code;

    public McpException(String message) {
        this(message, 0, null);
    }

    public McpException(String message, Throwable cause) {
        this(message, 0, cause);
    }

    public McpException(String message, int code) {
        this(message, code, null);
    }

    private McpException(String message, int code, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int code() {
        return code;
    }

    /**
     * 是否为"服务端明确拒绝了这次请求"（-32600 无效请求 / -32601 方法不存在 / -32602 参数不合法）。
     *
     * <p>这个判断在两处决定行为，含义一致——都是"客户端发的内容有问题"，
     * 而不是"连接有问题"：
     * <ul>
     *   <li>initialize：只有这一类错误值得换协议版本重试。超时、进程退出换版本只会白等一轮。</li>
     *   <li>tools/call：这一类应当转成业务失败回灌给模型（它改得了参数），
     *       其余（超时、服务端崩溃）必须冒泡。</li>
     * </ul>
     */
    public boolean isRequestRejected() {
        return code == -32600 || code == -32601 || code == -32602;
    }
}
