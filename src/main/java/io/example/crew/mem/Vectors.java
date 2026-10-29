package io.example.crew.mem;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

/**
 * 向量运算与序列化。纯 JDK，不引入任何数值库。
 *
 * <p>为什么不用 JSON 数组存向量：一个 1024 维向量写成 {@code [0.01515,-0.0118,…]}
 * 大约 12 KB 文本，而且为了控制体积必然要截断小数位——截断之后"存档前算出的相似度"
 * 与"重读后算出的相似度"会出现微小差异，跨进程调试时这种漂移极难定位。
 * 改成 float32 原样 base64 编码是 5.4 KB，且完全无损。
 *
 * <p>相似度一律用余弦而非欧氏距离：不同嵌入模型的向量模长不可比（bge-m3 已归一化，
 * 但离线兜底实现未必），余弦只看方向，跨实现也能对齐。
 */
public final class Vectors {

    private Vectors() {
    }

    /** 余弦相似度，范围 [-1, 1]。长度不等或任一为零向量时返回 0（视为无关）。 */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na <= 0 || nb <= 0) {
            return 0;
        }
        double cos = dot / (Math.sqrt(na) * Math.sqrt(nb));
        // 浮点误差可能让同一向量自比得到 1.0000000000000002
        return Math.max(-1, Math.min(1, cos));
    }

    /** 就地 L2 归一化，返回同一数组。零向量原样返回。 */
    public static float[] normalize(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        if (sum <= 0) {
            return v;
        }
        double inv = 1.0 / Math.sqrt(sum);
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) (v[i] * inv);
        }
        return v;
    }

    /** float32 小端原样编码为 base64。 */
    public static String encode(float[] v) {
        if (v == null || v.length == 0) {
            return "";
        }
        ByteBuffer buffer = ByteBuffer.allocate(v.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float x : v) {
            buffer.putFloat(x);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    /** base64 解码回 float32。空串或非法输入返回空数组，不抛异常——存档损坏时应当降级而不是崩溃。 */
    public static float[] decode(String s) {
        if (s == null || s.isEmpty()) {
            return new float[0];
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(s);
            if (bytes.length % Float.BYTES != 0) {
                return new float[0];
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            float[] out = new float[bytes.length / Float.BYTES];
            for (int i = 0; i < out.length; i++) {
                out[i] = buffer.getFloat();
            }
            return out;
        } catch (IllegalArgumentException e) {
            return new float[0];
        }
    }
}
