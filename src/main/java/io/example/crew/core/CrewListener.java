package io.example.crew.core;

import io.example.crew.mem.Hit;
import io.example.crew.mem.MemoryItem;
import io.example.crew.tool.ToolSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 流程事件钩子，对应 CrewAI 1.15 的 {@code @on} 拦截点与 {@code CrewFailedEvent}。
 *
 * <p>存在的意义不是"打印日志"——而是让<b>可观测性、成本核算、失败归因、UI 进度</b>
 * 这些东西挂上来时不必改内核。之前这些能力是硬编码在 {@link Crew} 里的
 * {@code System.out.println}，想接一个 web 进度条就得动核心类，那是设计缺陷。
 *
 * <p>所有方法都有空实现，实现方只覆写关心的那几个。
 *
 * <p>约定：<b>监听器抛出的异常会中断流程</b>。这是刻意的——它让"人工介入"
 * （在某个任务前等待确认）和"外部取消"可以只用一个监听器实现，
 * 而不需要在 Crew 里另开一套 interrupt 机制。
 */
public interface CrewListener {

    /** 流程启动。 */
    default void onCrewStart(CrewInfo info, Map<String, String> inputs) {
    }

    /** 从存档恢复，{@code reused} 为本次直接复用的任务数。 */
    default void onResumed(String runId, int reused) {
    }

    /**
     * 单个任务开始执行。
     *
     * @param index 从 1 开始的任务序号
     * @param total 任务总数
     */
    default void onTaskStart(int index, int total, Task task) {
    }

    /** 任务产出直接来自存档，未消耗 token。 */
    default void onTaskReused(int index, int total, Task task, TaskOutput output) {
    }

    /** 发生一次工具调用。 */
    default void onToolCall(int index, String tool, Map<String, Object> arguments, String result) {
    }

    /**
     * 某道闸门判定不通过，即将重试。
     *
     * @param gate        闸门名：{@code 结构} / {@code 校验} / {@code 评审}
     * @param attempt     已重试次数
     * @param maxAttempts 允许的最大重试次数
     */
    default void onCheckFailed(int index, Task task, String gate, String reason, int attempt, int maxAttempts) {
    }

    /** 某道闸门连续失败，降级放行。这条比 {@code onCheckFailed} 更值得告警。 */
    default void onGaveUp(int index, Task task, String gate, String reason) {
    }

    /** 主管评审结论。 */
    default void onManagerReview(int index, Task task, boolean accepted, String verdict) {
    }

    /** 通用诊断信息（上下文压缩、原生协议降级等），不影响流程推进。 */
    default void onNote(int index, String note) {
    }

    /**
     * 任务开始前，工具集已确定。
     *
     * <p>单独一个事件而不是塞进 {@code onTaskStart}：工具可见性是任务级配置里最容易
     * 出错的一项（写错名字、绑定空集、以为继承了其实没继承），必须能在日志里独立看到。
     *
     * @param toolset 任务显式绑定的工具集；{@code null} 表示继承 Agent 自带的
     * @param tools   本任务实际可用的工具名
     */
    default void onToolsBound(int index, Task task, ToolSet toolset, List<String> tools) {
    }

    /**
     * 任务开始前完成了一次记忆召回。
     *
     * <p>{@code hits} 为空说明检索过但没找到相关内容——这与"没开记忆"是两件事，
     * 前者值得关注（可能是库太小或阈值太严），后者不用管。
     */
    default void onMemoryRecall(int index, Task task, List<Hit> hits) {
    }

    /** 任务产出已写入记忆。 */
    default void onMemoryWrite(int index, Task task, MemoryItem item) {
    }

    /** 任务完成。 */
    default void onTaskEnd(int index, int total, TaskOutput output) {
    }

    /** 任务执行抛异常，流程即将中止。 */
    default void onError(int index, Task task, Throwable error) {
    }

    /** 流程结束（正常完成）。 */
    default void onCrewEnd(CrewOutput output) {
    }

    /** 把多个监听器合成一个，按顺序广播。 */
    static CrewListener plus(CrewListener... listeners) {
        List<CrewListener> list = new ArrayList<>();
        for (CrewListener l : listeners) {
            if (l != null) {
                list.add(l);
            }
        }
        if (list.size() == 1) {
            return list.get(0);
        }
        return new CrewListener() {
            @Override
            public void onCrewStart(CrewInfo info, Map<String, String> inputs) {
                list.forEach(l -> l.onCrewStart(info, inputs));
            }

            @Override
            public void onResumed(String runId, int reused) {
                list.forEach(l -> l.onResumed(runId, reused));
            }

            @Override
            public void onTaskStart(int index, int total, Task task) {
                list.forEach(l -> l.onTaskStart(index, total, task));
            }

            @Override
            public void onTaskReused(int index, int total, Task task, TaskOutput output) {
                list.forEach(l -> l.onTaskReused(index, total, task, output));
            }

            @Override
            public void onToolCall(int index, String tool, Map<String, Object> arguments, String result) {
                list.forEach(l -> l.onToolCall(index, tool, arguments, result));
            }

            @Override
            public void onCheckFailed(int index, Task task, String gate, String reason, int attempt, int maxAttempts) {
                list.forEach(l -> l.onCheckFailed(index, task, gate, reason, attempt, maxAttempts));
            }

            @Override
            public void onGaveUp(int index, Task task, String gate, String reason) {
                list.forEach(l -> l.onGaveUp(index, task, gate, reason));
            }

            @Override
            public void onManagerReview(int index, Task task, boolean accepted, String verdict) {
                list.forEach(l -> l.onManagerReview(index, task, accepted, verdict));
            }

            @Override
            public void onNote(int index, String note) {
                list.forEach(l -> l.onNote(index, note));
            }

            @Override
            public void onToolsBound(int index, Task task, ToolSet toolset, List<String> tools) {
                list.forEach(l -> l.onToolsBound(index, task, toolset, tools));
            }

            @Override
            public void onMemoryRecall(int index, Task task, List<Hit> hits) {
                list.forEach(l -> l.onMemoryRecall(index, task, hits));
            }

            @Override
            public void onMemoryWrite(int index, Task task, MemoryItem item) {
                list.forEach(l -> l.onMemoryWrite(index, task, item));
            }

            @Override
            public void onTaskEnd(int index, int total, TaskOutput output) {
                list.forEach(l -> l.onTaskEnd(index, total, output));
            }

            @Override
            public void onError(int index, Task task, Throwable error) {
                list.forEach(l -> l.onError(index, task, error));
            }

            @Override
            public void onCrewEnd(CrewOutput output) {
                list.forEach(l -> l.onCrewEnd(output));
            }
        };
    }

    /**
     * 流程的静态信息，供监听器渲染头部。
     *
     * <p>不直接传 {@link Crew}，是为了让监听器只能读、不能反过来操作流程。
     */
    record CrewInfo(
            String name,
            String model,
            Process process,
            int agentCount,
            int taskCount,
            int contextTokens,
            List<String> taskLabels) {
    }
}
