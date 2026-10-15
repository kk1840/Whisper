package com.stepos.whisper.service;

import android.Manifest;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;

import com.stepos.whisper.R;
import com.stepos.whisper.Settings;
import com.stepos.whisper.asr.ChineseTextFilter;
import com.stepos.whisper.asr.ModelManager;
import com.stepos.whisper.asr.PcmTimeline;
import com.stepos.whisper.asr.StepAsrStreamClient;
import com.stepos.whisper.asr.WavReader;
import com.stepos.whisper.asr.WavWriter;
import com.stepos.whisper.conversation.ConversationManager;
import com.stepos.whisper.data.AppDatabase;
import com.stepos.whisper.data.FactCard;
import com.stepos.whisper.data.User;
import com.stepos.whisper.data.UserColors;
import com.stepos.whisper.data.UserFact;
import com.stepos.whisper.data.UserMerger;
import com.stepos.whisper.data.Utterance;
import com.stepos.whisper.glasses.GlassesManager;
import com.stepos.whisper.live.LiveNotifier;
import com.stepos.whisper.llm.ExtractionWorker;
import com.stepos.whisper.llm.FactSaver;
import com.stepos.whisper.llm.MergeSuggestion;
import com.stepos.whisper.notification.NotificationWorker;
import com.stepos.whisper.screenshot.ScreenshotWatcher;
import com.stepos.whisper.screenshot.ScreenshotWorker;
import com.stepos.whisper.ui.MergeOverlay;
import com.stepos.whisper.ui.ReminderOverlay;
import com.stepos.whisper.voiceprint.VoiceprintLibrary;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 前台录音服务。线程模型：
 * <ul>
 *   <li>audio 线程：AudioRecord 采集，每 100 ms 一帧 PCM 写入时间轴并推给 Step 流式识别；</li>
 *   <li>ws 线程（OkHttp）：接收云端中间结果（实时字幕）和整句定稿，定稿投递给处理线程；</li>
 *   <li>voiceprint 线程：讯飞声纹 1:N 检索与注册（结果回投处理线程补标）；</li>
 *   <li>处理线程（mProc）：句子归属（逐句做声纹）、陌生人建档、会话管理、数据库写入；
 *       screenshot 线程（{@link ScreenshotWorker}）：聊天截图 OCR 和事项提取；
 *       screenshot-watch 线程（{@link ScreenshotWatcher}）：查询媒体库发现新截图。</li>
 * </ul>
 */
public class RecordingService extends Service implements ConversationManager.Listener {
    private static final String TAG = "RecordingService";

    public static final String ACTION_PAUSE = "com.stepos.whisper.action.PAUSE";
    public static final String ACTION_RESUME = "com.stepos.whisper.action.RESUME";
    public static final String ACTION_TOGGLE = "com.stepos.whisper.action.TOGGLE";

    private static final int RESTORE_LINES = 200;
    private static final int VOICE_KEEP_SAMPLES = ModelManager.SAMPLE_RATE * 15;

    private static final int FRAME_SAMPLES = ModelManager.SAMPLE_RATE / 10;
    /** 非机主句子的音频留证门槛（rememberVoice）。 */
    public static final long MIN_DURATION_MS = 1500;
    /** 讯飞口径有效语音需 >0.5 秒，再短不送检。 */
    private static final long SHORT_SEARCH_MIN_MS = 500;
    /**
     * 陌生人建档的最短句子：未命中必须"有信息量"才建档——中短句同本人实测分数 0.36~0.50
     * 本就会打穿 0.45 阈值，未命中不代表陌生人；且 3 秒正好是讯飞的注册下限，建档句
     * 自身就是可用的注册素材。
     */
    public static final long STRANGER_MIN_MS = 3000;
    /** 短句未命中时跟随最近确认说话人的窗口，超出后落「未知」。 */
    private static final long SPEAKER_STICKY_MS = 30_000;
    /** 声纹没命中时，多少秒内的连续未命中句子归给同一位新陌生人，避免每句都建一个。 */
    private static final long STRANGER_WINDOW_MS = 8000;
    /** 自动学习（陌生人建档、学习宽限跟随）给单个用户保留的云端声纹条数上限。 */
    private static final int AUTO_ENROLL_FEATURE_CAP = 3;
    /**
     * 手动指认/合并的学习宽限期：讯飞注册是异步入库的（实测延迟可超过十几秒），刚学的特征
     * 在窗口内检索不到；此期间未命中的句子跟随学习目标而不是再建陌生人，否则"合并后一说话
     * 又冒出陌生人N"无限循环。
     */
    private static final long LEARN_GRACE_MS = 120_000;
    private static final long UTTERANCE_RETENTION_MS = 30L * 24 * 60 * 60 * 1000;
    /** 回放结束后再屏蔽这么久，避开扬声器余音。 */
    private static final long PLAYBACK_TAIL_MS = 800;

    private static volatile RecordingService sInstance;

    @Nullable
    public static RecordingService getInstance() {
        return sInstance;
    }

    private static class UserInfo {
        /** 转写里显示的名字：还没命名的陌生人显示“未知”，工作名（陌生人N）只用于用户列表和命名弹窗。 */
        final String name;
        final int color;
        final boolean owner;
        final boolean stranger;

        UserInfo(String name, int color, boolean owner, boolean stranger) {
            this.name = name;
            this.color = color;
            this.owner = owner;
            this.stranger = stranger;
        }
    }

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService mProc;

    private AppDatabase mDb;
    private ExtractionWorker mExtraction;
    private ScreenshotWorker mScreenshots;
    private ScreenshotWatcher mWatcher;
    private NotificationWorker mNotifications;
    private ReminderOverlay mOverlay;
    private MergeOverlay mMergeOverlay;
    private LiveNotifier mLive;
    /** 截图与微信通知两条链各自“昵称等指定”的任务，合并成一条提示给主界面。worker 线程写，主线程读。 */
    private final List<ServiceState.AliasPrompt> mScreenshotWaiting = new ArrayList<>();
    private final List<ServiceState.AliasPrompt> mNotificationWaiting = new ArrayList<>();
    private final Observer<ServiceState.AiStatus> mAiObserver = s -> mLive.onAiStatus(s);
    private PowerManager.WakeLock mWakeLock;
    private ConnectivityManager.NetworkCallback mNetworkCallback;

