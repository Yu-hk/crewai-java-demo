package io.example.crew.tool;

import io.example.crew.schema.Field;

import java.util.Map;

/**
 * 四则运算工具，用于演示工具调用闭环。
 *
 * <p>刻意选择"确定性计算"作为示例工具：本地小模型做算术容易出错，
 * 而工具调用正是模型不可靠能力的补偿手段，演示价值比"查天气"更高。
 * 表达式求值采用递归下降，不依赖脚本引擎（JDK 17 已移除 Nashorn）。
 */
public final class CalculatorTool implements Tool {

    /** 参数契约。声明后会被反射成 JSON Schema，同时用于调用前的参数校验。 */
    public static final class Params {

        @Field(desc = "算术表达式，仅支持 + - * / 与括号，例如 (3*2-1)/1*100")
        String expression;
    }

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public String description() {
        return "精确计算四则运算表达式，支持 + - * / 与括号。任何涉及数值计算的地方都应使用它，不要心算。"
                + "传入的 expression 必须原样保留括号，否则运算优先级会改变。";
    }

    @Override
    public String signature() {
        return "expression: string，例如 \"(3-1)/1*100\"";
    }

    @Override
    public Class<?> parametersType() {
        return Params.class;
    }

    @Override
    public String invoke(Map<String, Object> arguments) {
        Object raw = arguments.get("expression");
        if (raw == null) {
            throw new ToolException("缺少参数 expression");
        }
        String expression = String.valueOf(raw).trim();
        if (expression.isEmpty()) {
            throw new ToolException("expression 不能为空");
        }
        double value = new Evaluator(expression).evaluate();
        if (Math.abs(value - Math.rint(value)) < 1e-9) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.valueOf(value);
    }

    /** 递归下降求值器：expr → term ((+|-) term)*，term → factor ((*|/) factor)*。 */
    private static final class Evaluator {
        private final String src;
        private int pos;

        Evaluator(String src) {
            this.src = src;
        }

        double evaluate() {
            double v = expr();
            skipWs();
            if (pos < src.length()) {
                throw new ToolException("表达式包含无法识别的内容: '" + src.substring(pos) + "'");
            }
            return v;
        }

        private double expr() {
            double value = term();
            while (true) {
                skipWs();
                if (eat('+')) {
                    value += term();
                } else if (eat('-')) {
                    value -= term();
                } else {
                    return value;
                }
            }
        }

        private double term() {
            double value = factor();
            while (true) {
                skipWs();
                if (eat('*')) {
                    value *= factor();
                } else if (eat('/')) {
                    double divisor = factor();
                    if (divisor == 0) {
                        throw new ToolException("除数为 0");
                    }
                    value /= divisor;
                } else {
                    return value;
                }
            }
        }

        private double factor() {
            skipWs();
            if (eat('(')) {
                double value = expr();
                skipWs();
                if (!eat(')')) {
                    throw new ToolException("括号未闭合");
                }
                return value;
            }
            if (eat('-')) {
                return -factor();
            }
            return number();
        }

        private double number() {
            skipWs();
            int start = pos;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new ToolException("位置 " + pos + " 处期望数字: " + src);
            }
            return Double.parseDouble(src.substring(start, pos));
        }

        private boolean eat(char c) {
            if (pos < src.length() && src.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        private void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }
    }
}
