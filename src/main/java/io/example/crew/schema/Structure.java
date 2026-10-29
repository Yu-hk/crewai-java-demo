package io.example.crew.schema;

import io.example.crew.util.Json;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 结构化输出契约：把一个带 {@link Field} 注解的 POJO 变成「JSON Schema + 校验器 + 绑定器」。
 *
 * <p>这是对齐 CrewAI {@code output_pydantic} 的落地方式。它解决的是一个具体问题：
 * 自由文本产出无法被下游程序消费。没有契约时，下游要么靠正则碰运气，要么靠人读。
 *
 * <p>三个设计取舍：
 * <ul>
 *   <li><b>不引 Jackson/Gson</b>：只支持本框架需要的类型（标量、枚举、List、嵌套 POJO），
 *       用反射自己走，避免为了一个示例拖进一整套依赖树。</li>
 *   <li><b>校验错误必须可读</b>：错误信息会被原样回灌给模型让它自我修正，因此必须写成
 *       "字段 xxx 缺失（必填）"而不是 "ValidationException"。</li>
 *   <li><b>宽容转换</b>：{@code "500"} → 500 允许，{@code 500} → "500" 允许。
 *       小模型的类型漂移是常态，卡死在这里只会陷入无限重试；真正该卡死的是缺字段和结构错误。</li>
 * </ul>
 */
public final class Structure {

    private final Class<?> type;
    private final List<java.lang.reflect.Field> fields = new ArrayList<>();

