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
import com.stepos.whisper.data.Utterance;
import com.stepos.whisper.service.ServiceState;
import com.stepos.whisper.service.TranscriptLine;
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
    private static final String OWNER_WHO = "无（这段对话里没有已登记的熟人，记录与机主本人相关的事项）";

    public interface Listener {
        /** 完整通知行（worker 线程），按 notificationJobId 更新，不追加重复行。 */
        void onLine(TranscriptLine line);

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
    private final NotificationAliasStore mAliases;
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
        mAliases = new NotificationAliasStore(db, userCreated -> {
            if (userCreated) mListener.onUsersChanged();
            post(this::resumeWaiting);
        });
        post(this::drain);
    }

    /** 一条微信消息入队（任意线程，监听器防抖后调用）。 */
    public void enqueue(String sender, String text, long postTime) {
        enqueue(sender, text, postTime, null);
    }

    public void enqueue(String sender, String text, long postTime, String packageName) {
        AppDatabase.IO.execute(() -> {
            insertJob(mDb, sender, text, postTime, packageName);
            retryPending();
        });
    }

    /** 先落库并立即发布会话行；录音服务不在时同样可用。数据库线程调用。 */
    public static void insertJob(AppDatabase db, String sender, String text, long postTime) {
        insertJob(db, sender, text, postTime, null);
    }

    public static void insertJob(AppDatabase db, String sender, String text, long postTime, String packageName) {
        NotificationJob job = new NotificationJob();
        job.packageName = packageName;
        job.sender = sender;
        job.text = text;
        job.postTime = postTime;
        job.createdAt = System.currentTimeMillis();
        job.id = db.notificationJobDao().insert(job);
        ServiceState.upsertNotificationLine(lineFor(db, job, Utterance.AI_QUEUED));
    }

    private static TranscriptLine lineFor(AppDatabase db, NotificationJob job, int aiState) {
        return NotificationAliasStore.lineFor(db, job, aiState);
    }

    private void publishLine(NotificationJob job, int aiState) {
        TranscriptLine line = lineFor(mDb, job, aiState);
        ServiceState.upsertNotificationLine(line);
        mListener.onLine(line);
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

    @Override
    public PendingAlias findPendingAlias(long jobId) {
        return mAliases.findPendingAlias(jobId);
    }

    @Override
    public void setAlias(String name, Long userId) {
        mAliases.setAlias(name, userId);
    }

    @Override
    public void createUserForAlias(String name, String userName) {
        mAliases.createUserForAlias(name, userName);
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
        List<NotificationJob> jobs = mDb.notificationJobDao().getAll();
        for (NotificationJob job : jobs) {
            publishLine(job, Utterance.AI_QUEUED);
        }
        // 先登记全部未知昵称，不能被队首已映射消息的离线重试挡住；此轮绝不提取。
        for (NotificationJob job : jobs) {
            if (mDb.chatAliasDao().get(NameMatcher.normalize(job.sender)) == null) {
                registerWaiting(job);
            }
        }
        boolean retry = false;
        for (NotificationJob cached : jobs) {
            NotificationJob job = mDb.notificationJobDao().get(cached.id);
            if (job == null) {
                clearWaiting(cached);
                continue;
            }
            if (!process(job)) {
                retry = true;
            }
        }
        if (retry) scheduleRetry();
    }

    private void registerWaiting(NotificationJob job) {
        if (mWaiting.add(job.id)) {
            publishWaiting();
        }
        if (mNotified.add(job.id)) {
            mListener.onAliasNeeded(job.id, Collections.singletonList(job.sender));
        }
    }

    /** 返回 false 表示应稍后重试（网络失败或尚未达到拒绝次数上限）。 */
    private boolean process(NotificationJob job) {
        // 昵称对应不需要网络，先做完：离线时陌生人弹窗照样能及时出现。
        ChatAlias alias = mDb.chatAliasDao().get(NameMatcher.normalize(job.sender));
        if (alias == null) {
            registerWaiting(job);
            return true;
        }
        if (mWaiting.remove(job.id)) {
            publishWaiting();
        }
        if (alias.userId == null) {
            // 机主明确忽略：保留消息，但不显示 AI 已识别。
            finish(job, Utterance.AI_NONE);
            return true;
        }
        User user = mDb.userDao().get(alias.userId);
        if (user == null) {
            finish(job, Utterance.AI_NONE);
            return true;
        }
        if (BuildConfig.STEPFUN_API_KEY.isEmpty() || !ExtractionWorker.isNetworkAvailable(mContext)) {
            return false;
        }
        String who = user.isOwner ? OWNER_WHO : user.name;
        StringBuilder transcript = new StringBuilder(mContext.getString(NotificationSource.label(job.packageName)))
                .append("消息时间：")
                .append(mTimeFormat.format(new Date(job.postTime))).append('\n');
        for (String line : job.text.split("\n")) {
            if (!line.isEmpty()) {
                transcript.append(user.name).append("：").append(line).append('\n');
            }
        }
        List<UserFact> existing = mDb.userFactDao().getPending(user.id);
        publishLine(job, Utterance.AI_QUEUED);
        try {
            ExtractionResult result = mClient.extract(who, existing, transcript.toString(),
                    StepFunChatClient.Source.NOTIFICATION);
            completeExtraction(job, user.id, result);
        } catch (StepFunChatClient.RetryableException e) {
            Log.w(TAG, "extraction deferred: " + e.getMessage());
            return false;
        } catch (IOException e) {
            Log.e(TAG, "extraction rejected", e);
            return reject(job);
        }
        return true;
    }

    /** 网络请求已经结束；事实、候选与任务删除作为一个提交，再发布界面事件。 */
    private void completeExtraction(NotificationJob job, long userId, ExtractionResult result) {
        List<UserFact> added = new ArrayList<>();
        List<MergeSuggestion> merges = new ArrayList<>();
        boolean committed = mDb.runInTransaction(() -> {
            if (mDb.notificationJobDao().get(job.id) == null) {
                return false;
            }
            added.addAll(FactSaver.save(mDb, userId, result));
            merges.addAll(FactSaver.saveMergeCandidates(mDb, userId, result.mergedTodos));
            mDb.notificationJobDao().delete(job.id);
            return true;
        });
        if (!committed) {
            clearWaiting(job);
            return;
        }
        mRetryStep = 0;
        clearWaiting(job);
        publishLine(job, Utterance.AI_DONE);
        if (!added.isEmpty()) {
            mListener.onFactsAdded(userId, added);
        }
        for (MergeSuggestion merge : merges) {
            mListener.onMergeSuggested(merge);
        }
    }

    /** 这条消息处理完或放弃，提交移出队列后再发布完成状态。 */
    private void finish(NotificationJob job, int aiState) {
        boolean committed = mDb.runInTransaction(() -> {
            if (mDb.notificationJobDao().get(job.id) == null) {
                return false;
            }
            mDb.notificationJobDao().delete(job.id);
            return true;
        });
        clearWaiting(job);
        if (committed) {
            publishLine(job, aiState);
        }
    }

    private void clearWaiting(NotificationJob job) {
        mNotified.remove(job.id);
        if (mWaiting.remove(job.id)) {
            publishWaiting();
        }
    }

    /** 返回 false 让 drain 为尚未达到上限的拒绝安排退避重试。 */
    private boolean reject(NotificationJob job) {
        int outcome = mDb.runInTransaction(() -> {
            NotificationJob current = mDb.notificationJobDao().get(job.id);
            if (current == null) {
                return 0;
            }
            if (current.attempts + 1 >= MAX_REJECTED_ATTEMPTS) {
                mDb.notificationJobDao().delete(job.id);
                return 1;
            }
            mDb.notificationJobDao().incrementAttempts(job.id);
            return 2;
        });
        if (outcome == 2) {
            return false;
        }
        clearWaiting(job);
        if (outcome == 1) {
            Log.e(TAG, "job " + job.id + " dropped after " + MAX_REJECTED_ATTEMPTS + " attempts");
            publishLine(job, Utterance.AI_NONE);
        }
        return true;
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
