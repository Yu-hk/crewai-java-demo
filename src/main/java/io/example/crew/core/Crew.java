package io.example.crew.core;

import io.example.crew.llm.LlmClient;
import io.example.crew.mem.Hit;
import io.example.crew.mem.Importance;
import io.example.crew.mem.Memory;
import io.example.crew.mem.MemoryItem;
import io.example.crew.mem.MemoryQuery;
import io.example.crew.mem.MemoryScope;
import io.example.crew.schema.Structure;
import io.example.crew.tool.ToolRegistry;
import io.example.crew.util.Console;
import io.example.crew.util.Tokens;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 智能体团队，对应 CrewAI 的 {@code Crew(agents, tasks, process)}。
 *
 * <p>职责很窄：按 {@link Process} 迭代任务、管理上下文预算、执行校验与重试、记录产出。
 * 不负责提示词拼装（在 {@link Agent} / {@link Task}），不负责模型通信（在
 * {@code LlmClient}），也不负责输出渲染（在 {@link CrewListener}）——
 * 这几层分开，才能分别替换与单独测试。
 *
 * <p>单个任务的执行有三道闸，任何一道不通过都会带着具体意见重跑：
 * <ol>
 *   <li><b>结构化校验</b>：JSON 能否解析、必填字段是否齐全、类型是否匹配</li>
 *   <li><b>Guardrails</b>：任务级业务规则（条数、字数、必须包含的片段）</li>
 *   <li><b>主管评审</b>：仅 {@code HIERARCHICAL} 下执行，语义级整体判断</li>
 * </ol>
 * 三道闸都有"重试耗尽则降级放行"的兜底：宁可产出有瑕疵也不想让链路卡死，
 * 但放行事实必须记进 {@link TaskOutput#notes()}，否则问题会被静默吞掉。
 *
 * <p>可选的状态持久化：传入 {@link RunStore} 与 {@code runId} 后，每完成一个任务就落盘一次；
 * 再次以同一 {@code runId} 启动时，已完成的任务直接从存档复用，不再消耗 token。
 * 这是"一条 130 秒的链路在第 3 步失败就要全量重跑"这个问题的正解。
 */
public final class Crew {

    /** 未指定 runId 时用于生成临时运行标识，保证同一毫秒内的多次运行互不混淆。 */
    private static final java.util.concurrent.atomic.AtomicLong ADHOC_COUNTER =
            new java.util.concurrent.atomic.AtomicLong();

    private final String name;
    private final List<Agent> agents;
    private final List<Task> tasks;
    private final Process process;
    private final Agent manager;
    private final int maxReworks;
    private final ContextBudget contextBudget;
    private final boolean debugPrompts;
    private final Memory memory;
    private final int memoryTopK;
    private final int memoryMaxChars;
    private final int memoryPinnedMaxChars;

    private Crew(Builder b) {
        this.name = b.name;
        this.agents = List.copyOf(b.agents);
        this.tasks = List.copyOf(b.tasks);
        this.process = b.process;
        this.manager = b.manager;
        this.maxReworks = b.maxReworks;
        this.contextBudget = new ContextBudget(b.contextTokens);
        this.debugPrompts = b.debugPrompts;
        this.memory = b.memory;
        this.memoryTopK = Math.max(1, b.memoryTopK);
        this.memoryMaxChars = Math.max(80, b.memoryMaxChars);
        this.memoryPinnedMaxChars = Math.max(0, b.memoryPinnedMaxChars);
        if (process == Process.HIERARCHICAL && manager == null) {
            throw new IllegalStateException("HIERARCHICAL 流程必须提供 manager");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public String name() {
        return name;
    }

    public Process process() {
        return process;
    }

    /**
     * 启动流水线，输出到控制台。
     *
     * @param inputs 变量表，用于替换任务描述中的 {placeholder}
     */
    public CrewOutput kickoff(Map<String, String> inputs) {
        return kickoff(inputs, new ConsoleListener(), null, null);
    }

    /** 启动流水线，事件交给指定监听器，不持久化。 */
    public CrewOutput kickoff(Map<String, String> inputs, CrewListener listener) {
        return kickoff(inputs, listener, null, null);
    }

    /**
     * 启动流水线，支持断点续跑。
     *
     * <p>传 {@code store} 与 {@code runId} 时，行为是<b>幂等</b>的：同一个 runId
     * 第一次跑是全新执行，第二次跑会跳过已完成的任务。调用方不需要区分
     * "首次运行"和"恢复运行"，也就不会出现两套代码路径各自有 bug 的情况。
     *
     * @param store  存档实现；为 null 表示不持久化
     * @param runId  运行标识，须匹配 {@code [A-Za-z0-9_-]{1,64}}
     */
    public CrewOutput kickoff(Map<String, String> inputs, CrewListener listener,
                              RunStore store, String runId) {
        CrewListener l = listener == null ? CrewListener.plus() : listener;
        Map<String, String> vars = inputs == null ? Map.of() : inputs;

        // 记忆隔离需要一个"本次运行"的标识。没有显式 runId 时也必须造一个，
        // 否则本运行写进去的产出会在后续任务里被当成"历史经验"再召回一遍，
        // 同一段内容既是前序上下文又是相关记忆，模型会当作两个独立证据。
        // 加计数器是因为同一毫秒内连跑两次（测试里很常见）会拿到相同标识。
        String effectiveRunId = runId != null && !runId.isBlank()
                ? runId
                : "adhoc-" + System.currentTimeMillis() + "-" + ADHOC_COUNTER.incrementAndGet();

        RunState state = null;
        if (store != null) {
            if (runId == null || runId.isBlank()) {
                throw new IllegalArgumentException("启用持久化时必须提供 runId");
            }
            state = store.load(runId).orElseGet(() -> RunState.newRun(runId, name, vars));
            verifyResumable(state, vars);
        }

        l.onCrewStart(info(), vars);
        long startedAt = System.currentTimeMillis();
        List<TaskOutput> outputs = new ArrayList<>();

        // 恢复已完成的任务：不调用模型，只把存档文本重新绑定成结构化对象
        if (state != null && !state.completed().isEmpty()) {
            for (int i = 0; i < state.completed().size(); i++) {
                Task task = tasks.get(i);
                TaskOutput restored = restore(task, state.completed().get(i));
                outputs.add(restored);
                l.onTaskReused(i + 1, tasks.size(), task, restored);
                // 复用的产出同样进记忆：它们确实是这条链路的经验，
                // 只是这次没有重新经历一遍。少了它们，中断重启后的记忆会有缺口。
                if (memory != null) {
                    rememberTask(task, vars, restored.raw(), effectiveRunId, l, i + 1);
                }
            }
            l.onResumed(runId, outputs.size());
        }
        int reused = outputs.size();

        for (int i = outputs.size(); i < tasks.size(); i++) {
            Task task = tasks.get(i);
            int ordinal = i + 1;
            try {
                l.onTaskStart(ordinal, tasks.size(), task);
                TaskOutput output = runTask(task, vars, outputs, ordinal, l, effectiveRunId);
                outputs.add(output);
                l.onTaskEnd(ordinal, tasks.size(), output);
                if (state != null) {
                    state.append(output);
                    store.save(state);
                }
            } catch (RuntimeException e) {
                l.onError(ordinal, task, e);
                // 失败教训是记忆里最值钱的一类：下一次跑同一条链路时，
                // "上次这里因为 X 挂了"比任何成功产出都更能改变行为。
                if (memory != null) {
                    rememberFailure(task, vars, e, effectiveRunId);
                }
                if (state != null) {
                    state.status(RunState.Status.FAILED);
                    store.save(state);
                }
                throw new CrewException(
                        "任务「" + task.label() + "」执行失败（已完成 " + outputs.size() + "/"
                                + tasks.size() + " 步" + (state == null ? "" : "，runId=" + runId)
                                + (state == null ? "" : "，可用同一 runId 续跑") + "）",
                        outputs.size(), tasks.size(), e);
            }
        }

        if (state != null) {
            state.status(RunState.Status.DONE);
            store.save(state);
        }
        if (memory != null) {
            memory.store().flush();
        }

        long totalMillis = System.currentTimeMillis() - startedAt;
        LlmClient.Usage usage = agents.get(0).llm().usage();
        String raw = outputs.isEmpty() ? "" : outputs.get(outputs.size() - 1).raw();
        CrewOutput result = new CrewOutput(name, raw, List.copyOf(outputs), process,
                totalMillis, usage, reused);
        l.onCrewEnd(result);
        return result;
    }

    /**
     * 续跑前的三道一致性校验。
     *
     * <p>这三项任何一项不满足，复用存档都会产生"看起来正常、实际错位"的结果——
     * 那比重新跑一遍危险得多，所以宁可显式失败也不静默兼容。
     */
    private void verifyResumable(RunState state, Map<String, String> inputs) {
        if (!state.inputs().isEmpty() && !state.inputs().equals(inputs)) {
            throw new IllegalStateException(
                    "存档 " + state.runId() + " 的输入与本次不一致，不能续跑。\n  存档: "
                            + state.inputs() + "\n  本次: " + inputs
                            + "\n请换一个 runId，或删除该存档后重跑。");
        }
        if (state.completed().size() > tasks.size()) {
            throw new IllegalStateException(
                    "存档 " + state.runId() + " 已完成 " + state.completed().size()
                            + " 步，多于当前任务数 " + tasks.size() + "，任务序列已被删减。");
        }
        for (int i = 0; i < state.completed().size(); i++) {
            String expected = tasks.get(i).label();
            String actual = state.completed().get(i).label();
            if (!Objects.equals(expected, actual)) {
                throw new IllegalStateException(
                        "存档 " + state.runId() + " 第 " + (i + 1) + " 步是「" + actual
                                + "」，当前是「" + expected + "」，任务序列已变更，不能续跑。"
                                + "\n请换一个 runId，或删除该存档后重跑。");
            }
        }
    }

    /**
     * 把存档记录还原成 {@link TaskOutput}。
     *
     * <p>结构化对象不落盘，这里用 {@code outputType} 重新绑定一次。绑定失败不算错误——
     * 说明当时就是降级放行的，保持 {@code parsed == null} 即可，语义与原始运行一致。
     */
    private TaskOutput restore(Task task, RunState.Record record) {
        Object parsed = null;
        if (task.outputType() != null) {
            Structure.BindResult binding = Structure.of(task.outputType()).bind(record.raw());
            if (binding.ok()) {
                parsed = binding.value();
            }
        }
        return new TaskOutput(task, record.raw(), record.agentRole(), record.millis(),
                record.attempts(), parsed, record.notes());
    }

    /** 执行单个任务，含结构化校验、guardrail 校验与主管评审三道闸。 */
    private TaskOutput runTask(Task task, Map<String, String> inputs, List<TaskOutput> prior,
                               int ordinal, CrewListener listener, String runId) {
        Agent agent = task.agent();
        Structure structure = task.outputType() == null ? null : Structure.of(task.outputType());
        AgentEvents events = agentEvents(ordinal, listener);

        // 有效工具在这里一次性定下来，之后五处都从它取：系统提示词的工具清单、
        // tools[] 声明、调用分发、提示词里"先调工具"的措辞、以及绑定日志。
        // 任何一处改回读 agent.registry()，都会让任务级绑定时灵时不灵。
        ToolRegistry runTools = task.effectiveTools();
        listener.onToolsBound(ordinal, task, task.toolset(), runTools.names());

        // 召回放在重试循环之外：检索的输入是任务描述，重试不会改变它。
        // 放进循环里意味着每次返工都要多做一次嵌入调用，纯浪费。
        String memoryBlock = "";
        if (memory != null) {
            List<Hit> hits = memory.recall(MemoryQuery.builder()
                    .text(task.renderedDescription(inputs))
                    .topK(memoryTopK)
                    .excludeRunId(runId)
                    .build());
            // 常驻约定 + 检索结果。两者拼接而不是合并检索：
            // 常驻约定的性质是"无条件生效"，与"可能相关"是两种不同的指令强度，
            // 混在一段里会让模型把必须遵守的内容当成可选项。
            memoryBlock = memory.pinnedBlock(memoryPinnedMaxChars) + memory.render(hits, memoryMaxChars);
            if (memory.pinnedCount() > 0) {
                listener.onNote(ordinal, "常驻约定 " + memory.pinnedCount() + " 条已注入");
            }
            listener.onMemoryRecall(ordinal, task, hits);
        }
        int memoryTokens = Tokens.estimate(memoryBlock);

        String feedback = null;
        Object parsed = null;
        int attempts = 0;
        int structFails = 0;
        int guardFails = 0;
        int reworks = 0;
        long startedAt = System.currentTimeMillis();
        List<String> notes = new ArrayList<>();

        while (true) {
            attempts++;

            ContextBudget.Result context = contextBudget.assemble(
                    task.selectContext(prior), agent.llm(), memoryTokens);
            if (context.compressed()) {
                addNote(notes, "上下文压缩至约 " + context.tokens() + " tok");
            }
            if (context.note() != null && !context.note().isBlank()) {
                listener.onNote(ordinal, context.note());
            }

            String prompt = task.buildPrompt(inputs, memoryBlock, context.text(), feedback, structure,
                    !runTools.isEmpty());
            if (debugPrompts) {
                // 调试开关，明确面向开发者，不进事件流
                Console.block(prompt, Console.gray("  │ "));
            }
            String raw = agent.execute(prompt, events, runTools);
            parsed = null;

            // 闸门一：结构化契约
            if (structure != null) {
                Structure.BindResult binding = structure.bind(raw);
                if (!binding.ok()) {
                    if (structFails < task.outputRetries()) {
                        structFails++;
                        feedback = "上一次输出未通过格式校验。请修正下列问题后重新输出，只输出一个 JSON 对象：\n- "
                                + String.join("\n- ", binding.errors());
                        listener.onCheckFailed(ordinal, task, "结构",
                                binding.errors().size() + " 项：" + binding.errorText(),
                                structFails, task.outputRetries());
                        continue;
                    }
                    addNote(notes, "结构化校验连续失败，已放行原始文本");
                    listener.onGaveUp(ordinal, task, "结构", binding.errorText());
                } else {
                    parsed = binding.value();
                }
            }

            // 闸门二：任务级 guardrails
            String guardFailure = checkGuardrails(task, raw, parsed);
            if (guardFailure != null) {
                if (guardFails < task.maxRetries()) {
                    guardFails++;
                    feedback = guardFailure;
                    listener.onCheckFailed(ordinal, task, "校验", guardFailure,
                            guardFails, task.maxRetries());
                    continue;
                }
                addNote(notes, "校验连续失败已放行：" + guardFailure);
                listener.onGaveUp(ordinal, task, "校验", guardFailure);
            }

            // 闸门三：主管评审（仅 HIERARCHICAL）
            if (process == Process.HIERARCHICAL && reworks < maxReworks) {
                String verdict = manager.execute(reviewPrompt(task, inputs, raw));
                boolean accepted = !verdict.toUpperCase().contains("REWORK");
                String opinion = accepted ? "ACCEPT" : stripPrefix(verdict);
                listener.onManagerReview(ordinal, task, accepted, opinion);
                if (!accepted) {
                    reworks++;
                    feedback = opinion;
                    continue;
                }
            }

            long cost = System.currentTimeMillis() - startedAt;
            if (memory != null) {
                rememberTask(task, inputs, raw, runId, listener, ordinal);
            }
            return new TaskOutput(task, raw, agent.role(), cost, attempts, parsed, notes);
        }
    }

    /**
     * 把任务产出写入短期记忆。
     *
     * <p><b>文本的第一段必须是任务描述，产出只能跟在后面。</b>这一点最初写错了：
     * 存的是"任务标签 + 产出"，结果下一轮同样任务几乎召不回自己上次的结果——
     * 因为检索用的 query 是<b>任务描述</b>，而存进去的是<b>产出</b>，
     * 两者在语义空间里可能相距很远（一个说"分析成本"，另一个讲"调试链路变长"）。
     *
     * <p>检索键与存储键必须是同一个东西。任务描述是这条记忆的天然检索键：
     * 下游任务表达"我要做什么"时，与历史记忆里"当时要做什么"的相似度，
     * 远高于它与"当时产出了什么"的相似度。
     *
     * <p>产出截到 400 字：整篇塞进去会让向量变成"平均语义"，
     * 反而不如保留开头这段最能代表结论的内容。
     */
    private void rememberTask(Task task, Map<String, String> inputs, String raw,
                              String runId, CrewListener listener, int ordinal) {
        String body = Console.truncate(raw, 400);
        if (body.isBlank()) {
            return;
        }
        String description = Console.truncate(task.renderedDescription(inputs), 300);
        String text = description + "\n产出：" + body;
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("runId", runId);
        meta.put("task", task.label());
        meta.put("agent", task.agent().role());
        meta.put("kind", "task-output");
        MemoryItem item = memory.remember(text, MemoryScope.SHORT_TERM, meta, Importance.of(text));
        listener.onMemoryWrite(ordinal, task, item);
    }

    /** 失败教训写入记忆，重要性固定 0.7：宁可它偶尔多占一点预算，也不要它被稀释掉。 */
    private void rememberFailure(Task task, Map<String, String> inputs, RuntimeException error, String runId) {
        String reason = Console.truncate(String.valueOf(error.getMessage()), 160);
        if (reason.isBlank()) {
            return;
        }
        String description = Console.truncate(task.renderedDescription(inputs), 300);
        String text = description + "\n上次执行失败：" + reason;
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("runId", runId);
        meta.put("task", task.label());
        meta.put("kind", "task-failure");
        memory.remember(text, MemoryScope.SHORT_TERM, meta, 0.7);
    }

    /**
     * 把流程事件桥接到 Agent 回调。
     *
     * <p>桥接层存在的理由：Agent 是共享对象，不知道自己在为哪个任务干活；
     * 而任务序号只有 Crew 知道。这一层把两者对齐，事件归属才不会错。
     */
    private static AgentEvents agentEvents(int ordinal, CrewListener listener) {
        return new AgentEvents() {
            @Override
            public void onToolCall(String tool, Map<String, Object> arguments, String result) {
                listener.onToolCall(ordinal, tool, arguments, result);
            }

            @Override
            public void onNote(String note) {
                listener.onNote(ordinal, note);
            }
        };
    }

    private static void addNote(List<String> notes, String note) {
        if (!notes.contains(note)) {
            notes.add(note);
        }
    }

    private static String checkGuardrails(Task task, String raw, Object parsed) {
        if (task.guardrails().isEmpty()) {
            return null;
        }
        Guardrail.Check check = new Guardrail.Check(raw, parsed, task);
        for (Guardrail guardrail : task.guardrails()) {
            Guardrail.Result result = guardrail.check(check);
            if (!result.pass()) {
                String reason = result.message() == null || result.message().isBlank()
                        ? "未通过校验" : result.message();
                return "[" + guardrail.name() + "] " + reason;
            }
        }
        return null;
    }

    private String reviewPrompt(Task task, Map<String, String> inputs, String candidate) {
        return """
                【评审对象】
                任务要求：%s
                期望输出：%s
                实际产出：
                %s

                【你的职责】
                你是流程主管，只做一件事：判断上述产出能否直接交付。
                逐条核对"期望输出"里的每一项是否都在实际产出中出现，缺项才算不合格。
                格式、字数符合且没有跑题时，必须判定合格。
                如果合格，只输出 ACCEPT。
                如果不合格，只输出一行 REWORK: 加一句具体、可执行的修改意见（不超过 60 字），明确指出缺了哪一项。
                不要输出其他任何内容。
                """.formatted(
                Console.truncate(task.renderedDescription(inputs), 400),
                nvl(task.expectedOutput()),
                Console.truncate(candidate, 1200));
    }

    private static String stripPrefix(String verdict) {
        String cleaned = verdict.replaceAll("(?i).*?REWORK\\s*[:：]\\s*", "");
        String trimmed = cleaned.strip();
        return trimmed.isEmpty() ? "未给出具体意见，请提高产出的针对性与完整度" : trimmed;
    }

    private static String nvl(String s) {
        return s == null || s.isBlank() ? "（未指定）" : s;
    }

    private CrewListener.CrewInfo info() {
        return new CrewListener.CrewInfo(
                name,
                agents.get(0).llm().model(),
                process,
                agents.size(),
                tasks.size(),
                contextBudget.maxTokens(),
                tasks.stream().map(t -> t.label() + "[" + t.agent().role() + "]").toList());
    }

    /** Crew 构造器。 */
    public static final class Builder {
        private String name = "未命名流水线";
        private final List<Agent> agents = new ArrayList<>();
        private final List<Task> tasks = new ArrayList<>();
        private Process process = Process.SEQUENTIAL;
        private Agent manager;
        private int maxReworks = 1;
        private int contextTokens = 2500;
        private boolean debugPrompts;
        private Memory memory;
        private int memoryTopK = 4;
        private int memoryMaxChars = 600;
        private int memoryPinnedMaxChars = 300;

        public Builder name(String v) {
            this.name = v;
            return this;
        }

        public Builder agents(Agent... v) {
            this.agents.addAll(List.of(v));
            return this;
        }

        public Builder tasks(Task... v) {
            this.tasks.addAll(List.of(v));
            return this;
        }

        public Builder process(Process v) {
            this.process = v;
            return this;
        }

        public Builder manager(Agent v) {
            this.manager = v;
            return this;
        }

        public Builder maxReworks(int v) {
            this.maxReworks = v;
            return this;
        }

        /** 注入前序上下文的 token 预算。超出后保留最近几条原文，更早的压缩成要点。 */
        public Builder contextTokens(int v) {
            this.contextTokens = v;
            return this;
        }

        /** 打印每个任务实际发出去的 prompt，用于排查"提示词写得对不对"。 */
        public Builder debugPrompts(boolean v) {
            this.debugPrompts = v;
            return this;
        }

        /**
         * 挂载记忆。传入后每个任务开始前召回相关记忆、结束后写入产出。
         *
         * <p>对应 CrewAI 的 {@code Crew(memory=True)}，但由调用方决定用哪种存储与嵌入——
         * 记忆的持久化范围（进程内 / 跨进程）是业务决策，不该由框架写死。
         */
        public Builder memory(Memory v) {
            this.memory = v;
            return this;
        }

        /** 每个任务最多注入几条记忆。超过 4 条后收益急剧下降，而每条都在挤占任务描述的预算。 */
        public Builder memoryTopK(int v) {
            this.memoryTopK = v;
            return this;
        }

        /** 记忆块的字数上限。它与 {@code contextTokens} 共享同一份预算。 */
        public Builder memoryMaxChars(int v) {
            this.memoryMaxChars = v;
            return this;
        }

        /** 常驻约定块的字数上限。设 0 表示不注入常驻记忆。 */
        public Builder memoryPinnedMaxChars(int v) {
            this.memoryPinnedMaxChars = v;
            return this;
        }

        public Crew build() {
            if (agents.isEmpty()) {
                throw new IllegalStateException("Crew 至少需要一个 Agent");
            }
            if (tasks.isEmpty()) {
                throw new IllegalStateException("Crew 至少需要一个 Task");
            }
            return new Crew(this);
        }
    }
}
