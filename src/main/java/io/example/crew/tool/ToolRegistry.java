package io.example.crew.tool;

import io.example.crew.llm.ToolSpec;
import io.example.crew.schema.Structure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Predicate;

/**
 * 工具注册表：负责把工具清单渲染进提示词、生成 function calling 声明、按名称分发调用。
 *
 * <p>三套输出对应三种消费方：{@link #describe()} 给文本协议的老路子，
 * {@link #specs()} 给原生 function calling，{@link #invoke} 给两者共用的执行入口。
 * 保留两套声明不是为了向后兼容，而是因为弱模型上原生协议确实会失灵。
 */
public final class ToolRegistry {

    private static final String EMPTY_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Map<String, String> parametersJson = new LinkedHashMap<>();
    private final Map<String, Class<?>> parameterTypes = new LinkedHashMap<>();

    public ToolRegistry() {
    }

    /**
     * 注册工具。
     *
     * <p>参数 Schema 在这里就生成好，而不是等到调用时——参数类写错（字段漏了 {@code @Field}）
     * 应该在启动阶段就炸出来，而不是跑了几分钟后在某个任务里炸。
     */
    public ToolRegistry register(Tool tool) {
        String name = tool.name();
        if (tools.containsKey(name)) {
            // 静默覆盖在多 MCP 服务端场景下极其危险：两个服务端都有 search，
            // 后注册的把前一个顶掉，模型照样调用，结果来自错误的服务端且毫无提示。
            throw new IllegalArgumentException("工具名冲突：" + name
                    + "。多个 MCP 服务端的同名工具请用 toolPrefix 区分，例如 \"fs__search\"。");
        }
        Class<?> type = tool.parametersType();
        String schema = tool.parametersJson();
        tools.put(name, tool);
        parameterTypes.put(name, type);
        parametersJson.put(name, schema != null && !schema.isBlank()
                ? schema
                : type == null ? EMPTY_SCHEMA : Structure.of(type).jsonSchema());
        return this;
    }

    /** 批量注册（用于把 MCP 服务端暴露的一整组工具挂上来）。 */
    public ToolRegistry registerAll(Collection<? extends Tool> items) {
        for (Tool tool : items) {
            register(tool);
        }
        return this;
    }

    public static ToolRegistry of(Tool... items) {
        ToolRegistry registry = new ToolRegistry();
        for (Tool tool : items) {
            registry.register(tool);
        }
        return registry;
    }

    /**
     * 拷贝构造。
     *
     * <p>子集操作走这里而不是 {@code register}：重新注册会再跑一遍 Schema 生成，
     * 而 MCP 工具的 Schema 来自服务端（`parametersJson()`），既不该重算也不该有第二份。
     * 拷贝的是同一批 {@link Tool} 实例——工具本身无状态，被多个注册表持有是安全的。
     */
    private ToolRegistry(ToolRegistry source) {
        this.tools.putAll(source.tools);
        this.parametersJson.putAll(source.parametersJson);
        this.parameterTypes.putAll(source.parameterTypes);
    }

    /** 注册表里所有工具的暴露名，按注册顺序。 */
    public List<String> names() {
        return List.copyOf(tools.keySet());
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    /** 按谓词过滤出子集；返回新实例，原注册表不变。 */
    public ToolRegistry filter(Predicate<Tool> predicate) {
        ToolRegistry out = new ToolRegistry(this);
        out.tools.entrySet().removeIf(e -> !predicate.test(e.getValue()));
        out.parametersJson.keySet().retainAll(out.tools.keySet());
        out.parameterTypes.keySet().retainAll(out.tools.keySet());
        return out;
    }

    /**
     * 按名挑出子集。名字可以是暴露名，也可以是 {@link Tool#aliases()} 里的别名。
     *
     * <p>找不到就抛异常，而不是静默跳过：工具名写错属于配置错误，
     * 应该在启动阶段炸出来。静默跳过会让"任务 A 少了个工具"表现为模型能力不足，
     * 排查方向完全跑偏。
     */
    public ToolRegistry select(Collection<String> wanted) {
        Set<String> missing = new LinkedHashSet<>(wanted);
        Map<String, Tool> picked = new LinkedHashMap<>();
        for (Tool tool : tools.values()) {
            String hit = match(tool, wanted);
            if (hit != null) {
                picked.put(tool.name(), tool);
                missing.remove(hit);
                missing.remove(tool.name());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("工具集中不存在：" + missing
                    + "，当前可用：" + names());
        }
        ToolRegistry out = new ToolRegistry(this);
        out.tools.keySet().retainAll(picked.keySet());
        out.parametersJson.keySet().retainAll(picked.keySet());
        out.parameterTypes.keySet().retainAll(picked.keySet());
        return out;
    }

    /** 返回命中的那个名字（暴露名或别名），未命中返回 null。 */
    private static String match(Tool tool, Collection<String> wanted) {
        if (wanted.contains(tool.name())) {
            return tool.name();
        }
        for (String alias : tool.aliases()) {
            if (wanted.contains(alias)) {
                return alias;
            }
        }
        return null;
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }

    public Collection<Tool> all() {
        return tools.values();
    }

    /** 生成写入系统提示词的工具清单（文本协议用）。 */
    public String describe() {
        StringJoiner joiner = new StringJoiner("\n");
        for (Tool tool : tools.values()) {
            String signature = tool.signature().isEmpty() ? "" : " 参数: " + tool.signature();
            joiner.add("- " + tool.name() + ": " + tool.description() + signature);
        }
        return joiner.toString();
    }

    /** 生成 function calling 声明。 */
    public List<ToolSpec> specs() {
        List<ToolSpec> specs = new ArrayList<>(tools.size());
        for (Tool tool : tools.values()) {
            specs.add(new ToolSpec(tool.name(), tool.description(), parametersJson.get(tool.name())));
        }
        return specs;
    }

    /**
     * 调用工具。任何失败都转换为文本返回，而不是抛出中断整个 Crew ——
     * 让模型看到错误信息后自行修正，这是 ReAct 循环能收敛的关键。
     *
     * <p>参数校验放在这里而不是各工具内部，好处是所有工具共享同一套报错格式，
     * 模型看到的一致，修正成功率也更高。
     */
    public String invoke(String name, Map<String, Object> arguments) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return "ERROR: 未知工具 '" + name + "'，可用工具: " + tools.keySet();
        }
        Class<?> parameterType = parameterTypes.get(name);
        if (parameterType != null) {
            Structure.BindResult binding = Structure.of(parameterType).bindMap(arguments);
            if (!binding.ok()) {
                return "ERROR: 参数不合法 —— " + binding.errorText();
            }
        }
        try {
            return tool.invoke(arguments);
        } catch (Tool.ToolException e) {
            return "ERROR: " + e.getMessage();
        } catch (RuntimeException e) {
            // 连接级故障必须冒泡：回灌给模型只会让它反复重试一个它无法修复的问题。
            if (e instanceof FatalToolFailure) {
                throw e;
            }
            return "ERROR: 工具执行异常 " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
