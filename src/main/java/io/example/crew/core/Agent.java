package io.example.crew.core;

import io.example.crew.llm.ChatMessage;
import io.example.crew.llm.ChatReply;
import io.example.crew.llm.LlmClient;
import io.example.crew.llm.LlmException;
import io.example.crew.llm.NativeToolCall;
import io.example.crew.llm.ToolSpec;
import io.example.crew.tool.ToolRegistry;
import io.example.crew.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一个角色化的智能体，对应 CrewAI 的 {@code Agent(role, goal, backstory)}。
 *
 * <p>这里承载了 CrewAI 最值得借鉴的部分：<b>三段式人设如何拼装为系统提示词</b>。
 * 顺序、措辞、约束条款的写法，直接决定输出质量，比 API 形状重要得多。
 *
 * <p>工具调用有两条通路，优先走原生：
 * <ol>
 *   <li><b>原生 function calling</b>：把工具声明放进请求的 {@code tools[]}，
 *       由 Ollama 侧约束模型输出结构化的 {@code tool_calls}，无需解析文本。</li>
 *   <li><b>文本协议降级</b>：模型输出 {@code [TOOL] {...}}，框架用平衡括号扫描解析。
 *       保留它是因为部分模型 / Ollama 版本会对 {@code tools} 字段直接返回 400，
 *       没有降级就意味着整条链路不可用。</li>
 * </ol>
 * 降级是<b>粘性</b>的：一旦某次原生调用被拒，后续不再尝试，避免每次任务都白撞一次 400。
 */
public final class Agent {

    private final String role;
    private final String goal;
    private final String backstory;
    private final LlmClient llm;
    private final ToolRegistry tools;
    private final double temperature;
    private final int maxIter;

    /** 原生协议被服务端拒绝后置为 true，此后一律走文本协议。 */
    private volatile boolean nativeToolsDisabled;

