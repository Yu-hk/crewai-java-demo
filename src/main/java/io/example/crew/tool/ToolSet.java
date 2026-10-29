package io.example.crew.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一个命名的工具集合：把"工具从哪里来"与"这一轮给谁用"分开。
 *
 * <p>为什么需要它——只把工具挂在 Agent 上有三个绕不过去的问题：
 *
 * <ol>
 *   <li><b>工具清单会挤掉任务本身</b>。一个 MCP 服务端常见 10~20 个工具，
 *       全挂上去时光是工具描述就占掉上千 token，模型反而抓不住任务要求。
 *       实测把 {@code server-everything} 的 13 个工具挂上后，模型一次都没调用——
 *       它们与任务无关，只是噪音。</li>
 *   <li><b>同一个 Agent 承担多个任务时，各阶段需要的工具不同</b>。写作 Agent
 *       在起草阶段不需要字数工具，在校对阶段才需要；为了工具差异拆成两个 Agent，
 *       人设就要维护两份，而人设恰恰是最不该分裂的部分。</li>
 *   <li><b>"工具来源"是运维概念，"工具可见性"是设计概念</b>。前者由服务端决定
 *       （连上什么就有什么），后者应由任务决定（这一步该看到什么）。两者混在
 *       Agent 字段里，任何一方变化都要改另一边。</li>
 * </ol>
 *
 * <p>因此 {@code ToolSet} 是<b>不可变</b>的：{@link #only}、{@link #exclude}、
 * {@link #merge} 都返回新实例。工具集通常在启动阶段构造一次、被多个任务共享，
 * 可变的集合迟早会出现"某个任务把它改小了，另一个任务跟着遭殃"。
 *
 * <pre>{@code
 * ToolSet all = mcp.toolSet("demo");                       // 服务端给的全部
 * ToolSet math = all.only("calculator");                   // 按原始名挑，前缀无关
 * ToolSet writing = all.only("word_count").named("成文·字数核对");
 *
 * Task.builder().toolset(math)                             // 任务级绑定
 * }</pre>
 */
public final class ToolSet {

    /** 工具集来源不是某个 MCP 服务端时的标记。 */
    public static final String ORIGIN_LOCAL = "local";

    private final String name;
    private final String origin;
    private final ToolRegistry registry;

    private ToolSet(String name, String origin, ToolRegistry registry) {
        this.name = blankTo(name, "未命名工具集");
        this.origin = blankTo(origin, ORIGIN_LOCAL);
        this.registry = registry;
    }

    /** 本地工具集。 */
    public static ToolSet of(String name, Tool... tools) {
        return of(name, List.of(tools));
    }

    public static ToolSet of(String name, Collection<? extends Tool> tools) {
        return from(name, ORIGIN_LOCAL, tools);
    }

    /** 指定来源的工具集（MCP 服务端由 {@code McpServer.toolSet} 调用）。 */
    public static ToolSet from(String name, String origin, Collection<? extends Tool> tools) {
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(tools);
        return new ToolSet(name, origin, registry);
    }

    /**
     * 空工具集。
     *
     * <p>它不是"没有工具集"——绑定空集意味着<b>本任务明确不给工具</b>，
     * 用于那些"必须靠模型自身知识写、调工具反而会走偏"的任务。
     * 未绑定（{@code null}）才会回落到 Agent 自带的工具。这个区别在
     * {@code Crew} 里是显式分支，不是靠空集合顺带实现的。
     */
    public static ToolSet empty(String name) {
        return new ToolSet(name, ORIGIN_LOCAL, new ToolRegistry());
    }

    public String name() {
        return name;
    }

    public String origin() {
        return origin;
    }

    public ToolRegistry registry() {
        return registry;
    }

    public List<String> toolNames() {
        return registry.names();
    }

    public int size() {
        return registry.all().size();
    }

    public boolean isEmpty() {
        return registry.isEmpty();
    }

    /**
     * 只保留指定工具。
     *
     * <p>名字可以是注册名，也可以是别名（MCP 工具因此可以按原始名书写，
     * 不受前缀策略影响）。任何一个名字没命中都会抛异常——工具名写错是配置错误，
     * 必须在启动阶段暴露，否则会表现为"某个任务莫名其妙少了个工具"。
     */
    public ToolSet only(String... wanted) {
        return new ToolSet(name, origin, registry.select(List.of(wanted)));
    }

    /** 排除指定工具，其余保留。用于"这个服务端大部分都能用，但生成类工具不许调"。 */
    public ToolSet exclude(String... unwanted) {
        Set<String> drop = new LinkedHashSet<>();
        ToolRegistry kept = registry.filter(tool -> {
            for (String name : unwanted) {
                if (name.equals(tool.name()) || tool.aliases().contains(name)) {
                    drop.add(name);
                    return false;
                }
            }
            return true;
        });
        Set<String> missing = new LinkedHashSet<>(List.of(unwanted));
        missing.removeAll(drop);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("工具集中不存在，无法排除：" + missing
                    + "，当前可用：" + registry.names());
        }
        return new ToolSet(name, origin, kept);
    }

    /** 追加工具，返回新工具集。 */
    public ToolSet with(Tool... extra) {
        List<Tool> merged = new ArrayList<>(registry.all());
        merged.addAll(List.of(extra));
        return from(name, origin, merged);
    }

    /**
     * 合并多个工具集。
     *
     * <p>重名直接失败而不是后者覆盖：两个来源都有 {@code search} 时，
     * 模型照样能调用成功，但结果来自哪个服务端完全不可见。
     * 宁可启动失败，也不要这种静默的错答。
     */
    public ToolSet merge(ToolSet... others) {
        List<Tool> merged = new ArrayList<>(registry.all());
        List<String> origins = new ArrayList<>();
        origins.add(origin);
        for (ToolSet other : others) {
            if (other == null) {
                continue;
            }
            merged.addAll(other.registry.all());
            origins.add(other.origin);
        }
        String combined = String.join("+", new LinkedHashSet<>(origins));
        return from(name, combined, merged);
    }

    /**
     * 改名，用于在日志与审计里体现用途（如 {@code "成文·字数核对"}）。
     *
     * <p>工具集常被复用于不同任务，名字写死会让人误以为"这是同一个用途"。
     * 改名只影响展示，不改变内容与来源。
     */
    public ToolSet named(String newName) {
        return new ToolSet(newName, origin, registry);
    }

    /** 一行摘要，用于启动日志与任务绑定提示。 */
    public String summary() {
        if (isEmpty()) {
            return name + " · 空（明确不给工具）· 来源 " + origin;
        }
        return name + " · " + size() + " 个工具 · 来源 " + origin + " · " + registry.names();
    }

    @Override
    public String toString() {
        return "ToolSet[" + name + " " + registry.names() + " from " + origin + "]";
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
