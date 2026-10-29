package io.example.crew.mem;

/**
 * 记忆重要性的启发式打分。
 *
 * <p>CrewAI / mem0 的做法是"再调一次 LLM 给这条记忆打 0~10 分"。这里没这么做，原因很实际：
 * 每写一条记忆就多一次模型调用，在 7B 本地模型上一次要 2~5 秒，而且打分结果本身不稳定
 * （同一段文本两三次调用给出 4 分、7 分、5 分）。用它做长期权重，等于给检索引入随机噪声。
 *
 * <p>替代方案是"启发式给底分 + 调用方可覆盖"：{@link Memory#remember} 默认用这里算的分，
 * 需要精确控制时显式传 importance。启发式只做一件有把握的事——识别<b>约束与教训</b>。
 * "必须""不要""失败过"这类词的共现，比模型打分更能说明一条信息会不会长期有用。
 *
 * <p>所有分都偏低（基线 0.4）是刻意的：importance 只占复合分 10%，
 * 它的职责是"把差点被漏掉的重要信息拉回视野"，不是主导排序。
 */
public final class Importance {

    /** 大部分记忆的起点分。低于 0.5 意味着"默认不重要"，符合大多数产出的实际价值。 */
    private static final double BASE = 0.4;

    private Importance() {
    }

    public static double of(String text) {
        if (text == null || text.isBlank()) {
            return 0.05;
        }
        double score = BASE;
        if (containsAny(text, "必须", "不要", "禁止", "一律", "永远", "绝不", "务必", "必须不", "must", "never")) {
            score += 0.25;
        }
        if (containsAny(text, "偏好", "习惯", "倾向于", "更喜欢", "要求是", "约定", "规范")) {
            score += 0.20;
        }
        if (containsAny(text, "失败", "报错", "踩坑", "教训", "注意", "超时", "不可用", "坑：")) {
            score += 0.20;
        }
        if (containsAny(text, "结论", "因此", "说明", "根本原因", "实测")) {
            score += 0.10;
        }

        int length = text.strip().length();
        if (length < 10) {
            // 太短，信息量不足以单独成立
            score -= 0.15;
        } else if (length > 800) {
            // 太长，多半是搬运的原文，检索价值被稀释
            score -= 0.10;
        }
        return Math.max(0.05, Math.min(1.0, score));
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
