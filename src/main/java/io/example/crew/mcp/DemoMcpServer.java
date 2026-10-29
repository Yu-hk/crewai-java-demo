package io.example.crew.mcp;

import io.example.crew.util.Json;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个可独立运行的 MCP 服务端（stdio），用来在没有任何外部依赖的环境里验证客户端。
 *
 * <p>存在的理由：连 {@code npx @modelcontextprotocol/server-everything} 需要装 Node 和网络，
 * 而"客户端到底能不能正确握手、能不能正确解析 tool 结果"这件事不应该依赖外部环境才能验证。
 * 这个类就是协议的最小合法对端——也顺便示范了服务端一侧要做哪些事。
 *
 * <p>三条纪律，也是所有 MCP 服务端都必须遵守的：
 * <ul>
 *   <li>stdout <b>只</b>能输出协议消息，一行一条 JSON。任何多打的字符都会破坏分帧。</li>
 *   <li>日志一律走 stderr。往 stdout 打一行 "listening..." 就足以让客户端解析失败。</li>
 *   <li>通知（无 id）不回复。</li>
 * </ul>
 *
 * <pre>{@code
 * java -cp out io.example.crew.mcp.DemoMcpServer    # 手工试：粘贴一行 JSON 请求，回车
 * }</pre>
 */
public final class DemoMcpServer {

    static final String SERVER_NAME = "crew-demo-tools";
    static final String SERVER_VERSION = "1.0.0";
    private static final List<String> SUPPORTED_VERSIONS = List.of("2025-06-18", "2024-11-05");

    private final PrintStream out;
    private final BufferedReader in;
    private final java.util.concurrent.atomic.AtomicInteger reverseId = new java.util.concurrent.atomic.AtomicInteger(9000);

    DemoMcpServer(PrintStream out, BufferedReader in) {
        this.out = out;
        this.in = in;
    }

