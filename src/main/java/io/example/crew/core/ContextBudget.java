package io.example.crew.core;

import io.example.crew.llm.ChatMessage;
import io.example.crew.llm.LlmClient;
import io.example.crew.util.Tokens;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文预算管理，对应 CrewAI 的 {@code respect_context_window}。
 *
 * <p>解决的问题：顺序流程里，第 N 个任务要吞下前 N-1 个产出。链路一长，
 * 上下文必然溢出窗口，而溢出在本地模型上的表现是<b>静默丢内容</b>——
 * 不报错，只是模型"忘了"前面说过什么。这比抛异常难查得多。
 *
 * <p>策略分三级，越往后越贵：
 * <ol>
 *   <li>总预算内 → 全部原文注入</li>
 *   <li>超预算 → 保留<b>最近</b>几条原文（预算的一半，约 2 条），更早的用一次低成本调用压成要点</li>
 *   <li>压缩失败 → 逐条硬截断，并在文本里标注"已压缩"，让下游模型知道信息有损</li>
 * </ol>
 *
 * <p>注意"保留最近的"这个方向很重要：顺序流程里，越靠后的产出越接近当前任务的输入。
 * 反过来丢最近的内容，模型会直接失去上下文衔接。
 */
public final class ContextBudget {

    /** 每条产出的排版开销（分隔头、换行），粗估 40 token。 */
    private static final int BLOCK_OVERHEAD_TOKENS = 40;

    private final int maxTokens;

    public ContextBudget(int maxTokens) {
        // 下限 160：低于这个量级时，压缩本身就要花一次调用，还不如直接少放几条上下文。
        this.maxTokens = Math.max(160, maxTokens);
    }

    public int maxTokens() {
        return maxTokens;
    }

    /**
     * 组装前序上下文文本。
     *
     * @param outputs 已完成的产出，按执行顺序
     * @param llm     用于压缩的客户端；为 null 时退化为硬截断
     */
    public Result assemble(List<TaskOutput> outputs, LlmClient llm) {
        return assemble(outputs, llm, 0);
    }

    /**
     * 组装前序上下文文本，为其他提示词段落预留预算。
     *
     * <p>{@code reservedTokens} 是记忆块占用的量。不预留的话，记忆和上下文各自都"在预算内"，
     * 加起来却超了——预算管理的经典失败模式：每个部分都合规，整体越界。
     *
     * <p>预留后的下限是 160 tok：如果记忆块大到把上下文预算挤到 160 以下，
     * 说明真正该缩的是记忆块本身（{@code Crew.memoryMaxChars}），而不是继续压缩上下文。
     * 这里只保证不至于算出负预算。
     *
     * @param reservedTokens 已被其他段落占用的 token 数
     */
    public Result assemble(List<TaskOutput> outputs, LlmClient llm, int reservedTokens) {
        int budget = Math.max(160, maxTokens - Math.max(0, reservedTokens));
        return assembleWithin(outputs, llm, budget, reservedTokens);
    }

