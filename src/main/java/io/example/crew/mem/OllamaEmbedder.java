package io.example.crew.mem;

import io.example.crew.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地 Ollama 嵌入客户端，调用 {@code POST /api/embed}（批量）。
 *
 * <p>四件事决定了这个类的形状：
 *
 * <p><b>① 批量而不是逐条。</b>索引一份文档会切出几十个分块，逐条请求的耗时几乎全在往返延迟上。
 * bge-m3 一次吃 8~16 条最划算；再大反而因为单次推理变长而没有收益。
 *
 * <p><b>② 缓存。</b>记忆检索每次都要嵌入查询文本，而任务描述在同一轮里会反复出现
 * （构造检索、渲染、调试打印）。缓存键就是原文，LRU 上限 2048 条，
 * 一份千条记忆的库也只占约 5 MB。
 *
 * <p><b>③ 老接口降级。</b>{@code /api/embeddings}（单条 {@code prompt} → {@code embedding}）是
 * 旧版接口，{@code /api/embed} 是 0.2.0 之后的新接口。首次请求遇到 404 就记住并降级，
 * 之后全程走旧接口——这比在文档里写"请升级 Ollama" 有用得多。
 *
 * <p><b>④ 基线按模型查表。</b>见 {@link #baseline()}。
 */
public final class OllamaEmbedder implements Embedder {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);
    private static final int BATCH_SIZE = 8;
    private static final int CACHE_LIMIT = 2048;

    /**
     * 各模型实测的无关文本相似度基线。
     *
     * <p>来源：本机实测，45 对互不相关的中文短句两两余弦，取<b>均值</b>。
     * bge-m3 的均值 0.3915、最大 0.5382——最大值这一项很关键：它说明在 bge-m3 上
     * 任何低于 0.54 的绝对阈值都挡不住噪声，所以检索层主要靠相对排序，基线只用于
     * 判断"整批候选是不是全是噪声"。
     *
     * <p>换模型 / 换语言 / 换文本长度都会让这个值变化，它只是一张经验表。
     * 需要精确值时用 {@link #probe(String, String)} 自己量。
     */
    private static final Map<String, Double> BASELINE_TABLE = Map.of(
            "bge-m3", 0.39,
            "bge-large", 0.34,
            "nomic-embed-text", 0.22,
            "mxbai-embed-large", 0.30);

    /** 未知模型的保守取值：宁可多过滤一点噪声，也不要让无关记忆挤占提示词。 */
    private static final double UNKNOWN_BASELINE = 0.30;

    private final String baseUrl;
    private final String model;
    private final double baselineOverride;
    private final HttpClient http;
    private final Map<String, float[]> cache;
    private volatile int dimension;
    private volatile boolean legacyApi;

    public OllamaEmbedder(String baseUrl, String model) {
        this(baseUrl, model, Double.NaN);
    }

    /**
     * @param baselineOverride 已知的无关联基线；传 {@link Double#NaN} 表示按模型名查表
     */
    public OllamaEmbedder(String baseUrl, String model, double baselineOverride) {
        // 与 OllamaClient 同样的坑：JDK HttpClient 会把 localhost 解析到 ::1，
        // 而 Ollama 只监听 IPv4，直接 ConnectException。
        String normalized = baseUrl.replace("//localhost:", "//127.0.0.1:");
        this.baseUrl = normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
        this.model = model;
        this.baselineOverride = baselineOverride;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                return size() > CACHE_LIMIT;
            }
        });
    }

    @Override
    public float[] embed(String text) {
        List<float[]> single = embedAll(List.of(text == null ? "" : text));
        return single.isEmpty() ? new float[0] : single.get(0);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        float[][] out = new float[texts.size()][];
        List<String> missing = new ArrayList<>();
        List<Integer> missingIndex = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i) == null ? "" : texts.get(i);
            float[] cached = cache.get(text);
            if (cached != null) {
                out[i] = cached;
            } else {
                missing.add(text);
                missingIndex.add(i);
            }
        }
        for (int start = 0; start < missing.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, missing.size());
            List<String> batch = missing.subList(start, end);
            List<float[]> vectors = legacyApi ? embedLegacy(batch) : embedBatch(batch);
            for (int j = 0; j < vectors.size(); j++) {
                float[] vector = vectors.get(j);
                out[missingIndex.get(start + j)] = vector;
                cache.put(batch.get(j), vector);
            }
        }
        List<float[]> result = new ArrayList<>(out.length);
        for (float[] v : out) {
            result.add(v == null ? new float[0] : v);
        }
        return result;
    }

    private List<float[]> embedBatch(List<String> batch) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"model\":\"").append(Json.escape(model)).append("\",\"input\":[");
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(Json.escape(batch.get(i))).append('"');
        }
        sb.append("]}");

        HttpResponse<String> response = post("/api/embed", sb.toString());
        if (response.statusCode() == 404) {
            legacyApi = true;
            return embedLegacy(batch);
        }
        Map<String, Object> json = parse(response, "/api/embed");
        Object raw = json.get("embeddings");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new MemException("嵌入响应缺少 embeddings 字段（模型 " + model
                    + "，响应片段：" + abbreviate(response.body()) + "）");
        }
        List<float[]> out = new ArrayList<>(list.size());
        for (Object item : list) {
            out.add(toVector(item));
        }
        if (out.size() != batch.size()) {
            throw new MemException("嵌入条数不符：请求 " + batch.size() + " 条，返回 " + out.size() + " 条");
        }
        return out;
    }

    /** 旧版接口 {@code /api/embeddings}，一次一条，只能循环。 */
    private List<float[]> embedLegacy(List<String> batch) {
        List<float[]> out = new ArrayList<>(batch.size());
        for (String text : batch) {
            String body = "{\"model\":\"" + Json.escape(model) + "\",\"prompt\":\""
                    + Json.escape(text) + "\"}";
            HttpResponse<String> response = post("/api/embeddings", body);
            Map<String, Object> json = parse(response, "/api/embeddings");
            out.add(toVector(json.get("embedding")));
        }
        return out;
    }

    private float[] toVector(Object raw) {
        if (!(raw instanceof List<?> list)) {
            throw new MemException("嵌入向量格式不正确，期望数组，实际：" + raw);
        }
        float[] vector = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Object value = list.get(i);
            vector[i] = value instanceof Number n ? n.floatValue() : 0f;
        }
        if (dimension == 0) {
            dimension = vector.length;
        } else if (dimension != vector.length) {
            throw new MemException("嵌入维度不一致：已有 " + dimension + " 维，本次 " + vector.length + " 维");
        }
        return vector;
    }

    private HttpResponse<String> post(String path, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new MemException("无法连接 Ollama 嵌入接口 (" + baseUrl + ")。"
                    + "请确认服务已启动，且已拉取嵌入模型：ollama pull " + model, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MemException("嵌入调用被中断", e);
        }
    }

    private Map<String, Object> parse(HttpResponse<String> response, String path) {
        if (response.statusCode() >= 400) {
            throw new MemException("嵌入接口 " + path + " 返回 HTTP " + response.statusCode()
                    + "（若提示模型不存在，先执行 ollama pull " + model + "）："
                    + abbreviate(response.body()));
        }
        try {
            return Json.parseObject(response.body());
        } catch (RuntimeException e) {
            throw new MemException("嵌入响应无法解析：" + abbreviate(response.body()), e);
        }
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public double baseline() {
        if (!Double.isNaN(baselineOverride)) {
            return baselineOverride;
        }
        String key = model.contains(":") ? model.substring(0, model.indexOf(':')) : model;
        return BASELINE_TABLE.getOrDefault(key, UNKNOWN_BASELINE);
    }

    /**
     * 实测某个模型在"无关文本"上的相似度基线，供调阈值时参考。
     *
     * <p>不放进 {@link #baseline()} 自动执行，是因为它会额外触发一次模型加载（冷启动数秒），
     * 而查表结果在绝大多数场景已经够用。需要精确值时手动跑一次。
     *
     * <p>用法：用 3 组互不相关的句子，两两算余弦取均值——单对样本噪声太大。
     */
    public double probe(String textA, String textB) {
        List<float[]> vectors = embedAll(List.of(textA, textB));
        return Vectors.cosine(vectors.get(0), vectors.get(1));
    }

    /** 清空嵌入缓存，用于测试或换模型后。 */
    public void clearCache() {
        cache.clear();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
