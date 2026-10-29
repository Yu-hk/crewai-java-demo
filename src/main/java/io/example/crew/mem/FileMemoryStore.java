package io.example.crew.mem;

import io.example.crew.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 JSON Lines 的持久化存储。
 *
 * <p>为什么是 JSONL 而不是一个 JSON 数组：追加一行就是一条新记忆，不必为了写一条而
 * 反序列化整个文件；某一行写坏了也只损失那一条，其余照常可读。存档损坏的爆炸半径要小。
 *
 * <p>写入用"临时文件 + 原子替换"：进程在写一半时被杀，留下的是完整的旧文件而不是半个新文件。
 * 这一点对记忆尤其重要——记忆是跨运行资产，损坏一次就永久丢了。
 *
 * <p>{@link #flush()} 是显式调用而不是每次 {@code put} 自动触发：索引一份 500 块的文档时，
 * 自动 flush 会变成 500 次全量重写。批量写入由调用方在结尾统一 flush 一次。
 */
public final class FileMemoryStore implements MemoryStore {

    private final Path file;
    private final Map<String, MemoryItem> items = new LinkedHashMap<>();
    private final List<String> loadErrors = new ArrayList<>();
    private boolean dirty;

    public FileMemoryStore(Path file) {
        this.file = file;
        load();
    }

    public Path file() {
        return file;
    }

    /** 加载时跳过的坏行数量与原因，便于排查存档损坏。 */
    public List<String> loadErrors() {
        return List.copyOf(loadErrors);
    }

    @Override
    public synchronized void put(MemoryItem item) {
        items.put(item.id(), item);
        dirty = true;
    }

    @Override
    public synchronized Optional<MemoryItem> get(String id) {
        return Optional.ofNullable(items.get(id));
    }

    @Override
    public synchronized List<MemoryItem> all() {
        return new ArrayList<>(items.values());
    }

    @Override
    public synchronized List<MemoryItem> byScope(MemoryScope scope) {
        List<MemoryItem> out = new ArrayList<>();
        for (MemoryItem item : items.values()) {
            if (item.scope() == scope) {
                out.add(item);
            }
        }
        return out;
    }

    @Override
    public synchronized int size() {
        return items.size();
    }

    @Override
    public synchronized boolean remove(String id) {
        boolean removed = items.remove(id) != null;
        dirty |= removed;
        return removed;
    }

    @Override
    public synchronized void clear() {
        items.clear();
        dirty = true;
    }

    @Override
    public synchronized void flush() {
        if (!dirty) {
            return;
        }
        StringBuilder sb = new StringBuilder(items.size() * 512);
        for (MemoryItem item : items.values()) {
            sb.append(Json.write(toMap(item))).append('\n');
        }
        Path parent = file.toAbsolutePath().getParent();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                // 部分文件系统（如某些网络盘）不支持原子替换，退化为普通覆盖
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException e) {
            throw new MemException("写入记忆存档失败：" + file + "：" + e.getMessage(), e);
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MemException("读取记忆存档失败：" + file + "：" + e.getMessage(), e);
        }
        int lineNo = 0;
        for (String line : lines) {
            lineNo++;
            if (line.isBlank()) {
                continue;
            }
            try {
                MemoryItem item = fromMap(Json.parseObject(line));
                items.put(item.id(), item);
            } catch (RuntimeException e) {
                // 单行损坏不该让整个记忆库不可用
                loadErrors.add("第 " + lineNo + " 行：" + e.getMessage());
            }
        }
    }

    private static Map<String, Object> toMap(MemoryItem item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", item.id());
        map.put("scope", item.scope().name());
        map.put("text", item.text());
        if (!item.meta().isEmpty()) {
            map.put("meta", item.meta());
        }
        map.put("v", Vectors.encode(item.embedding()));
        map.put("createdAt", item.createdAt());
        map.put("lastUsedAt", item.lastUsedAt());
        map.put("importance", item.importance());
        map.put("hits", item.hits());
        return map;
    }

    @SuppressWarnings("unchecked")
    private static MemoryItem fromMap(Map<String, Object> map) {
        Object id = map.get("id");
        Object text = map.get("text");
        if (id == null || text == null) {
            throw new IllegalArgumentException("缺少 id 或 text 字段");
        }
        Map<String, String> meta = new LinkedHashMap<>();
        if (map.get("meta") instanceof Map<?, ?> rawMeta) {
            for (Map.Entry<?, ?> e : rawMeta.entrySet()) {
                meta.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        long createdAt = number(map.get("createdAt"), System.currentTimeMillis());
        return new MemoryItem(
                String.valueOf(id),
                MemoryScope.of(String.valueOf(map.get("scope"))),
                String.valueOf(text),
                meta,
                Vectors.decode(str(map.get("v"))),
                createdAt,
                number(map.get("lastUsedAt"), createdAt),
                doubleValue(map.get("importance"), 0.5),
                (int) number(map.get("hits"), 0));
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(Object value, long fallback) {
        return value instanceof Number n ? n.longValue() : fallback;
    }

    private static double doubleValue(Object value, double fallback) {
        return value instanceof Number n ? n.doubleValue() : fallback;
    }
}
