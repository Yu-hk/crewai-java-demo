package io.example.crew.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 读写工具。
 *
 * <p>只做本示例需要的事：序列化字符串、递归下降解析 JSON 文本。
 * 引入它是为了让整个项目零第三方依赖，可直接 javac 编译。
 */
public final class Json {

    private Json() {
    }

    /** 转义为 JSON 字符串字面量内容（不含首尾引号）。 */
    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object value = p.parseValue();
        p.skipWs();
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("JSON 根节点不是对象: " + abbreviate(text));
        }
        return (Map<String, Object>) v;
    }

    /** 按路径取值并转字符串，任一层缺失返回 null。 */
    public static String str(Map<String, Object> root, String... path) {
        Object cur = root;
        for (String key : path) {
            if (!(cur instanceof Map<?, ?> map)) {
                return null;
            }
            cur = map.get(key);
        }
        return cur == null ? null : String.valueOf(cur);
    }

    /** 按路径取数值，缺失或非数字返回 0。 */
    public static long num(Map<String, Object> root, String key) {
        Object v = root.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }

    /** 取浮点数，缺失或非数字返回兜底值。 */
    public static double dbl(Map<String, Object> root, String key, double fallback) {
        Object v = root.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return fallback;
    }

    /**
     * 把 Java 值序列化为 JSON 文本。支持 Map / List / String / Number / Boolean / null。
     *
     * <p>只服务于"发请求"与"渲染 schema"两个场景，因此不处理循环引用与自定义对象。
     */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(128);
        writeTo(sb, value);
        return sb.toString();
    }

    private static void writeTo(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            sb.append('"').append(escape(s)).append('"');
        } else if (value instanceof Double || value instanceof Float) {
            // 解析阶段所有数字都会变成 Double，若原样输出，"200" 会写成 "200.0"。
            // 对 JSON Schema 里声明 integer 的参数，严格的服务端（如 zod 校验的 MCP 服务端）
            // 会直接判定类型不符并拒绝整个调用。整数值必须还原成整数形态。
            double d = ((Number) value).doubleValue();
            if (Double.isFinite(d) && d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(String.valueOf(e.getKey()))).append("\":");
                writeTo(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object item : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeTo(sb, item);
            }
            sb.append(']');
        } else {
            sb.append('"').append(escape(String.valueOf(value))).append('"');
        }
    }

    /**
     * 从模型输出中抠出第一个 JSON 对象。
     *
     * <p>小模型几乎必然夹带 ```json 围栏或"好的，结果如下："这类前言，因此先剥围栏再按
     * 平衡括号扫描，比直接 {@code parse} 整段文本的存活率高得多。
     *
     * @return 合法的 JSON 对象文本；找不到返回 null
     */
    public static String extractObject(String text) {
        if (text == null) {
            return null;
        }
        String s = text;
        int fence = s.indexOf("```");
        if (fence >= 0) {
            int bodyStart = fence + 3;
            if (s.startsWith("json", bodyStart)) {
                bodyStart += 4;
            }
            int fenceEnd = s.indexOf("```", bodyStart);
            if (fenceEnd > bodyStart) {
                s = s.substring(bodyStart, fenceEnd);
            }
        }
        int start = s.indexOf('{');
        if (start < 0) {
            return null;
        }
        int end = balancedEnd(s, start);
        return end < 0 ? null : s.substring(start, end + 1);
    }

    /** 从 {@code start} 处的 '{' 起找到配对的 '}'，跳过字符串内的括号。返回 -1 表示不闭合。 */
    public static int balancedEnd(String s, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 120 ? s : s.substring(0, 120) + "...";
    }

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        Object parseValue() {
            skipWs();
            if (pos >= src.length()) {
                throw error("意外结束");
            }
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            skipWs();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                String key = string();
                skipWs();
                if (peek() != ':') {
                    throw error("期望 ':'");
                }
                pos++;
                map.put(key, parseValue());
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return map;
                } else {
                    throw error("期望 ',' 或 '}'");
                }
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            pos++;
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return list;
                } else {
                    throw error("期望 ',' 或 ']'");
                }
            }
        }

        private String string() {
            if (peek() != '"') {
                throw error("期望字符串");
            }
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= src.length()) {
                    throw error("转义符后意外结束");
                }
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > src.length()) {
                            throw error("\\u 转义不完整");
                        }
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw error("非法转义 \\" + esc);
                }
            }
            throw error("字符串未闭合");
        }

        private Object number() {
            int start = pos;
            while (pos < src.length() && "-+.eE0123456789".indexOf(src.charAt(pos)) >= 0) {
                pos++;
            }
            if (start == pos) {
                throw error("非法字符 '" + src.charAt(pos) + "'");
            }
            return Double.parseDouble(src.substring(start, pos));
        }

        private Object literal(String word, Object value) {
            if (!src.startsWith(word, pos)) {
                throw error("期望 " + word);
            }
            pos += word.length();
            return value;
        }

        private char peek() {
            if (pos >= src.length()) {
                throw error("意外结束");
            }
            return src.charAt(pos);
        }

        void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private IllegalArgumentException error(String msg) {
            return new IllegalArgumentException("JSON 解析失败(" + msg + ") 位置 " + pos + ": " + abbreviate(src));
        }
    }
}
