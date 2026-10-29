package io.example.crew.flow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 最小可用的编排图，只做四件事：拓扑排序、按祖先注入输入、定位失败节点、检测环。
 *
 * <p><b>它不是要让这个项目变成 DAG 引擎</b>。反过来——如果团队已有 DAG 引擎，
 * 这个类应该被删掉，只留 {@link CrewNode}。放在这里是为了自证：
 * Crew 可以被嵌进任意显式流程而不需要改造，从而说明"Flow 职责不该由 Crew 承担"。
 *
 * <p>相比真实引擎刻意省略的：并行分支、条件边、循环、检查点、重试。这些都不难，
 * 但都属于流程引擎的职责，在这里实现只会造出一个半成品。
 */
public final class SimpleDag {

    private final Map<String, DagNode> nodes;
    private final Map<String, List<String>> upstream;
    private final Map<String, List<String>> downstream;

    private SimpleDag(Builder b) {
        this.nodes = new LinkedHashMap<>(b.nodes);
        this.upstream = new LinkedHashMap<>();
        this.downstream = new LinkedHashMap<>();
        nodes.keySet().forEach(n -> {
            upstream.put(n, new ArrayList<>());
            downstream.put(n, new ArrayList<>());
        });
        for (Map.Entry<String, String> edge : b.edges.entrySet()) {
            String from = edge.getKey();
            String to = edge.getValue();
            if (!nodes.containsKey(from) || !nodes.containsKey(to)) {
                throw new IllegalStateException(
                        "边 " + from + " → " + to + " 引用了不存在的节点");
            }
            upstream.get(to).add(from);
            downstream.get(from).add(to);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 执行整张图。
     *
     * @param inputs 全局输入，所有节点都能看到
     */
    public DagResult run(Map<String, Object> inputs) {
        List<String> order = topologicalOrder();
        Map<String, Object> globals = inputs == null ? Map.of() : inputs;
        Map<String, Map<String, Object>> outputs = new LinkedHashMap<>();

        for (String name : order) {
            Map<String, Object> nodeInput = new LinkedHashMap<>(globals);
            for (String ancestor : ancestorsInOrder(name, order)) {
                nodeInput.putAll(outputs.getOrDefault(ancestor, Map.of()));
            }
            Map<String, Object> out;
            try {
                out = nodes.get(name).run(nodeInput);
            } catch (RuntimeException e) {
                // 带节点名的异常：多节点链路里"哪个节点炸了"是第一个要回答的问题
                throw new DagException("编排图节点「" + name + "」执行失败：" + e.getMessage(), name, e);
            }
            outputs.put(name, out == null ? Map.of() : out);
        }
        return new DagResult(order, outputs);
    }

    /** Kahn 拓扑排序；节点按声明顺序稳定输出，便于对照日志。 */
    private List<String> topologicalOrder() {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        nodes.keySet().forEach(n -> indegree.put(n, upstream.get(n).size()));

        Deque<String> ready = new ArrayDeque<>();
        indegree.forEach((n, d) -> {
            if (d == 0) {
                ready.add(n);
            }
        });

        List<String> order = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            String name = ready.poll();
            order.add(name);
            for (String next : downstream.get(name)) {
                int left = indegree.merge(next, -1, Integer::sum);
                if (left == 0) {
                    ready.add(next);
                }
            }
        }

        if (order.size() != nodes.size()) {
            Set<String> remaining = new LinkedHashSet<>(nodes.keySet());
            remaining.removeAll(order);
            throw new IllegalStateException("编排图存在环，无法排序，涉及节点：" + remaining);
        }
        return order;
    }

    /** 取某节点全部祖先，按拓扑序返回（近的后放，便于后者覆盖前者）。 */
    private List<String> ancestorsInOrder(String name, List<String> order) {
        Set<String> ancestors = new HashSet<>();
        collectAncestors(name, ancestors);
        List<String> ordered = new ArrayList<>(ancestors.size());
        for (String candidate : order) {
            if (ancestors.contains(candidate)) {
                ordered.add(candidate);
            }
        }
        return ordered;
    }

    private void collectAncestors(String name, Set<String> sink) {
        for (String parent : upstream.get(name)) {
            if (sink.add(parent)) {
                collectAncestors(parent, sink);
            }
        }
    }

    /** 编排图构造器。 */
    public static final class Builder {
        private final Map<String, DagNode> nodes = new LinkedHashMap<>();
        /** 单前驱假设：本示例的图是链式的，一对多会覆盖，够用且不会静默出错。 */
        private final Map<String, String> edges = new LinkedHashMap<>();

        /** 加入一个纯代码节点（取数、校验、渲染等确定性步骤）。 */
        public Builder node(String name, Function<Map<String, Object>, Map<String, Object>> fn) {
            return node(new DagNode() {
                @Override
                public String name() {
                    return name;
                }

                @Override
                public Map<String, Object> run(Map<String, Object> inputs) {
                    return fn.apply(inputs);
                }
            });
        }

        public Builder node(DagNode node) {
            if (nodes.containsKey(node.name())) {
                throw new IllegalStateException("节点重名：" + node.name());
            }
            nodes.put(node.name(), node);
            return this;
        }

        /** 声明依赖：{@code from} 完成后才执行 {@code to}。 */
        public Builder edge(String from, String to) {
            String previous = edges.put(from, to);
            if (previous != null && !previous.equals(to)) {
                throw new IllegalStateException(
                        "节点 " + from + " 已有后继 " + previous + "，本示例不支持分支");
            }
            return this;
        }

        public SimpleDag build() {
            if (nodes.isEmpty()) {
                throw new IllegalStateException("编排图至少需要一个节点");
            }
            return new SimpleDag(this);
        }
    }
}
