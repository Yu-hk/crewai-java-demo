package io.example.crew.core;

import io.example.crew.llm.ChatMessage;
import io.example.crew.llm.LlmClient;
import io.example.crew.util.Console;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * {@link Guardrail} 的工厂集合。
 *
 * <p>设计原则：<b>能用代码判断的，绝不用模型判断</b>。
 * 一个"必须出现两条判断"的规则，用 {@link #contains} 判定是 0 成本、100% 可复现的；
 * 用 LLM 判定是 1 次调用、且可能判断错。所以这里的顺序是先给函数式工具，
 * 最后才提供 {@link #llm} 作为语义判断的兜底。
 */
public final class Guardrails {

    private Guardrails() {
    }

    /** 自定义断言。最通用的一种，期望输出本身不可枚举时用它。 */
    public static Guardrail require(String name, String failMessage, Predicate<Guardrail.Check> test) {
        return new FnGuardrail(name, test, failMessage);
    }

    /** 输出必须同时包含全部关键片段。适用于"漏项"类问题——小模型最典型的失败模式。 */
    public static Guardrail contains(String name, String failMessage, String... fragments) {
        return new FnGuardrail(name, check -> {
            List<String> missing = new ArrayList<>();
            for (String fragment : fragments) {
                if (!check.output().contains(fragment)) {
                    missing.add(fragment);
                }
            }
            return missing.isEmpty();
        }, failMessage);
    }

    /** 输出不得短于 N 个字符。防止模型用一句"已完成"敷衍了事。 */
    public static Guardrail minChars(String name, int min) {
        return require(name, "产出过短，至少需要 " + min + " 字，请补充必要内容",
                check -> check.output().strip().length() >= min);
    }

    /** 输出不得超过 N 个字符。防止模型无视字数上限。 */
    public static Guardrail maxChars(String name, int max) {
        return require(name, "产出超出字数上限，请压缩篇幅后重新输出",
                check -> check.output().strip().length() <= max);
    }

    /** 输出必须能解析出 JSON 对象且已通过结构校验。 */
    public static Guardrail structured(String name) {
        return require(name, "输出必须是合法的 JSON 对象并通过字段校验", check -> check.parsed() != null);
    }

    /**
     * 用一次模型调用做语义验收。
     *
     * <p>成本与风险都要如实计入：它多花一次调用，且判定本身可能出错。
     * 因此失败时（调用异常）选择放行并告警，而不是阻断整条链路——
     * 校验器自己挂掉不应该让业务停摆。
     *
     * @param criteria 验收标准，写成可勾选的具体条目，不要写"质量要好"这种话
     */
    public static Guardrail llm(LlmClient llm, String name, String criteria) {
        return new LlmGuardrail(llm, name, criteria);
    }

    private record FnGuardrail(String name, Predicate<Guardrail.Check> test, String failMessage)
            implements Guardrail {

        @Override
        public Result check(Check context) {
            return test.test(context) ? Result.ok() : Result.fail(failMessage);
        }
    }

    private record LlmGuardrail(LlmClient llm, String name, String criteria) implements Guardrail {

        @Override
        public Result check(Check context) {
            String prompt = """
                    【验收标准】
                    %s

                    【待检产出】
                    %s

                    只做判断，不要改写产出。
                    完全满足标准时，只输出 PASS。
                    不满足时，只输出一行 FAIL: 加一句具体缺了什么（不超过 50 字）。
                    """.formatted(criteria, clip(context.output(), 2000));
            try {
                String verdict = llm.chat(List.of(
                        ChatMessage.system("你是严格的内容验收员，只输出 PASS 或一行 FAIL: 理由。"),
                        ChatMessage.user(prompt)), 0.0).content();
                String upper = verdict.toUpperCase();
                if (upper.contains("FAIL")) {
                    String reason = verdict.replaceAll("(?i).*?FAIL\\s*[:：]?\\s*", "").strip();
                    return Result.fail(reason.isEmpty() ? "未满足验收标准" : reason);
                }
                return Result.ok();
            } catch (RuntimeException e) {
                Console.warn("LLM 校验器 [" + name + "] 调用失败，按放行处理：" + e.getMessage());
                return Result.ok();
            }
        }
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "\n…(已截断)";
    }
}
