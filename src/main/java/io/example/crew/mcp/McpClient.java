package io.example.crew.mcp;

import io.example.crew.util.Json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP 客户端（JSON-RPC 2.0 over stdio）。
 *
 * <p>只实现"工具调用"这条最小可用链路：{@code initialize} → {@code tools/list} → {@code tools/call}。
 * Resources、Prompts、Sampling、Elicitation 都不做——它们不是多智能体流程的必需品，
 * 而每加一项就多一套状态机。
 *
 * <p>三个协议细节决定了实现的形状：
 * <ul>
 *   <li><b>响应不保证按顺序返回</b>，因此不能"发一条读一条"，必须靠 {@code id} 配平。
 *       读线程统一分发，主线程按 future 等待。</li>
 *   <li><b>服务端会发通知</b>（如 {@code notifications/tools/list_changed}），可能夹在任何两条响应之间。
 *       解析时不能假设"下一条就是我要的"。</li>
 *   <li><b>服务端也可能反向请求客户端</b>（sampling / roots）。必须显式回一个
 *       "方法未实现"错误，否则服务端会一直挂在那里等响应，表现为客户端超时。</li>
 * </ul>
 */
public final class McpClient implements AutoCloseable {

    /** 由新到旧尝试。新版服务端遇到不认识的版本会直接报错，而不是降级。 */
    private static final List<String> PROTOCOL_VERSIONS = List.of("2025-06-18", "2024-11-05");

    private static final int INIT_TIMEOUT_MILLIS = 60_000;
    private static final int REQUEST_TIMEOUT_MILLIS = 60_000;
    private static final int MAX_LIST_PAGES = 20;

    private final McpTransport transport;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final Thread reader;
    private final int requestTimeoutMillis;

    private volatile boolean closed;
    private volatile String protocolVersion = "";
    private volatile String serverName = "unknown";
    private volatile String serverVersion = "";
    private volatile Map<String, Object> serverCapabilities = Map.of();
    private volatile String failure;

