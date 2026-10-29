package io.example.crew.core;

import io.example.crew.llm.LlmClient;

import java.util.List;

/**
 * 一次 Crew 运行的完整结果，对应 CrewAI 的 {@code CrewOutput}。
 *
 * @param name        流水线名称
 * @param raw         最终产出（最后一个任务的结果）
 * @param taskOutputs 各任务产出，按执行顺序
 * @param process     执行策略
 * @param millis      本次运行的墙钟耗时。<b>不含</b>复用存档那部分任务的历史耗时——
 *                    否则"续跑省了多少时间"就看不出来了
 * @param usage       本次运行的 token 用量（存档复用的任务不计入）
 * @param reusedTasks 直接从存档复用、未消耗 token 的任务数
 */
public record CrewOutput(
        String name,
        String raw,
        List<TaskOutput> taskOutputs,
        Process process,
        long millis,
        LlmClient.Usage usage,
        int reusedTasks) {

    public TaskOutput last() {
        return taskOutputs.isEmpty() ? null : taskOutputs.get(taskOutputs.size() - 1);
    }
}
