package io.example.crew.util;

/**
 * token 估算。纯启发式，不做分词。
 *
 * <p>为什么必须有：上下文预算按"字符数"算是错的。同样 1500 个字符，中文约 1000–1500 token，
 * 英文只有 350–400 token，差三倍。按字符设预算的结果是"中文 prompt 爆窗口、英文 prompt 浪费预算"。
 *
 * <p>经验系数（对 Qwen / Llama 系 tokenizer 的近似）：
 * <ul>
 *   <li>CJK 字符（含全角标点）：约 1 字 = 1 token</li>
 *   <li>ASCII / 拉丁字符：约 3.5 字符 = 1 token</li>
 * </ul>
 * 误差在 ±15% 以内，用于"留多少余量"的决策足够。真正的精确计数只能用模型侧上报的
 * {@code prompt_eval_count}，那是事后值，不能用于事前预算。
 */
public final class Tokens {

    private Tokens() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (int) Math.ceil(other / 3.5);
    }

    /** 系统提示词、格式说明等固定开销的粗略估计，用于给预算留安全余量。 */
    public static int overhead(int messageCount) {
        return messageCount * 8;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // 基本汉字
                || (c >= 0x3000 && c <= 0x303F)  // 中文标点
                || (c >= 0xFF00 && c <= 0xFFEF)  // 全角字符
                || (c >= 0x3400 && c <= 0x4DBF); // 扩展 A
    }
}
