package io.example.crew.core;

import io.example.crew.schema.Structure;
import io.example.crew.tool.ToolRegistry;
import io.example.crew.tool.ToolSet;
import io.example.crew.util.Console;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一个可验收的工作单元，对应 CrewAI 的 {@code Task(description, expected_output, agent)}。
 *
 * <p>{@code expectedOutput} 不是装饰：它是把自由文本约束成可用产出的主要手段。
 * 少了它，下游任务拿到的就是一段不知道边界的散文。
 *
 * <p>对齐 CrewAI 后新增两组能力：
 * <ul>
 *   <li>{@code outputType} → 对应 {@code output_pydantic}。声明后提示词里会附上
 *       JSON Schema 与逐字段中文说明，Crew 在产出不合法时把错误回灌重试。</li>
 *   <li>{@code guardrails} → 对应 {@code guardrail=}。任务级强制校验，
 *       与"主管评审"的区别见 {@link Guardrail}。</li>
 * </ul>
 *
 * <p>上下文不再由 Task 自己截断：预算决策属于流程层，由 {@link ContextBudget} 统一处理，
 * Task 只负责把算好的文本插进 prompt。职责分开后才能单独调整预算策略而不动模板。
 */
public final class Task {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    private final String label;
    private final String description;
    private final String expectedOutput;
    private final Agent agent;
    private final List<Task> explicitContext;
    private final Class<?> outputType;
    private final int outputRetries;
    private final List<Guardrail> guardrails;
    private final int maxRetries;
    private final ToolSet toolset;

