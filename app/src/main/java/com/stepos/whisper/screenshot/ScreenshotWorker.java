package com.stepos.whisper.screenshot;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Process;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.stepos.whisper.BuildConfig;
import com.stepos.whisper.data.AppDatabase;
import com.stepos.whisper.data.ChatAlias;
import com.stepos.whisper.data.ScreenshotJob;
import com.stepos.whisper.data.User;
import com.stepos.whisper.data.UserColors;
import com.stepos.whisper.data.UserFact;
import com.stepos.whisper.llm.ChatParse;
import com.stepos.whisper.llm.ExtractionWorker;
import com.stepos.whisper.llm.FactSaver;
import com.stepos.whisper.llm.NameMatcher;
import com.stepos.whisper.llm.StepFunChatClient;
import com.stepos.whisper.ocr.OcrEngine;
import com.stepos.whisper.ocr.OcrResult;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 聊天截图处理队列。每张截图：
 * <ol>
 *   <li>本机 OCR，把每行文字连同左 / 右 / 中位置整理成文本（{@link ChatLayoutText}）；</li>
 *   <li>把这段文本发给大模型，整理出参与者和按发送者排列的对话（{@link ChatParse}）；</li>
 *   <li>把参与者的昵称对应到用户：只有机主指定过的昵称直接对应；没指定过的昵称一律等机主指定，
 *       名字或以前的昵称相似（含完全同名）的人会在指定窗口里预先选中作为推荐，但绝不自动对应
 *       （见 {@link ChatAlias}、{@link NameMatcher}）；</li>
 *   <li>按对应到的每个人分别把对话发给大模型提取待办和事实。</li>
 * </ol>
 * 网络问题按退避间隔整体重试；请求被拒绝的截图最多尝试 {@link #MAX_REJECTED_ATTEMPTS} 次。
 * 处理在 screenshot 线程上串行进行；机主在界面上指定昵称时的数据库读写走 {@link AppDatabase#IO}，
 * 保证“写入对应关系”一定先于“查下一个待指定昵称”，再回到 screenshot 线程继续处理。
 */
public class ScreenshotWorker {
    private static final String TAG = "ScreenshotWorker";
    private static final int MAX_REJECTED_ATTEMPTS = 3;
    private static final int[] RETRY_DELAYS_S = {5, 10, 20, 40, 60, 120, 300};
    private static final long JOB_RETENTION_MS = 3L * 24 * 60 * 60 * 1000;
    /** 队列空闲这么久后释放 OCR 模型（约 140 MB 内存），下次用时再加载（约 0.3 秒）。 */
    private static final long OCR_IDLE_CLOSE_MS = 60_000;
    private static final int SAMPLE_MESSAGES = 3;
    private static final String OWNER_WHO = "无（这段聊天里没有已登记的熟人，记录与机主本人相关的事项）";

    /** 截图处理进度，见 {@link Listener#onProgress}。 */
    public enum Stage {
        /** 系统开始保存新截图。 */
        DETECTED,
        /** 本机识别文字。 */
        OCR,
        /** 已发给 AI，整理对话、提取事项。 */
        AI,
        /** 处理完，count 是新增的事项数（可能为 0）。 */
        DONE,
        /** 没识别到文字或不是聊天内容，已跳过。 */
        NO_TEXT,
        /** 有昵称等机主指定，由 {@link Listener#onAliasNeeded} 另行提示。 */
        ALIAS,
        /** 网络问题，稍后自动重试。 */
        DEFERRED,
        /** AI 拒绝了请求。 */
        FAILED
    }

    public interface Listener {
        /** 处理进度（worker 线程）。推迟或失败过的截图之后重试时不再报告，免得每次重试都提示一遍。 */
        void onProgress(Stage stage, int count);

        /** 提取到新的待办或事实（worker 线程）。 */
        void onFactsAdded(long userId, List<UserFact> facts);

        /** 截图里出现了还没对应到用户的昵称，需要机主指定（worker 线程）。每张截图只通知一次。 */
        void onAliasNeeded(long jobId, List<String> names);

        /** 之前等待指定的昵称都已处理（worker 线程）。 */
        void onAliasesResolved();

        /** 还有昵称等机主指定的截图任务变化了（worker 线程），界面据此显示 / 隐藏提示条。 */
        void onAliasWaiting(List<Long> jobIds);

        /** 从截图昵称新建了用户（数据库线程）。 */
        void onUsersChanged();
    }

    /** 等机主指定的一个昵称及其上下文。 */
    public static final class PendingAlias {
        public final long jobId;
        public final String name;
        /** 这个昵称在截图里说的话（最多几条）。 */
        public final List<String> messages;
        /** 这张截图里还有几个昵称等待指定（含这个）。 */
        public final int remaining;
        /** 看起来像是这位用户（名字或以前的昵称相似），-1 表示没有推荐。 */
        public final long suggestedUserId;

        PendingAlias(long jobId, String name, List<String> messages, int remaining, long suggestedUserId) {
            this.jobId = jobId;
            this.name = name;
            this.messages = messages;
            this.remaining = remaining;
            this.suggestedUserId = suggestedUserId;
        }
    }

    private final Context mContext;
    private final AppDatabase mDb;
    private final StepFunChatClient mClient;
    private final Listener mListener;
    private final Gson mGson = new Gson();
    /** 后台优先级：OCR 会把几个核跑满，onnxruntime 的工作线程继承这个优先级，不至于把主线程饿出 ANR。 */
    private final ScheduledExecutorService mExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "screenshot");
        return t;
    });
    private final SimpleDateFormat mTimeFormat = new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA);

    // worker 线程
    private OcrEngine mOcr;
    private ScheduledFuture<?> mCloseOcr;
    private ScheduledFuture<?> mScheduledRetry;
    private int mRetryStep;
    /** 有昵称等待机主指定的截图，指定后重新处理。 */
    private final Set<Long> mWaiting = new LinkedHashSet<>();
    /** 已经通知过机主的截图，避免每次重试都弹一次。 */
    private final Set<Long> mNotified = new HashSet<>();
    /** 推迟或失败过的截图，重试时不报告进度。 */
    private final Set<Long> mQuiet = new HashSet<>();

    public ScreenshotWorker(Context context, AppDatabase db, Listener listener) {
        mContext = context.getApplicationContext();
        mDb = db;
        mListener = listener;
        mClient = new StepFunChatClient(BuildConfig.STEPFUN_API_KEY);
        mExecutor.execute(() -> {
            mDb.screenshotJobDao().deleteBefore(System.currentTimeMillis() - JOB_RETENTION_MS);
            drain();
        });
    }

    /** 系统开始保存一张新截图（任意线程）：先提示检测到，写完后 {@link #enqueue} 再处理。本应用自己的截图不提示。 */
    public void onScreenshotSaving(String displayName) {
        if (!mContext.getPackageName().equals(sourceApp(displayName))) {
            mListener.onProgress(Stage.DETECTED, 0);
        }
    }

    /** 新截图入队（任意线程）。本应用自己的截图不处理。 */
    public void enqueue(Uri uri, String displayName, long takenAtMs) {
        String app = sourceApp(displayName);
        if (mContext.getPackageName().equals(app)) {
            return;
        }
        ScreenshotJob job = new ScreenshotJob();
        job.uri = uri.toString();
        job.sourceApp = app;
        job.takenAt = takenAtMs;
        job.createdAt = System.currentTimeMillis();
        mDb.screenshotJobDao().insert(job);
        post(() -> {
            cancelScheduledRetry();
            drain();
        });
    }

    /** 网络恢复或用户手动触发时调用，立即重试。 */
    public void retryPending() {
        post(() -> {
            cancelScheduledRetry();
            drain();
        });
    }

    /** 投递到 screenshot 线程；服务已退出（线程池已关）时静默丢弃，任务都在数据库里，下次启动接着处理。 */
    private void post(Runnable task) {
        try {
            mExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "worker already shut down, dropping task");
        }
    }

    /**
     * 查某张截图里下一个等待指定的昵称；都已指定（或截图已处理完）时返回 null。
     * 只读数据库，必须在后台线程（建议 {@link AppDatabase#IO}）调用。
     */
    public PendingAlias findPendingAlias(long jobId) {
        ScreenshotJob job = mDb.screenshotJobDao().get(jobId);
        ChatParse parse = job == null ? null : parse(job);
        if (parse == null) {
            return null;
        }
        List<String> unresolved = unresolvedNames(parse);
        if (unresolved.isEmpty()) {
            return null;
        }
        String name = unresolved.get(0);
        return new PendingAlias(jobId, name, parse.messagesOf(name, SAMPLE_MESSAGES), unresolved.size(),
                suggestUserFor(name));
    }

    /** 机主指定昵称对应的用户；userId 为 null 表示忽略这个昵称。任意线程。 */
    public void setAlias(String name, Long userId) {
        AppDatabase.IO.execute(() -> {
            putAlias(name, userId);
            post(this::resumeWaiting);
        });
    }

    /** 机主为昵称新建一个用户（还没有声纹，之后可以补录）。任意线程。 */
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

    public void shutdown() {
        mExecutor.execute(this::closeOcr);
        mExecutor.shutdown();
    }

    // ---------------------------------------------------------------- 昵称与用户的对应

    private void putAlias(String name, Long userId) {
        ChatAlias alias = new ChatAlias();
        alias.name = NameMatcher.normalize(name);
        alias.userId = userId;
        alias.createdAt = System.currentTimeMillis();
        mDb.chatAliasDao().put(alias);
    }

    /**
     * 查昵称对应的用户：只认机主指定过的对应关系（{@link ChatAlias}），返回 null 表示需要机主指定。
     * 和某个人同名、相似都不自动对应，只在指定窗口里作为推荐预先选中（见 {@link #suggestUserFor}），
     * 由机主确认——同名不一定是同一个人，对应错了会把别人的事记到这个人头上。
     */
    private ChatAlias resolveAlias(String name) {
        return mDb.chatAliasDao().get(name);
    }

    /**
     * 找名字或以前的昵称最像这个昵称的用户作为推荐（完全同名相似度为 1，排最前），相似度不够时返回 -1。
     * 只是推荐，不写入对应关系。
     */
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

    private List<String> unresolvedNames(ChatParse parse) {
        List<String> out = new ArrayList<>();
        for (String name : parse.otherNames()) {
            if (mDb.chatAliasDao().get(name) == null) {
                out.add(name);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- worker 线程

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
            Log.w(TAG, "StepFun API key not configured, screenshots left in queue");
            return;
        }
        try {
            for (ScreenshotJob job : mDb.screenshotJobDao().getAll()) {
                if (mWaiting.contains(job.id)) {
                    continue;
                }
                if (!process(job)) {
                    scheduleRetry();
                    return;
                }
            }
        } finally {
            scheduleOcrClose();
        }
    }

    /** 返回 false 表示遇到网络类问题，应稍后整体重试。 */
    private boolean process(ScreenshotJob job) {
        // 第一步：本机 OCR。结果落库，之后重试不再重复识别。
        if (job.layoutText == null) {
            progress(job, Stage.OCR, 0);
            String text = ocr(job);
            if (text == null) {
                finish(job, Stage.NO_TEXT, 0);
                return true;
            }
            job.layoutText = text;
            mDb.screenshotJobDao().setLayoutText(job.id, text);
            Log.i(TAG, "job " + job.id + ": ocr " + text.split("\n").length + " lines");
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "job " + job.id + " layout:\n" + text);
            }
        }
        if (!ExtractionWorker.isNetworkAvailable(mContext)) {
            defer(job);
            return false;
        }
        progress(job, Stage.AI, 0);
        // 第二步：把 OCR 文字发给大模型，整理出参与者和对话。
        if (job.parsed == null) {
            try {
                ChatParse parse = mClient.parseChatScreenshot(appLabel(job.sourceApp), job.layoutText);
                job.parsed = mGson.toJson(parse);
                mDb.screenshotJobDao().setParsed(job.id, job.parsed);
                mRetryStep = 0;
                Log.i(TAG, "job " + job.id + ": " + parse.otherNames().size() + " others, "
                        + (parse.dialogue == null ? 0 : parse.dialogue.size()) + " messages");
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "job " + job.id + " parsed: " + job.parsed);
                }
            } catch (StepFunChatClient.RetryableException e) {
                Log.w(TAG, "parse deferred: " + e.getMessage());
                defer(job);
                return false;
            } catch (IOException e) {
                Log.e(TAG, "parse rejected", e);
                reject(job);
                return true;
            }
        }
        ChatParse parse = parse(job);
        String dialogue = parse == null ? null : parse.dialogueText();
        if (dialogue == null) {
            if (job.attempts == 0 && looksLikeChat(job.layoutText)) {
                // 模型偶尔会把明显的聊天截图整理成空结果，重新整理一次。
                Log.w(TAG, "job " + job.id + ": empty parse for chat-like layout, retrying once");
                mDb.screenshotJobDao().incrementAttempts(job.id);
                mDb.screenshotJobDao().setParsed(job.id, null);
                job.attempts++;
                job.parsed = null;
                return process(job);
            }
            finish(job, Stage.NO_TEXT, 0);
            return true;
        }

        // 第三步：昵称对应到用户。
        List<String> unresolved = new ArrayList<>();
        Map<Long, List<String>> targets = new LinkedHashMap<>();
        for (String name : parse.otherNames()) {
            ChatAlias alias = resolveAlias(name);
            if (alias == null) {
                unresolved.add(name);
            } else if (alias.userId != null) {
                List<String> nicks = targets.get(alias.userId);
                if (nicks == null) {
                    nicks = new ArrayList<>();
                    targets.put(alias.userId, nicks);
                }
                nicks.add(name);
            }
        }
        if (!unresolved.isEmpty()) {
            mWaiting.add(job.id);
            publishWaiting();
            if (mNotified.add(job.id)) {
                mListener.onAliasNeeded(job.id, unresolved);
            }
            progress(job, Stage.ALIAS, 0);
            return true;
        }
        if (targets.isEmpty()) {
            User owner = mDb.userDao().getOwner();
            if (owner == null) {
                finish(job, Stage.DONE, 0);
                return true;
            }
            targets.put(owner.id, new ArrayList<>());
        }

        // 第四步：按对应到的每个人提取待办和事实。
        String transcript = "截图时间：" + mTimeFormat.format(new Date(job.takenAt)) + "\n" + dialogue;
        int addedCount = 0;
        for (Map.Entry<Long, List<String>> e : targets.entrySet()) {
            User user = mDb.userDao().get(e.getKey());
            if (user == null) {
                continue;
            }
            String who = user.isOwner ? OWNER_WHO : user.name;
            List<String> nicks = e.getValue();
            if (!user.isOwner && !nicks.isEmpty() && !(nicks.size() == 1 && nicks.get(0).equals(user.name))) {
                who += "（截图里显示为“" + String.join("”“", nicks) + "”）";
            }
            List<UserFact> existing = mDb.userFactDao().getPending(user.id);
            try {
                List<UserFact> added = FactSaver.save(mDb, user.id,
                        mClient.extract(who, existing, transcript, StepFunChatClient.Source.SCREENSHOT));
                mRetryStep = 0;
                if (!added.isEmpty()) {
                    addedCount += added.size();
                    mListener.onFactsAdded(user.id, added);
                }
            } catch (StepFunChatClient.RetryableException ex) {
                Log.w(TAG, "extraction deferred: " + ex.getMessage());
                defer(job);
                return false;
            } catch (IOException ex) {
                Log.e(TAG, "extraction rejected", ex);
                reject(job);
                return true;
            }
        }
        finish(job, Stage.DONE, addedCount);
        return true;
    }

    private void progress(ScreenshotJob job, Stage stage, int count) {
        if (!mQuiet.contains(job.id)) {
            mListener.onProgress(stage, count);
        }
    }

    /** 这张截图处理完或放弃，移出队列。 */
    private void finish(ScreenshotJob job, Stage stage, int count) {
        progress(job, stage, count);
        mDb.screenshotJobDao().delete(job.id);
        mNotified.remove(job.id);
        mQuiet.remove(job.id);
        if (mWaiting.remove(job.id)) {
            publishWaiting();
        }
    }

    private void defer(ScreenshotJob job) {
        progress(job, Stage.DEFERRED, 0);
        mQuiet.add(job.id);
    }

    private void reject(ScreenshotJob job) {
        if (job.attempts + 1 >= MAX_REJECTED_ATTEMPTS) {
            Log.e(TAG, "job " + job.id + " dropped after " + MAX_REJECTED_ATTEMPTS + " attempts");
            finish(job, Stage.FAILED, 0);
        } else {
            progress(job, Stage.FAILED, 0);
            mQuiet.add(job.id);
            mDb.screenshotJobDao().incrementAttempts(job.id);
        }
    }

    /** 左右两侧都有文字的截图大概率是聊天界面。 */
    static boolean looksLikeChat(String layoutText) {
        return layoutText != null && layoutText.contains("[左] ") && layoutText.contains("[右] ");
    }

    private ChatParse parse(ScreenshotJob job) {
        if (job.parsed == null) {
            return null;
        }
        try {
            return mGson.fromJson(job.parsed, ChatParse.class);
        } catch (JsonParseException e) {
            Log.w(TAG, "bad parse json for job " + job.id, e);
            return null;
        }
    }

    /** OCR 并整理成带位置标记的文本；图片已删除、模型缺失或没有文字时返回 null。 */
    private String ocr(ScreenshotJob job) {
        Bitmap bitmap;
        try {
            bitmap = ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(mContext.getContentResolver(), Uri.parse(job.uri)),
                    (decoder, info, source) -> decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "cannot decode " + job.uri + ": " + e.getMessage());
            return null;
        }
        try {
            if (mOcr == null) {
                mOcr = new OcrEngine();
            }
            OcrResult result = mOcr.recognize(bitmap);
            return ChatLayoutText.build(result, bitmap.getWidth(), bitmap.getHeight());
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "ocr failed", e);
            return null;
        } finally {
            bitmap.recycle();
        }
    }

    private void scheduleOcrClose() {
        if (mCloseOcr != null) {
            mCloseOcr.cancel(false);
        }
        mCloseOcr = mOcr == null ? null : mExecutor.schedule(this::closeOcr, OCR_IDLE_CLOSE_MS, TimeUnit.MILLISECONDS);
    }

    private void closeOcr() {
        if (mOcr != null) {
            mOcr.close();
            mOcr = null;
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

    /** 系统截图文件名形如 S61001-19250538_com.tencent.mm.png，下划线后是被截图的应用包名。 */
    static String sourceApp(String displayName) {
        int underscore = displayName.indexOf('_');
        int dot = displayName.lastIndexOf('.');
        if (underscore < 0 || dot <= underscore + 1) {
            return null;
        }
        String pkg = displayName.substring(underscore + 1, dot);
        return pkg.indexOf('.') > 0 ? pkg : null;
    }

    private String appLabel(String pkg) {
        if (pkg == null) {
            return null;
        }
        try {
            PackageManager pm = mContext.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(info) + "（" + pkg + "）";
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
