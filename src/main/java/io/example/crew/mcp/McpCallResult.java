package io.example.crew.mcp;

/**
 * {@code tools/call} 的执行结果。
 *
 * @param isError 工具执行失败（参数不合法、目标不存在等"业务失败"），
 *                注意这不是 JSON-RPC 层的 error——那类错误会直接抛 {@link McpException}
 * @param text    已拍平的文本结果
 */
public record McpCallResult(boolean isError, String text) {
}
