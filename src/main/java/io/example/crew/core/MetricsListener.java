package io.example.crew.core;

import io.example.crew.util.Console;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 只做统计、不改输出的监听器：工具调用、校验失败、评审驳回、存档复用。
 *
 * <p>这类数据以前只能靠翻日志数，而"这个任务重试了 3 次"和"整条链路重试了 3 次"
 * 是两回事——成本归因必须能落到任务粒度。这也是 {@link CrewListener#plus}
 * 存在的理由：渲染和统计是两个关注点，各写一个类，组合使用。
 */
public final class MetricsListener implements CrewListener {

    private int toolCalls;
    private int structFails;
    private int guardFails;
    private int gaveUps;
    private int reviews;
    private int rejects;
    private int reused;
    private int compressed;

    /** 每任务的重试次数，按任务序号。 */
    private final Map<String, Integer> retriesByTask = new LinkedHashMap<>();

    @Override
    public void onTaskReused(int index, int total, Task task, TaskOutput output) {
        reused++;
    }

    @Override
    public void onToolCall(int index, String tool, Map<String, Object> arguments, String result) {
        toolCalls++;
    }

    @Override
    public void onCheckFailed(int index, Task task, String gate, String reason, int attempt, int maxAttempts) {
        if ("结构".equals(gate)) {
            structFails++;
        } else if ("校验".equals(gate)) {
            guardFails++;
        }
    }

    @Override
    public void onGaveUp(int index, Task task, String gate, String reason) {
        gaveUps++;
    }

    @Override
    public void onManagerReview(int index, Task task, boolean accepted, String verdict) {
        reviews++;
        if (!accepted) {
            rejects++;
        }
    }

    @Override
    public void onNote(int index, String note) {
        if (note != null && note.contains("上下文压缩")) {
            compressed++;
        }
    }

    @Override
    public void onTaskEnd(int index, int total, TaskOutput output) {
        retriesByTask.put(output.task().label(), Math.max(0, output.attempts() - 1));
    }

    public int toolCalls() {
        return toolCalls;
    }

    public int reused() {
        return reused;
    }

    /** 打印统计块。由调用方决定何时打印（例如只在流程尾部打一次）。 */
    public void report() {
        Console.section("过程质量");
        Console.bullet("工具调用 " + toolCalls + " 次");
        int fails = structFails + guardFails;
        Console.bullet("校验未通过 " + fails + " 次（结构 " + structFails + " / 业务 " + guardFails + "）");
        if (reviews > 0) {
            Console.bullet("主管评审 " + reviews + " 次 · 驳回 " + rejects + " 次"
                    + "（驳回率 " + Math.round(rejects * 100.0 / reviews) + "%）");
        }
        if (gaveUps > 0) {
            // 降级放行是"产出有瑕疵但仍交付"，必须显式暴露，否则会被静默接受
            Console.bullet("降级放行 " + gaveUps + " 次 ← 需要人工复核");
        }
        if (compressed > 0) {
            Console.bullet("上下文压缩 " + compressed + " 次");
        }
        if (reused > 0) {
            Console.bullet("存档复用 " + reused + " 个任务（未消耗 token）");
        }
        if (!retriesByTask.isEmpty()) {
            String detail = retriesByTask.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .map(e -> e.getKey() + "×" + e.getValue())
                    .reduce((a, b) -> a + "、" + b)
                    .orElse("无");
            Console.last("重试分布：" + detail);
        } else {
            Console.last("重试分布：无");
        }
    }
}
