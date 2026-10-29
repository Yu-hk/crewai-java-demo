package io.example.crew.flow;

import io.example.crew.core.Crew;
import io.example.crew.core.CrewListener;
import io.example.crew.core.CrewOutput;
import io.example.crew.core.ConsoleListener;
import io.example.crew.core.RunStore;
import io.example.crew.core.TaskOutput;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把一个 {@link Crew} 包装成编排图里的普通节点。
 *
 * <p>这是整个桥接层的核心，代码量不到 100 行，但它决定了架构方向：
 * <b>Crew 只负责"需要自主协作的那一段"</b>，前后步骤（取数、校验、渲染、落库）
 * 用确定性的代码节点表达。真实项目里绝大多数步骤属于后者。
 *
 * <p>输入映射的一个细节：编排图的上下文是 {@code Map<String,Object>}，
 * 而 Crew 的变量表是 {@code Map<String,String>}。这里做了无损降级（非字符串转字符串），
 * 因为变量表只用于替换任务描述里的 {@code {placeholder}}——它最终一定要变成文本。
 * 反过来把 Crew 的变量表提升成 Object 没有收益，只会让模板替换多一层类型判断。
 */
public final class CrewNode implements DagNode {

    private final String name;
    private final Crew crew;
    private final CrewListener listener;
    private final RunStore store;
    private final String runId;

    private CrewNode(Builder b) {
        this.name = b.name;
        this.crew = b.crew;
        this.listener = b.listener;
        this.store = b.store;
        this.runId = b.runId;
    }

    public static Builder builder(String name, Crew crew) {
        return new Builder(name, crew);
    }

    /** 便捷构造：使用控制台输出、不持久化。 */
    public static CrewNode of(String name, Crew crew) {
        return builder(name, crew).build();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Map<String, Object> run(Map<String, Object> inputs) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (inputs != null) {
            inputs.forEach((k, v) -> vars.put(k, stringify(v)));
        }

        CrewOutput output = crew.kickoff(vars, listener, store, runId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("raw", output.raw());
        result.put("millis", output.millis());
        result.put("reusedTasks", output.reusedTasks());
        for (TaskOutput taskOutput : output.taskOutputs()) {
            String label = taskOutput.task().label();
            // 逐任务产出单独暴露，下游可以精准引用某一步，而不必从最终答案里再切一次
            result.put("task." + label, taskOutput.raw());
            if (taskOutput.hasParsed()) {
                result.put("parsed." + label, String.valueOf(taskOutput.parsed()));
            }
        }
        return result;
    }

    private static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        return value instanceof String s ? s : String.valueOf(value);
    }

    /** CrewNode 构造器。 */
    public static final class Builder {
        private final String name;
        private final Crew crew;
        private CrewListener listener = new ConsoleListener(false);
        private RunStore store;
        private String runId;

        private Builder(String name, Crew crew) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("CrewNode 需要节点名");
            }
            this.name = name;
            this.crew = crew;
        }

        /** 把 Crew 的事件转发给外部监听器。 */
        public Builder listener(CrewListener v) {
            this.listener = v;
            return this;
        }

        /** 让这个节点支持断点续跑。 */
        public Builder store(RunStore v, String runId) {
            this.store = v;
            this.runId = runId;
            return this;
        }

        public CrewNode build() {
            if (crew == null) {
                throw new IllegalStateException("CrewNode '" + name + "' 缺少 crew");
            }
            if (store != null && runId == null) {
                throw new IllegalStateException("CrewNode '" + name + "' 启用持久化但未提供 runId");
            }
            return new CrewNode(this);
        }
    }
}
