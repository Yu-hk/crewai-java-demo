package io.example.crew.core;

/**
 * 输出校验器，对应 CrewAI 的 {@code Task(guardrail=...)}。
 *
 * <p>与"主管评审"（{@code Process.HIERARCHICAL}）的区别必须说清，否则容易做重：
 * <ul>
 *   <li><b>Guardrail</b>：挂在单个任务上，<b>每次必跑</b>，判定标准是代码里写死的（或一段固定 prompt），
 *       失败即带错误信息重做。它是"质量下限"。</li>
 *   <li><b>主管评审</b>：挂在流程层，对每一步做整体判断，标准由模型自己理解。它是"整体一致性"。</li>
 * </ul>
 * 二者都跑等于双倍成本。生产上通常只用 Guardrail —— 因为它的判定可复现、可单测。
 */
@FunctionalInterface
public interface Guardrail {

    Result check(Check context);

    default String name() {
        return "guardrail";
    }

    /**
     * 校验输入。
     *
     * @param output 模型原始输出
     * @param parsed 结构化输出绑定结果，未声明 {@code outputType} 时为 null
     * @param task   所属任务，校验器可从中读取期望输出等元信息
     */
    record Check(String output, Object parsed, Task task) {
    }

    /**
     * 校验结果。
     *
     * @param pass    true 表示通过
     * @param message 不通过时的可执行修改意见，会原样回灌给模型
     */
    record Result(boolean pass, String message) {

        public static Result ok() {
            return new Result(true, "");
        }

        public static Result fail(String message) {
            return new Result(false, message);
        }
    }
}
