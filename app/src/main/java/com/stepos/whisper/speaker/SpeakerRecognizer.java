package com.stepos.whisper.speaker;

import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager;
import com.stepos.whisper.asr.ModelManager;
import com.stepos.whisper.data.User;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 端侧声纹识别：3D-Speaker 提取声纹向量，SpeakerEmbeddingManager 检索比对。
 * 每个用户有一条主声纹，另外可以有若干条额外声纹（比如压低声音时的声音，由“关联到已有用户”合并而来），
 * 任意一条匹配上都算这个人。只在处理线程上调用。
 */
public class SpeakerRecognizer {
    /** 短于这个时长的句子声纹不可靠，不参与识别。 */
    public static final long MIN_DURATION_MS = 1500;
    /** 达到这个时长且匹配分数较高时，才用这句话更新匹配上的那条声纹。 */
    private static final long UPDATE_MIN_DURATION_MS = 3000;
    private static final float UPDATE_MIN_MARGIN = 0.15f;
    /** 平均声纹的样本权重上限，保证声纹能随时间缓慢适应。 */
    private static final int MAX_SAMPLE_WEIGHT = 30;
    /** 每个用户最多保留的额外声纹条数，超出时丢掉最早的。 */
    public static final int MAX_EXTRA = 6;

    /** 在已有的额外声纹（{@link User#extraEmbeddings} 格式）后追加一条，超出 {@link #MAX_EXTRA} 时丢掉最早的。 */
    public static byte[] appendExtra(byte[] extraBytes, float[] embedding) {
        List<float[]> extras = EmbeddingUtils.splitBytes(extraBytes, embedding.length);
        extras.add(embedding);
        while (extras.size() > MAX_EXTRA) {
            extras.remove(0);
        }
        return EmbeddingUtils.joinBytes(extras);
    }

    public static class Match {
        public final long userId;
        public final float score;
        /** 0 为主声纹，k 为第 k 条额外声纹。 */
        final int slot;

        Match(long userId, float score, int slot) {
            this.userId = userId;
            this.score = score;
            this.slot = slot;
        }
    }

    private final SpeakerEmbeddingExtractor mExtractor;
    private final SpeakerEmbeddingManager mManager;
    /** 每个用户的声纹列表，第 0 条为主声纹。 */
    private final Map<Long, List<float[]>> mEmbeddings = new HashMap<>();
    private final Map<Long, Integer> mSampleCounts = new HashMap<>();
    private float mThreshold;

    public SpeakerRecognizer(SpeakerEmbeddingExtractor extractor, float threshold) {
        mExtractor = extractor;
        mManager = new SpeakerEmbeddingManager(extractor.dim());
        mThreshold = threshold;
    }

    public void setThreshold(float threshold) {
        mThreshold = threshold;
    }

    public float getThreshold() {
        return mThreshold;
    }

    public int getDim() {
        return mManager.getDim();
    }

    /** 返回归一化后的声纹向量。 */
    public float[] extract(float[] samples) {
        OnlineStream stream = mExtractor.createStream();
        try {
            stream.acceptWaveform(samples, ModelManager.SAMPLE_RATE);
            stream.inputFinished();
            if (!mExtractor.isReady(stream)) {
                return null;
            }
            return EmbeddingUtils.normalize(mExtractor.compute(stream));
        } finally {
            stream.release();
        }
    }

    public void loadUsers(List<User> users) {
        for (String name : mManager.allSpeakerNames()) {
            mManager.remove(name);
        }
        mEmbeddings.clear();
        mSampleCounts.clear();
        int dim = mManager.getDim();
        for (User user : users) {
            float[] main = EmbeddingUtils.fromBytes(user.embedding);
            if (main == null || main.length != dim) {
                continue;
            }
            List<float[]> all = new ArrayList<>();
            all.add(main);
            all.addAll(EmbeddingUtils.splitBytes(user.extraEmbeddings, dim));
            putUser(user.id, all, user.sampleCount);
        }
    }

    public void putUser(long userId, float[] embedding, int sampleCount) {
        List<float[]> all = new ArrayList<>();
        all.add(embedding);
        putUser(userId, all, sampleCount);
    }

    private void putUser(long userId, List<float[]> embeddings, int sampleCount) {
        removeUser(userId);
        for (int i = 0; i < embeddings.size(); i++) {
            mManager.add(key(userId, i), embeddings.get(i));
        }
        mEmbeddings.put(userId, embeddings);
        mSampleCounts.put(userId, sampleCount);
    }

    public void removeUser(long userId) {
        List<float[]> old = mEmbeddings.remove(userId);
        if (old != null) {
            for (int i = 0; i < old.size(); i++) {
                mManager.remove(key(userId, i));
            }
        }
        mSampleCounts.remove(userId);
    }

    public Match identify(float[] embedding) {
        String name = mManager.search(embedding, mThreshold);
        if (name == null || name.isEmpty()) {
            return null;
        }
        int sep = name.indexOf('#');
        long userId = Long.parseLong(sep < 0 ? name : name.substring(0, sep));
        int slot = sep < 0 ? 0 : Integer.parseInt(name.substring(sep + 1));
        List<float[]> list = mEmbeddings.get(userId);
        float[] ref = list == null || slot >= list.size() ? null : list.get(slot);
        float score = ref == null ? mThreshold : EmbeddingUtils.dot(ref, embedding);
        return new Match(userId, score, slot);
    }

    /**
     * 用一句高置信度的话更新匹配上的那条声纹。返回 true 表示有更新，
     * 需要用 {@link #getEmbedding} / {@link #getExtraEmbeddings} / {@link #getSampleCount} 持久化。
     */
    public boolean maybeAdapt(Match match, float[] embedding, long durationMs) {
        if (durationMs < UPDATE_MIN_DURATION_MS || match.score < mThreshold + UPDATE_MIN_MARGIN) {
            return false;
        }
        List<float[]> list = mEmbeddings.get(match.userId);
        if (list == null || match.slot >= list.size()) {
            return false;
        }
        int count = Math.min(getSampleCount(match.userId), MAX_SAMPLE_WEIGHT);
        List<float[]> updated = new ArrayList<>(list);
        updated.set(match.slot, EmbeddingUtils.runningMean(list.get(match.slot), count, embedding));
        putUser(match.userId, updated, getSampleCount(match.userId) + 1);
        return true;
    }

    public float[] getEmbedding(long userId) {
        List<float[]> list = mEmbeddings.get(userId);
        return list == null ? null : list.get(0);
    }

    public List<float[]> getExtraEmbeddings(long userId) {
        List<float[]> list = mEmbeddings.get(userId);
        return list == null ? new ArrayList<>() : new ArrayList<>(list.subList(1, list.size()));
    }

    public int getSampleCount(long userId) {
        Integer c = mSampleCounts.get(userId);
        return c == null ? 0 : c;
    }

    public void release() {
        mManager.release();
    }

    private static String key(long userId, int slot) {
        return slot == 0 ? String.valueOf(userId) : userId + "#" + slot;
    }
}
