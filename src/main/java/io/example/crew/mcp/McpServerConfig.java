package io.example.crew.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个 MCP 服务端的连接声明。
 *
 * <p>对应真实配置里的 {@code mcpServers} 条目：
 * <pre>{@code
 * { "filesystem": { "command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "."] } }
 * }</pre>
 *
 * @param name       服务端标识，只用于日志与工具前缀
 * @param command    启动命令（含参数）
 * @param env        追加的环境变量，可为空
 * @param toolPrefix 工具名前缀，空串表示不加前缀。多服务端并存时必填，
 *                   否则同名工具会在注册表里撞车
 * @param maxTools   最多注册多少个工具，0 表示不限制。服务端工具数动辄几十个，
 *                   全塞进系统提示词会挤掉任务描述本身
 */
public record McpServerConfig(String name, List<String> command, Map<String, String> env,
                              String toolPrefix, int maxTools) {

    public McpServerConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("MCP 服务端必须有 name");
        }
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("MCP 服务端 " + name + " 缺少启动命令");
        }
        command = List.copyOf(command);
        env = env == null ? Map.of() : Map.copyOf(env);
        toolPrefix = toolPrefix == null ? "" : toolPrefix;
    }

    public static McpServerConfig stdio(String name, String... command) {
        return new McpServerConfig(name, List.of(command), Map.of(), "", 0);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public List<String> effectiveCommand() {
        return command;
    }

    public static final class Builder {
        private final String name;
        private final List<String> command = new ArrayList<>();
        private final Map<String, String> env = new LinkedHashMap<>();
        private String toolPrefix = "";
        private int maxTools;

        private Builder(String name) {
            this.name = name;
        }

        public Builder run(String... parts) {
            command.addAll(List.of(parts));
            return this;
        }

        public Builder env(String key, String value) {
            env.put(key, value);
            return this;
        }

        public Builder toolPrefix(String prefix) {
            this.toolPrefix = prefix;
            return this;
        }

        public Builder maxTools(int limit) {
            this.maxTools = limit;
            return this;
        }

        public McpServerConfig build() {
            return new McpServerConfig(name, command, env, toolPrefix, maxTools);
        }
    }
}
