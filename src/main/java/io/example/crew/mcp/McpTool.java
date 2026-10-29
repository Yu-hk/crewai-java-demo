package io.example.crew.mcp;

import io.example.crew.tool.Tool;

import java.util.List;
import java.util.Map;

/**
 * 把一个 MCP 远端工具包装成本地 {@link Tool}。
 *
 * <p>转换过程中有三件事必须做，否则模型要么调不到、要么调错：
 * <ol>
 *   <li><b>工具名合法化</b>。MCP 规范对工具名没有字符限制，实际见过 {@code fs.read_file}
 *       这种带点的名字；而 function calling 的 {@code name} 字段要求
 *       {@code ^[a-zA-Z0-9_-]{1,64}$}，带点会被服务端直接判为非法请求。</li>
 *   <li><b>描述压平</b>。服务端描述常是多行 Markdown，直接拼进系统提示词会破坏
 *       工具清单的列表结构，模型读到的是一堆散乱段落。</li>
 *   <li><b>保留原始名</b>。合法化只影响"模型看到的名字"，调用时仍要用服务端认识的原名。</li>
 * </ol>
 */
public final class McpTool implements Tool {

    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_DESCRIPTION_CHARS = 320;

    private final McpClient client;
    private final McpToolInfo info;
    private final String exposedName;
    private final String serverName;

    /**
     * 构造适配器。
     *
     * <p>公开是为了让调用方可以自己决定前缀策略——{@link McpServer} 只是最常用的一种包装方式。
     * 需要多个服务端并存时，前缀必须由调用方给出，框架无法替它猜。
     */
    public McpTool(McpClient client, McpToolInfo info, String serverName, String toolPrefix) {
        this.client = client;
        this.info = info;
        this.serverName = serverName;
        this.exposedName = sanitize(toolPrefix + info.name());
    }

    /**
     * 把任意字符串收敛成合法的工具名。
     *
     * <p>不抛异常而是替换：一个名字不合规不应该导致整个服务端的工具都挂不上，
     * 那会把"某个工具用不了"升级成"整条链路起不来"。
     */
    static String sanitize(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        String name = sb.toString();
        if (name.isEmpty()) {
            name = "mcp_tool";
        }
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
    }

    @Override
    public String name() {
        return exposedName;
    }

    /**
     * 原始名 + 服务端名，供工具集按"人写的名字"挑选。
     *
     * <p>加了前缀之后 {@code fs__read_file} 才是注册名，但配置里写 {@code read_file}
     * 更自然，也更不容易随前缀策略变化而失效。
     */
    @Override
    public List<String> aliases() {
        return List.of(info.name(), serverName + "/" + info.name());
    }

    @Override
    public String description() {
        String text = info.description();
        if (text == null || text.isBlank()) {
            return "MCP 工具 " + info.name() + "（服务端未提供说明）";
        }
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= MAX_DESCRIPTION_CHARS
                ? flat
                : flat.substring(0, MAX_DESCRIPTION_CHARS) + "…";
    }

    /**
     * 参数签名，供文本协议降级时展示。
     *
     * <p>从服务端下发的 Schema 里抠出顶层属性名与类型，拼成 {@code a: string, b*: number}。
     * 必填参数加 {@code *} 标记——7B 模型在文本协议下最容易漏的恰恰是必填项。
     */
    @Override
    public String signature() {
        String schema = info.inputSchema();
        if (schema == null || schema.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> root = io.example.crew.util.Json.parseObject(schema);
            Object props = root.get("properties");
            if (!(props instanceof Map<?, ?> map) || map.isEmpty()) {
                return "";
            }
            java.util.Set<String> required = new java.util.HashSet<>();
            if (root.get("required") instanceof java.util.List<?> list) {
                for (Object item : list) {
                    required.add(String.valueOf(item));
                }
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!sb.isEmpty()) {
                    sb.append(", ");
                }
                String key = String.valueOf(entry.getKey());
                sb.append(key).append(required.contains(key) ? "*" : "").append(": ");
                sb.append(typeOf(entry.getValue()));
            }
            return sb.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String typeOf(Object schemaNode) {
        if (!(schemaNode instanceof Map<?, ?> map)) {
            return "any";
        }
        Object type = map.get("type");
        if (type != null) {
            return String.valueOf(type);
        }
        // 联合类型、anyOf 之类在本地小模型上没必要精确表达，统一记为 any
        return "any";
    }

    /** 原样透传服务端 Schema：契约的拥有者不是客户端。 */
    @Override
    public String parametersJson() {
        return info.inputSchema();
    }

    @Override
    public String invoke(Map<String, Object> arguments) throws ToolException {
        McpCallResult result;
        try {
            result = client.callTool(info.name(), arguments);
        } catch (McpException e) {
            // 服务端 reject 了这次请求（参数不合 schema 等）：转成业务失败交给模型，
            // 它改得了参数。连接类故障（超时、进程退出）则必须冒泡。
            if (e.isRequestRejected()) {
                throw new ToolException("服务端拒绝了调用：" + e.getMessage());
            }
            throw e;
        }
        if (result.isError()) {
            // 业务失败：交给模型看，它多半能自己改参数重试
            throw new ToolException(result.text().isBlank()
                    ? "工具 " + info.name() + " 返回 isError，但未给出原因"
                    : result.text());
        }
        return result.text().isBlank() ? "(工具执行成功，无文本输出)" : result.text();
    }

    /** 原始工具名（服务端使用的名字），用于日志与排障。 */
    public String rawName() {
        return info.name();
    }

    public String serverName() {
        return serverName;
    }
}