    private Result assembleWithin(List<TaskOutput> outputs, LlmClient llm, int budget, int reservedTokens) {
        if (outputs == null || outputs.isEmpty()) {
            return new Result("", 0, false, null);
        }

        List<Integer> costs = new ArrayList<>(outputs.size());
        int total = 0;
        for (TaskOutput output : outputs) {
            int cost = Tokens.estimate(output.raw()) + BLOCK_OVERHEAD_TOKENS;
            costs.add(cost);
            total += cost;
        }
        if (total <= budget) {
            String text = render(outputs);
            return new Result(text, Tokens.estimate(text), false, null);
        }

        // 保留最近若干条原文，预算的一半
        int recentBudget = Math.max(240, budget / 2);
        int cut = outputs.size();
        int used = 0;
        while (cut > 0) {
            int cost = costs.get(cut - 1);
            if (used + cost > recentBudget) {
                break;
            }
            used += cost;
            cut--;
        }
        if (cut == outputs.size()) {
            // 最后一条单独就超预算：只能截断它，此时"压缩"没有意义。
            // 但预留信息仍要说明——否则调用方会以为是产出太长，而实际是记忆块把预算吃掉了。
            TaskOutput last = outputs.get(outputs.size() - 1);
            String clipped = clip(last.raw(), recentBudget * 2);
            String text = block(last, clipped + "\n…(超预算，已截断)");
            return new Result(text, Tokens.estimate(text), true,
                    "上下文预算不足：最近一条产出即超出 " + recentBudget + " tok，已截断"
                            + (reservedTokens > 0
                            ? "（预算 " + budget + " tok 中已有 " + reservedTokens + " tok 预留给记忆块）" : ""));
        }

        List<TaskOutput> older = outputs.subList(0, cut);
        List<TaskOutput> recent = outputs.subList(cut, outputs.size());
        List<String> warnings = new ArrayList<>();
        String summary = summarize(older, llm, Math.max(160, budget / 4), warnings);

        StringBuilder sb = new StringBuilder();
        if (!summary.isBlank()) {
            sb.append("--- 更早任务要点（原文已压缩）---\n").append(summary).append("\n\n");
        }
        sb.append(render(recent));
        String text = sb.toString();
        warnings.add(0, "上下文超预算（约 " + total + " tok > " + budget
                + " tok）：已压缩 " + older.size() + " 条早期产出，保留 " + recent.size() + " 条原文"
                + (reservedTokens > 0 ? "；其中 " + reservedTokens + " tok 已预留给记忆块" : ""));
        return new Result(text, Tokens.estimate(text), true, String.join("；", warnings));
    }

    /** 用一次低成本调用把早期产出压成事实要点。 */
    private String summarize(List<TaskOutput> older, LlmClient llm, int targetTokens, List<String> warnings) {
        if (llm == null || older.isEmpty()) {
            return "";
        }
        StringBuilder source = new StringBuilder();
        for (TaskOutput output : older) {
            source.append("[").append(output.task().label()).append("]\n")
                    .append(clip(output.raw(), 1200)).append("\n\n");
        }
        String prompt = """
                下面是一组已完成任务的原始产出。请把它们压缩为事实要点，供后续任务当背景使用。
                要求：
                1. 保留全部具体数字、结论、专有名词与限定条件；
                2. 删除修辞、重复与寒暄；
                3. 总长不超过 %d 字；
                4. 直接输出要点，不要写"总结如下"这类前缀。
                """.formatted(Math.max(80, targetTokens));
        try {
            String summary = llm.chat(List.of(
                    ChatMessage.system("你是严谨的信息压缩器，只保留可验证的事实与结论。"),
                    ChatMessage.user(prompt + "\n【原始产出】\n" + source)), 0.1).content();
            if (summary != null && !summary.isBlank()) {
                return summary.strip();
            }
        } catch (RuntimeException e) {
            warnings.add("上下文压缩调用失败，退化为逐条截断：" + e.getMessage());
        }
        return fallback(older);
    }

    private static String fallback(List<TaskOutput> older) {
        StringBuilder sb = new StringBuilder();
        for (TaskOutput output : older) {
            sb.append("[").append(output.task().label()).append("] ")
                    .append(clip(output.raw(), 200)).append("\n");
        }
        return sb.toString().strip();
    }

    private static String render(List<TaskOutput> outputs) {
        StringBuilder sb = new StringBuilder();
        for (TaskOutput output : outputs) {
            sb.append(block(output, output.raw()));
        }
        return sb.toString();
    }

    private static String block(TaskOutput output, String body) {
        return "--- " + output.task().label() + "（由 " + output.agentRole() + " 完成）---\n"
                + body + "\n\n";
    }

    private static String clip(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars) + "\n…(已省略)";
    }

    /**
     * 组装结果。
     *
     * @param text       注入提示词的上下文正文
     * @param tokens     估算 token 数
     * @param compressed 是否发生了压缩或截断（用于把损耗记进 TaskOutput 备注）
     * @param note       需要告知调用方的处理说明，无则 null。由调用方决定怎么呈现，
     *                   这样本类不必依赖控制台——库代码写死 {@code System.out} 就没法在服务里用
     */
    public record Result(String text, int tokens, boolean compressed, String note) {
    }
}
