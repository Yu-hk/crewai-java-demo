package io.example.crew.core;

import java.util.List;

/**
 * 单个任务的执行结果。
 *
 * @param task      产生该结果的任务
 * @param raw       模型返回的原始文本
 * @param agentRole 执行者角色
 * @param millis    总耗时（毫秒，含校验失败后的重试）
 * @param attempts  执行次数（含各种重试，1 表示一次通过）
 * @param parsed    结构化输出绑定成功后的 POJO；未声明 outputType 或校验最终失败时为 null
 * @param notes     执行期的降级与损耗记录（上下文被压缩、校验失败放行等），用于事后归因
 */
public record TaskOutput(
        Task task,
        String raw,
        String agentRole,
        long millis,
        int attempts,
        Object parsed,
        List<String> notes) {

    public TaskOutput {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public boolean hasParsed() {
        return parsed != null;
    }
}