    public static void main(String[] args) throws Exception {
        // 关键：必须显式 UTF-8。Windows 上 System.out 默认 GBK，中文结果会直接乱码。
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream log = System.err;
        log.println("[" + SERVER_NAME + "] started, protocol versions " + SUPPORTED_VERSIONS);

        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            DemoMcpServer server = new DemoMcpServer(out, in);
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> request;
                try {
                    request = Json.parseObject(line);
                } catch (RuntimeException e) {
                    log.println("[" + SERVER_NAME + "] 无法解析的请求行：" + e.getMessage());
                    continue;
                }
                Map<String, Object> response = server.handle(request);
                if (response != null) {
                    server.send(response);
                }
            }
        }
        log.println("[" + SERVER_NAME + "] stdin closed, exiting");
    }

    private void send(Map<String, Object> message) {
        out.println(Json.write(message));
        out.flush();
    }

    /**
     * 由服务端主动向客户端发起请求（MCP 的"反向请求"），同步等待响应。
     *
     * <p>这是协议里最容易被客户端实现漏掉的一条：服务端会发起 {@code roots/list}、
     * {@code sampling/createMessage}、{@code elicitation/create}，然后一直等回应。
     * 客户端若把这类入站消息当成"不认识的通知"直接丢弃，服务端就会永久挂住，
     * 表现为客户端那边"工具调用超时"，而真正的责任方根本没留下日志。
     *
     * <p>等待期间客户端可能发来别的请求（正常不会，因为它在等工具结果），
     * 这里顺手处理掉，避免把消息吞了。
     */
    private Map<String, Object> reverseRequest(String method, Map<String, Object> params) {
        int id = reverseId.incrementAndGet();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", params);
        send(request);

        try {
            int guard = 0;
            String line;
            while ((line = in.readLine()) != null && guard++ < 100) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> message;
                try {
                    message = Json.parseObject(line);
                } catch (RuntimeException e) {
                    continue;
                }
                Object rid = message.get("id");
                if (rid instanceof Number n && n.intValue() == id) {
                    return message;
                }
                Map<String, Object> response = handle(message);
                if (response != null) {
                    send(response);
                }
            }
        } catch (java.io.IOException e) {
            System.err.println("[" + SERVER_NAME + "] 反向请求读失败：" + e.getMessage());
        }
        return null;
    }

    /** 处理一条请求；返回 null 表示不应回复（通知）。 */
    Map<String, Object> handle(Map<String, Object> request) {
        Object idValue = request.get("id");
        String method = Json.str(request, "method");

        if (idValue == null) {
            // 通知：notifications/initialized 等，协议规定不回复
            return null;
        }
        if (method == null) {
            return error(idValue, -32600, "缺少 method");
        }

        Map<String, Object> params = request.get("params") instanceof Map<?, ?> m ? cast(m) : Map.of();

        return switch (method) {
            case "initialize" -> result(idValue, initialize(params));
            case "ping" -> result(idValue, Map.of());
            case "tools/list" -> result(idValue, toolsList());
            case "tools/call" -> result(idValue, toolsCall(params));
            default -> error(idValue, -32601, "未实现的方法：" + method);
        };
    }

    private Map<String, Object> initialize(Map<String, Object> params) {
        String requested = Json.str(params, "protocolVersion");
        String agreed = SUPPORTED_VERSIONS.contains(requested) ? requested : SUPPORTED_VERSIONS.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", agreed);
        result.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        result.put("serverInfo", Map.of("name", SERVER_NAME, "version", SERVER_VERSION));
        return result;
    }

    private Map<String, Object> toolsList() {
        List<Object> tools = new ArrayList<>();

        Map<String, Object> wordCount = new LinkedHashMap<>();
        wordCount.put("name", "word_count");
        wordCount.put("description", "统计一段文本的字符数、中文字数与非空白字符数。"
                + "当需要确认篇幅是否满足字数上限时使用，比心算可靠。");
        Map<String, Object> wordCountProps = new LinkedHashMap<>();
        wordCountProps.put("text", Map.of("type", "string", "description", "待统计的文本"));
        wordCountProps.put("limit", Map.of("type", "integer", "description", "字符数上限，省略则不判断超限"));
        wordCount.put("inputSchema", Map.of(
                "type", "object",
                "properties", wordCountProps,
                "required", List.of("text")));
        tools.add(wordCount);

        Map<String, Object> now = new LinkedHashMap<>();
        now.put("name", "now");
        now.put("description", "获取当前本地时间（ISO-8601 格式）。需要给文档落款日期时使用。");
        now.put("inputSchema", Map.of("type", "object", "properties", Map.of()));
        tools.add(now);

        Map<String, Object> echo = new LinkedHashMap<>();
        echo.put("name", "echo.debug");
        echo.put("description", "原样返回输入文本，用于连通性排查。名字里带点，用于验证客户端的工具名合法化。");
        echo.put("inputSchema", Map.of(
                "type", "object",
                "properties", Map.of("text", Map.of("type", "string")),
                "required", List.of("text")));
        tools.add(echo);

        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("name", "probe_client");
        probe.put("description", "向客户端发起反向请求（roots/list），返回客户端的应答情况。仅用于验证协议实现。");
        probe.put("inputSchema", Map.of("type", "object", "properties", Map.of()));
        tools.add(probe);

        return Map.of("tools", tools);
    }

    private Map<String, Object> toolsCall(Map<String, Object> params) {
        String name = Json.str(params, "name");
        Map<String, Object> args = params.get("arguments") instanceof Map<?, ?> m ? cast(m) : Map.of();

        try {
            String text = switch (name == null ? "" : name) {
                case "word_count" -> wordCount(args);
                case "now" -> ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                case "echo.debug" -> "echo: " + requireText(args, "text");
                case "probe_client" -> probeClient();
                default -> throw new IllegalArgumentException("未知工具：" + name);
            };
            return Map.of("content", List.of(Map.of("type", "text", "text", text)), "isError", false);
        } catch (IllegalArgumentException e) {
            // 业务失败用 isError 表达，让模型看到原因并自行修正参数
            return Map.of("content", List.of(Map.of("type", "text", "text", e.getMessage())), "isError", true);
        }
    }

    /** 发起一次反向请求，把客户端的应答形状描述成文本。 */
    private String probeClient() {
        Map<String, Object> response = reverseRequest("roots/list", Map.of());
        if (response == null) {
            System.err.println("[" + SERVER_NAME + "] probe: 客户端未回应 roots/list，连接已断或客户端读循环已停");
            return "客户端未回应 roots/list（连接中断）";
        }
        if (response.containsKey("error")) {
            Map<String, Object> error = response.get("error") instanceof Map<?, ?> m ? cast(m) : Map.of();
            int code = (int) Json.num(error, "code");
            System.err.println("[" + SERVER_NAME + "] probe: 客户端以 code=" + code + " 拒绝了 roots/list");
            return "客户端明确拒绝了 roots/list，code=" + code
                    + "，message=" + Json.str(error, "message") + "（符合预期：客户端不支持该能力）";
        }
        System.err.println("[" + SERVER_NAME + "] probe: 客户端实现了 roots/list");
        return "客户端实现了 roots/list，返回：" + Json.write(response.get("result"));
    }

    private static String wordCount(Map<String, Object> args) {
        String text = requireText(args, "text");
        int total = text.length();
        int chinese = 0;
        int nonBlank = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                chinese++;
            }
            if (!Character.isWhitespace(c)) {
                nonBlank++;
            }
        }
        String base = "总字符数 %d，中文 %d 字，非空白 %d 字符".formatted(total, chinese, nonBlank);
        Object limit = args.get("limit");
        if (limit instanceof Number n) {
            int max = n.intValue();
            base += total <= max
                    ? "；未超出上限 " + max
                    : "；已超出上限 " + max + " 共 " + (total - max) + " 字，需精简";
        }
        return base;
    }

    private static String requireText(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("参数 " + key + " 必须是非空字符串，收到：" + value);
        }
        return s;
    }

    // ------------------------------------------------------------------ JSON-RPC 信封

    private static Map<String, Object> result(Object id, Map<String, Object> value) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("result", value);
        return message;
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", id);
        envelope.put("error", Map.of("code", code, "message", message));
        return envelope;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
