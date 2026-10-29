package io.example.crew.mem;

/**
 * 记忆作用域。对应 CrewAI 的四类记忆，但这里刻意让作用域<b>带行为</b>而不只是标签。
 *
 * <p>CrewAI 用四个不同的存储类（ShortTermMemory / LongTermMemory / EntityMemory /
 * ContextualMemory）表达同样的区分，代价是四套检索路径。这里用"一个存储 + 一个作用域枚举 +
 * 按作用域变化的参数"来表达，检索逻辑只有一条，差异收敛到三个可调项：
 *
 * <table border="1">
 *   <caption>作用域差异</caption>
 *   <tr><th>作用域</th><th>时间敏感性</th><th>典型内容</th><th>写入时机</th></tr>
 *   <tr><td>{@link #SHORT_TERM}</td><td>强，半衰期 1 天</td><td>本次/近期任务的产出</td><td>任务完成时自动写入</td></tr>
 *   <tr><td>{@link #LONG_TERM}</td><td>弱，半衰期 30 天</td><td>稳定结论、用户偏好、踩过的坑</td><td>人工写入或跨运行沉淀</td></tr>
 *   <tr><td>{@link #ENTITY}</td><td>中，半衰期 7 天</td><td>关于某个项目/术语/人的累积认知</td><td>同实体更新而非新增</td></tr>
 *   <tr><td>{@link #KNOWLEDGE}</td><td>无</td><td>文档分块</td><td>建库时一次写入</td></tr>
 * </table>
 *
 * <p>「时间敏感性」具体体现为 recency 打分的半衰期。文档知识不该随时间贬值——
 * 三个月前写的规范今天照样有效，如果对它施加衰减，检索会系统性偏向新导入的文档，
 * 这是纯向量检索不会犯但复合打分很容易犯的错误。
 */
public enum MemoryScope {

    /** 短期：本次及近期运行的产出，价值随对话推进快速衰减。 */
    SHORT_TERM("短期", 24),

    /** 长期：跨运行稳定有效的信息，半年后依然该被召回。 */
    LONG_TERM("长期", 720),

    /** 实体：围绕某个具体对象的累积描述。 */
    ENTITY("实体", 168),

    /** 知识：外部文档分块，无时间属性。半衰期为 0 表示不衰减。 */
    KNOWLEDGE("知识", 0);

    private final String label;
    private final double halfLifeHours;

    MemoryScope(String label, double halfLifeHours) {
        this.label = label;
        this.halfLifeHours = halfLifeHours;
    }

    public String label() {
        return label;
    }

    /** recency 半衰期（小时）；0 表示该作用域不做时间衰减。 */
    public double halfLifeHours() {
        return halfLifeHours;
    }

    public static MemoryScope of(String name) {
        for (MemoryScope scope : values()) {
            if (scope.name().equalsIgnoreCase(name) || scope.label.equals(name)) {
                return scope;
            }
        }
        return SHORT_TERM;
    }
}
