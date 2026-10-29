package io.example.crew.core;

/**
 * Crew 的执行策略，对应 CrewAI 的 {@code Process}。
 */
public enum Process {

    /** 任务按声明顺序依次执行，前序产出作为后序任务的上下文。 */
    SEQUENTIAL,

    /**
     * 顺序执行 + 主管评审。
     *
     * <p>注意：这是对 CrewAI {@code Process.hierarchical} 的<b>简化</b>。
     * CrewAI 原版由 manager 动态拆分任务并派发给任意 worker；本地 7B 级模型做动态
     * 任务分解的稳定性不足，翻车成本高于收益，因此这里收敛为更可靠的形态：
     * 任务顺序仍由代码决定，manager 只负责"验收 / 打回重做"。
     * 换用强模型时，可把 {@link Crew} 的循环替换为真正的动态派发。
     */
    HIERARCHICAL
}
