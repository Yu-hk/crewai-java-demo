package io.example.crew.mem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库：把外部文档切块、嵌入、存入 {@link MemoryScope#KNOWLEDGE}，再按语义检索。
 *
 * <p>对外看它是一套独立能力，实现上刻意<b>复用 {@link Memory} 的存储与检索</b>。
 * 理由：文档块和记忆条目在数据结构上完全同构（文本 + 向量 + 元数据），
 * 差别只在两个参数——作用域是 KNOWLEDGE、打分时不看时间也不看重要性。
 * 为这点差别另建一套存储和检索，会得到两份需要同步维护的相似代码。
 *
 * <p>两个参数上的差异值得单独说：
 * <ul>
 *   <li><b>不打时间分。</b>三个月前导入的规范和今天导入的规范等价，
 *       施加时间衰减会让检索系统性偏向新文档。</li>
 *   <li><b>不打重要性分。</b>{@link Importance} 的启发式识别的是"约束与教训"，
 *       文档块里到处是"必须""注意"，它会给所有块同样的高分，等于纯噪声。
 *       文档的相关性应当只由语义决定。</li>
 * </ul>
 *
 * <p>重新索引同一个文档名时会先清掉该文档的旧块。否则文档改版后新旧两版块会同时存在，
 * 检索把过时的段落当成现行规定引出来，这比检索不到更危险。
 */
public final class Knowledge {

    private final Memory memory;
    private final String name;
    private final int maxChars;
    private final int overlap;
    private final boolean markdown;

    private Knowledge(Builder b) {
        this.memory = b.memory;
        this.name = b.name;
        this.maxChars = b.maxChars;
        this.overlap = b.overlap;
        this.markdown = b.markdown;
    }

    public static Builder builder(String name, Memory memory) {
        return new Builder(name, memory);
    }

    public String name() {
        return name;
    }

    /** 索引一段纯文本，返回切出的块数。 */
    public int index(String text) {
        return index(text, markdown);
    }

    /**
     * 索引一段文本。
     *
     * @param asMarkdown 为 true 时按标题层级切节，适合 README / 设计文档；
     *                   为 false 时按标点与字符数切分，适合日志与纯文本
     */
    public int index(String text, boolean asMarkdown) {
        clear();
        List<String> bodies = asMarkdown
                ? Chunker.splitMarkdown(text, maxChars)
                : Chunker.split(text, maxChars, overlap);
        for (int i = 0; i < bodies.size(); i++) {
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("source", name);
            meta.put("chunk", String.valueOf(i));
            // importance 传固定值：这个字段在本作用域不参与打分（权重为 0），
            // 但写一个中性值能让 dump 与统计不出现无意义的高分。
            memory.remember(bodies.get(i), MemoryScope.KNOWLEDGE, meta, 0.5, false);
        }
        memory.store().flush();
        return bodies.size();
    }

    /** 索引一个文件，块中记录文件名。 */
    public int indexFile(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MemException("读取文档失败：" + file + "：" + e.getMessage(), e);
        }
        return index(text, markdown || file.toString().toLowerCase().endsWith(".md"));
    }

    /**
     * 检索相关段落。
     *
     * <p>权重给成纯语义（1, 0, 0）：知识库里只有一类条目，没有需要靠时间或重要性区分的东西，
     * 多给的那点权重只会引入噪声。
     */
    public List<Hit> retrieve(String query, int topK) {
        return memory.recall(MemoryQuery.builder()
                .text(query)
                .scopes(MemoryScope.KNOWLEDGE)
                .topK(topK)
                .weights(1.0, 0, 0)
                .build());
    }

    /** 检索并渲染成提示词片段，带上块序号便于模型引用。 */
    public String block(String query, int topK, int maxChars) {
        List<Hit> hits = retrieve(query, topK);
        if (hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append("【参考资料】\n")
                .append("以下是检索到的文档片段，可能对完成本任务有帮助。与任务无关的请忽略。\n");
        int used = sb.length();
        int ordinal = 0;
        for (Hit hit : hits) {
            ordinal++;
            String chunkNo = hit.item().meta("chunk");
            String body = hit.item().text();
            String head = "[资料 %d]（来源 %s 第 %s 块 · 相似度 %.2f）\n"
                    .formatted(ordinal, name, chunkNo == null ? "?" : chunkNo, hit.relevance());
            if (used + head.length() + body.length() > maxChars) {
                int room = maxChars - used - head.length();
                if (room < 80) {
                    break;
                }
                body = body.substring(0, room) + "…";
            }
            sb.append(head).append(body).append("\n\n");
            used += head.length() + body.length() + 2;
        }
        return sb.toString();
    }

    /** 本知识库已索引的块数。 */
    public int chunks() {
        int count = 0;
        for (MemoryItem item : memory.store().byScope(MemoryScope.KNOWLEDGE)) {
            if (name.equals(item.meta("source"))) {
                count++;
            }
        }
        return count;
    }

    /** 清掉本知识库的全部块，不影响其他文档与其他作用域。 */
    public int clear() {
        List<MemoryItem> stale = new ArrayList<>();
        for (MemoryItem item : memory.store().byScope(MemoryScope.KNOWLEDGE)) {
            if (name.equals(item.meta("source"))) {
                stale.add(item);
            }
        }
        for (MemoryItem item : stale) {
            memory.store().remove(item.id());
        }
        if (!stale.isEmpty()) {
            memory.store().flush();
        }
        return stale.size();
    }

    /** Knowledge 构造器。 */
    public static final class Builder {
        private final String name;
        private final Memory memory;
        private int maxChars = Chunker.DEFAULT_MAX_CHARS;
        private int overlap = Chunker.DEFAULT_OVERLAP;
        private boolean markdown;

        private Builder(String name, Memory memory) {
            this.name = name == null || name.isBlank() ? "未命名文档" : name;
            this.memory = memory;
        }

        public Builder chunkSize(int v) {
            this.maxChars = Math.max(80, v);
            return this;
        }

        public Builder overlap(int v) {
            this.overlap = Math.max(0, v);
            return this;
        }

        /** 按 Markdown 标题层级切节。.md 文件会自动启用。 */
        public Builder markdown(boolean v) {
            this.markdown = v;
            return this;
        }

        public Knowledge build() {
            if (memory == null) {
                throw new IllegalStateException("Knowledge 需要 Memory 提供存储与嵌入");
            }
            return new Knowledge(this);
        }
    }
}
