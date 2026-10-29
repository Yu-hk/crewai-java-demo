package io.example.crew.llm;

import io.example.crew.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地 Ollama 客户端，调用 {@code POST /api/chat}（非流式）。
 *
 * <p>这个类刻意做"笨"：<b>只发一次请求，不做重试、不做限流</b>。
 * 重试与限流属于运行时策略，放在 {@link ResilientLlmClient} 装饰器里，
 * 这样换成云端 provider 时策略代码不用重写。类内唯一的判断是"这次失败值不值得重试"，
 * 通过 {@link LlmException#retryable()} 交给上游。
 *
 * <p>关键取舍：
 * <ul>
 *   <li>{@code stream=false} —— 示例要的是链路正确性，不是打字机效果。</li>
 *   <li>{@code num_ctx=8192} —— Ollama 默认上下文仅 2048，多任务链路会把前序产出塞进
 *       prompt，不显式抬高会被静默截断，这是本地模型最常见的"莫名其妙丢上下文"原因。</li>
 *   <li>{@code tools[]} —— 走原生 function calling，由 Ollama 侧约束输出格式。</li>
 * </ul>
 */
public final class OllamaClient implements LlmClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private static final int NUM_CTX = 8192;

    private final String baseUrl;
    private final String model;
    private final boolean nativeTools;
    private final HttpClient http;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();

    public OllamaClient(String baseUrl, String model) {
        this(baseUrl, model, true);
    }

    /**
     * @param nativeTools 是否启用原生 function calling。
     *                    设为 false 可强制走文本协议，用于对比两种协议在同一模型上的成功率
     */
    public OllamaClient(String baseUrl, String model, boolean nativeTools) {
        // 关键细节：Ollama 默认只监听 IPv4 127.0.0.1，而 JDK HttpClient 会把 localhost
        // 优先解析到 ::1，直接抛 ConnectException（curl 会自动回退所以感知不到）。
        // 因此这里把 localhost 归一化为 127.0.0.1。
        String normalized = baseUrl.replace("//localhost:", "//127.0.0.1:");
        this.baseUrl = normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
        this.model = model;
        this.nativeTools = nativeTools;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public ChatReply chat(List<ChatMessage> messages, double temperature, List<ToolSpec> tools) {
        String body = buildBody(messages, temperature, tools);
        HttpResponse<String> response = send(body);

        Map<String, Object> json;
        try {
            json = Json.parseObject(response.body());
        } catch (RuntimeException e) {
            throw new LlmException("Ollama 返回了无法解析的响应 (HTTP " + response.statusCode() + "): "
                    + abbreviate(response.body()), false, e);
        }

        String errorText = Json.str(json, "error");
        if (errorText != null) {
            throw new LlmException("Ollama 报错: " + errorText
                    + "（若提示模型不存在，先执行 ollama pull " + model + "）", false, null);
        }
        if (response.statusCode() >= 400) {
            throw new LlmException("Ollama HTTP " + response.statusCode() + ": " + abbreviate(response.body()),
                    response.statusCode(), isRetryableStatus(response.statusCode()), null);
        }

        calls.incrementAndGet();
        promptTokens.addAndGet(Json.num(json, "prompt_eval_count"));
        completionTokens.addAndGet(Json.num(json, "eval_count"));

        String content = Json.str(json, "message", "content");
        return new ChatReply(content == null ? "" : content, parseToolCalls(json));
    }

    @Override
    public boolean supportsNativeTools() {
        return nativeTools;
    }

    private HttpResponse<String> send(String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new LlmException("无法连接 Ollama (" + baseUrl + ")。请确认服务已启动：ollama serve；"
                    + "已安装模型：ollama list。底层原因: " + e.getMessage(), true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("调用 Ollama 被中断", false, e);
        }
    }

    /** 429 与 5xx 属于暂时性故障；401/403/404 属于配置错误，重试无意义。 */
    private static boolean isRetryableStatus(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    @SuppressWarnings("unchecked")
    private static List<NativeToolCall> parseToolCalls(Map<String, Object> json) {
        Object messageRaw = json.get("message");
        if (!(messageRaw instanceof Map<?, ?> message)) {
            return List.of();
        }
        Object callsRaw = message.get("tool_calls");
        if (!(callsRaw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<NativeToolCall> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> call)) {
                continue;
            }
            Object functionRaw = call.get("function");
            if (!(functionRaw instanceof Map<?, ?> function)) {
                continue;
            }
            Object name = function.get("name");
            if (name == null) {
                continue;
            }
            Object args = function.get("arguments");
            Map<String, Object> arguments = Map.of();
            if (args instanceof Map<?, ?> map) {
                arguments = (Map<String, Object>) map;
            } else if (args instanceof String s && !s.isBlank()) {
                // 部分 provider / 版本会把 arguments 序列化成字符串，这里容忍。
                try {
                    arguments = Json.parseObject(s);
                } catch (RuntimeException ignored) {
                    arguments = Map.of();
                }
            }
            result.add(new NativeToolCall(String.valueOf(name), arguments));
        }
        return result;
    }

    private String buildBody(List<ChatMessage> messages, double temperature, List<ToolSpec> tools) {
        StringBuilder sb = new StringBuilder(512 + messages.size() * 512);
        sb.append("{\"model\":\"").append(Json.escape(model)).append("\",");
        sb.append("\"stream\":false,");
        sb.append("\"options\":{\"temperature\":").append(temperature)
                .append(",\"num_ctx\":").append(NUM_CTX).append("},");
        if (tools != null && !tools.isEmpty()) {
            sb.append("\"tools\":").append(toolsJson(tools)).append(',');
        }
        sb.append("\"messages\":[");
        StringJoiner joiner = new StringJoiner(",");
        for (ChatMessage message : messages) {
            joiner.add(messageJson(message));
        }
        sb.append(joiner).append("]}");
        return sb.toString();
    }

    private static String toolsJson(List<ToolSpec> tools) {
        List<Object> specs = new ArrayList<>(tools.size());
        for (ToolSpec tool : tools) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.put("parameters", safeParse(tool.parametersJson()));
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("type", "function");
            wrapper.put("function", function);
            specs.add(wrapper);
        }
        return Json.write(specs);
    }

    private static Object safeParse(String json) {
        try {
            return Json.parse(json);
        } catch (RuntimeException e) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("type", "object");
            empty.put("properties", Map.of());
            return empty;
        }
    }

    private static String messageJson(ChatMessage message) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("role", message.role());
        json.put("content", message.content() == null ? "" : message.content());
        if (message.toolName() != null) {
            json.put("tool_name", message.toolName());
        }
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            List<Object> calls = new ArrayList<>(message.toolCalls().size());
            for (NativeToolCall call : message.toolCalls()) {
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", call.name());
                function.put("arguments", call.arguments());
                Map<String, Object> wrapper = new LinkedHashMap<>();
                wrapper.put("function", function);
                calls.add(wrapper);
            }
            json.put("tool_calls", calls);
        }
        return Json.write(json);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public Usage usage() {
        return new Usage(calls.get(), promptTokens.get(), completionTokens.get());
    }
}