    private McpClient(McpTransport transport, int requestTimeoutMillis) {
        this.transport = transport;
        this.requestTimeoutMillis = requestTimeoutMillis;
        this.reader = new Thread(this::readLoop, "mcp-reader");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    /** 启动子进程并建立连接。 */
    public static McpClient connect(List<String> command) {
        return connect(command, REQUEST_TIMEOUT_MILLIS);
    }

    public static McpClient connect(List<String> command, int requestTimeoutMillis) {
        McpClient client = new McpClient(new StdioTransport(command), requestTimeoutMillis);
        try {
            client.initialize();
            return client;
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
    }

    /** 复用已有传输（便于测试或接入远程传输）。 */
    public static McpClient connect(McpTransport transport) {
        return connect(transport, REQUEST_TIMEOUT_MILLIS);
    }

    public static McpClient connect(McpTransport transport, int requestTimeoutMillis) {
        McpClient client = new McpClient(transport, requestTimeoutMillis);
        try {
            client.initialize();
            return client;
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
    }

    // ------------------------------------------------------------------ 握手

    private void initialize() {
        McpException last = null;
        for (String version : PROTOCOL_VERSIONS) {
            try {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("protocolVersion", version);
                params.put("capabilities", Map.of());
                params.put("clientInfo", Map.of("name", "crewai-java-demo", "version", "1.0"));

                Map<String, Object> result = request("initialize", params, INIT_TIMEOUT_MILLIS);
                this.protocolVersion = nvl(Json.str(result, "protocolVersion"), version);
                this.serverName = nvl(Json.str(result, "serverInfo", "name"), "unknown");
                this.serverVersion = nvl(Json.str(result, "serverInfo", "version"), "");
                Object caps = result.get("capabilities");
                this.serverCapabilities = caps instanceof Map<?, ?> m ? cast(m) : Map.of();

                // 握手最后一步是通知，不是请求：服务端不等回应。
                notify("notifications/initialized", null);
                return;
            } catch (McpException e) {
                // 只在"服务端明确拒绝"时换版本；超时或进程死亡换版本没有意义，会白等一轮。
                if (!e.isRequestRejected()) {
                    throw e;
                }
                last = e;
            }
        }
        throw new McpException("initialize 失败：协议版本均不被接受 " + PROTOCOL_VERSIONS
                + (last == null ? "" : "，最后一次原因：" + last.getMessage()), last);
    }

    // ------------------------------------------------------------------ 能力

    /** 列出全部工具，自动跟随 {@code nextCursor} 翻页。 */
    public List<McpToolInfo> listTools() {
        List<McpToolInfo> all = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_LIST_PAGES; page++) {
            Map<String, Object> params = cursor == null ? Map.of() : Map.of("cursor", cursor);
            Map<String, Object> result = request("tools/list", params, requestTimeoutMillis);
            for (Object item : asList(result.get("tools"))) {
                if (item instanceof Map<?, ?> map) {
                    all.add(McpToolInfo.from(cast(map)));
                }
            }
            cursor = Json.str(result, "nextCursor");
            if (cursor == null || cursor.isBlank()) {
                break;
            }
        }
        return all;
    }

    /**
     * 调用工具。
     *
     * <p>注意 {@code isError} 与 JSON-RPC {@code error} 是两回事：前者表示工具执行完了但失败了
     * （参数不对、文件不存在），属于"业务失败"，应当交给模型看；后者表示协议层出错，属于"连接坏了"。
     */
    public McpCallResult callTool(String name, Map<String, Object> arguments) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("arguments", arguments == null ? Map.of() : arguments);

        Map<String, Object> result = request("tools/call", params, requestTimeoutMillis);
        boolean isError = Boolean.TRUE.equals(result.get("isError"));
        return new McpCallResult(isError, flattenContent(result.get("content")));
    }

    /**
     * 把 {@code content} 数组压成一个字符串。
     *
     * <p>MCP 的返回是"内容块数组"，可以同时包含文本、图片、音频、资源引用。
     * 这里只拼接文本块，其余类型降级成占位标记——多模态内容无法塞进纯文本对话，
     * 但完全不提会让模型误以为工具什么都没返回。
     */
    private static String flattenContent(Object content) {
        List<Object> blocks = asList(content);
        if (blocks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object block : blocks) {
            if (!(block instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> b = cast(map);
            String type = nvl(Json.str(b, "type"), "text");
            if ("text".equals(type)) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(nvl(Json.str(b, "text"), ""));
            } else {
                String mime = nvl(Json.str(b, "mimeType"), "未知类型");
                sb.append("\n[").append(type).append(" 内容已省略：").append(mime).append("]");
            }
        }
        return sb.toString().strip();
    }

    // ------------------------------------------------------------------ 元信息

    public String protocolVersion() {
        return protocolVersion;
    }

    public String serverName() {
        return serverName;
    }

    public String serverVersion() {
        return serverVersion;
    }

    public Map<String, Object> serverCapabilities() {
        return serverCapabilities;
    }

    public String diagnostics() {
        return transport.diagnostics();
    }

    // ------------------------------------------------------------------ JSON-RPC

    private Map<String, Object> request(String method, Map<String, Object> params, int timeoutMillis) {
        int id = nextId.getAndIncrement();
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pending.put(id, future);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("method", method);
        if (params != null) {
            body.put("params", params);
        }
        try {
            transport.send(Json.write(body));
        } catch (IOException e) {
            pending.remove(id);
            throw new McpException("发送 " + method + " 失败：" + e.getMessage(), e);
        }

        Map<String, Object> message;
        try {
            message = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new McpException(method + " 超时（" + timeoutMillis + "ms）—— 服务端未响应。诊断："
                    + transport.diagnostics());
        } catch (InterruptedException e) {
            pending.remove(id);
            Thread.currentThread().interrupt();
            throw new McpException(method + " 被中断", e);
        } catch (ExecutionException e) {
            pending.remove(id);
            throw new McpException(method + " 失败：" + e.getCause().getMessage(), e.getCause());
        }

        if (message.containsKey("error")) {
            Map<String, Object> error = message.get("error") instanceof Map<?, ?> m ? cast(m) : Map.of();
            int code = (int) Json.num(error, "code");
            String detail = nvl(Json.str(error, "message"), "未知错误");
            throw new McpException(method + " 返回错误 " + code + "：" + detail, code);
        }
        Object result = message.get("result");
        return result instanceof Map<?, ?> m ? cast(m) : Map.of();
    }

    private void notify(String method, Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("method", method);
        if (params != null) {
            body.put("params", params);
        }
        try {
            transport.send(Json.write(body));
        } catch (IOException e) {
            throw new McpException("发送通知 " + method + " 失败：" + e.getMessage(), e);
        }
    }

    private void readLoop() {
        while (!closed) {
            String line;
            try {
                line = transport.receive();
            } catch (IOException e) {
                failAll("读取失败：" + e.getMessage());
                return;
            }
            if (line == null) {
                failAll("服务端关闭了输出流：" + transport.diagnostics());
                return;
            }
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> message;
            try {
                message = Json.parseObject(line);
            } catch (RuntimeException e) {
                // 服务端把日志打到了 stdout，破坏了分帧。忽略这一行比整个连接报废更合理。
                continue;
            }
            dispatch(message);
        }
    }

    private void dispatch(Map<String, Object> message) {
        Object idValue = message.get("id");
        boolean hasMethod = message.containsKey("method");

        if (idValue == null) {
            // 服务端通知（list_changed / progress / log），当前不需要处理
            return;
        }
        if (!(idValue instanceof Number number)) {
            return;
        }
        int id = number.intValue();

        if (hasMethod) {
            // 服务端反向请求：必须显式拒绝，否则它会一直等
            Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("jsonrpc", "2.0");
            reply.put("id", id);
            reply.put("error", Map.of("code", -32601,
                    "message", "客户端未实现 " + message.get("method") + "，仅支持工具调用"));
            try {
                transport.send(Json.write(reply));
            } catch (IOException ignored) {
                // 发送失败说明连接已坏，主线程会从超时或 failAll 感知
            }
            return;
        }

        CompletableFuture<Map<String, Object>> future = pending.remove(id);
        if (future != null) {
            future.complete(message);
        }
    }

    private void failAll(String reason) {
        failure = reason;
        for (Integer id : List.copyOf(pending.keySet())) {
            CompletableFuture<Map<String, Object>> future = pending.remove(id);
            if (future != null) {
                future.completeExceptionally(new McpException(reason));
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        failAll("客户端已关闭");
        reader.interrupt();
        transport.close();
    }

    // ------------------------------------------------------------------ 小工具

    private static String nvl(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static List<Object> asList(Object value) {
        return value instanceof List<?> list ? List.copyOf(list) : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /** 供将来扩展远程传输时读取失败原因。 */
    public String failure() {
        return failure;
    }
}
