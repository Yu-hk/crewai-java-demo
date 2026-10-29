package io.example.crew.core;

import java.util.Map;

/**
 * Agent 执行期的回调，由调用方注入。
 *
 * <p>单独抽这个接口，是因为 {@link Agent} 是<b>可复用</b>的：同一个 Agent 会被多个
 * Task 复用，也会被主管评审这类"非任务"场景复用。因此回调不能绑在 Agent 上，
 * 只能由调用方在每次 {@code execute} 时传入。绑定在构造期的后果是事件归属错乱——
 * 任务 3 的工具调用被记到任务 1 的账上。
 *
 * <p>更重要的目的：让 Agent <b>不再直接写 Console</b>。库代码一旦写死 {@code System.out}，
 * 就没法在 web 服务里用（会把日志塞进 stdout），也没法写断言测试。
 */
public interface AgentEvents {

    /** 空实现，用于不关心事件的场景。 */
    AgentEvents NONE = new AgentEvents() {
    };

    /** 发生一次工具调用。 */
    default void onToolCall(String tool, Map<String, Object> arguments, String result) {
    }

    /** 执行期的诊断信息（例如原生工具协议不可用、已降级）。 */
    default void onNote(String note) {
    }
}
