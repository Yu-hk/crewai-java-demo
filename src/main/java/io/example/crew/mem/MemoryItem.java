package io.example.crew.mem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条记忆。
 *
 * <p>字段里最容易被砍掉、但最不该砍的是 {@code importance}。纯向量检索只看"像不像"，
 * 而"用户要求所有输出必须用中文"这类信息，与某个具体任务描述的相似度可能只有 0.4——
 * 按纯向量排它永远排不进 topK，可它一旦丢失，后面每一步产出都跑偏。
 * 价值高的信息往往字面相关性低，这是关键词与向量共同的天花板。
 *
 * <p>`id` 取文本内容的哈希而非随机 UUID：同样的文本天然落到同一个 id，
 * 重复写入变成覆盖，不需要额外的去重簿记。语义级重复（措辞不同）另由相似度判定处理。
 */
public record MemoryItem(
        String id,
        MemoryScope scope,
        String text,
        Map<String, String> meta,
        float[] embedding,
        long createdAt,
        long lastUsedAt,
        double importance,
        int hits) {

    public MemoryItem {
        meta = meta == null ? Map.of() : Map.copyOf(meta);
    }

    /** 新建一条记忆。importance 会被夹到 [0,1]。 */
    public static MemoryItem create(MemoryScope scope, String text, float[] embedding,
                                    Map<String, String> meta, double importance, long now) {
        String body = text == null ? "" : text.strip();
        return new MemoryItem(
                fingerprint(scope, body),
                scope,
                body,
                meta,
                embedding,
                now,
                now,
                clamp(importance),
                0);
    }

    /** 命中一次：刷新使用时间并累加计数。recency 打分依赖 lastUsedAt，不是 createdAt。 */
    public MemoryItem touched(long now) {
        return new MemoryItem(id, scope, text, meta, embedding, createdAt, now, importance, hits + 1);
    }

    /**
     * 与另一条"语义重复"的记忆合并。
     *
     * <p>三个字段的处理各有理由：
     * <ul>
     *   <li>{@code id} / {@code createdAt} 保留旧的——它们是这条记忆的出生证明，
     *       换了 id 就等于在存储里凭空多一条。</li>
     *   <li>文本取新的（可能更完整），但<b>向量保留旧的</b>。两者相似度已经超过 0.95，
     *       语义等价；而 id 与向量一旦不一致，跨进程存档就会出现"同一条记忆两次算出的
     *       相似度不同"的现象。</li>
     *   <li>{@code importance} 取两者较大值——合并不能降级一条重要记忆的分量。</li>
     * </ul>
     *
     * <p>{@code hits} 表示"这条记忆被重申了几次"，合并一次加一。它是纯粹的统计量，
     * 不参与打分（参与打分会导致反复写入的记忆永远排在前面，形成自我强化）。
     */
    public MemoryItem merge(MemoryItem other, long now) {
        Map<String, String> merged = new LinkedHashMap<>(meta);
        merged.putAll(other.meta);
        return new MemoryItem(id, scope,
                other.text == null || other.text.isBlank() ? text : other.text,
                merged,
                embedding,
                createdAt, now, Math.max(importance, other.importance), Math.max(hits, other.hits) + 1);
    }

    /** 距某个时刻的小时数，用于 recency 衰减。 */
    public double ageHours(long now) {
        return Math.max(0, (now - lastUsedAt) / 3_600_000.0);
    }

    public String meta(String key) {
        return meta.get(key);
    }

    /**
     * 是否为常驻记忆。
     *
     * <p>常驻记忆不参与检索，每次都会直接注入。存在这一类是因为有一批信息与<b>任何</b>
     * 任务描述都不相关，却对<b>所有</b>输出都有约束力——"标题只用二级""不要写套话""
     * 结论先行"都属此类。实测这类文本与任务主题的余弦相似度只有 0.33 左右，
     * 低于无关基线 0.39，任何检索阈值都会把它们筛掉。
     *
     * <p>把它们塞进系统提示词也是一种做法，但那样就固化了：想改一条约定得重新发版。
     * 放进记忆库并标记为常驻，约定就可以像数据一样增删。
     */
    public boolean pinned() {
        return "true".equalsIgnoreCase(meta.get("pinned"));
    }

    /** 摘要，用于日志与控制台。 */
    public String summary(int maxChars) {
        String flat = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        return flat.length() <= maxChars ? flat : flat.substring(0, maxChars) + "…";
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.5;
        }
        return Math.max(0, Math.min(1, value));
    }

    /** 内容指纹：作用域 + 文本，取 SHA-256 前 12 个 hex 字符。 */
    static String fingerprint(MemoryScope scope, String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((scope.name() + "\u0000" + text).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走到这里说明运行环境异常
            return Integer.toHexString((scope.name() + text).hashCode());
        }
    }
}
