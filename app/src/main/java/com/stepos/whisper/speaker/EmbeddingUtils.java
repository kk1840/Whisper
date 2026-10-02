package com.stepos.whisper.speaker;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public final class EmbeddingUtils {
    private EmbeddingUtils() {
    }

    public static float[] normalize(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += x * x;
        }
        float norm = (float) Math.sqrt(sum);
        float[] out = new float[v.length];
        if (norm < 1e-8f) {
            return out;
        }
        for (int i = 0; i < v.length; i++) {
            out[i] = v[i] / norm;
        }
        return out;
    }

    /** 两个向量都已归一化时等价于余弦相似度。 */
    public static float dot(float[] a, float[] b) {
        float s = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    public static float cosine(float[] a, float[] b) {
        return dot(normalize(a), normalize(b));
    }

    /** 增量平均：(mean * count + sample) / (count + 1)，结果重新归一化。 */
    public static float[] runningMean(float[] mean, int count, float[] sample) {
        float[] out = new float[mean.length];
        for (int i = 0; i < mean.length; i++) {
            out[i] = (mean[i] * count + sample[i]) / (count + 1);
        }
        return normalize(out);
    }

    public static byte[] toBytes(float[] v) {
        ByteBuffer buf = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float x : v) {
            buf.putFloat(x);
        }
        return buf.array();
    }

    /** 多个向量首尾拼接成一段字节。 */
    public static byte[] joinBytes(List<float[]> vectors) {
        int total = 0;
        for (float[] v : vectors) {
            total += v.length;
        }
        ByteBuffer buf = ByteBuffer.allocate(total * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float[] v : vectors) {
            for (float x : v) {
                buf.putFloat(x);
            }
        }
        return buf.array();
    }

    /** {@link #joinBytes} 的逆操作；长度对不上维度时丢弃多余部分。 */
    public static List<float[]> splitBytes(byte[] bytes, int dim) {
        List<float[]> out = new ArrayList<>();
        float[] all = fromBytes(bytes);
        if (all == null || dim <= 0) {
            return out;
        }
        for (int off = 0; off + dim <= all.length; off += dim) {
            float[] v = new float[dim];
            System.arraycopy(all, off, v, 0, dim);
            out.add(v);
        }
        return out;
    }

    public static float[] fromBytes(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[bytes.length / 4];
        for (int i = 0; i < v.length; i++) {
            v[i] = buf.getFloat();
        }
        return v;
    }
}
