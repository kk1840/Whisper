package com.stepos.whisper.voiceprint;

import com.stepos.whisper.data.User;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 声纹库业务封装：固定云端分组 {@link #GROUP_ID} + 本地 {@link User#featureIds} 映射。
 * 云端特征与本地用户的对应关系只存在于本地（卸载即失联，但云端数据仍在，可通过
 * queryFeatureList 恢复，featureInfo 里存了名字）。所有网络调用在内部单线程上排队执行，
 * 回调也在该线程上触发，调用方需要的话自行投递回主线程/处理线程。
 */
public class VoiceprintLibrary {
    private static final String TAG = "VoiceprintLibrary";
    /** 云端固定分组名。重装/换机后仍指向同一个云端声纹库。 */
    public static final String GROUP_ID = "whisper_demo";
    /** 每个用户最多保留的声纹条数，超出丢最早的（沿用本地版“主+附加声纹”的语义）。 */
    public static final int MAX_FEATURES_PER_USER = 6;

    public interface Listener<T> {
        /** error 为 null 表示成功；result 可能为 null（如检索无命中）。 */
        void onResult(T result, IOException error);
    }

    /** 1:N 命中结果。 */
    public static class Match {
        public final long userId;
        public final float score;

        Match(long userId, float score) {
            this.userId = userId;
            this.score = score;
        }
    }

    private final XfVoiceprintClient mClient;
    private final ExecutorService mExec;
    /** featureId → 本地 userId。loadUsers 在处理线程重建后整体替换。 */
    private volatile Map<String, Long> mFeatureToUser = Collections.emptyMap();
    private volatile float mThreshold;

    public VoiceprintLibrary(float threshold) {
        this(threshold, new XfVoiceprintClient());
    }

    VoiceprintLibrary(float threshold, XfVoiceprintClient client) {
        mClient = client;
        mExec = Executors.newSingleThreadExecutor(r -> new Thread(r, "voiceprint"));
        mThreshold = threshold;
    }

    public void setThreshold(float threshold) {
        mThreshold = threshold;
    }

    public boolean hasCredentials() {
        return mClient.hasCredentials();
    }

    /** 处理线程调用：按 {@link User#featureIds} 重建 featureId→userId 映射。 */
    public void loadUsers(List<User> users) {
        Map<String, Long> map = new HashMap<>();
        for (User u : users) {
            for (String fid : parseFeatureIds(u.featureIds)) {
                map.put(fid, u.id);
            }
        }
        mFeatureToUser = map;
    }

    /** 首次启动建组；未确认的业务错误一律返回失败，不猜测“已存在”错误码。 */
    public void ensureGroup(Listener<Boolean> listener) {
        if (!enqueue(() -> {
            IOException error = null;
            try {
                mClient.createGroup(GROUP_ID, "whisper demo");
            } catch (IOException e) {
                error = e;
            } catch (RuntimeException e) {
                error = new IOException("invalid voiceprint group response", e);
            }
            deliver(listener, error == null ? true : null, error);
        })) {
            deliver(listener, null, new IOException("voiceprint queue shut down"));
        }
    }

    /**
     * 1:N 检索：返回分数达到阈值且 featureId 能映射到本地用户的最佳候选。
     * 没有注册用户或无命中时 result 为 null、error 为 null。
     */
    public void search(byte[] pcm16k, Listener<Match> listener) {
        // 空映射也走异步投递：若在调用线程（speech-proc）上同步回调，后续 proc() 自投递
        // 的补标任务在单线程定时池上会异常滞留，句子永远停在“待识别”。
        if (!enqueue(() -> {
            Map<String, Long> map = mFeatureToUser;
            if (map.isEmpty()) {
                deliver(listener, null, null);
                return;
            }
            Match match;
            try {
                XfVoiceprintClient.SearchResult r = mClient.searchFea(GROUP_ID, 5, pcm16k);
                match = bestLocalMatch(r.scores, map, mThreshold);
            } catch (IOException e) {
                deliver(listener, null, e);
                return;
            } catch (RuntimeException e) {
                deliver(listener, null, new IOException("invalid voiceprint search result", e));
                return;
            }
            deliver(listener, match, null);
        })) {
            deliver(listener, null, new IOException("voiceprint queue shut down"));
        }
    }

    /** 不依赖云端返回顺序，只在有效且本地可映射的候选中按分数降序取最佳。 */
    static Match bestLocalMatch(List<XfVoiceprintClient.Score> scores, Map<String, Long> map,
                                float threshold) {
        if (!Float.isFinite(threshold)) {
            throw new IllegalArgumentException("invalid voiceprint threshold");
        }
        List<XfVoiceprintClient.Score> local = new ArrayList<>();
        for (XfVoiceprintClient.Score score : scores) {
            if (score != null && Float.isFinite(score.score) && map.containsKey(score.featureId)) {
                local.add(score);
            }
        }
        local.sort((a, b) -> Float.compare(b.score, a.score));
        if (local.isEmpty() || local.get(0).score < threshold) {
            return null;
        }
        XfVoiceprintClient.Score best = local.get(0);
        return new Match(map.get(best.featureId), best.score);
    }

    /**
     * 注册一条声纹并返回新 featureId（调用方负责把它写进 {@link User#featureIds}）。
     * 成功后映射表同步更新，无需重新 loadUsers。
     */
    public void enroll(long userId, String name, boolean owner, byte[] pcm16k, Listener<String> listener) {
        String featureId = "u" + userId + "_" + Long.toString(System.currentTimeMillis(), Character.MAX_RADIX);
        String featureInfo = truncate(name, 60) + "|" + (owner ? "机主|" : "") + LocalDate.now();
        if (!enqueue(() -> {
            try {
                mClient.createFeature(GROUP_ID, featureId, featureInfo, pcm16k);
                Map<String, Long> map = new HashMap<>(mFeatureToUser);
                map.put(featureId, userId);
                mFeatureToUser = map;
                deliver(listener, featureId, null);
            } catch (IOException e) {
                deliver(listener, null, e);
            }
        })) {
            deliver(listener, null, new IOException("voiceprint queue shut down"));
        }
    }

    /** 删除用户的全部云端声纹，尽力而为，返回成功删除的条数。 */
    public void deleteUser(List<String> featureIds, Listener<Integer> listener) {
        if (featureIds == null || featureIds.isEmpty()) {
            deliver(listener, 0, null);
            return;
        }
        if (!enqueue(() -> {
            int ok = 0;
            IOException last = null;
            for (String fid : featureIds) {
                try {
                    mClient.deleteFeature(GROUP_ID, fid);
                    ok++;
                } catch (IOException e) {
                    last = e;
                }
            }
            deliver(listener, ok, last);
        })) {
            deliver(listener, 0, new IOException("voiceprint queue shut down"));
        }
    }

    /** 列出云端分组里的全部特征（重装恢复用）。 */
    public void queryCloud(Listener<List<XfVoiceprintClient.FeatureEntry>> listener) {
        if (!enqueue(() -> {
            try {
                deliver(listener, mClient.queryFeatureList(GROUP_ID), null);
            } catch (IOException e) {
                deliver(listener, null, e);
            }
        })) {
            deliver(listener, null, new IOException("voiceprint queue shut down"));
        }
    }

    public void shutdown() {
        mExec.shutdown();
    }

    private static volatile VoiceprintLibrary sCleaner;

    /** 界面删除用户时清理云端声纹（尽力而为，失败只打日志）。独立于录音服务里的实例。 */
    public static void deleteFeaturesQuietly(List<String> featureIds) {
        if (featureIds == null || featureIds.isEmpty()) {
            return;
        }
        VoiceprintLibrary lib = sCleaner;
        if (lib == null) {
            synchronized (VoiceprintLibrary.class) {
                if (sCleaner == null) {
                    sCleaner = new VoiceprintLibrary(0.6f);
                }
                lib = sCleaner;
            }
        }
        List<String> ids = featureIds;
        lib.deleteUser(ids, (ok, err) -> {
            if (err != null) {
                android.util.Log.w("VoiceprintLibrary", "cloud delete: " + err);
            }
        });
    }

    private boolean enqueue(Runnable r) {
        try {
            mExec.execute(r);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    private static <T> void deliver(Listener<T> listener, T result, IOException error) {
        if (listener != null) {
            listener.onResult(result, error);
        }
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }

    // ---------------------------------------------------------------- featureIds 序列化

    /** "fid1,fid2" → 列表，容忍 null/空串/多余逗号。 */
    public static List<String> parseFeatureIds(String featureIds) {
        List<String> out = new ArrayList<>();
        if (featureIds != null) {
            for (String s : featureIds.split(",")) {
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /**
     * 追加一条声纹，超过 {@link #MAX_FEATURES_PER_USER} 时丢最早的，
     * 并尽力删除丢弃条目在云端的特征，避免留下无人认领的孤儿特征。
     */
    public static String appendFeatureId(String featureIds, String featureId) {
        List<String> list = parseFeatureIds(featureIds);
        list.add(featureId);
        deleteOverflow(list);
        return String.join(",", list);
    }

    /** 合并两个用户的声纹清单（from 并入 to 用），超过上限丢最早的并同步删云端。 */
    public static String mergeFeatureIds(String to, String from) {
        List<String> list = parseFeatureIds(to);
        for (String fid : parseFeatureIds(from)) {
            if (!list.contains(fid)) {
                list.add(fid);
            }
        }
        deleteOverflow(list);
        return String.join(",", list);
    }

    /** 就地丢掉超上限的最旧条目，云端对应特征异步删除（尽力而为）。 */
    private static void deleteOverflow(List<String> list) {
        List<String> dropped = null;
        while (list.size() > MAX_FEATURES_PER_USER) {
            if (dropped == null) {
                dropped = new ArrayList<>();
            }
            dropped.add(list.remove(0));
        }
        if (dropped != null) {
            deleteFeaturesQuietly(dropped);
        }
    }
}
