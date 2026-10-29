package io.example.crew.mcp;

import io.example.crew.util.Json;

import java.util.Map;

/**
 * {@code tools/list} 返回的单个工具描述。
 *
 * @param name           工具名
 * @param description    用途说明（服务端提供，可能很长，长到会挤占提示词预算）
 * @param inputSchema    JSON Schema 文本，原样透传给模型，不做改写
 */
public record McpToolInfo(String name, String description, String inputSchema) {

    static McpToolInfo from(Map<String, Object> raw) {
        String name = Json.str(raw, "name");
        if (name == null || name.isBlank()) {
            throw new McpException("tools/list 返回了无名工具：" + raw);
        }
        String description = Json.str(raw, "description");
        // 有的服务端把标题放在 annotations.title 里，没有 description 时用它兜底，
        // 否则模型看到的工具说明是空的，根本不会去调。
        if (description == null || description.isBlank()) {
            description = Json.str(raw, "annotations", "title");
        }
        String schema = raw.get("inputSchema") == null ? null : Json.write(raw.get("inputSchema"));
        return new McpToolInfo(name, description, schema);
    }
}
