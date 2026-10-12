package com.stepos.whisper.notification;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import com.stepos.whisper.BuildConfig;
import com.stepos.whisper.data.AliasWorker;
import com.stepos.whisper.data.AppDatabase;
import com.stepos.whisper.data.ChatAlias;
import com.stepos.whisper.data.NotificationJob;
import com.stepos.whisper.data.PendingAlias;
import com.stepos.whisper.data.User;
import com.stepos.whisper.data.UserColors;
import com.stepos.whisper.data.UserFact;
import com.stepos.whisper.llm.ExtractionResult;
import com.stepos.whisper.llm.ExtractionWorker;
import com.stepos.whisper.llm.FactSaver;
import com.stepos.whisper.llm.MergeSuggestion;
import com.stepos.whisper.llm.NameMatcher;
import com.stepos.whisper.llm.StepFunChatClient;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 微信通知消息处理队列（仿 {@link com.stepos.whisper.screenshot.ScreenshotWorker}）。每条消息：
 * <ol>
 *   <li>发送者昵称对应到用户：只认机主指定过的 {@link ChatAlias}；没指定过的一律等机主指定，
 *       名字或以前的昵称相似的人作为推荐预选，绝不自动对应；</li>
 *   <li>把消息发给大模型提取待办和事实（{@link StepFunChatClient.Source#NOTIFICATION}，
 *       提示词里带“和已有待办是同一件事时输出合并建议”的规则）；</li>
 *   <li>合并建议先落成候选待办（数据不丢），由界面弹双卡片让机主确认。</li>
 * </ol>
 * 网络问题按退避间隔整体重试；请求被拒绝的消息最多尝试 {@link #MAX_REJECTED_ATTEMPTS} 次。
 * 处理在 notification 线程上串行进行。
 */
public class NotificationWorker implements AliasWorker {
    private static final String TAG = "NotificationWorker";
    private static final int MAX_REJECTED_ATTEMPTS = 3;
    private static final int[] RETRY_DELAYS_S = {5, 10, 20, 40, 60, 120, 300};
    private static final long JOB_RETENTION_MS = 3L * 24 * 60 * 60 * 1000;
    private static final String OWNER_WHO = "无（这段对话里没有已登记的熟人，记录与机主本人相关的事项）";

    /** 一条微信消息的处理进度，界面据此显示“识别到 XX 的消息 → 正在 AI 识别 → 结果”。 */
    public enum Stage {
        /** 识别到某人的消息，正要交给 AI。 */
        RECEIVED,
        /** 出现还没对应到用户的昵称，等机主指定。 */
        ALIAS,
        /** 识别完成，count 为新增的事项条数。 */
        DONE,
        /** 消息里没有需要记录的内容。 */
        NONE,
        /** 发现和已有待办疑似同一件事，count 为合并建议条数。 */
        MERGE,
        /** 机主选择忽略这个昵称，消息不再处理。 */
        IGNORED
    }

    public interface Listener {
        /** 处理进度（worker 线程）。 */
        void onProgress(Stage stage, String sender, int count);

        /** 提取到新的待办或事实（worker 线程）。 */
        void onFactsAdded(long userId, List<UserFact> facts);

        /** 出现了还没对应到用户的微信昵称，需要机主指定（worker 线程）。每条消息只通知一次。 */
        void onAliasNeeded(long jobId, List<String> names);

        /** 之前等待指定的昵称都已处理（worker 线程）。 */
        void onAliasesResolved();

        /** 还有昵称等机主指定的消息任务变化了（worker 线程），界面据此显示 / 隐藏提示条。 */
        void onAliasWaiting(List<Long> jobIds);

        /** 从微信昵称新建了用户（数据库线程）。 */
        void onUsersChanged();

        /** 新消息的待办和已有待办疑似同一件事，弹双卡片让机主裁决（worker 线程）。 */
        void onMergeSuggested(MergeSuggestion suggestion);
    }

    private final Context mContext;
    private final AppDatabase mDb;
    private final StepFunChatClient mClient;
    private final Listener mListener;
    private final SimpleDateFormat mTimeFormat = new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA);
    private final ScheduledExecutorService mExecutor = Executors.newSingleThreadScheduledExecutor(r ->
            new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, "notification"));

    // worker 线程
    private ScheduledFuture<?> mScheduledRetry;
    private int mRetryStep;
    /** 有昵称等待机主指定的消息，指定后重新处理。 */
    private final Set<Long> mWaiting = new LinkedHashSet<>();
    /** 已经通知过机主的消息，避免每次重试都弹一次。 */
    private final Set<Long> mNotified = new HashSet<>();

    public NotificationWorker(Context context, AppDatabase db, Listener listener) {
        mContext = context.getApplicationContext();
        mDb = db;
        mListener = listener;
        mClient = new StepFunChatClient(BuildConfig.STEPFUN_API_KEY);
        post(() -> {
            mDb.notificationJobDao().deleteBefore(System.currentTimeMillis() - JOB_RETENTION_MS);
            drain();
        });
    }

    /** 一条微信消息入队（任意线程，监听器防抖后调用）。 */
    public void enqueue(String sender, String text, long postTime) {
        AppDatabase.IO.execute(() -> {
            insertJob(mDb, sender, text, postTime);
            retryPending();
        });
    }

    /** 录音服务不在时监听器直接落库，等服务起来时接着处理。任意线程。 */
    public static void insertJob(AppDatabase db, String sender, String text, long postTime) {
        NotificationJob job = new NotificationJob();
        job.sender = sender;
        job.text = text;
        job.postTime = postTime;
        job.createdAt = System.currentTimeMillis();
        db.notificationJobDao().insert(job);
    }

    /** 网络恢复、服务启动或用户手动触发时调用，立即重试。 */
    public void retryPending() {
        post(() -> {
            cancelScheduledRetry();
            drain();
        });
    }

    public void shutdown() {
        mExecutor.shutdown();
    }

    // ---------------------------------------------------------------- 昵称与用户的对应

    /**
     * 查这条消息（或同批其它等待中的消息）下一个待指定的昵称；都已指定时返回 null。
     * 只读数据库，必须在后台线程（建议 {@link AppDatabase#IO}）调用。
     */
    @Override
    public PendingAlias findPendingAlias(long jobId) {
        NotificationJob job = mDb.notificationJobDao().get(jobId);
        PendingAlias fromJob = job == null ? null : pendingOf(job);
        if (fromJob != null) {
            return fromJob;
        }
        for (NotificationJob other : mDb.notificationJobDao().getAll()) {
            PendingAlias pending = pendingOf(other);
            if (pending != null) {
                return pending;
            }
        }
        return null;
    }

    /** 机主指定昵称对应的用户；userId 为 null 表示忽略这个昵称。任意线程。 */
    @Override
    public void setAlias(String name, Long userId) {
        AppDatabase.IO.execute(() -> {
            putAlias(name, userId);
            post(this::resumeWaiting);
        });
    }

    /** 机主为昵称新建一个用户（还没有声纹，之后可以补录）。任意线程。 */
    @Override
    public void createUserForAlias(String name, String userName) {
        AppDatabase.IO.execute(() -> {
            long now = System.currentTimeMillis();
            User user = new User();
            user.name = userName;
            user.createdAt = now;
            user.lastSeenAt = now;
            user.color = UserColors.pickUnused(mDb.userDao().getAllColors());
            long userId = mDb.userDao().insert(user);
            putAlias(name, userId);
            mListener.onUsersChanged();
            post(this::resumeWaiting);
        });
    }

    private PendingAlias pendingOf(NotificationJob job) {
        if (mDb.chatAliasDao().get(NameMatcher.normalize(job.sender)) != null) {
            return null;
        }
        List<String> messages = new ArrayList<>();
        for (String line : job.text.split("\n")) {
            if (!line.isEmpty()) {
                messages.add(line);
            }
        }
        return new PendingAlias(job.id, job.sender, messages, waitingJobCount(), suggestUserFor(job.sender));
    }

    /** 昵称还没指定的消息条数（含正在询问的这条）。 */
    private int waitingJobCount() {
        int count = 0;
        for (NotificationJob job : mDb.notificationJobDao().getAll()) {
            if (mDb.chatAliasDao().get(NameMatcher.normalize(job.sender)) == null) {
                count++;
            }
        }
        return Math.max(1, count);
    }

    private void putAlias(String name, Long userId) {
        ChatAlias alias = new ChatAlias();
        alias.name = NameMatcher.normalize(name);
        alias.userId = userId;
        alias.createdAt = System.currentTimeMillis();
        mDb.chatAliasDao().put(alias);
    }

    /** 找名字或以前的昵称最像这个昵称的用户作为推荐，相似度不够时返回 -1。只是推荐，不写入对应关系。 */
    private long suggestUserFor(String name) {
        long best = -1;
        float bestScore = NameMatcher.SUGGEST_THRESHOLD;
        for (User u : mDb.userDao().getAll()) {
            if (u.isOwner || u.isStranger) {
                continue;
            }
            float s = NameMatcher.similarity(name, u.name);
            if (s > bestScore) {
                bestScore = s;
                best = u.id;
            }
        }
        for (ChatAlias a : mDb.chatAliasDao().getAll()) {
            if (a.userId == null) {
                continue;
            }
            float s = NameMatcher.similarity(name, a.name);
            if (s > bestScore) {
                bestScore = s;
                best = a.userId;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- worker 线程

    /** 投递到 notification 线程；服务已退出（线程池已关）时静默丢弃，任务都在数据库里，下次启动接着处理。 */
    private void post(Runnable task) {
        try {
            mExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "worker already shut down, dropping task");
        }
    }

    private void resumeWaiting() {
        mWaiting.clear();
        publishWaiting();
        cancelScheduledRetry();
        drain();
        if (mWaiting.isEmpty()) {
            mListener.onAliasesResolved();
        }
    }

    private void publishWaiting() {
        mListener.onAliasWaiting(new ArrayList<>(mWaiting));
    }

    private void drain() {
        if (BuildConfig.STEPFUN_API_KEY.isEmpty()) {
            Log.w(TAG, "StepFun API key not configured, notifications left in queue");
            return;
        }
        for (NotificationJob job : mDb.notificationJobDao().getAll()) {
            if (mWaiting.contains(job.id)) {
                continue;
            }
            if (!process(job)) {
                scheduleRetry();
                return;
            }
        }
    }

    /** 返回 false 表示遇到网络类问题，应稍后整体重试。 */
    private boolean process(NotificationJob job) {
        // 昵称对应不需要网络，先做完：离线时陌生人弹窗照样能及时出现。
        ChatAlias alias = mDb.chatAliasDao().get(NameMatcher.normalize(job.sender));
        if (alias == null) {
            mWaiting.add(job.id);
            publishWaiting();
            if (mNotified.add(job.id)) {
                mListener.onProgress(Stage.ALIAS, job.sender, 0);
                mListener.onAliasNeeded(job.id, Collections.singletonList(job.sender));
            }
            return true;
        }
        if (!ExtractionWorker.isNetworkAvailable(mContext)) {
            return false;
        }
        if (alias.userId == null) {
            // 机主选择忽略这个昵称：任务到此为止，也要给进度条一个终态，别停在“等待指定”。
            mListener.onProgress(Stage.IGNORED, job.sender, 0);
            finish(job);
            return true;
        }
        User user = mDb.userDao().get(alias.userId);
        if (user == null) {
            // 对应到的人已被删除，当作忽略处理。
            mListener.onProgress(Stage.IGNORED, job.sender, 0);
            finish(job);
            return true;
        }
        String who = user.isOwner ? OWNER_WHO : user.name;
        StringBuilder transcript = new StringBuilder("微信消息时间：")
                .append(mTimeFormat.format(new Date(job.postTime))).append('\n');
        for (String line : job.text.split("\n")) {
            if (!line.isEmpty()) {
                transcript.append(job.sender).append("：").append(line).append('\n');
            }
        }
        List<UserFact> existing = mDb.userFactDao().getPending(user.id);
        mListener.onProgress(Stage.RECEIVED, job.sender, 0);
        try {
            ExtractionResult result = mClient.extract(who, existing, transcript.toString(),
                    StepFunChatClient.Source.NOTIFICATION);
            mRetryStep = 0;
            List<UserFact> added = FactSaver.save(mDb, user.id, result);
            if (!added.isEmpty()) {
                mListener.onFactsAdded(user.id, added);
            }
            List<MergeSuggestion> merges = FactSaver.saveMergeCandidates(mDb, user.id,
                    result.mergedTodos);
            for (MergeSuggestion merge : merges) {
                mListener.onMergeSuggested(merge);
            }
            mListener.onProgress(merges.isEmpty()
                    ? (added.isEmpty() ? Stage.NONE : Stage.DONE)
                    : Stage.MERGE, job.sender, merges.isEmpty() ? added.size() : merges.size());
        } catch (StepFunChatClient.RetryableException e) {
            Log.w(TAG, "extraction deferred: " + e.getMessage());
            return false;
        } catch (IOException e) {
            Log.e(TAG, "extraction rejected", e);
            reject(job);
            return true;
        }
        finish(job);
        return true;
    }

    /** 这条消息处理完或放弃，移出队列。 */
    private void finish(NotificationJob job) {
        mDb.notificationJobDao().delete(job.id);
        mNotified.remove(job.id);
        if (mWaiting.remove(job.id)) {
            publishWaiting();
        }
    }

    private void reject(NotificationJob job) {
        if (job.attempts + 1 >= MAX_REJECTED_ATTEMPTS) {
            Log.e(TAG, "job " + job.id + " dropped after " + MAX_REJECTED_ATTEMPTS + " attempts");
            finish(job);
        } else {
            mDb.notificationJobDao().incrementAttempts(job.id);
        }
    }

    private void scheduleRetry() {
        int delay = RETRY_DELAYS_S[Math.min(mRetryStep, RETRY_DELAYS_S.length - 1)];
        mRetryStep++;
        cancelScheduledRetry();
        mScheduledRetry = mExecutor.schedule(() -> {
            mScheduledRetry = null;
            drain();
        }, delay, TimeUnit.SECONDS);
    }

    private void cancelScheduledRetry() {
        if (mScheduledRetry != null) {
            mScheduledRetry.cancel(false);
            mScheduledRetry = null;
        }
    }
}
