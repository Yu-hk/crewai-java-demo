package io.example.crew.flow;

import java.util.List;
import java.util.Map;

/**
 * 编排图一次执行的完整结果。
 *
 * <p>保留每个节点的产出而不只返回最后一个，是因为排查时最常问的是
 * "第 2 步到底给出了什么"，而不是"最终答案是什么"。
 *
 * @param order       实际执行顺序（拓扑序）
 * @param nodeOutputs 各节点产出
 */
public record DagResult(List<String> order, Map<String, Map<String, Object>> nodeOutputs) {

    public DagResult {
        order = List.copyOf(order);
        nodeOutputs = Map.copyOf(nodeOutputs);
    }

    /** 取某个节点的产出，不存在返回空 Map。 */
    public Map<String, Object> node(String name) {
        return nodeOutputs.getOrDefault(name, Map.of());
    }

    /** 最后一个节点的产出。 */
    public Map<String, Object> last() {
        return order.isEmpty() ? Map.of() : node(order.get(order.size() - 1));
    }
}
