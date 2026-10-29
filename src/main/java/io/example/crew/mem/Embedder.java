package io.example.crew.mem;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本嵌入抽象。整个记忆系统的唯一外部依赖点。
 *
 * <p>比 {@code LlmClient} 多一个方法：{@link #baseline()}。这个方法看起来可有可无，
 * 实则是记忆检索能不能用的前提——
 *
 * <p>实测 bge-m3 在"完全无关"的两句中文上给出的余弦相似度不是 0，而是 <b>0.31 左右</b>。
 * 这是嵌入模型的各向异性（anisotropy）造成的普遍现象，不是 bug。如果按直觉把
 * "相似度低于 0.5 算无关"写死，换个模型（比如离线兜底的哈希实现，无关基线接近 0.02）
 * 这个阈值就全错了：要么什么都召不回来，要么把噪声全灌进提示词。
 *
 * <p>所以阈值必须由实现自己声明基线，检索层再用"基线 + 固定余量"换算成噪声下限。
 * <b>阈值不能跨模型复用</b>，这一条比任何调参技巧都重要。
 */
public interface Embedder {

    /** 把单条文本编码成向量。 */
    float[] embed(String text);

    /**
     * 批量编码。
     *
     * <p>默认逐条转调 {@link #embed}；HTTP 实现应当覆盖它——索引一份文档时会有几十个分块，
     * 逐条请求的开销主要在往返延迟而不是算力。
     */
    default List<float[]> embedAll(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            out.add(embed(text));
        }
        return out;
    }

    /** 向量维度，用于校验存档兼容性。 */
    int dimension();

    /** 模型标识，写入记忆元数据，便于换模型后识别旧向量已不可用。 */
    String model();

    /**
     * 无关文本的期望相似度基线。
     *
     * <p>检索层的噪声下限 = {@code baseline() + margin}。返回 0 表示"无关即 0"，
     * 适用于稀疏/哈希类嵌入。
     */
    double baseline();
}
