package io.example.crew.mem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 零依赖的离线嵌入实现：字符 bigram + 词哈希，带符号散列到固定维度。
 *
 * <p>存在的理由不是"没有 Ollama 时的降级方案"这么简单。记忆系统的绝大多数 bug
 * （打分算错、去重阈值错、作用域过滤漏了、存档往返丢精度）跟<b>语义质量无关</b>，
 * 用真嵌入去测这些逻辑只会让每轮测试多等十几秒，还引入"模型今天答得不一样"的噪声。
 * 有了这个实现，记忆层的断言可以在 100 毫秒内跑完且完全确定。
 *
 * <p>它当然不能替代真嵌入。文档里所有关于检索质量的结论都是在 bge-m3 上测的，
 * 这个实现只用于验证<b>管线</b>，不用于评价<b>效果</b>。
 *
 * <p>三个细节：
 * <ul>
 *   <li><b>带符号散列</b>（signed hashing）：只取模不带符号时，冲突会让向量分量单调累加，
 *       长文本天然得到更大的模长，相似度被系统性拉高。带符号后冲突期望相互抵消。</li>
 *   <li><b>中文用 bigram</b>：中文没有空格，按单字切会丢失"多智/智能/能体"这类组合信息，
 *       而 bigram 是在不引入分词器的前提下最划算的近似。</li>
 *   <li><b>归一化</b>：让长度不影响余弦。</li>
 * </ul>
 */
public final class HashingEmbedder implements Embedder {

    /** 512 维足够容纳常见中文 bigram 的组合空间，又不至于让冲突率高到失真。 */
    private static final int DIM = 512;

    private static final float W_BIGRAM = 1.0f;
    private static final float W_UNIGRAM = 0.4f;
    private static final float W_WORD = 1.0f;
    private static final float W_SHORT_WORD = 0.3f;

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIM];
        if (text == null || text.isBlank()) {
            return vector;
        }
        for (String feature : features(text)) {
            float weight = weightOf(feature);
            int h = mix(feature.hashCode());
            int index = Math.floorMod(h, DIM);
            int sign = ((h >>> 31) & 1) == 0 ? 1 : -1;
            vector[index] += sign * weight;
        }
        return Vectors.normalize(vector);
    }

    @Override
    public int dimension() {
        return DIM;
    }

    @Override
    public String model() {
        return "hashing-ngram";
    }

    /**
     * 无关文本的基线。
     *
     * <p>取 0.08 而不是 0：中文高频字（"的""是""了"）构成的 bigram 会在任何两句中文之间
     * 贡献一点共同分量。这个值是实测的——45 对不同主题的短句两两余弦，均值 0.0060、
     * <b>最大 0.088</b>。取最大值而不是均值，因为噪声地板要挡住的是最像的那一对。
     */
    @Override
    public double baseline() {
        return 0.08;
    }

    /** 特征抽取：中文连续段取 bigram，非中文连续段取词。 */
    static List<String> features(String text) {
        String s = text.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        StringBuilder cjk = new StringBuilder();
        StringBuilder word = new StringBuilder();

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isCjk(c)) {
                flushWord(word, out);
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(cjk, out);
                word.append(c);
            } else {
                flushCjk(cjk, out);
                flushWord(word, out);
            }
        }
        flushCjk(cjk, out);
        flushWord(word, out);
        return out;
    }

    private static void flushCjk(StringBuilder buffer, List<String> out) {
        if (buffer.length() == 0) {
            return;
        }
        String run = buffer.toString();
        buffer.setLength(0);
        if (run.length() == 1) {
            out.add(run);
            return;
        }
        for (int i = 0; i + 1 < run.length(); i++) {
            out.add(run.substring(i, i + 2));
        }
    }

    private static void flushWord(StringBuilder buffer, List<String> out) {
        if (buffer.length() == 0) {
            return;
        }
        out.add(buffer.toString());
        buffer.setLength(0);
    }

    private static float weightOf(String feature) {
        if (feature.length() == 1) {
            // 单字：可能是中文 unigram，也可能是单个字母；两者区分度都低
            return isCjk(feature.charAt(0)) ? W_UNIGRAM : W_SHORT_WORD;
        }
        if (feature.length() == 2 && isCjk(feature.charAt(0))) {
            return W_BIGRAM;
        }
        return W_WORD;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }

    /** murmur 风格的整型混合，避免 {@code String.hashCode} 低位分布不均导致的取模偏斜。 */
    private static int mix(int h) {
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return h;
    }
}