    // 云端识别（audio 线程写入，处理线程读取）
    private PcmTimeline mTimeline;
    private StepAsrStreamClient mStream;
    private volatile boolean mLiveCaption;

    /** 改名对话框正在回放录音时，麦克风会录到扬声器的声音，这段时间的输入全部丢弃。 */
    private volatile boolean mPlaybackActive;
    private volatile long mSuppressUntilMs;

    // 处理线程
    private VoiceprintLibrary mVoiceprint;
    private ConversationManager mConversations;
    private final Map<Long, ArrayDeque<byte[]>> mVoices = new HashMap<>();
    private final Map<Long, UserInfo> mUsers = new HashMap<>();
    /** 尚未返回的逐句检索：界面行 id → 数据库发言 id，手动指认先移除以使迟到回调失效。 */
    private final Map<Long, Long> mPendingVoiceprints = new HashMap<>();
    private final UtteranceExtractionState mExtractionState = new UtteranceExtractionState();
    /** 最近一次声纹未命中建档的陌生人（去重窗口内归同一人）。 */
    private long mLastStrangerId = TranscriptLine.NO_USER;
    private long mLastStrangerAtMs;
    /** 当前陌生人还没注册出去的句子音频（处理线程）：单句常不足 3 秒，攒够再拼接注册。 */
    private long mStrangerVoiceId = TranscriptLine.NO_USER;
    private final List<byte[]> mStrangerVoice = new ArrayList<>();
    /** 学习宽限期：最近一次手动指认/合并的目标用户与截止时刻（处理线程）。 */
    private long mLearnUserId = TranscriptLine.NO_USER;
    private long mLearnUntilMs;
    /**
     * 短句未命中的粘性锚点（处理线程）：最近一次确认归属（声纹命中、手动指认、陌生人
     * 建档）的用户与该句结束时刻。短句素材常打不到检索阈值，身份交给上下文兜底。
     */
    private long mLastVoiceUserId = TranscriptLine.NO_USER;
    private long mLastVoiceAtMs;

