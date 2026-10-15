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
import com.stepos.whisper.service.UtteranceExtractionState;
import com.stepos.whisper.service.ServiceState;
import com.stepos.whisper.service.ServiceState.AiStatus;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
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
    private static final int VOICEPRINT_WAIT_SECONDS = 5;
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
    private final UtteranceExtractionState mAttribution;
    private final ScheduledExecutorService mExecutor = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "extraction"));
    private final SimpleDateFormat mTimeFormat = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    // extraction 线程
    private int mRetryStep;
    private ScheduledFuture<?> mScheduledRetry;

    public ExtractionWorker(Context context, AppDatabase db, UtteranceExtractionState attribution,
                            Listener listener) {
        mContext = context.getApplicationContext();
        mDb = db;
        mAttribution = attribution;
        mListener = listener;
        mClient = new StepFunChatClient(BuildConfig.STEPFUN_API_KEY);
    }

    /** Exact sentence jobs reuse the existing persisted range shape, without a schema change. */
    public void enqueueSentence(Utterance sentence) {
        synchronized (ServiceState.class) {
            if (!mAttribution.isReady(sentence.startTime, sentence.endTime)) return;
            List<Utterance> queued = mDb.runInTransaction(() -> {
                List<Utterance> exact = mDb.utteranceDao().getExact(sentence.startTime, sentence.endTime);
                if (exact.isEmpty()) return Collections.<Utterance>emptyList();
                Utterance current = exact.get(0);
                if (current.aiState == Utterance.AI_DONE) return Collections.<Utterance>emptyList();
                // An old range already owns queued rows; do not duplicate them on recovery.
                if (current.aiState == Utterance.AI_QUEUED && isCovered(current)) return Collections.<Utterance>emptyList();
                User target = current.userId == null ? null : mDb.userDao().get(current.userId);
                if (target == null) target = mDb.userDao().getOwner();
                if (target == null) return Collections.<Utterance>emptyList();
                if (mDb.pendingExtractionDao().countExact(target.id, current.startTime, current.endTime) == 0) {
                    PendingExtraction item = new PendingExtraction();
                    item.userId = target.id;
                    item.startTime = current.startTime;
                    item.endTime = current.endTime;
                    item.createdAt = System.currentTimeMillis();
                    mDb.pendingExtractionDao().insert(item);
                }
                mDb.utteranceDao().raiseAiStateByIds(ids(exact), Utterance.AI_QUEUED);
                return exact;
            });
            notifyRows(queued, Utterance.AI_QUEUED, false);
        }
        retryPending();
    }

    private boolean isCovered(Utterance row) {
        for (PendingExtraction item : mDb.pendingExtractionDao().getAll()) {
            for (Utterance owned : jobRows(item)) {
                if (owned.id == row.id) return true;
            }
        }
        return false;
    }

    /** Prefer exact pairs: inclusive range boundaries must not capture neighboring sentences. */
    private List<Utterance> jobRows(PendingExtraction item) {
        List<Utterance> exact = mDb.utteranceDao().getExact(item.startTime, item.endTime);
        return exact.isEmpty() ? mDb.utteranceDao().getRange(item.startTime, item.endTime) : exact;
    }

    private static List<Long> ids(List<Utterance> rows) {
        List<Long> ids = new ArrayList<>();
        for (Utterance row : rows) ids.add(row.id);
        return ids;
    }

    private void notifyRows(List<Utterance> rows, int state, boolean reset) {
        for (Utterance row : rows) {
            // A strictly interior interval updates only this sentence in the existing UI API.
            long from = row.startTime + (row.endTime - row.startTime) / 2;
            mListener.onAiStateChanged(from, from, state, reset);
        }
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
        synchronized (ServiceState.class) {
            mAttribution.clear();
            mDb.pendingExtractionDao().deleteAll();
        }
        post(() -> {
            cancelScheduledRetry();
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
            List<PendingExtraction> items = mDb.pendingExtractionDao().getAll();
            if (items.isEmpty()) {
                publish(AiStatus.State.IDLE, "");
                return;
            }
            if (!isNetworkAvailable()) {
                publish(AiStatus.State.OFFLINE, "");
                return;
            }
            boolean completed = false;
            boolean pending = false;
            for (PendingExtraction item : items) {
                ProcessResult result = process(item);
                if (result == ProcessResult.VOICEPRINT_PENDING) {
                    pending = true;
                    continue; // A waiting old range must not starve ready sentences behind it.
                }
                if (result == ProcessResult.RETRY) {
                    scheduleRetry();
                    return;
                }
                completed = true;
            }
            if (pending && !completed) {
                scheduleVoiceprintWait();
                return;
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

    /** 声纹等待不是网络失败：保留队列，不增加失败次数或网络退避。 */
    private void scheduleVoiceprintWait() {
        publish(AiStatus.State.IDLE, "等待声纹识别");
        cancelScheduledRetry();
        mScheduledRetry = mExecutor.schedule(() -> {
            mScheduledRetry = null;
            drainQueue();
        }, VOICEPRINT_WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private enum ProcessResult { COMPLETE, RETRY, VOICEPRINT_PENDING }

    private ProcessResult process(PendingExtraction item) {
        final UtteranceExtractionState.Snapshot revision;
        final User user;
        final String transcript;
        final List<UserFact> existing;
        final List<Utterance> utterances;
        final List<Utterance> context;
        final boolean exact;
        synchronized (ServiceState.class) {
            if (mDb.pendingExtractionDao().exists(item.id) == 0) return ProcessResult.COMPLETE;
            utterances = jobRows(item);
            exact = !mDb.utteranceDao().getExact(item.startTime, item.endTime).isEmpty();
            if (utterances.isEmpty() || (exact && utterances.get(0).aiState == Utterance.AI_DONE)) {
                mDb.pendingExtractionDao().delete(item.id);
                return ProcessResult.COMPLETE;
            }
            if (mAttribution.snapshot(utterances) == null) return ProcessResult.VOICEPRINT_PENDING;
            if (exact) {
                Long uid = utterances.get(0).userId;
                User target = uid == null ? null : mDb.userDao().get(uid);
                if (target == null) target = mDb.userDao().getOwner();
                if (target != null && target.id != item.userId) {
                    item.userId = target.id;
                    item.attempts = 0;
                    mDb.pendingExtractionDao().retarget(item.id, target.id);
                }
            }
            user = mDb.userDao().get(item.userId);
            if (user == null) {
                finish(item, utterances, false);
                return ProcessResult.COMPLETE;
            }
            context = exact ? mDb.utteranceDao().getPreviousContext(
                    item.startTime - 30_000L, item.startTime) : new ArrayList<>();
            // Unresolved neighboring speech is not a dependency of this sentence.
            context.removeIf(row -> !mAttribution.isReady(row.startTime, row.endTime));
            List<Utterance> relevant = new ArrayList<>(utterances);
            relevant.addAll(context);
            revision = mAttribution.snapshot(relevant);
            if (revision == null) return ProcessResult.VOICEPRINT_PENDING;
            String fresh = formatTranscript(utterances, user);
            transcript = fresh == null ? null : (exact ? sentenceTranscript(context, user, fresh) : fresh);
            if (transcript == null) {
                finish(item, utterances, true);
                return ProcessResult.COMPLETE;
            }
            existing = mDb.userFactDao().getPending(user.id);
        }
        String who = user.isOwner ? "无（这段对话里没有已登记的熟人，记录与机主本人相关的事项）" : user.name;
        if (!isSnapshotValid(item, revision, utterances, user)) return ProcessResult.VOICEPRINT_PENDING;
        publish(AiStatus.State.WORKING, "");
        try {
            ExtractionResult result = mClient.extract(who, existing, transcript);
            synchronized (ServiceState.class) {
                if (!isSnapshotValid(item, revision, utterances, user)) return ProcessResult.VOICEPRINT_PENDING;
                completeExtraction(item, utterances, user.id, result);
                mRetryStep = 0;
            }
            return ProcessResult.COMPLETE;
        } catch (StepFunChatClient.RetryableException e) {
            if (!isSnapshotValid(item, revision, utterances, user)) return ProcessResult.VOICEPRINT_PENDING;
            Log.w(TAG, "extraction deferred: " + e.getMessage());
            return ProcessResult.RETRY;
        } catch (IOException e) {
            synchronized (ServiceState.class) {
                if (!isSnapshotValid(item, revision, utterances, user)) return ProcessResult.VOICEPRINT_PENDING;
                Log.e(TAG, "extraction rejected", e);
                if (item.attempts + 1 >= MAX_REJECTED_ATTEMPTS) {
                    finish(item, utterances, false);
                    publish(AiStatus.State.ERROR, shorten(e.getMessage()));
                } else {
                    mDb.pendingExtractionDao().incrementAttempts(item.id);
                }
            }
            return ProcessResult.COMPLETE;
        }
    }

    private String sentenceTranscript(List<Utterance> context, User user, String fresh) {
        String previous = formatTranscript(context, user, true);
        return "只从【NEW：本次新句】提取新增事项/事实或完成、取消、失效信息。"
                + "【CONTEXT_ONLY】仅用于理解指代，不得从上下文重复提取或更新事项/事实。\n"
                + "【CONTEXT_ONLY：之前30秒，只读上下文】\n" + (previous == null ? "" : previous)
                + "【END_CONTEXT_ONLY】\n【NEW：本次新句】\n" + fresh + "【END_NEW】\n";
    }

    private boolean isSnapshotValid(PendingExtraction item, UtteranceExtractionState.Snapshot revision,
                                    List<Utterance> rows, User user) {
        synchronized (ServiceState.class) {
            if (!mAttribution.isValid(revision) || mDb.pendingExtractionDao().exists(item.id) == 0) return false;
            User current = mDb.userDao().get(user.id);
            if (current == null || current.isOwner != user.isOwner || !current.name.equals(user.name)) return false;
            List<Utterance> now = jobRows(item);
            if (now.size() != rows.size()) return false;
            for (int i = 0; i < rows.size(); i++) {
                Utterance before = rows.get(i), after = now.get(i);
                if (before.id != after.id || !Objects.equals(before.userId, after.userId)
                        || !before.text.equals(after.text) || before.aiState != after.aiState) return false;
            }
            return true;
        }
    }

    /** Commit only the job's actual rows, not its inclusive neighboring boundaries. */
    private void finish(PendingExtraction item, List<Utterance> rows, boolean done) {
        mDb.runInTransaction(() -> {
            mDb.pendingExtractionDao().delete(item.id);
            if (done) mDb.utteranceDao().raiseAiStateByIds(ids(rows), Utterance.AI_DONE);
            else mDb.utteranceDao().resetQueuedByIds(ids(rows));
        });
        notifyRows(rows, done ? Utterance.AI_DONE : Utterance.AI_NONE, !done);
    }

    private static String shorten(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    private void completeExtraction(PendingExtraction item, List<Utterance> rows,
                                    long userId, ExtractionResult result) {
        List<UserFact> added = mDb.runInTransaction(() -> {
            List<UserFact> facts = FactSaver.save(mDb, userId, result);
            mDb.pendingExtractionDao().delete(item.id);
            mDb.utteranceDao().raiseAiStateByIds(ids(rows), Utterance.AI_DONE);
            return facts;
        });
        if (!added.isEmpty()) mListener.onFactsAdded(userId, added);
        notifyRows(rows, Utterance.AI_DONE, false);
    }

    /** 该用户在这段时间里一句话都没说时返回 null；机主的聊天块只要有内容即可。 */
    private String formatTranscript(List<Utterance> utterances, User target) {
        return formatTranscript(utterances, target, false);
    }

    private String formatTranscript(List<Utterance> utterances, User target, boolean contextOnly) {
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
        return contextOnly || userSpoke || target.isOwner ? sb.toString() : null;
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
