package io.example.crew.mcp;

import java.io.IOException;

/**
 * MCP 传输层抽象。
 *
 * <p>协议规定了几种传输（stdio / SSE / streamable HTTP），本示例只实现 stdio，
 * 因为它是唯一不需要处理"会话 ID + 断线重连 + OAuth"的传输，也最适合本地进程。
 * 生产环境要接远程 MCP 时，只需新增一个实现，{@link McpClient} 不用改。
 *
 * <p>接口刻意做成"一条消息进、一条消息出"的同步原语，而不是把整个
 * JSON-RPC 语义塞进来：传输层不该知道什么是 initialize、什么是 id。
 */
public interface McpTransport extends AutoCloseable {

    /** 发送一条完整消息（实现内部负责分帧与刷新）。 */
    void send(String message) throws IOException;

    /**
     * 读取一条完整消息。
     *
     * @return 消息文本；流已结束返回 null
     */
    String receive() throws IOException;

    /** 传输是否仍可用。进程已退出、管道已关闭都算不可用。 */
    boolean alive();

    /** 最近若干行 stderr，用于把服务端崩溃原因带给调用方。 */
    String diagnostics();

    @Override
    void close();
}
