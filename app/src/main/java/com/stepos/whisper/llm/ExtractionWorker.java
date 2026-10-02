package com.stepos.whisper.llm;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.util.Log;

import com.stepos.whisper.BuildConfig;
import com.stepos.whisper.data.AppDatabase;
import com.stepos.whisper.data.PendingExtraction;
import com.stepos.whisper.data.User;
import com.stepos.whisper.data.UserFact;
import com.stepos.whisper.data.Utterance;
import com.stepos.whisper.service.ServiceState;
import com.stepos.whisper.service.ServiceState.AiStatus;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 聊天块的待办和事实提取队列：先落库，再逐条交给大模型。
 * 网络错误、限流、服务端错误会按退避间隔自动重试，聊天块留在队列里不丢；
 * 请求被拒绝（4xx、返回无法解析）的聊天块最多尝试 {@link #MAX_REJECTED_ATTEMPTS} 次。
 */
public class ExtractionWorker {
    private static final String TAG = "ExtractionWorker";
    private static final int MAX_REJECTED_ATTEMPTS = 3;
    private static final int BATCH = 10;
    private static final int[] RETRY_DELAYS_S = {5, 10, 20, 40, 60, 120, 300};

    public interface Listener {
        /** 聊天块的 AI 状态变化，用于更新界面上的标记。 */
        void onAiStateChanged(long from, long to, int state, boolean reset);

        /** 提取到新的待办或事实（extraction 线程）。 */
        void onFactsAdded(long userId, List<UserFact> facts);
    }

    private final Context mContext;
    private final AppDatabase mDb;
    private final StepFunChatClient mClient;
    private final Listener mListener;
    private final ScheduledExecutorService mExecutor = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "extraction"));
    private final SimpleDateFormat mTimeFormat = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    // extraction 线程
    private int mRetryStep;
    private ScheduledFuture<?> mScheduledRetry;

    public ExtractionWorker(Context context, AppDatabase db, Listener listener) {
        mContext = context.getApplicationContext();
        mDb = db;
        mListener = listener;
        mClient = new StepFunChatClient(BuildConfig.STEPFUN_API_KEY);
    }

    /** 在调用线程上同步落库并标记为排队中，返回后再查未识别内容就不会重复入队。不能在主线程调用。 */
    public void enqueue(long userId, long startMs, long endMs) {
        PendingExtraction item = new PendingExtraction();
        item.userId = userId;
        item.startTime = startMs;
        item.endTime = endMs;
        item.createdAt = System.currentTimeMillis();
        mDb.pendingExtractionDao().insert(item);
        mDb.utteranceDao().raiseAiState(startMs, endMs, Utterance.AI_QUEUED);
        mListener.onAiStateChanged(startMs, endMs, Utterance.AI_QUEUED, false);
        post(this::drainQueue);
    }

    /** 网络恢复、定时触发或用户手动触发时调用，立即重试。 */
    public void retryPending() {
        post(() -> {
            cancelScheduledRetry();
            drainQueue();
        });
    }

    /** 清空聊天记录时调用。 */
    public void clearQueue() {
        post(() -> {
            cancelScheduledRetry();
            mDb.pendingExtractionDao().deleteAll();
            publish(AiStatus.State.IDLE, "");
        });
    }

    public void shutdown() {
        mExecutor.shutdownNow();
    }

    /** 投递到 extraction 线程；服务退出后线程池已关时静默丢弃，队列都在数据库里，下次启动接着处理。 */
    private void post(Runnable task) {
        try {
            mExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "extraction thread already shut down, dropping task");
        }
    }

    private void cancelScheduledRetry() {
        if (mScheduledRetry != null) {
            mScheduledRetry.cancel(false);
            mScheduledRetry = null;
        }
    }

    private void publish(AiStatus.State state, String detail) {
        ServiceState.setAiStatus(new AiStatus(state, mDb.pendingExtractionDao().count(), detail));
    }

    private void drainQueue() {
        if (BuildConfig.STEPFUN_API_KEY.isEmpty()) {
            publish(AiStatus.State.ERROR, "未配置 StepFun 密钥");
            return;
        }
        while (true) {
            List<PendingExtraction> items = mDb.pendingExtractionDao().getOldest(BATCH);
            if (items.isEmpty()) {
                publish(AiStatus.State.IDLE, "");
                return;
            }
            if (!isNetworkAvailable()) {
                publish(AiStatus.State.OFFLINE, "");
                return;
            }
            for (PendingExtraction item : items) {
                publish(AiStatus.State.WORKING, "");
                if (!process(item)) {
                    scheduleRetry();
                    return;
                }
            }
        }
    }

    private void scheduleRetry() {
        int delay = RETRY_DELAYS_S[Math.min(mRetryStep, RETRY_DELAYS_S.length - 1)];
        mRetryStep++;
        publish(AiStatus.State.RETRYING, String.valueOf(delay));
        cancelScheduledRetry();
        mScheduledRetry = mExecutor.schedule(() -> {
            mScheduledRetry = null;
            drainQueue();
        }, delay, TimeUnit.SECONDS);
    }

    /** 返回 false 表示遇到网络类问题，应稍后整体重试。 */
    private boolean process(PendingExtraction item) {
        User user = mDb.userDao().get(item.userId);
        if (user == null) {
            finish(item, false);
            return true;
        }
        List<Utterance> utterances = mDb.utteranceDao().getRange(item.startTime, item.endTime);
        String transcript = formatTranscript(utterances, user);
        if (transcript == null) {
            finish(item, true);
            return true;
        }
        List<UserFact> existing = mDb.userFactDao().getPending(user.id);
        String who = user.isOwner ? "无（这段对话里没有已登记的熟人，记录与机主本人相关的事项）" : user.name;
        try {
            ExtractionResult result = mClient.extract(who, existing, transcript);
            save(user.id, result);
            mRetryStep = 0;
            finish(item, true);
            return true;
        } catch (StepFunChatClient.RetryableException e) {
            Log.w(TAG, "extraction deferred: " + e.getMessage());
            return false;
        } catch (IOException e) {
            Log.e(TAG, "extraction rejected", e);
            if (item.attempts + 1 >= MAX_REJECTED_ATTEMPTS) {
                finish(item, false);
                publish(AiStatus.State.ERROR, shorten(e.getMessage()));
            } else {
                mDb.pendingExtractionDao().incrementAttempts(item.id);
            }
            return true;
        }
    }

    /** 移出队列；done 为 true 时标记为已识别，否则恢复为未识别（可再手动识别）。 */
    private void finish(PendingExtraction item, boolean done) {
        mDb.pendingExtractionDao().delete(item.id);
        if (done) {
            mDb.utteranceDao().raiseAiState(item.startTime, item.endTime, Utterance.AI_DONE);
            mListener.onAiStateChanged(item.startTime, item.endTime, Utterance.AI_DONE, false);
        } else {
            mDb.utteranceDao().resetQueued(item.startTime, item.endTime);
            mListener.onAiStateChanged(item.startTime, item.endTime, Utterance.AI_NONE, true);
        }
    }

    private static String shorten(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    private void save(long userId, ExtractionResult result) {
        List<UserFact> added = FactSaver.save(mDb, userId, result);
        if (!added.isEmpty()) {
            mListener.onFactsAdded(userId, added);
        }
    }

    /** 该用户在这段时间里一句话都没说时返回 null；机主的聊天块只要有内容即可。 */
    private String formatTranscript(List<Utterance> utterances, User target) {
        Map<Long, String> names = new HashMap<>();
        StringBuilder sb = new StringBuilder();
        boolean userSpoke = false;
        for (Utterance u : utterances) {
            String name;
            if (u.userId == null) {
                name = "其他人";
            } else {
                name = names.get(u.userId);
                if (name == null) {
                    User user = mDb.userDao().get(u.userId);
                    if (user == null) {
                        name = "其他人";
                    } else if (user.isOwner) {
                        name = "机主（" + user.name + "）";
                    } else {
                        name = user.name;
                    }
                    names.put(u.userId, name);
                }
                if (u.userId == target.id) {
                    userSpoke = true;
                }
            }
            sb.append('[').append(mTimeFormat.format(new Date(u.startTime))).append("] ")
                    .append(name).append("：").append(u.text).append('\n');
        }
        if (sb.length() == 0) {
            return null;
        }
        return userSpoke || target.isOwner ? sb.toString() : null;
    }

    private boolean isNetworkAvailable() {
        return isNetworkAvailable(mContext);
    }

    public static boolean isNetworkAvailable(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) {
            return false;
        }
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