    // 主线程
    private boolean mReady;
    private boolean mWantRecording = true;
    private AudioRecord mRecord;
    private Thread mAudioThread;
    private volatile boolean mRecording;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mDb = AppDatabase.get(this);
        // 处理线程先建好：下面的截图队列一构造就可能开始处理遗留任务并回调进来。
        mProc = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "speech-proc"));
        mExtraction = new ExtractionWorker(this, mDb, mExtractionState, new ExtractionWorker.Listener() {
            @Override
            public void onAiStateChanged(long from, long to, int state, boolean reset) {
                ServiceState.updateAiState(from, to, state, reset);
            }

            @Override
            public void onFactsAdded(long userId, List<UserFact> facts) {
                RecordingService.this.onFactsAdded(userId, facts);
            }
        });
        mOverlay = new ReminderOverlay(this);
        mMergeOverlay = new MergeOverlay(this);
        mLive = new LiveNotifier(this);
        mConversations = new ConversationManager(this);
        mLiveCaption = Settings.isLiveCaptionEnabled(this);
        mScreenshots = new ScreenshotWorker(this, mDb, new ScreenshotWorker.Listener() {
            @Override
            public void onProgress(ScreenshotWorker.Stage stage, int count) {
                mMain.post(() -> mLive.showScreenshotProgress(stage, count));
            }

            @Override
            public void onFactsAdded(long userId, List<UserFact> facts) {
                RecordingService.this.onFactsAdded(userId, facts);
            }

            @Override
            public void onAliasNeeded(long jobId, List<String> names) {
                // 在本应用里：直接弹出指定窗口；不在时：灵动岛双按钮卡片“以后再说 / 指定”。
                mMain.post(() -> {
                    if (ServiceState.isUiVisible()) {
                        ServiceState.requestAliasPrompt(jobId, false);
                    } else {
                        mLive.showChatAlias(jobId, names, false);
                    }
                });
            }

            @Override
            public void onAliasesResolved() {
                mMain.post(() -> mLive.cancelChatAlias());
            }

            @Override
            public void onAliasWaiting(List<Long> jobIds) {
                synchronized (mScreenshotWaiting) {
                    mScreenshotWaiting.clear();
                    for (long id : jobIds) {
                        mScreenshotWaiting.add(new ServiceState.AliasPrompt(id, false));
                    }
                }
                publishAliasWaiting();
            }

            @Override
            public void onUsersChanged() {
                reloadUsers();
            }
        });
        mNotifications = new NotificationWorker(this, mDb, new NotificationWorker.Listener() {
            @Override
            public void onLine(TranscriptLine line) {
                ServiceState.upsertNotificationLine(line);
            }

            @Override
            public void onFactsAdded(long userId, List<UserFact> facts) {
                RecordingService.this.onFactsAdded(userId, facts);
            }

            @Override
            public void onAliasNeeded(long jobId, List<String> names) {
                mMain.post(() -> {
                    if (ServiceState.isUiVisible()) {
                        ServiceState.requestAliasPrompt(jobId, true);
                    } else {
                        mLive.showChatAlias(jobId, names, true);
                    }
                });
            }

            @Override
            public void onAliasesResolved() {
                mMain.post(() -> mLive.cancelChatAlias());
            }

            @Override
            public void onAliasWaiting(List<Long> jobIds) {
                synchronized (mNotificationWaiting) {
                    mNotificationWaiting.clear();
                    for (long id : jobIds) {
                        mNotificationWaiting.add(new ServiceState.AliasPrompt(id, true));
                    }
                }
                publishAliasWaiting();
            }

            @Override
            public void onUsersChanged() {
                reloadUsers();
            }

            @Override
            public void onMergeSuggested(MergeSuggestion suggestion) {
                showMergeSuggestion(suggestion);
            }
        });
        mWatcher = new ScreenshotWatcher(this, new ScreenshotWatcher.Listener() {
            @Override
            public void onScreenshotSaving(String displayName) {
                mScreenshots.onScreenshotSaving(displayName);
            }

            @Override
            public void onScreenshot(Uri uri, String displayName, long takenAtMs) {
                mScreenshots.enqueue(uri, displayName, takenAtMs);
            }
        });
        if (Settings.isScreenshotWatchEnabled(this)) {
            mWatcher.start();
        }
        // 配对过智能眼镜且开关打开时，在后台自动重连；连接本身由 GlassesManager 持有，服务退出不断开。
        GlassesManager.applyEnabled(this);

        PowerManager pm = getSystemService(PowerManager.class);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "whisper:recording");
        mWakeLock.setReferenceCounted(false);

        startForeground(LiveNotifier.ID_RECORDING, mLive.buildRecording(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        ServiceState.setStatus(ServiceState.Status.PREPARING);
        ServiceState.aiStatus().observeForever(mAiObserver);

        mProc.execute(this::initEngine);
        mProc.scheduleWithFixedDelay(() -> mConversations.tick(System.currentTimeMillis()),
                30, 30, TimeUnit.SECONDS);

        mNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                mExtraction.retryPending();
                mScreenshots.retryPending();
                mNotifications.retryPending();
            }
        };
        getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(mNetworkCallback);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PAUSE.equals(action)) {
            pause();
        } else if (ACTION_RESUME.equals(action)) {
            resume();
        } else if (ACTION_TOGGLE.equals(action)) {
            if (mWantRecording) {
                pause();
            } else {
                resume();
            }
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        stopAudio();
        getSystemService(ConnectivityManager.class).unregisterNetworkCallback(mNetworkCallback);
        ServiceState.aiStatus().removeObserver(mAiObserver);
        mOverlay.dismiss();
        mMergeOverlay.dismiss();
        mLive.cancelAll();
        mWatcher.shutdown();
        mScreenshots.shutdown();
        mNotifications.shutdown();
        mProc.execute(() -> {
            mExtraction.shutdown();
            synchronized (ServiceState.class) {
                mPendingVoiceprints.clear();
                mExtractionState.clear();
                ServiceState.setPendingVoiceprints(false);
                mConversations.endAll();
            }
            if (mStream != null) {
                mStream.stop();
            }
            if (mVoiceprint != null) {
                mVoiceprint.shutdown();
            }
        });
        mProc.shutdown();
        ServiceState.resolveAllPending(getString(R.string.speaker_pending), getString(R.string.speaker_unknown));
        ServiceState.setPartial("");
        ServiceState.setStatus(ServiceState.Status.STOPPED);
        super.onDestroy();
    }

    /**
     * 投递到处理线程。服务退出后（线程池已关）界面或后台线程仍可能通过残留引用调过来，这时静默丢弃，
     * 不让 RejectedExecutionException 把进程打崩。
     */
    private void proc(Runnable task) {
        if (mProc.isShutdown()) {
            return;
        }
        try {
            mProc.execute(task);
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "processing thread already shut down, dropping task");
        }
    }

    // ---------------------------------------------------------------- 对外接口（主线程调用）

    public void pause() {
        mWantRecording = false;
        stopAudio();
        if (mReady) {
            ServiceState.setStatus(ServiceState.Status.PAUSED);
            updateStatusNotification();
        }
    }

    public void resume() {
        mWantRecording = true;
        startAudio();
    }

    /** 用户希望录音（没有点“停止”）。 */
    public boolean isRecordingWanted() {
        return mWantRecording;
    }

    public void setLiveCaptionEnabled(boolean enabled) {
        mLiveCaption = enabled;
        if (!enabled) {
            ServiceState.setPartial("");
        }
    }

    public void setScreenshotWatchEnabled(boolean enabled) {
        if (enabled) {
            mWatcher.start();
        } else {
            mWatcher.stop();
        }
    }

    public ScreenshotWorker getScreenshots() {
        return mScreenshots;
    }

    public NotificationWorker getNotifications() {
        return mNotifications;
    }

    /** 通知监听服务连上、或有新消息落库后调用：让通知队列立即跑一遍。任意线程。 */
    public void kickNotifications() {
        if (mNotifications != null) {
            mNotifications.retryPending();
        }
    }

    /** 微信新消息的待办和已有待办疑似同一件事：全屏弹两张卡片让机主裁决（worker 线程或任意线程）。 */
    public void showMergeSuggestion(MergeSuggestion suggestion) {
        mMain.post(() -> {
            if (!mMergeOverlay.show(suggestion, () -> AppDatabase.IO.execute(
                    () -> FactSaver.applyMerge(mDb, suggestion)))) {
                Log.w(TAG, "merge overlay unavailable, keeping both todos");
            }
        });
    }

    /** 两条“昵称等指定”链的任务合成一条提示（截图 + 微信通知）。任意线程。 */
    private void publishAliasWaiting() {
        List<ServiceState.AliasPrompt> all;
        synchronized (mScreenshotWaiting) {
            synchronized (mNotificationWaiting) {
                all = new ArrayList<>(mScreenshotWaiting);
                all.addAll(mNotificationWaiting);
            }
        }
        ServiceState.setAliasWaiting(all);
    }

    public void setSpeakerThreshold(float threshold) {
        if (mVoiceprint != null) {
            mVoiceprint.setThreshold(threshold);
        }
    }

    /** 改名对话框开始/停止回放录音时调用。 */
    public void setPlaybackActive(boolean active) {
        if (!active) {
            mSuppressUntilMs = System.currentTimeMillis() + PLAYBACK_TAIL_MS;
        }
        mPlaybackActive = active;
    }

    /** 用户在界面上改名、改颜色或删除用户后调用，重新加载声纹库。 */
    public void reloadUsers() {
        proc(() -> {
            if (mVoiceprint == null) {
                return;
            }
            Map<Long, UserInfo> old = new HashMap<>(mUsers);
            loadUsers();
            for (Map.Entry<Long, UserInfo> e : old.entrySet()) {
                long id = e.getKey();
                UserInfo now = mUsers.get(id);
                if (now == null || (e.getValue().stranger && !now.stranger)) {
                    mMain.post(() -> mLive.cancelStranger(id));
                }
                if (now == null) {
                    mMain.post(() -> mLive.cancelReminder(id));
                    mConversations.forget(id);
                    mVoices.remove(id);
                    //noinspection ResultOfMethodCallIgnored
                    voiceFile(this, id).delete();
                    ServiceState.relabelUser(id, getString(R.string.speaker_unknown),
                            TranscriptLine.NO_USER, TranscriptLine.COLOR_UNKNOWN);
                } else if (!now.name.equals(e.getValue().name) || now.color != e.getValue().color) {
                    ServiceState.relabelUser(id, now.name, id, now.color);
                }
            }
        });
    }

    /**
     * 把用户 from 并入已有用户 to（同一个人换了说话方式被认成了新陌生人）：
     * 发言、事项、声纹都归到 to，界面上 from 的发言全部换成 to 的名字和颜色。
     * from 缓存的语音（voices/&lt;id&gt;.wav）是这个人的真实声音，合并前转注册成 to 的
     * 云端声纹——下次这个人再开口就能自动认出来，证据不再随文件一起被删掉。
     */
    public void mergeUser(long from, long to) {
        proc(() -> {
            enrollVoiceEvidence(from, to);
            if (!UserMerger.merge(mDb, from, to)) {
                return;
            }
            mMain.post(() -> {
                mLive.cancelStranger(from);
                mLive.cancelReminder(from);
            });
            mConversations.merge(from, to);
            mVoices.remove(from);
            //noinspection ResultOfMethodCallIgnored
            voiceFile(this, from).delete();
            // 新特征（划转+语音证据注册）还在讯飞异步入库：宽限期内未命中的句子跟随 to，不再建陌生人。
            mLearnUserId = to;
            mLearnUntilMs = System.currentTimeMillis() + LEARN_GRACE_MS;
            if (mLastStrangerId == from) {
                mLastStrangerId = to;
            }
            if (mStrangerVoiceId == from) {
                // 攒着的本来就是同一个人的声音，跟着划给 to。
                mStrangerVoiceId = to;
            }
            if (mVoiceprint != null) {
                loadUsers();
            }
            UserInfo info = mUsers.get(to);
            if (info != null) {
                ServiceState.relabelUser(from, info.name, to, info.color);
            }
        });
    }

    /** 合并前把 from 缓存的语音 WAV 读回 PCM 注册给 to（约 15 秒真实说话，是最好的注册素材）。 */
    private void enrollVoiceEvidence(long from, long to) {
        if (mVoiceprint == null) {
            return;
        }
        File wav = voiceFile(this, from);
        if (!wav.canRead()) {
            return;
        }
        try {
            enrollAudioToUser(to, WavReader.readPcm16k(wav));
        } catch (IOException e) {
            Log.w(TAG, "voice evidence enroll failed: " + e);
        }
    }

    /**
     * 把转写里的一句话归给某人（可能是刚新建、还不在 mUsers 里的人）：没认出是谁的灰色句子，
     * 或者认错了人、长按改归属的句子。还没交给 AI 的话，之后会按这个人提取事项。
     * 指认即学习：时间轴窗口里还能切出这句话的音频（≥3 秒）就顺带注册给该用户。
     */
    public void assignLine(long lineId, long startMs, long endMs, long userId) {
        // 在入队时就阻止声纹结果，避免已排队的回调抢在手动指认任务前产生副作用。
        final long manualRevision;
        synchronized (ServiceState.class) {
            ServiceState.blockVoiceprint(lineId);
            manualRevision = mExtractionState.pending(startMs, endMs);
        }
        proc(() -> {
            if (!mUsers.containsKey(userId)) {
                loadUsers();
            }
            UserInfo info = mUsers.get(userId);
            synchronized (ServiceState.class) {
                if (sInstance != this || !mExtractionState.isCurrent(startMs, endMs, manualRevision)) return;
                Long utteranceId = mPendingVoiceprints.remove(lineId);
                if (info != null) {
                    if (utteranceId != null) {
                        mDb.utteranceDao().assignUser(Collections.singletonList(utteranceId), userId);
                    } else {
                        mDb.utteranceDao().assignByTime(startMs, endMs, userId);
                    }
                    ServiceState.relabelLine(lineId, info.name, userId, info.color);
                    // 手动指认是最强的归属结论，刷新粘性锚点让随后的短句跟随它。
                    mLastVoiceUserId = userId;
                    mLastVoiceAtMs = endMs;
                }
                ServiceState.setPendingVoiceprints(!mPendingVoiceprints.isEmpty());
                if (mExtractionState.ready(startMs, endMs, manualRevision)) {
                    for (Utterance row : mDb.utteranceDao().getExact(startMs, endMs)) {
                        mExtraction.enqueueSentence(row);
                    }
                    mExtraction.retryPending();
                }
            }
            refreshLatestSpeaker(lineId);
            if (mVoiceprint != null && mTimeline != null) {
                long startB = mTimeline.globalByteAtWallMs(startMs);
                long endB = mTimeline.globalByteAtWallMs(endMs);
                if (startB >= 0 && endB > startB) {
                    synchronized (ServiceState.class) {
                        if (mExtractionState.isCurrent(startMs, endMs, manualRevision)) {
                            enrollAudioToUser(userId, mTimeline.cutGlobal(startB, endB),
                                    () -> mExtractionState.isCurrent(startMs, endMs, manualRevision));
                        }
                    }
                }
            }
            // 指认即学习：新特征异步入库有延迟，宽限期内未命中的句子跟随该用户，不再建陌生人。
            mLearnUserId = userId;
            mLearnUntilMs = System.currentTimeMillis() + LEARN_GRACE_MS;
        });
    }

    /** 清除全部聊天内容（转写和待识别队列），用户和事项保留。 */
    public void clearAllChats() {
        proc(() -> {
            // 清空后使所有尚未返回的声纹结果失效。
            synchronized (ServiceState.class) {
                mPendingVoiceprints.clear();
                mExtractionState.clear();
                mConversations.clear();
                mExtraction.clearQueue();
                mDb.utteranceDao().deleteAll();
                ServiceState.clearLines();
                ServiceState.setPendingVoiceprints(false);
            }
        });
    }

    /** 主界面点 AI 状态标签、「更多」里的“让 AI 立即识别”、灵动岛“立即重试”：把所有还没交给 AI 的内容送去识别。 */
    public void extractAllNotExtracted() {
        proc(this::extractPending);
    }

    /** Manual retry and startup recovery enqueue each ready sentence, never whole sessions. */
    private void extractPending() {
        for (Utterance row : mDb.utteranceDao().getUnfinished()) {
            mExtraction.enqueueSentence(row);
        }
        mExtraction.retryPending();
    }

    private void queueSentence(long utteranceId, long startMs, long endMs) {
        mExtractionState.ready(startMs, endMs);
        List<Utterance> rows = mDb.utteranceDao().getByIds(Collections.singletonList(utteranceId));
        if (!rows.isEmpty()) mExtraction.enqueueSentence(rows.get(0));
    }

    // ---------------------------------------------------------------- 初始化（处理线程）

    /** 录音无法进行（模型缺失、没有权限、麦克风打不开）：界面和灵动岛胶囊同时显示出错。 */
    private void fail(String message) {
        ServiceState.setError(message);
        mMain.post(() -> mLive.setRecordingError(message));
    }

    private void initEngine() {
        mTimeline = new PcmTimeline(90);
        mVoiceprint = new VoiceprintLibrary(Settings.getSpeakerThreshold(this));
        mStream = new StepAsrStreamClient(mTimeline, mCloudListener);
        loadUsers();
        long now = System.currentTimeMillis();
        for (User u : mDb.userDao().getAll()) {
            if (!u.isOwner && now - u.lastSeenAt < ConversationManager.SESSION_TIMEOUT_MS) {
                mConversations.restore(u.id, u.lastSeenAt);
            }
        }
        mDb.utteranceDao().deleteBefore(now - UTTERANCE_RETENTION_MS);
        restoreLines();
        extractPending();
        if (!mStream.hasApiKey() || !mVoiceprint.hasCredentials()) {
            fail("未配置 StepFun 或讯飞密钥（local.properties）");
            return;
        }
        if (!Settings.isXfGroupReady(this)) {
            mVoiceprint.ensureGroup((ok, err) -> {
                if (err != null) {
                    Log.w(TAG, "ensureGroup: " + err);
                } else if (Boolean.TRUE.equals(ok)) {
                    Settings.setXfGroupReady(RecordingService.this, true);
                }
            });
        }
        mMain.post(() -> {
            mReady = true;
            if (mWantRecording) {
                startAudio();
            } else {
                ServiceState.setStatus(ServiceState.Status.PAUSED);
                updateStatusNotification();
            }
        });
    }

    /** 云端流式识别回调（OkHttp 线程）：字幕直接上屏，整句定稿转处理线程。 */
    private final StepAsrStreamClient.Listener mCloudListener = new StepAsrStreamClient.Listener() {
        @Override
        public void onPartial(String text) {
            if (mLiveCaption && text != null && !text.isEmpty()) {
                ServiceState.setPartial(text);
            }
        }

        @Override
        public void onSentence(String transcript, long startByte, long endByte) {
            proc(() -> handleSentence(transcript, startByte, endByte));
        }

        @Override
        public void onSpeechSeconds(long speechMs) {
        }

        @Override
        public void onConnectionChange(boolean connected) {
            if (!connected && mRecording) {
                ServiceState.setPartial("⚠ 网络断开，转写重连中…");
            }
        }
    };

    private void loadUsers() {
        List<User> users = mDb.userDao().getAll();
        if (mVoiceprint != null) {
            mVoiceprint.loadUsers(users);
        }
        mUsers.clear();
        for (User u : users) {
            mUsers.put(u.id, new UserInfo(displaySpeakerName(u), UserColors.orDefault(u.color, u.id),
                    u.isOwner, u.isStranger));
        }
    }

    /** 命名（rename 会清掉 isStranger）前显示“未知”，命名后显示真名。 */
    private String displaySpeakerName(User u) {
        return u.isStranger ? getString(R.string.speaker_unknown) : u.name;
    }

    /** 服务重启后从数据库恢复最近的转写，界面不至于一片空白。 */
    private void restoreLines() {
        ServiceState.clearLines();
        for (Utterance u : mDb.utteranceDao().getLatest(RESTORE_LINES)) {
            UserInfo info = u.userId == null ? null : mUsers.get(u.userId);
            if (info != null) {
                ServiceState.addLine(u.startTime, u.endTime, info.name, u.text, u.userId, info.color, u.aiState);
            } else {
                ServiceState.addLine(u.startTime, u.endTime, getString(R.string.speaker_unknown), u.text,
                        TranscriptLine.NO_USER, TranscriptLine.COLOR_UNKNOWN, u.aiState);
            }
        }
    }

    /** 某个用户最近声音片段的 WAV 文件，改名时循环播放。 */
    public static File voiceFile(android.content.Context context, long userId) {
        File dir = new File(context.getFilesDir(), "voices");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, userId + ".wav");
    }

    /** 保留该用户最近约 15 秒的语音并写成 WAV（字节 PCM，300ms 静音间隔拼接）。 */
    private void rememberVoice(long userId, byte[] pcm) {
        ArrayDeque<byte[]> clips = mVoices.get(userId);
        if (clips == null) {
            clips = new ArrayDeque<>();
            mVoices.put(userId, clips);
        }
        clips.addLast(pcm);
        int total = 0;
        for (byte[] c : clips) {
            total += c.length;
        }
        while (total > VOICE_KEEP_SAMPLES * 2 && clips.size() > 1) {
            total -= clips.removeFirst().length;
        }
        int gap = ModelManager.SAMPLE_RATE * 3 / 10 * 2;
        byte[] out = new byte[total + gap * (clips.size() - 1)];
        int pos = 0;
        for (byte[] c : clips) {
            System.arraycopy(c, 0, out, pos, c.length);
            pos += c.length + gap;
        }
        try {
            WavWriter.write(voiceFile(this, userId), toFloats(out));
        } catch (IOException e) {
            Log.w(TAG, "write voice failed", e);
        }
    }

    private static float[] toFloats(byte[] pcm) {
        float[] out = new float[pcm.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = ((short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8))) / 32768f;
        }
        return out;
    }

    // ---------------------------------------------------------------- 采集（主线程 + audio 线程）

    private void startAudio() {
        if (!mReady || mRecording) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail(getString(R.string.permission_denied));
            return;
        }
        int minBuf = AudioRecord.getMinBufferSize(ModelManager.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord record;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    ModelManager.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuf, FRAME_SAMPLES * 2 * 4));
        } catch (IllegalArgumentException | SecurityException e) {
            Log.e(TAG, "AudioRecord create failed", e);
            fail(getString(R.string.mic_open_failed));
            return;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            fail(getString(R.string.mic_open_failed));
            return;
        }
        mRecord = record;
        record.startRecording();
        mRecording = true;
        mStream.start();
        mAudioThread = new Thread(this::audioLoop, "audio");
        mAudioThread.start();
        mWakeLock.acquire();
        ServiceState.setStatus(ServiceState.Status.RECORDING);
        updateStatusNotification();
    }

    private void stopAudio() {
        if (!mRecording) {
            return;
        }
        mRecording = false;
        try {
            mAudioThread.join(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        mRecord.stop();
        mRecord.release();
        mRecord = null;
        mAudioThread = null;
        mStream.stop();
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
    }

    private boolean isSuppressed() {
        return mPlaybackActive || System.currentTimeMillis() < mSuppressUntilMs;
    }

    private void audioLoop() {
        short[] buf = new short[FRAME_SAMPLES];
        while (mRecording) {
            int n = mRecord.read(buf, 0, buf.length);
            if (n < 0) {
                Log.e(TAG, "AudioRecord.read error " + n);
                break;
            }
            if (n == 0) {
                continue;
            }
            if (isSuppressed()) {
                // 改名对话框回放期间麦克风录到扬声器：整帧丢弃，不进云端时间轴。
                continue;
            }
            byte[] pcm = new byte[n * 2];
            for (int i = 0; i < n; i++) {
                pcm[2 * i] = (byte) buf[i];
                pcm[2 * i + 1] = (byte) (buf[i] >> 8);
            }
            mStream.acceptFrame(pcm);
        }
    }

    // ---------------------------------------------------------------- 识别与归属（处理线程）

    /**
     * 云端整句定稿：每句先待识别，只送本句 PCM。证据按句长分层——命中永远优先；
     * ≥{@link #STRANGER_MIN_MS} 的未命中有信息量，走陌生人建档；更短的未命中（含检索
     * 失败）素材常打不到阈值，跟随上下文归属（学习宽限 > 粘性锚点），都没有才落「未知」。
     */
    private void handleSentence(String transcript, long startByte, long endByte) {
        if (sInstance != this) {
            return;
        }
        String text = ChineseTextFilter.apply(transcript);
        if (!ChineseTextFilter.hasContent(text)) {
            return;
        }
        long startMs = mTimeline.wallMsAtGlobal(startByte);
        long endMs = Math.max(startMs + 500, mTimeline.wallMsAtGlobal(endByte));
        long durationMs = (endByte - startByte) * 1000 / PcmTimeline.BYTES_PER_SEC;
        final long utteranceId;
        final long lineId;
        synchronized (ServiceState.class) {
            mExtractionState.pending(startMs, endMs);
            ServiceState.setPendingVoiceprints(true);
            utteranceId = saveUtterance(null, text, startMs, endMs);
            lineId = displaySentence(text, startMs, endMs);
            mPendingVoiceprints.put(lineId, utteranceId);
        }
        byte[] pcm = mTimeline.cutGlobal(startByte, endByte);
        if (mVoiceprint == null || pcm == null || durationMs < SHORT_SEARCH_MIN_MS) {
            finishSentence(lineId, startMs, endMs, durationMs, null,
                    durationMs < STRANGER_MIN_MS ? followShortMiss(startMs, null) : null);
            return;
        }
        mVoiceprint.search(pcm, (match, err) -> proc(() -> {
            synchronized (ServiceState.class) {
                if (sInstance == this && ServiceState.isVoiceprintBlocked(lineId)) {
                    // 手动指认已入队，等其落库后再解除门禁。
                    return;
                }
                if (sInstance != this || !mPendingVoiceprints.containsKey(lineId)
                        || !ServiceState.canApplyVoiceprint(lineId)) {
                    Long dropped = mPendingVoiceprints.remove(lineId);
                    if (sInstance == this && dropped != null) {
                        // UI history may have evicted the line while search was in flight.
                        queueSentence(dropped, startMs, endMs);
                    }
                    ServiceState.setPendingVoiceprints(!mPendingVoiceprints.isEmpty());
                    return;
                }
                if (err != null) {
                    Log.w(TAG, "voiceprint search failed: " + err);
                    finishSentence(lineId, startMs, endMs, durationMs, null,
                            durationMs < STRANGER_MIN_MS ? followShortMiss(startMs, pcm) : null);
                    return;
                }
                Long finalId = matchedUser(match);
                if (finalId == null) {
                    finalId = durationMs >= STRANGER_MIN_MS
                            ? strangerFor(text, startMs, endMs, pcm)
                            : followShortMiss(startMs, pcm);
                }
                finishSentence(lineId, startMs, endMs, durationMs, pcm, finalId);
            }
        }));
    }

    /** 身份来自有效声纹、用户明确指认或短句上下文跟随；未命中的长句由调用方走陌生人建档。 */
    private Long matchedUser(VoiceprintLibrary.Match match) {
        return match != null && mUsers.containsKey(match.userId) ? match.userId : null;
    }

    /**
     * 短句（&lt;{@link #STRANGER_MIN_MS}）未命中的兜底（处理线程）：学习宽限期最权威——
     * 刚指认/合并的人的特征还在讯飞异步入库，检索不到不代表不认识，句子跟随学习目标并
     * 攒素材；否则跟随粘性窗口内最近一次确认归属的说话人。宽限跟随才算学习素材——粘性
     * 跟随是弱证据，若把跟随句注册给锚点用户，他人插话会污染该用户的声纹特征。
     */
    private Long followShortMiss(long startMs, byte[] pcm) {
        Long follow = followShortMiss(mLearnUserId, mLearnUntilMs, System.currentTimeMillis(),
                mLastVoiceUserId, mLastVoiceAtMs, startMs, mUsers.keySet());
        if (follow != null && follow == mLearnUserId && pcm != null) {
            enrollAudioAuto(follow, pcm);
        }
        return follow;
    }

    /** 纯决策：宽限跟随 &gt; 粘性跟随 &gt; 未知；目标必须仍是已知用户。 */
    static Long followShortMiss(long learnUserId, long learnUntilMs, long now,
                                long lastVoiceUserId, long lastVoiceAtMs, long startMs,
                                Set<Long> knownUserIds) {
        if (learnUserId != TranscriptLine.NO_USER && learnUserId != 0
                && knownUserIds.contains(learnUserId) && now < learnUntilMs) {
            return learnUserId;
        }
        if (lastVoiceUserId != TranscriptLine.NO_USER && lastVoiceUserId != 0
                && knownUserIds.contains(lastVoiceUserId)
                && startMs - lastVoiceAtMs <= SPEAKER_STICKY_MS) {
            return lastVoiceUserId;
        }
        return null;
    }

    /**
     * 声纹未命中的长句归陌生人（处理线程）：学习宽限期内跟随刚指认/合并的人——新特征还在
     * 讯飞异步入库，检索不到不代表不认识；否则沿用去重窗口内的陌生人，窗口外建档新陌生人
     * 并弹命名窗。同一陌生人的句子音频在这里累积，攒够 3 秒拼接注册成云端声纹——单句常不足
     * 讯飞的注册下限，攒着才能让陌生人下次开口就被认出来。
     */
    private Long strangerFor(String text, long startMs, long endMs, byte[] pcm) {
        if (mLearnUserId != TranscriptLine.NO_USER && mUsers.containsKey(mLearnUserId)
                && System.currentTimeMillis() < mLearnUntilMs) {
            enrollAudioAuto(mLearnUserId, pcm);
            return mLearnUserId;
        }
        long strangerId = mLastStrangerId;
        if (strangerId == TranscriptLine.NO_USER || !mUsers.containsKey(strangerId)
                || startMs - mLastStrangerAtMs > STRANGER_WINDOW_MS) {
            strangerId = createStranger(text, endMs);
        }
        mLastStrangerAtMs = endMs;
        if (pcm != null) {
            if (mStrangerVoiceId != strangerId) {
                mStrangerVoice.clear();
                mStrangerVoiceId = strangerId;
            }
            mStrangerVoice.add(pcm);
            int total = 0;
            for (byte[] clip : mStrangerVoice) {
                total += clip.length;
            }
            if (total >= PcmTimeline.BYTES_PER_SEC * 3) {
                byte[] joined = new byte[total];
                int pos = 0;
                for (byte[] clip : mStrangerVoice) {
                    System.arraycopy(clip, 0, joined, pos, clip.length);
                    pos += clip.length;
                }
                mStrangerVoice.clear();
                enrollAudioAuto(strangerId, joined);
            }
        }
        return strangerId;
    }

    /** 句子一律先待识别，返回界面行 id。 */
    private long displaySentence(String text, long startMs, long endMs) {
        String speaker = getString(R.string.speaker_pending);
        mMain.post(() -> {
            if (sInstance == this) mLive.setLatestLine(speaker, TranscriptLine.COLOR_UNKNOWN, text);
        });
        long lineId = ServiceState.addLine(startMs, endMs, speaker, text, TranscriptLine.NO_USER,
                TranscriptLine.COLOR_UNKNOWN, Utterance.AI_NONE);
        Runnable resolve = () -> {
            if (ServiceState.resolvePendingLine(lineId, speaker, getString(R.string.speaker_unknown))) {
                refreshLatestSpeaker(lineId);
            }
        };
        try {
            mProc.schedule(resolve, 30, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            resolve.run();
        }
        return lineId;
    }

    /** 只消费仍有效的逐句回调；手动指认、清空或服务销毁后不再改库或产生副作用。 */
    private void finishSentence(long lineId, long startMs, long endMs, long durationMs,
                                byte[] pcm, Long finalUserId) {
        synchronized (ServiceState.class) {
            if (ServiceState.isVoiceprintBlocked(lineId)) {
                return;
            }
            try {
                Long utteranceId = mPendingVoiceprints.remove(lineId);
                if (sInstance != this || utteranceId == null || !ServiceState.canApplyVoiceprint(lineId)) {
                    return;
                }
                if (finalUserId == null || !mUsers.containsKey(finalUserId)) {
                    if (ServiceState.resolvePendingLine(lineId, getString(R.string.speaker_pending),
                            getString(R.string.speaker_unknown))) {
                        refreshLatestSpeaker(lineId);
                    }
                    queueSentence(utteranceId, startMs, endMs);
                    return;
                }
                long uid = finalUserId;
                UserInfo info = mUsers.get(uid);
                if (!ServiceState.applyVoiceprint(lineId, info.name, uid, info.color)) {
                    return;
                }
                // 确认归属（命中、跟随或建档）即刷新粘性锚点，短句未命中时跟随它。
                mLastVoiceUserId = uid;
                mLastVoiceAtMs = endMs;
                mDb.utteranceDao().assignUser(Collections.singletonList(utteranceId), uid);
                refreshLatestSpeaker(lineId);
                mDb.userDao().touch(uid, endMs);
                queueSentence(utteranceId, startMs, endMs);
                if (!info.owner) {
                    mConversations.onUtterance(uid, startMs, endMs);
                    if (durationMs >= MIN_DURATION_MS && pcm != null) {
                        rememberVoice(uid, pcm);
                    }
                }
            } finally {
                ServiceState.setPendingVoiceprints(!mPendingVoiceprints.isEmpty());
            }
        }
    }

    private void refreshLatestSpeaker(long lineId) {
        mMain.post(() -> {
            TranscriptLine latest = ServiceState.latestLine();
            if (sInstance == this && latest != null && latest.id == lineId) {
                mLive.setLatestLine(latest.speaker, latest.color, latest.text);
            }
        });
    }

    /**
     * 没认出来的声音直接建档为“陌生人N”。主界面在前台时直接弹出命名窗口（填名字、颜色或
     * 关联到已有用户）；不在时灵动岛弹出“以后再说 / 命名”卡片，回到主界面时还没处理的话再弹命名窗口。
     */
    private long createStranger(String text, long atMs) {
        String name = nextStrangerName();
        User user = new User();
        user.name = name;
        user.isStranger = true;
        user.createdAt = System.currentTimeMillis();
        user.lastSeenAt = atMs;
        user.color = UserColors.pickUnused(mDb.userDao().getAllColors());
        long userId = mDb.userDao().insert(user);
        mUsers.put(userId, new UserInfo(getString(R.string.speaker_unknown), user.color, false, true));
        mLastStrangerId = userId;
        int color = user.color;
        mMain.post(() -> {
            ServiceState.requestStrangerPrompt(userId);
            if (!ServiceState.isUiVisible()) {
                mLive.showStranger(userId, name, color, text);
            }
        });
        return userId;
    }

    /** 仅手动指认/合并注册本次明确选择的语音；至少 3 秒，失败不重试。 */
    private void enrollAudioToUser(long userId, byte[] pcm) {
        enrollAudioToUser(userId, pcm, () -> true);
    }

    /**
     * 自动注册（陌生人建档、学习宽限跟随）：同样至少 3 秒、失败不重试，但每人最多
     * {@link #AUTO_ENROLL_FEATURE_CAP} 条，防止陌生人误建档把云端特征灌爆。
     */
    private void enrollAudioAuto(long userId, byte[] pcm) {
        if (mVoiceprint == null || pcm == null || pcm.length < PcmTimeline.BYTES_PER_SEC * 3) {
            return;
        }
        User user = mDb.userDao().get(userId);
        if (user == null
                || VoiceprintLibrary.parseFeatureIds(user.featureIds).size() >= AUTO_ENROLL_FEATURE_CAP) {
            return;
        }
        mVoiceprint.enroll(userId, user.name, user.isOwner, pcm, (featureId, err) -> proc(() -> {
            if (err != null || featureId == null) {
                Log.w(TAG, "auto enroll failed: " + err);
                return;
            }
            synchronized (ServiceState.class) {
                User u = mDb.userDao().get(userId);
                if (u != null) {
                    u.featureIds = VoiceprintLibrary.appendFeatureId(u.featureIds, featureId);
                    mDb.userDao().update(u);
                }
            }
        }));
    }

    private void enrollAudioToUser(long userId, byte[] pcm, java.util.function.BooleanSupplier valid) {
        if (!valid.getAsBoolean()) return;
        if (mVoiceprint == null || pcm == null || pcm.length < PcmTimeline.BYTES_PER_SEC * 3) {
            return;
        }
        User user = mDb.userDao().get(userId);
        if (user == null) {
            return;
        }
        mVoiceprint.enroll(userId, user.name, user.isOwner, pcm, (featureId, err) -> proc(() -> {
            if (err != null || featureId == null) {
                Log.w(TAG, "manual enroll failed: " + err);
                return;
            }
            synchronized (ServiceState.class) {
                if (!valid.getAsBoolean()) return;
                User u = mDb.userDao().get(userId);
                if (u != null) {
                    u.featureIds = VoiceprintLibrary.appendFeatureId(u.featureIds, featureId);
                    mDb.userDao().update(u);
                }
            }
        }));
    }

    /** 从 1 开始找最小的、还没有人叫的“陌生人N”，删掉或改名后空出来的编号会被重新用上。 */
    private String nextStrangerName() {
        Set<String> used = new HashSet<>(mDb.userDao().getAllNames());
        for (int n = 1; ; n++) {
            String name = getString(R.string.stranger_name, n);
            if (!used.contains(name)) {
                return name;
            }
        }
    }

    private long saveUtterance(Long userId, String text, long startMs, long endMs) {
        Utterance u = new Utterance();
        u.userId = userId;
        u.text = text;
        u.startTime = startMs;
        u.endTime = endMs;
        return mDb.utteranceDao().insert(u);
    }

    // ---------------------------------------------------------------- 会话回调（处理线程）

    @Override
    public void onConversationStarted(long userId) {
        List<FactCard> facts = mDb.userFactDao().getPendingCards(userId);
        UserInfo info = mUsers.get(userId);
        if (facts.isEmpty() || info == null) {
            return;
        }
        boolean uiVisible = ServiceState.isUiVisible();
        int color = info.color;
        mMain.post(() -> {
            // 在本应用里：全屏变暗弹出全部卡片；不在本应用里（或悬浮窗弹不出来）：灵动岛中卡。
            // 眼镜不再跟着会话提醒推：新提取的事项已经即时推过“新增”页，这里再推同一个人
            // 的全部未完成事项会把同一批内容在镜片上显示两遍。
            if (!uiVisible || !mOverlay.show(userId, info.name, facts)) {
                mLive.showReminder(userId, info.name, color, facts);
            }
        });
    }

    @Override
    public void onConversationSegment(long userId, long startMs, long endMs) {
        // Sentence finalization owns extraction; session callbacks only maintain reminders.
    }

    /** 从灵动岛提醒点“查看”回到应用后调用：全屏弹出这个人的全部卡片。 */
    public void showFocus(long userId) {
        mMain.post(() -> mLive.cancelReminder(userId));
        proc(() -> {
            List<FactCard> facts = mDb.userFactDao().getPendingCards(userId);
            UserInfo info = mUsers.get(userId);
            if (facts.isEmpty() || info == null) {
                return;
            }
            int color = info.color;
            mMain.post(() -> {
                if (!mOverlay.show(userId, info.name, facts)) {
                    mLive.showReminder(userId, info.name, color, facts);
                }
            });
        });
    }

    /**
     * AI 提取到新事项（extraction / screenshot / notification 线程）：眼镜已连接时立刻推一页“新增”到镜片上，
     * 与手机是否在前台无关；不在本应用里时再补灵动岛小卡，在应用里上方卡片区会直接出现。
     * 从截图昵称新建的用户可能还没进声纹库，查不到时回数据库取名字和颜色。
     */
    private void onFactsAdded(long userId, List<UserFact> facts) {
        boolean uiVisible = ServiceState.isUiVisible();
        proc(() -> {
            UserInfo info = mUsers.get(userId);
            if (info == null) {
                User u = mDb.userDao().get(userId);
                if (u == null) {
                    return;
                }
                info = new UserInfo(u.name, UserColors.orDefault(u.color, u.id), u.isOwner, u.isStranger);
            }
            UserInfo shown = info;
            if (GlassesManager.isReady() && !facts.isEmpty()) {
                List<FactCard> cards = new ArrayList<>(facts.size());
                for (UserFact f : facts) {
                    FactCard card = new FactCard();
                    card.fact = f;
                    card.userName = shown.name;
                    card.userColor = shown.color;
                    cards.add(card);
                }
                GlassesManager.showNew(shown.name, cards);
            }
            if (!uiVisible) {
                mMain.post(() -> mLive.showNewFacts(shown.name, shown.color, facts));
            }
        });
    }

    public LiveNotifier getLive() {
        return mLive;
    }

    /** 后台线程弹个 Toast（调试触发用）。 */
    public void toastFromBackground(String message) {
        mMain.post(() -> android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show());
    }

    // ---------------------------------------------------------------- 常驻通知（灵动岛录音卡片）

    private void updateStatusNotification() {
        mLive.setRecordingState(mReady, mRecording);
    }
}