    private Agent(Builder b) {
        this.role = b.role;
        this.goal = b.goal;
        this.backstory = b.backstory;
        this.llm = b.llm;
        this.tools = b.tools;
        this.temperature = b.temperature;
        this.maxIter = b.maxIter;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String role() {
        return role;
    }

    public String goal() {
        return goal;
    }

    public boolean hasTools() {
        return !tools.isEmpty();
    }

    /** Agent 自带的工具注册表。任务未绑定工具集时以它为准。 */
    public ToolRegistry registry() {
        return tools;
    }

    public LlmClient llm() {
        return llm;
    }

    /** 本次执行是否会使用原生 function calling。 */
    public boolean useNativeTools() {
        return useNativeTools(tools);
    }

    /**
     * 按<b>实际要用的</b>工具集判断是否走原生协议。
     *
     * <p>不能用 {@code this.tools} 代答：任务绑定的工具集可能为空（明确不给工具），
     * 此时即便 Agent 自带一堆工具，也不该给模型发 {@code tools[]} 声明。
     */
    private boolean useNativeTools(ToolRegistry effective) {
        return !effective.isEmpty() && llm.supportsNativeTools() && !nativeToolsDisabled;
    }

    /**
     * 拼装系统提示词（用 Agent 自带的工具集）。
     *
     * @see #systemPrompt(ToolRegistry)
     */
    public String systemPrompt() {
        return systemPrompt(tools);
    }

    /**
     * 拼装系统提示词。这是整个框架的"效果开关"，四段结构：
     * 身份 → 目标 → 工具协议 → 行为约束。
     *
     * <p>工具清单必须与实际下发的 {@code tools[]} 声明来自<b>同一个</b>注册表。
     * 两处不一致的后果很具体：文本协议下模型照提示词去调一个没有声明的工具，
     * 框架按名分发找不到，回灌一句"未知工具"，模型再换个名字重试——白白烧掉两轮。
     *
     * @param effective 本次任务实际可用的工具（任务绑定的工具集，或 Agent 自带的）
     */
    public String systemPrompt(ToolRegistry effective) {
        ToolRegistry available = effective == null ? tools : effective;
        boolean nativeMode = useNativeTools(available);
        StringBuilder sb = new StringBuilder(512);
        sb.append("You are ").append(role).append(".\n");
        sb.append(backstory).append("\n\n");
        sb.append("Your personal goal is: ").append(goal).append("\n\n");

        if (!available.isEmpty()) {
            sb.append("可用工具：\n").append(available.describe()).append("\n\n");
            if (nativeMode) {
                sb.append("需要计算或查询时，直接发起工具调用，不要自己推算；")
                        .append("拿到工具结果后再给最终答案。\n\n");
            } else {
                sb.append("需要调用工具时，只输出一行，格式必须完全如下：\n");
                sb.append("[TOOL] {\"name\": \"<工具名>\", \"arguments\": {<参数>}}\n");
                sb.append("系统会以 [TOOL_RESULT] 把结果返回给你。同一个回合内不要既调用工具又给最终答案。\n\n");
            }
        }

        sb.append("行为约束：\n");
        sb.append("1. 只输出任务要求的成果本身，不要写\"好的\"\"以下是\"\"希望有帮助\"这类寒暄。\n");
        sb.append("2. 全部使用简体中文。\n");
        sb.append("3. 不要编造无法确认的具体数字、日期、机构名；不确定时明确写\"推测\"。\n");
        sb.append("4. 严格遵守任务中给出的字数与格式要求。\n");
        return sb.toString();
    }

    /**
     * 执行一次任务，返回最终文本。不关心过程事件时使用。
     *
     * @param userPrompt 由 Task 组装的用户消息（任务描述 + 期望输出 + 前序上下文）
     */
    public String execute(String userPrompt) {
        return execute(userPrompt, AgentEvents.NONE);
    }

    /**
     * 执行一次任务，过程事件通过 {@code events} 上报。
     *
     * <p>Agent 自身不再打印任何东西：控制台渲染、指标统计、UI 进度都由调用方
     * 通过 {@link AgentEvents} 决定。这是这个类能被非 CLI 场景复用的前提。
     */
    public String execute(String userPrompt, AgentEvents events) {
        return execute(userPrompt, events, tools);
    }

    /**
     * 执行一次任务，工具集由调用方在运行期指定。
     *
     * <p>工具集是<b>参数</b>而不是 Agent 的字段，这一点是刻意的：同一个 Agent
     * 会被多个任务共用，若把"本轮可用工具"写回字段，任务之间就会互相串味
     * （上一个任务缩小过的工具集，下一个任务还在用）。这也让 {@code Crew}
     * 可以在不改 Agent 的前提下按任务裁剪工具。
     *
     * @param effectiveTools 本次实际可用的工具；为 null 时用 Agent 自带的
     */
    public String execute(String userPrompt, AgentEvents events, ToolRegistry effectiveTools) {
        ToolRegistry available = effectiveTools == null ? tools : effectiveTools;
        AgentEvents sink = events == null ? AgentEvents.NONE : events;
        if (useNativeTools(available)) {
            try {
                return executeNative(userPrompt, sink, available);
            } catch (LlmException e) {
                // 400 通常意味着该模型不支持 tools 字段。其余错误（连接失败、超时）直接抛出，
                // 否则会在文本协议上再失败一遍，把一次故障拖成两次超时。
                if (e.statusCode() == 400) {
                    nativeToolsDisabled = true;
                    sink.onNote("模型不支持原生工具调用，本次起降级为文本协议");
                } else {
                    throw e;
                }
            }
        }
        return executeTextProtocol(userPrompt, sink, available);
    }

    private String executeNative(String userPrompt, AgentEvents events, ToolRegistry available) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt(available)));
        messages.add(ChatMessage.user(userPrompt));
        List<ToolSpec> specs = available.specs();

        for (int iteration = 1; iteration <= maxIter; iteration++) {
            ChatReply reply = llm.chat(messages, temperature, specs);
            if (!reply.hasToolCalls()) {
                return reply.content().strip();
            }
            // 助手消息必须原样带回调用请求，否则模型不知道自己上一轮要过工具。
            messages.add(ChatMessage.assistantToolCalls(reply.content(), reply.toolCalls()));
            for (NativeToolCall call : reply.toolCalls()) {
                String result = available.invoke(call.name(), call.arguments());
                events.onToolCall(call.name(), call.arguments(), result);
                messages.add(ChatMessage.tool(call.name(), result));
            }
        }

        messages.add(ChatMessage.user("已达迭代上限（" + maxIter + " 轮），请不要再调用工具，立即给出最终答案。"));
        return llm.chat(messages, temperature, List.of()).content().strip();
    }

    private String executeTextProtocol(String userPrompt, AgentEvents events, ToolRegistry available) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt(available)));
        messages.add(ChatMessage.user(userPrompt));

        for (int iteration = 1; iteration <= maxIter; iteration++) {
            String reply = llm.chat(messages, temperature, List.of()).content();
            ToolCall call = ToolCall.parse(reply);
            if (call == null) {
                return reply.strip();
            }
            String result = available.invoke(call.name(), call.arguments());
            events.onToolCall(call.name(), call.arguments(), result);
            messages.add(ChatMessage.assistant(reply));
            messages.add(ChatMessage.user(
                    "[TOOL_RESULT] " + result + "\n请据此继续；若信息已足够，直接给出最终答案。"));
        }

        messages.add(ChatMessage.user("已达迭代上限（" + maxIter + " 轮），请不要再调用工具，立即给出最终答案。"));
        return llm.chat(messages, temperature, List.of()).content().strip();
    }

    /** 模型输出中的一次工具调用（文本协议）。解析容错：取最后一个 '{' 起的平衡大括号块。 */
    private record ToolCall(String name, Map<String, Object> arguments) {

        @SuppressWarnings("unchecked")
        static ToolCall parse(String reply) {
            if (reply == null) {
                return null;
            }
            int marker = reply.indexOf("[TOOL]");
            if (marker < 0) {
                return null;
            }
            String json = Json.extractObject(reply.substring(marker));
            if (json == null) {
                return null;
            }
            try {
                Map<String, Object> parsed = Json.parseObject(json);
                Object name = parsed.get("name");
                if (name == null) {
                    return null;
                }
                Object args = parsed.get("arguments");
                Map<String, Object> argMap = args instanceof Map<?, ?> map
                        ? (Map<String, Object>) map
                        : Map.of();
                return new ToolCall(String.valueOf(name), argMap);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** Agent 构造器。 */
    public static final class Builder {
        private String role = "助手";
        private String goal = "完成交给你的任务";
        private String backstory = "你是一名经验丰富的专业人士。";
        private LlmClient llm;
        private ToolRegistry tools = new ToolRegistry();
        private double temperature = 0.7;
        private int maxIter = 6;

        public Builder role(String v) {
            this.role = v;
            return this;
        }

        public Builder goal(String v) {
            this.goal = v;
            return this;
        }

        public Builder backstory(String v) {
            this.backstory = v;
            return this;
        }

        public Builder llm(LlmClient v) {
            this.llm = v;
            return this;
        }

        /**
         * 挂载 Agent 级工具。
         *
         * <p>传 {@code null} 等价于"不挂任何工具"，而不是报错：工具改由任务绑定时，
         * 构造 Agent 的代码不该为了绕过空指针校验而写一个空注册表。
         */
        public Builder tools(ToolRegistry v) {
            this.tools = v == null ? new ToolRegistry() : v;
            return this;
        }

        public Builder temperature(double v) {
            this.temperature = v;
            return this;
        }

        /** 单次执行内允许的最大 LLM 往返轮数（含工具调用轮），对应 CrewAI 的 {@code max_iter}。 */
        public Builder maxIter(int v) {
            this.maxIter = Math.max(1, v);
            return this;
        }

        public Agent build() {
            if (llm == null) {
                throw new IllegalStateException("Agent '" + role + "' 缺少 llm");
            }
            return new Agent(this);
        }
    }
}