    private Task(Builder b) {
        this.label = b.label;
        this.description = b.description;
        this.expectedOutput = b.expectedOutput;
        this.agent = b.agent;
        this.explicitContext = List.copyOf(b.explicitContext);
        this.outputType = b.outputType;
        this.outputRetries = b.outputRetries;
        this.guardrails = List.copyOf(b.guardrails);
        this.maxRetries = b.maxRetries;
        this.toolset = b.toolset;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String label() {
        return label == null || label.isBlank() ? Console.truncate(description, 18) : label;
    }

    public Agent agent() {
        return agent;
    }

    public String description() {
        return description;
    }

    public String expectedOutput() {
        return expectedOutput;
    }

    public Class<?> outputType() {
        return outputType;
    }

    /** 结构化校验失败后的最大重试次数。 */
    public int outputRetries() {
        return outputRetries;
    }

    public List<Guardrail> guardrails() {
        return guardrails;
    }

    /** 校验失败后的最大重试次数。 */
    public int maxRetries() {
        return maxRetries;
    }

    public List<Task> explicitContext() {
        return explicitContext;
    }

    /**
     * 本任务绑定的工具集；为 {@code null} 表示未绑定（继承 Agent 自带的工具）。
     *
     * <p>注意"未绑定"与"绑定空集"是两件事：前者继承，后者明确不给工具。
     */
    public ToolSet toolset() {
        return toolset;
    }

    /** 本任务实际可用的工具：绑定了就用绑定的，否则用 Agent 自带的。 */
    public ToolRegistry effectiveTools() {
        return toolset == null ? agent.registry() : toolset.registry();
    }

    /** 把描述中的 {placeholder} 替换为实际值，供提示词与评审使用。 */
    public String renderedDescription(Map<String, String> inputs) {
        return interpolate(description, inputs);
    }

    /**
     * 选出本任务需要注入的上下文来源。
     *
     * <p>显式声明了 context 就只取那几条（用于跳过无关产出、节省预算），
     * 否则继承全部前序产出（等价于顺序模式下的累积上下文）。
     */
    public List<TaskOutput> selectContext(List<TaskOutput> prior) {
        if (explicitContext.isEmpty()) {
            return prior;
        }
        List<TaskOutput> selected = new ArrayList<>();
        for (TaskOutput output : prior) {
            if (explicitContext.contains(output.task())) {
                selected.add(output);
            }
        }
        return selected;
    }

    /**
     * 组装本任务的用户消息。
     *
     * <p>顺序刻意安排为：任务 → 期望输出 → 相关记忆 → 前序上下文 → 输出格式 → 返工意见 → 指令。
     * 模型对结尾最敏感，所以"必须修正的点"和"现在开始输出"放在最后。
     *
     * <p>记忆排在前序上下文<b>之前</b>，因为两者的可信度不同：前序上下文是同一条链路里
     * 刚刚产出的、必然相关；记忆是跨运行检索来的、可能只是碰巧词面相近。
     * 把更确定的材料放在更靠近指令的位置，让模型最后读到的是最该依赖的内容。
     *
     * @param inputs      全局输入变量，用于替换描述中的 {placeholder}
     * @param contextText 已按预算处理好的前序上下文（可为空串）
     * @param feedback    上一版未通过的原因，首次执行为 null
     * @param structure   结构化契约，未声明 outputType 时为 null
     */
    public String buildPrompt(Map<String, String> inputs, String contextText, String feedback, Structure structure) {
        return buildPrompt(inputs, "", contextText, feedback, structure, agent.hasTools());
    }

    /** 带记忆块的版本；{@code memoryBlock} 为空串时输出与上一个重载完全一致。 */
    public String buildPrompt(Map<String, String> inputs, String memoryBlock, String contextText,
                              String feedback, Structure structure) {
        return buildPrompt(inputs, memoryBlock, contextText, feedback, structure, agent.hasTools());
    }

    /**
     * 带记忆块与"本轮是否真的有工具"的版本。
     *
     * @param hasTools 本任务实际可用工具是否非空。必须由调用方按<b>任务绑定</b>后的
     *                 工具集给出，不能取 {@code agent.hasTools()}：任务可以通过绑定空集
     *                 把工具收走，此时提示词里再说"先调工具再填 JSON"就是在指挥模型
     *                 去用一个它没有的工具。
     */
    public String buildPrompt(Map<String, String> inputs, String memoryBlock, String contextText,
                              String feedback, Structure structure, boolean hasTools) {
        StringBuilder sb = new StringBuilder(768);
        sb.append("【当前任务】\n").append(interpolate(description, inputs)).append("\n\n");

        if (expectedOutput != null && !expectedOutput.isBlank()) {
            sb.append("【期望输出】\n").append(expectedOutput).append("\n\n");
        }

        if (memoryBlock != null && !memoryBlock.isBlank()) {
            sb.append(memoryBlock.strip()).append("\n\n");
        }

        if (contextText != null && !contextText.isBlank()) {
            sb.append("【前序任务结果】\n").append(contextText.strip()).append("\n\n");
        }

        if (structure != null) {
            sb.append("【输出格式：必须是 JSON】\n");
            sb.append("只输出一个 JSON 对象。不要用 ``` 代码块包裹，不要写任何解释文字，不要输出多余字段。\n");
            if (hasTools) {
                // 实测发现：同时要求"输出 JSON"和"用工具算数"时，模型会直接编一个数字塞进 JSON，
                // 工具调用完全不发生。必须把两步的先后顺序说死，否则结构化输出会压掉工具调用。
                sb.append("如果本任务需要计算，必须先调用工具取得结果，再把工具返回值填进 JSON；")
                        .append("不要填写未经工具计算得出的数字。\n");
            }
            sb.append("字段说明：\n").append(structure.fieldGuide()).append("\n\n");
            sb.append("JSON Schema：\n").append(structure.jsonSchema()).append("\n\n");
        }

        if (feedback != null && !feedback.isBlank()) {
            sb.append("【上一版未通过，必须修正】\n").append(feedback).append("\n\n");
        }

        sb.append("现在直接输出本任务的成果。");
        return sb.toString();
    }

    private static String interpolate(String template, Map<String, String> inputs) {
        if (inputs == null || inputs.isEmpty() || template == null) {
            return template;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = inputs.getOrDefault(m.group(1), m.group(0));
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Task 构造器。 */
    public static final class Builder {
        private String label;
        private String description = "";
        private String expectedOutput;
        private Agent agent;
        private final List<Task> explicitContext = new ArrayList<>();
        private Class<?> outputType;
        private int outputRetries = 2;
        private final List<Guardrail> guardrails = new ArrayList<>();
        private int maxRetries = 2;
        private ToolSet toolset;

        public Builder label(String v) {
            this.label = v;
            return this;
        }

        public Builder description(String v) {
            this.description = v;
            return this;
        }

        public Builder expectedOutput(String v) {
            this.expectedOutput = v;
            return this;
        }

        public Builder agent(Agent v) {
            this.agent = v;
            return this;
        }

        /** 显式限定上下文来源；不调用则继承全部前序产出。 */
        public Builder context(Task... tasks) {
            this.explicitContext.addAll(List.of(tasks));
            return this;
        }

        /**
         * 声明结构化输出契约，对应 CrewAI 的 {@code output_pydantic}。
         *
         * <p>类型必须是带 {@code @Field} 注解字段的 POJO，且有无参构造器。
         */
        public Builder outputType(Class<?> v) {
            this.outputType = v;
            return this;
        }

        public Builder outputRetries(int v) {
            this.outputRetries = Math.max(0, v);
            return this;
        }

        /** 挂载输出校验器，对应 CrewAI 的 {@code guardrails=[...]}。多个按声明顺序执行，首个失败即中止。 */
        public Builder guardrails(Guardrail... items) {
            this.guardrails.addAll(List.of(items));
            return this;
        }

        public Builder maxRetries(int v) {
            this.maxRetries = Math.max(0, v);
            return this;
        }

        /**
         * 绑定本任务专属的工具集，覆盖 Agent 自带的工具。
         *
         * <p>这是"工具按任务给"的入口：同一个 Agent 在不同阶段可以只看到该阶段需要的工具。
         * 传 {@code ToolSet.empty(...)} 表示本任务明确不给工具（模型只能靠自身知识作答），
         * 不传则继承 Agent 自带的工具——两者语义不同，不会互相兜底。
         */
        public Builder toolset(ToolSet v) {
            this.toolset = v;
            return this;
        }

        public Task build() {
            if (agent == null) {
                throw new IllegalStateException("Task 缺少 agent: " + description);
            }
            return new Task(this);
        }
    }
}
