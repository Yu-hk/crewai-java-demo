package io.example.crew.flow;

import java.util.Map;

/**
 * 编排图中的一个节点。
 *
 * <p>刻意做得比真实 DAG 引擎简陋：只要求"接收上下文、返回产出"。
 * 目的是证明一件事——<b>{@link io.example.crew.core.Crew} 可以缩小成图里的一个节点</b>，
 * 而不是必须成为整个应用。
 *
 * <p>为什么这个方向是对的：CrewAI 官方从 Flows 起步、把 Crew 嵌进去，就是这个结构。
 * 反过来（让 Crew 当骨架、用提示词去驱动流程）会把可复现性交给模型，
 * 一旦某步跑偏，整条链路的行为无法通过读代码推演。
 */
public interface DagNode {

    /** 节点名，用于日志、寻址与下游引用。 */
    String name();

    /**
     * 执行节点。
     *
     * @param inputs 全局输入 + 所有祖先节点的产出（后者覆盖前者）
     * @return 本节点产出，会合并进下游节点的输入
     */
    Map<String, Object> run(Map<String, Object> inputs);
}
