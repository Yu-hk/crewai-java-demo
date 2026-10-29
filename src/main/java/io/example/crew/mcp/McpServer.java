package io.example.crew.mcp;

import io.example.crew.tool.Tool;
import io.example.crew.tool.ToolSet;
import io.example.crew.util.Console;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个已连接的 MCP 服务端：持有客户端生命周期，并把远端工具转换成本地 {@link Tool}。
 *
 * <p>用 {@code try-with-resources} 管理，因为它是子进程的所有者——
 * 忘了关闭就会留下僵尸进程，而 MCP 服务端通常不会自己退出。
 *
 * <pre>{@code
 * try (McpServer fs = McpServer.open(McpServerConfig.stdio("fs", "npx", "-y", "@modelcontextprotocol/server-filesystem", "."))) {
 *     ToolRegistry tools = ToolRegistry.of(new CalculatorTool()).registerAll(fs.tools());
 * }
 * }</pre>
 */
public final class McpServer implements AutoCloseable {

    private final McpServerConfig config;
    private final McpClient client;
    private final List<Tool> tools;

    private McpServer(McpServerConfig config, McpClient client, List<Tool> tools) {
        this.config = config;
        this.client = client;
        this.tools = tools;
    }

    public static McpServer open(McpServerConfig config) {
        StdioTransport transport = new StdioTransport(config.command(), config.env());
        McpClient client = McpClient.connect(transport);
        try {
            Object toolsCapability = client.serverCapabilities().get("tools");
            if (toolsCapability == null) {
                throw new McpException("服务端 " + config.name()
                        + " 的 capabilities 里没有 tools，无法作为工具源使用。实际能力："
                        + client.serverCapabilities().keySet());
            }

            List<McpToolInfo> infos = client.listTools();
            if (infos.isEmpty()) {
                throw new McpException("服务端 " + config.name() + " 未暴露任何工具");
            }

            List<Tool> wrapped = new ArrayList<>(infos.size());
            for (McpToolInfo info : infos) {
                if (config.maxTools() > 0 && wrapped.size() >= config.maxTools()) {
                    Console.warn("MCP 服务端 " + config.name() + " 工具数超过上限 "
                            + config.maxTools() + "，其余 " + (infos.size() - wrapped.size())
                            + " 个未注册（避免挤占提示词预算）");
                    break;
                }
                wrapped.add(new McpTool(client, info, config.name(), config.toolPrefix()));
            }
            return new McpServer(config, client, List.copyOf(wrapped));
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
    }

    public List<Tool> tools() {
        return tools;
    }

    /**
     * 把本服务端的工具包装成一个命名工具集，用于按任务绑定或按需裁剪。
     *
     * <p>来源标记写 {@code mcp:<服务端名>}，出现在任务绑定日志里，
     * 是为了让"这个任务的工具从哪来"一眼可查——排查工具误用时，
     * 第一个问题永远是"它到底看到了哪些工具、来自哪个服务端"。
     */
    public ToolSet toolSet(String name) {
        return ToolSet.from(name, "mcp:" + config.name(), tools);
    }

    public McpClient client() {
        return client;
    }

    public String name() {
        return config.name();
    }

    /** 一行摘要，用于启动日志。 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(config.name()).append(" · ").append(client.serverName());
        if (!client.serverVersion().isBlank()) {
            sb.append(' ').append(client.serverVersion());
        }
        sb.append(" · 协议 ").append(client.protocolVersion())
                .append(" · ").append(tools.size()).append(" 个工具");
        return sb.toString();
    }

    @Override
    public void close() {
        client.close();
    }
}