    private Structure(Class<?> type) {
        this.type = type;
        for (java.lang.reflect.Field f : type.getDeclaredFields()) {
            if (f.isSynthetic() || Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (f.getAnnotation(Field.class) == null) {
                throw new IllegalArgumentException(
                        type.getSimpleName() + "." + f.getName() + " 缺少 @Field 注解；结构化输出的每个字段都必须显式声明");
            }
            fields.add(f);
        }
        if (fields.isEmpty()) {
            throw new IllegalArgumentException(type.getSimpleName() + " 没有任何字段，无法作为输出契约");
        }
    }

    public static Structure of(Class<?> type) {
        if (type.isPrimitive() || type == String.class) {
            throw new IllegalArgumentException(type.getSimpleName() + " 不是 POJO，结构化输出的根类型必须是带 @Field 字段的类");
        }
        return new Structure(type);
    }

    public Class<?> type() {
        return type;
    }

    // ── Schema 渲染 ───────────────────────────────────────────────

    /** 生成标准 JSON Schema 文本，直接注入提示词与 Ollama 的 parameters 字段。 */
    public String jsonSchema() {
        return objectSchema(type);
    }

    /** 生成中文逐字段说明。模型对"中文说明 + Schema"的组合，遵循度明显高于纯 Schema。 */
    public String fieldGuide() {
        StringJoiner joiner = new StringJoiner("\n");
        for (java.lang.reflect.Field f : fields) {
            Field ann = f.getAnnotation(Field.class);
            String typeName = describeType(f.getType(), f.getGenericType());
            StringBuilder line = new StringBuilder("- ").append(f.getName())
                    .append("（").append(ann.optional() ? "可选" : "必填").append("，").append(typeName).append("）");
            if (!ann.desc().isBlank()) {
                line.append("：").append(ann.desc());
            }
            if (ann.maxChars() > 0) {
                line.append("，不超过 ").append(ann.maxChars()).append(" 字");
            }
            joiner.add(line.toString());
        }
        return joiner.toString();
    }

    private static String objectSchema(Class<?> type) {
        StringBuilder props = new StringBuilder("{");
        StringBuilder required = new StringBuilder("[");
        boolean firstProp = true;
        boolean firstRequired = true;
        for (java.lang.reflect.Field f : type.getDeclaredFields()) {
            if (f.isSynthetic() || Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            Field ann = f.getAnnotation(Field.class);
            if (ann == null) {
                continue;
            }
            if (!firstProp) {
                props.append(',');
            }
            firstProp = false;
            props.append('"').append(f.getName()).append("\":")
                    .append(withDescription(schemaOf(f.getType(), f.getGenericType()), ann.desc()));
            if (!ann.optional()) {
                if (!firstRequired) {
                    required.append(',');
                }
                firstRequired = false;
                required.append('"').append(f.getName()).append('"');
            }
        }
        props.append('}');
        required.append(']');
        return "{\"type\":\"object\",\"properties\":" + props + ",\"required\":" + required + "}";
    }

    private static String schemaOf(Class<?> type, Type generic) {
        if (type == String.class || type == CharSequence.class) {
            return "{\"type\":\"string\"}";
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class
                || type == short.class || type == Short.class) {
            return "{\"type\":\"integer\"}";
        }
        if (type == double.class || type == Double.class || type == float.class || type == Float.class) {
            return "{\"type\":\"number\"}";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "{\"type\":\"boolean\"}";
        }
        if (type.isEnum()) {
            return "{\"type\":\"string\",\"enum\":" + enumLiterals(type) + "}";
        }
        if (List.class.isAssignableFrom(type) || type.isArray()) {
            Class<?> element = Object.class;
            if (generic instanceof ParameterizedType pt && pt.getActualTypeArguments()[0] instanceof Class<?> c) {
                element = c;
            } else if (type.isArray()) {
                element = type.getComponentType();
            }
            return "{\"type\":\"array\",\"items\":" + schemaOf(element, null) + "}";
        }
        if (type == Object.class) {
            return "{\"type\":\"object\"}";
        }
        return objectSchema(type);
    }

    private static String withDescription(String schema, String desc) {
        if (desc == null || desc.isBlank()) {
            return schema;
        }
        return "{\"description\":\"" + Json.escape(desc) + "\"," + schema.substring(1);
    }

    private static String enumLiterals(Class<?> type) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (Object constant : type.getEnumConstants()) {
            joiner.add("\"" + Json.escape(String.valueOf(constant)) + "\"");
        }
        return joiner.toString();
    }

    private static String describeType(Class<?> type, Type generic) {
        if (type == String.class) {
            return "字符串";
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            return "整数";
        }
        if (type == double.class || type == Double.class || type == float.class || type == Float.class) {
            return "数字";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "布尔值";
        }
        if (type.isEnum()) {
            return "枚举（" + String.join(" / ", enumLiterals(type).replace("[", "").replace("]", "")
                    .replace("\"", "").split(",")) + "）";
        }
        if (List.class.isAssignableFrom(type)) {
            if (generic instanceof ParameterizedType pt
                    && pt.getActualTypeArguments()[0] instanceof Class<?> element) {
                return describeType(element, null) + "数组";
            }
            return "数组";
        }
        return "对象";
    }

    // ── 绑定与校验 ───────────────────────────────────────────────

    /** 从模型自由文本中提取 JSON 并绑定。 */
    public BindResult bind(String rawText) {
        String json = Json.extractObject(rawText);
        if (json == null) {
            return new BindResult(null, List.of(
                    "输出中找不到 JSON 对象；必须只输出一个 JSON 对象，不要写解释文字或代码块围栏"));
        }
        Map<String, Object> map;
        try {
            map = Json.parseObject(json);
        } catch (RuntimeException e) {
            return new BindResult(null, List.of("JSON 语法错误：" + e.getMessage()));
        }
        return bindMap(map);
    }

    public BindResult bindMap(Map<String, Object> map) {
        List<String> errors = new ArrayList<>();
        Object instance = instantiate(type, errors);
        if (instance == null) {
            return new BindResult(null, errors);
        }
        fill(instance, type, map, "", errors);
        return new BindResult(errors.isEmpty() ? instance : null, List.copyOf(errors));
    }

    private void fill(Object target, Class<?> type, Map<String, Object> map, String prefix, List<String> errors) {
        for (java.lang.reflect.Field f : type.getDeclaredFields()) {
            if (f.isSynthetic() || Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            Field ann = f.getAnnotation(Field.class);
            if (ann == null) {
                continue;
            }
            String path = prefix.isEmpty() ? f.getName() : prefix + "." + f.getName();
            Object raw = map.get(f.getName());
            if (raw == null) {
                if (!ann.optional()) {
                    errors.add("字段 " + path + " 缺失（必填）");
                }
                continue;
            }
            Object converted = convert(raw, f.getType(), f.getGenericType(), path, ann.maxChars(), errors);
            if (converted == null) {
                continue;
            }
            try {
                f.setAccessible(true);
                f.set(target, converted);
            } catch (ReflectiveOperationException e) {
                errors.add("字段 " + path + " 写入失败：" + e.getMessage());
            }
        }
    }

    private Object convert(Object raw, Class<?> target, Type generic, String path, int maxChars, List<String> errors) {
        if (target == String.class) {
            String value;
            if (raw instanceof String s) {
                value = s;
            } else if (raw instanceof Number || raw instanceof Boolean) {
                value = String.valueOf(raw);
            } else {
                errors.add("字段 " + path + " 应为字符串");
                return null;
            }
            if (maxChars > 0 && value.length() > maxChars) {
                errors.add("字段 " + path + " 超长 " + value.length() + " 字（上限 " + maxChars + " 字）");
                return null;
            }
            return value;
        }
        if (target == int.class || target == Integer.class) {
            Double d = asNumber(raw);
            if (d == null) {
                errors.add("字段 " + path + " 应为整数，实际是 " + preview(raw));
                return null;
            }
            return d.intValue();
        }
        if (target == long.class || target == Long.class) {
            Double d = asNumber(raw);
            if (d == null) {
                errors.add("字段 " + path + " 应为整数，实际是 " + preview(raw));
                return null;
            }
            return d.longValue();
        }
        if (target == double.class || target == Double.class) {
            Double d = asNumber(raw);
            if (d == null) {
                errors.add("字段 " + path + " 应为数字，实际是 " + preview(raw));
                return null;
            }
            return d;
        }
        if (target == boolean.class || target == Boolean.class) {
            if (raw instanceof Boolean b) {
                return b;
            }
            String s = String.valueOf(raw).trim();
            if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) {
                return Boolean.parseBoolean(s);
            }
            errors.add("字段 " + path + " 应为 true / false，实际是 " + preview(raw));
            return null;
        }
        if (target.isEnum()) {
            String s = String.valueOf(raw).trim();
            for (Object constant : target.getEnumConstants()) {
                if (String.valueOf(constant).equalsIgnoreCase(s)) {
                    return constant;
                }
            }
            errors.add("字段 " + path + " 取值不合法：" + preview(raw)
                    + "（只能取 " + enumLiterals(target) + "）");
            return null;
        }
        if (List.class.isAssignableFrom(target)) {
            if (!(raw instanceof List<?> list)) {
                errors.add("字段 " + path + " 应为数组，实际是 " + preview(raw));
                return null;
            }
            Class<?> element = Object.class;
            if (generic instanceof ParameterizedType pt && pt.getActualTypeArguments()[0] instanceof Class<?> c) {
                element = c;
            }
            List<Object> result = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                Object item = convert(list.get(i), element, null, path + "[" + i + "]", 0, errors);
                if (item == null) {
                    return null;
                }
                result.add(item);
            }
            return result;
        }
        if (raw instanceof Map<?, ?> nested) {
            @SuppressWarnings("unchecked")
            Map<String, Object> nestedMap = (Map<String, Object>) nested;
            Object instance = instantiate(target, errors);
            if (instance == null) {
                return null;
            }
            fill(instance, target, nestedMap, path, errors);
            return instance;
        }
        errors.add("字段 " + path + " 应为对象，实际是 " + preview(raw));
        return null;
    }

    private static Double asNumber(Object raw) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw instanceof String s) {
            String cleaned = s.trim().replace("%", "").replace(",", "").replace("，", "");
            try {
                return Double.parseDouble(cleaned);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String preview(Object raw) {
        String text = raw instanceof String s ? "\"" + s + "\"" : String.valueOf(raw);
        return text.length() <= 40 ? text : text.substring(0, 40) + "…";
    }

    private static Object instantiate(Class<?> type, List<String> errors) {
        try {
            Constructor<?> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (NoSuchMethodException e) {
            errors.add(type.getSimpleName() + " 缺少无参构造器，无法绑定");
        } catch (ReflectiveOperationException e) {
            errors.add("实例化 " + type.getSimpleName() + " 失败：" + e.getMessage());
        }
        return null;
    }

    /**
     * 绑定结果。
     *
     * @param value  绑定成功时的实例，失败为 null
     * @param errors 面向模型的可读错误列表，会原样进入重试提示词
     */
    public record BindResult(Object value, List<String> errors) {

        public boolean ok() {
            return errors == null || errors.isEmpty();
        }

        public String errorText() {
            return errors == null ? "" : String.join("；", errors);
        }
    }
}
