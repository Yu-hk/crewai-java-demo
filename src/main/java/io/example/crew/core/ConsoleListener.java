package io.example.crew.core;

import io.example.crew.mem.Hit;
import io.example.crew.tool.ToolSet;
import io.example.crew.util.Console;

import java.util.List;

/**
 * 把流程事件渲染到控制台，格式与原实现保持一致。
 *
 * <p>它现在承担了原本散落在 {@link Crew} 里的全部 {@code System.out.println}。
 * 内核因此变成纯粹的编排逻辑，不再关心"有没有终端"——这是能被 web 服务、
 * 批处理或 IDE 插件复用的前提。
 */
public final class ConsoleListener implements CrewListener {

    /** 是否在每个任务结束后打印产出预览。批处理场景通常关掉它。 */
    private final boolean showPreview;

    public ConsoleListener() {
        this(true);
    }

    public ConsoleListener(boolean showPreview) {
        this.showPreview = showPreview;
    }

    @Override
    public void onCrewStart(CrewInfo info, java.util.Map<String, String> inputs) {
        Console.title("Crew · " + info.name());
        Console.field("模型", info.model());
        Console.field("流程", info.process().name()
                + (info.process() == Process.HIERARCHICAL ? "（主管评审）" : ""));
        Console.field("成员", info.agentCount() + " 个智能体 / " + info.taskCount() + " 个任务");
        Console.field("上下文预算", info.contextTokens() + " tok");
        Console.field("输入", String.valueOf(inputs));
        Console.last("任务序列：" + String.join(" → ", info.taskLabels()));
    }

    @Override
    public void onResumed(String runId, int reused) {
        Console.section("断点续跑 · " + runId);
        Console.bullet("复用已完成任务 " + reused + " 项，跳过对应 LLM 调用");
    }

    @Override
    public void onTaskStart(int index, int total, Task task) {
        System.out.println();
        System.out.println(Console.gray("  → ") + Console.bold(task.agent().role())
                + Console.gray(" 开始执行 · 第 " + index + "/" + total + " 步 · " + task.label()));
    }

    @Override
    public void onTaskReused(int index, int total, Task task, TaskOutput output) {
        System.out.println();
        System.out.println(Console.gray("  ↺ ") + task.label()
                + Console.gray(" 复用存档 · 第 " + index + "/" + total + " 步 · "
                + Console.millis(output.millis()) + " · 0 tok"));
    }

    @Override
    public void onToolCall(int index, String tool, java.util.Map<String, Object> arguments, String result) {
        System.out.println(Console.gray("  ⚙ 工具调用 ") + Console.cyan(tool)
                + Console.gray(String.valueOf(arguments)) + Console.gray(" → ")
                + Console.truncate(result, 60));
    }

    @Override
    public void onCheckFailed(int index, Task task, String gate, String reason, int attempt, int maxAttempts) {
        Console.warn(gate + "未通过，重试 " + attempt + "/" + maxAttempts + "："
                + Console.truncate(reason, 100));
    }

    @Override
    public void onGaveUp(int index, Task task, String gate, String reason) {
        Console.warn(gate + "连续失败，降级放行：" + Console.truncate(reason, 100));
    }

    @Override
    public void onManagerReview(int index, Task task, boolean accepted, String verdict) {
        if (!accepted) {
            Console.warn("主管驳回：" + Console.truncate(verdict, 90));
        }
    }

    @Override
    public void onNote(int index, String note) {
        Console.info("  · " + note);
    }

    @Override
    public void onMemoryRecall(int index, Task task, List<Hit> hits) {
        if (hits.isEmpty()) {
            System.out.println(Console.gray("  ◌ 记忆未命中"));
            return;
        }
        StringBuilder line = new StringBuilder(Console.gray("  ◈ 记忆召回 ") + hits.size() + " 条");
        for (Hit hit : hits) {
            line.append("\n").append(Console.gray("     · "))
                    .append(Console.gray("[" + hit.item().scope().label() + " "
                            + String.format("%.2f", hit.score()) + "] "))
                    .append(Console.truncate(hit.item().summary(60), 60));
        }
        System.out.println(line);
    }

    /**
     * 只在"任务显式绑定工具集"时打印。
     *
     * <p>继承 Agent 工具这种默认情况不输出：每个任务都打一行"继承了 3 个工具"只是噪音。
     * 而一旦某任务显式绑了工具集，这行就是排查工具误用的第一手线索。
     */
    @Override
    public void onToolsBound(int index, Task task, ToolSet toolset, List<String> tools) {
        if (toolset == null) {
            return;
        }
        String detail = toolset.isEmpty()
                ? Console.gray("（明确不给工具）")
                : Console.gray(String.join(", ", tools));
        System.out.println(Console.gray("  ⚙ 工具集「" + toolset.name() + "」· 来源 "
                + toolset.origin() + " · ") + detail);
    }

    @Override
    public void onTaskEnd(int index, int total, TaskOutput output) {
        StringBuilder line = new StringBuilder();
        line.append(Console.gray("  ← ")).append(Console.green("完成")).append(Console.gray(" · "))
                .append("第 ").append(index).append("/").append(total).append(" 步 · ")
                .append(Console.millis(output.millis())).append(" · ")
                .append(Console.chars(output.raw().length()));
        if (output.attempts() > 1) {
            line.append(" · 重试 ").append(output.attempts() - 1).append(" 次");
        }
        if (output.hasParsed()) {
            line.append(" · ").append(Console.green("结构化 ✓"));
        }
        System.out.println(line);

        for (String note : output.notes()) {
            System.out.println(Console.gray("     ⚠ ") + Console.gray(note));
        }
        if (showPreview) {
            String preview = output.hasParsed() ? String.valueOf(output.parsed()) : output.raw();
            System.out.println(Console.gray("     ") + Console.truncate(preview, 120));
        }
    }

    @Override
    public void onError(int index, Task task, Throwable error) {
        Console.error("任务「" + task.label() + "」失败：" + error.getMessage());
    }
}
