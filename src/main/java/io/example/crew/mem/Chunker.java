package io.example.crew.mem;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档分块。
 *
 * <p>分块是知识库检索质量的第一决定因素，比嵌入模型的选择影响更大。三件事要同时成立：
 *
 * <ul>
 *   <li><b>块要自成一体。</b>一块里如果只有半句话，它的向量指向不明确，检索时谁也不像。</li>
 *   <li><b>块不能太大。</b>一块塞进三四个论点，向量是这几件事的平均值，反而对哪件事都不像；
 *       而且注入时会占掉本该留给任务描述的预算。</li>
 *   <li><b>边界不能切在语义中间。</b>所以优先按空行分段，其次按句末标点，实在不行才硬切。</li>
 * </ul>
 *
 * <p>相邻块之间保留一段重叠。重叠的收益是"被切在边界上的完整意思，两边都能匹配上"；
 * 代价是检索可能同时命中相邻两块，看着像重复。两害相权，重叠要留——
 * 缺了它，边界处的信息就是永久丢失，而重复只是浪费一点预算。
 *
 * <p>中文按<b>字符数</b>而不是 token 数分块。token 估算在中文上误差能到 30%
 * （见 {@code Tokens}），而分块尺寸需要的是稳定边界，不是精确计费。
 */
public final class Chunker {

    /** 默认块大小。400 字约等于两三个自然段的论说量。 */
    public static final int DEFAULT_MAX_CHARS = 400;

    /** 默认重叠量。60 字大约是一到两个短句。 */
    public static final int DEFAULT_OVERLAP = 60;

    private static final char[] SENTENCE_ENDS = {'。', '！', '？', '；', '\n', '.', '!', '?', ';'};

    private static final java.util.regex.Pattern HEADING =
            java.util.regex.Pattern.compile("^#{1,6} .*");

    private Chunker() {
    }

    /** 一块内容及其在原文中的位置，位置用于溯源"这段话出自文档哪一节"。 */
    public record Chunk(int index, String text, int start, int end) {
    }

    public static List<String> split(String text) {
        return split(text, DEFAULT_MAX_CHARS, DEFAULT_OVERLAP);
    }

    public static List<String> split(String text, int maxChars, int overlap) {
        List<Chunk> chunks = splitWithOffsets(text, maxChars, overlap);
        List<String> out = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            out.add(chunk.text());
        }
        return out;
    }

    public static List<Chunk> splitWithOffsets(String text, int maxChars, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int limit = Math.max(80, maxChars);
        int gap = Math.max(0, Math.min(overlap, limit / 2));
        List<int[]> fences = fences(text);

        List<Chunk> out = new ArrayList<>();
        int start = 0;
        int length = text.length();
        while (start < length) {
            int end = Math.min(start + limit, length);
            if (end < length) {
                int soft = findBreak(text, start + limit / 2, end, fences);
                if (soft > start) {
                    end = soft;
                }
                // 断点若落在代码块内部，干脆退到围栏开头。
                // 只在退让后仍能切出足够内容时才这么做，否则超长代码块会导致死循环。
                int fenceStart = fenceStartAt(fences, end - 1);
                if (fenceStart > start + limit / 4) {
                    end = fenceStart;
                }
            }
            String body = text.substring(start, end).strip();
            if (!body.isEmpty()) {
                out.add(new Chunk(out.size(), body, start, end));
            }
            if (end >= length) {
                break;
            }
            // 往回退一段作为重叠；must make progress，否则会死循环
            int next = Math.max(start + 1, end - gap);
            start = next;
        }
        return out;
    }

    private static int fenceStartAt(List<int[]> fences, int pos) {
        for (int[] range : fences) {
            if (pos >= range[0] && pos < range[1]) {
                return range[0];
            }
        }
        return -1;
    }

    /**
     * 找出所有 {@code ```} 围栏区间 {@code [start, end)}。
     *
     * <p>切分时躲开这些区间：代码块被拦腰截断后，两半都是无法阅读的碎片，
     * 而它们仍然会被正常嵌入、正常被检索命中——检索看不出问题，
     * 只有把 prompt 打印出来才看得见。这是"上下文污染"里最难发现的一类。
     */
    private static List<int[]> fences(String text) {
        List<int[]> out = new ArrayList<>();
        int pos = 0;
        int open = -1;
        while (true) {
            int idx = text.indexOf("```", pos);
            if (idx < 0) {
                break;
            }
            if (open < 0) {
                open = idx;
            } else {
                out.add(new int[]{open, idx + 3});
                open = -1;
            }
            pos = idx + 3;
        }
        if (open >= 0) {
            // 未闭合的围栏（文档本身写坏了）也要保护起来，否则会被切成更碎的片
            out.add(new int[]{open, text.length()});
        }
        return out;
    }

    private static boolean insideFence(List<int[]> fences, int pos) {
        for (int[] range : fences) {
            if (pos >= range[0] && pos < range[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 在 [from, to] 区间里找最靠后的语义断点，跳过代码块内部。
     *
     * <p>找不到就返回 {@code to}，由调用方硬切。硬切不理想，但比无限递归找断点好——
     * 一份没有标点的长文本（比如日志）必须能被处理。
     */
    private static int findBreak(String text, int from, int to, List<int[]> fences) {
        for (int i = to - 1; i >= from; i--) {
            if (insideFence(fences, i)) {
                continue;
            }
            char c = text.charAt(i);
            for (char end : SENTENCE_ENDS) {
                if (c == end) {
                    return i + 1;
                }
            }
        }
        return to;
    }

    /**
     * Markdown 专用分块：先按标题层级切节，再合并短节、拆长节。
     *
     * <p>为什么需要单独一条路径：通用分块只认句末标点，会把代码块和表格拦腰截断。
     * 实测索引本仓库 README（40 KB）时，切出的块里出现"k ``` ### 8.2"这种半截代码围栏，
     * 检索命中了它，注入给模型的却是一段无法阅读的碎片。
     *
     * <p>标题是 Markdown 自带的语义边界，用它切分等于免费拿到了作者已经标注好的结构。
     * 切完再做两件事：短节相邻合并（避免一个标题下只有一句话就成为一块），
     * 长节二次切分（避免某一节独占预算）。
     *
     * <p>这一层刻意<b>不加块间重叠</b>：按节切分后每块本身就是完整的语义单元，
     * 重叠只会让相邻块在检索时同时命中、看起来像重复。
     */
    public static List<String> splitMarkdown(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int limit = Math.max(120, maxChars);
        List<String> units = new ArrayList<>();
        for (String section : sections(text)) {
            if (section.length() <= limit) {
                units.add(section);
            } else {
                units.addAll(split(section, limit, 0));
            }
        }

        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            if (current.length() > 0 && current.length() + unit.length() > limit) {
                out.add(current.toString().strip());
                current.setLength(0);
            }
            current.append(unit);
        }
        if (!current.isEmpty()) {
            out.add(current.toString().strip());
        }
        out.removeIf(String::isEmpty);
        return out;
    }

    /** 按 Markdown 标题行切节，标题归入其所在小节。 */
    private static List<String> sections(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (HEADING.matcher(line).matches() && current.length() > 0) {
                out.add(current.toString());
                current.setLength(0);
            }
            current.append(line).append('\n');
        }
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }
}
