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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
 *   <li>处理线程（mProc）：句子归属（按说话段批量做声纹）、陌生人建档、会话管理、数据库写入；
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
    /** 有未识别内容时自动交给 AI 的间隔。 */
    private static final long AUTO_EXTRACT_MINUTES = 1;
    private static final int VOICE_KEEP_SAMPLES = ModelManager.SAMPLE_RATE * 15;

    private static final int FRAME_SAMPLES = ModelManager.SAMPLE_RATE / 10;
    /** 短于这个时长的句子不进说话段，改为单独送一次 1:N 检索。 */
    public static final long MIN_DURATION_MS = 1500;
    /** 短句单独送检的下限：讯飞口径有效语音需 >0.5 秒，再短送了也只有空结果。 */
    private static final long SHORT_SEARCH_MIN_MS = 500;
    /** 短句沿用上一位说话人的最大间隔。 */
    private static final long SHORT_UTTERANCE_FOLLOW_MS = 5000;
    /** 声纹没命中时，多少秒内的连续未命中句子归给同一位新陌生人，避免每句都建一个。 */
    private static final long STRANGER_WINDOW_MS = 8000;
    /** 声纹按“说话段”检索：句间静默超过该值即收段（讯飛建议检索音频 3~5 秒起，整段拼接送一次）。 */
    private static final long TURN_GAP_MS = 2000;
    /** 说话段内累计音频上限，到点立刻收段（讯飞单请求上限 4MB base64 ≈ 90 秒，15 秒很安全）。 */
    private static final long TURN_MAX_MS = 15000;
    /** 自动学习（陌生人建档）给单个用户保留的云端声纹条数上限。 */
    private static final int AUTO_ENROLL_FEATURE_CAP = 3;
    /**
     * 手动指认/合并的学习宽限期：讯飞注册是异步入库的（实测延迟可超过十几秒），刚学的特征
     * 在窗口内检索不到；此期间段级未命中跟随学习目标而不是再建陌生人，否则"合并后一说话
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
    private long mLastSpeakerId = TranscriptLine.NO_USER;
    private long mLastSpeakerEndMs;
    /** 最近一次声纹未命中建档的陌生人（去重窗口内归同一人）。 */
    private long mLastStrangerId = TranscriptLine.NO_USER;
    private long mLastStrangerAtMs;
    /** 学习宽限期：最近一次手动指认/合并的目标用户与截止时刻（处理线程）。 */
    private long mLearnUserId = TranscriptLine.NO_USER;
    private long mLearnUntilMs;

    /** 说话段里一句已上屏、等段收口后统一做声纹归属的句子。 */
    private static class TurnSentence {
        final long lineId;
        final String text;
        final long startMs;
        final long endMs;
        final long durationMs;
        final Long provisional;
        final byte[] pcm;

        TurnSentence(long lineId, String text, long startMs, long endMs, long durationMs,
                     Long provisional, byte[] pcm) {
            this.lineId = lineId;
            this.text = text;
            this.startMs = startMs;
            this.endMs = endMs;
            this.durationMs = durationMs;
            this.provisional = provisional;
            this.pcm = pcm;
        }
    }

    /** 说话段缓冲（处理线程）：句子定稿先临时归属上屏，段收口后整段送声纹。 */
    private final List<TurnSentence> mTurn = new ArrayList<>();
    private long mTurnBytes;

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
        mExtraction = new ExtractionWorker(this, mDb, new ExtractionWorker.Listener() {
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
            public void onProgress(NotificationWorker.Stage stage, String sender, int count) {
                ServiceState.setNotifProgress(new ServiceState.NotifProgress(stage.ordinal(), sender, count));
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
        // 配对过 Even G2 眼镜且开关打开时，在后台自动重连；连接本身由 GlassesManager 持有，服务退出不断开。
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
        mProc.scheduleWithFixedDelay(this::extractPending, AUTO_EXTRACT_MINUTES, AUTO_EXTRACT_MINUTES,
                TimeUnit.MINUTES);

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
            flushTurn();
            mConversations.endAll();
            mExtraction.shutdown();
            if (mStream != null) {
                mStream.stop();
            }
            if (mVoiceprint != null) {
                mVoiceprint.shutdown();
            }
        });
        mProc.shutdown();
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
        // 停录即收口说话段，最后一段话也要做声纹归属。
        proc(this::flushTurn);
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
            if (mLastSpeakerId == from) {
                mLastSpeakerId = to;
            }
            if (mLastStrangerId == from) {
                mLastStrangerId = to;
            }
            // 新特征（划转+语音证据注册）还在讯飞异步入库：宽限期内未命中的段跟随 to。
            mLearnUserId = to;
            mLearnUntilMs = System.currentTimeMillis() + LEARN_GRACE_MS;
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
            enrollAudioToUser(to, WavReader.readPcm16k(wav), true);
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
        proc(() -> {
            mDb.utteranceDao().assignByTime(startMs, endMs, userId);
            if (!mUsers.containsKey(userId) && mVoiceprint != null) {
                loadUsers();
            }
            if (mVoiceprint != null && mTimeline != null) {
                long startB = mTimeline.globalByteAtWallMs(startMs);
                long endB = mTimeline.globalByteAtWallMs(endMs);
                if (startB >= 0 && endB > startB) {
                    enrollAudioToUser(userId, mTimeline.cutGlobal(startB, endB), true);
                }
            }
            // 指认即学习：新特征异步入库有延迟，宽限期内未命中的段跟随该用户，不再建陌生人。
            mLearnUserId = userId;
            mLearnUntilMs = System.currentTimeMillis() + LEARN_GRACE_MS;
            UserInfo info = mUsers.get(userId);
            if (info != null) {
                ServiceState.relabelLine(lineId, info.name, userId, info.color);
            }
        });
    }

    /** 清除全部聊天内容（转写和待识别队列），用户和事项保留。 */
    public void clearAllChats() {
        proc(() -> {
            // 行马上要被清掉，说话段不再做归属。
            mTurn.clear();
            mTurnBytes = 0;
            mConversations.clear();
            mExtraction.clearQueue();
            mDb.utteranceDao().deleteAll();
            ServiceState.clearLines();
        });
    }

    /** 主界面点 AI 状态标签、「更多」里的“让 AI 立即识别”、灵动岛“立即重试”：把所有还没交给 AI 的内容送去识别。 */
    public void extractAllNotExtracted() {
        proc(this::extractPending);
    }

    /**
     * 每 {@link #AUTO_EXTRACT_MINUTES} 分钟或手动触发（处理线程）：进行中的会话先把已说的部分交给 AI（会话继续）；
     * 其余未识别的内容按聊天块（间隔超过会话超时即分块）送去识别，
     * 每块按其中的非机主用户分别提取，只有机主或无法归属的块记在机主名下。
     */
    private void extractPending() {
        mConversations.checkpoint(System.currentTimeMillis());
        List<Utterance> all = mDb.utteranceDao().getNotExtracted();
        User owner = mDb.userDao().getOwner();
        int i = 0;
        while (i < all.size()) {
            int j = i;
            long blockEnd = all.get(i).endTime;
            while (j + 1 < all.size()
                    && all.get(j + 1).startTime - blockEnd <= ConversationManager.SESSION_TIMEOUT_MS) {
                j++;
                blockEnd = Math.max(blockEnd, all.get(j).endTime);
            }
            long blockStart = all.get(i).startTime;
            Set<Long> userIds = new LinkedHashSet<>();
            for (int k = i; k <= j; k++) {
                Long uid = all.get(k).userId;
                UserInfo info = uid == null ? null : mUsers.get(uid);
                if (info != null && !info.owner) {
                    userIds.add(uid);
                }
            }
            if (userIds.isEmpty() && owner != null) {
                userIds.add(owner.id);
            }
            for (long uid : userIds) {
                mExtraction.enqueue(uid, blockStart, blockEnd);
            }
            i = j + 1;
        }
        mExtraction.retryPending();
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
        if (!mStream.hasApiKey() || !mVoiceprint.hasCredentials()) {
            fail("未配置 StepFun 或讯飞密钥（local.properties）");
            return;
        }
        if (!Settings.isXfGroupReady(this)) {
            mVoiceprint.ensureGroup((ok, err) -> {
                if (err != null) {
                    Log.w(TAG, "ensureGroup: " + err);
                } else {
                    Settings.setXfGroupReady(RecordingService.this, true);
                }
            });
        }
        loadUsers();
        long now = System.currentTimeMillis();
        for (User u : mDb.userDao().getAll()) {
            if (!u.isOwner && now - u.lastSeenAt < ConversationManager.SESSION_TIMEOUT_MS) {
                mConversations.restore(u.id, u.lastSeenAt);
            }
        }
        mDb.utteranceDao().deleteBefore(now - UTTERANCE_RETENTION_MS);
        restoreLines();
        mExtraction.retryPending();

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
     * 云端整句定稿：句子先按兜底策略（沿用上一位说话人/“待识别”灰色）上屏入库；达到
     * {@link #MIN_DURATION_MS} 的进入说话段缓冲，段收口（静默超过 {@link #TURN_GAP_MS} 或段内
     * 音频达到 {@link #TURN_MAX_MS}）后把整段拼接的音频送讯飞做一次 1:N 检索，再统一补标段内
     * 所有句子——整段音频比单句长，分数更稳（讯飛建议检索音频 3~5 秒起）。没命中则整段归
     * 陌生人并顺手注册声纹（转写里显示“未知”）。更短的句子走 {@link #searchShortSentence}。
     */
    private void handleSentence(String transcript, long startByte, long endByte) {
        String text = ChineseTextFilter.apply(transcript);
        if (!ChineseTextFilter.hasContent(text)) {
            return;
        }
        long startMs = mTimeline.wallMsAtGlobal(startByte);
        long endMs = Math.max(startMs + 500, mTimeline.wallMsAtGlobal(endByte));
        long durationMs = (endByte - startByte) * 1000 / PcmTimeline.BYTES_PER_SEC;

        Long provisional = null;
        if (mLastSpeakerId != TranscriptLine.NO_USER && mUsers.containsKey(mLastSpeakerId)
                && startMs - mLastSpeakerEndMs < SHORT_UTTERANCE_FOLLOW_MS) {
            provisional = mLastSpeakerId;
        }
        long lineId = displaySentence(provisional, text, startMs, endMs);
        final Long fallback = provisional;

        if (durationMs < MIN_DURATION_MS) {
            searchShortSentence(fallback, lineId, startByte, endByte, startMs, endMs, durationMs);
            return;
        }
        byte[] pcm = mTimeline.cutGlobal(startByte, endByte);
        if (pcm == null) {
            finishSentence(fallback, lineId, startMs, endMs, durationMs, null, fallback);
            return;
        }
        if (!mTurn.isEmpty() && startMs - mTurn.get(mTurn.size() - 1).endMs > TURN_GAP_MS) {
            // 静默了太久：上一段先收口（正常情况下定时器已处理，这里兜底）。
            flushTurn();
        }
        mTurn.add(new TurnSentence(lineId, text, startMs, endMs, durationMs, fallback, pcm));
        mTurnBytes += pcm.length;
        if (mTurnBytes >= TURN_MAX_MS * PcmTimeline.BYTES_PER_SEC / 1000) {
            flushTurn();
        } else {
            scheduleTurnFlush();
        }
    }

    /**
     * 短句（&lt; {@link #MIN_DURATION_MS}）不进说话段——段是整段一个身份，短句混进去只会稀释
     * 整段音频；改为单独送一次 1:N 检索（&ge; {@link #SHORT_SEARCH_MIN_MS} 才送）：命中就用检索
     * 结果覆盖兜底归属（纠正“对方插话的短回应被 5 秒跟随启发式跟成上一个说话人”），没命中或
     * 检索失败维持兜底（跟随/“未知”），最差也只是退回短句不送检时的行为。
     * 分数沿用常规阈值不再收紧：同本人短句分数本就偏低（实测 0.36~0.50），再抬高门槛就永远
     * 不触发；异人常在 0.3 以下，错配风险很小。短句不做陌生人建档（注册素材至少要 3 秒）。
     */
    private void searchShortSentence(Long provisional, long lineId, long startByte, long endByte,
                                     long startMs, long endMs, long durationMs) {
        byte[] pcm = mTimeline.cutGlobal(startByte, endByte);
        if (mVoiceprint == null || pcm == null || durationMs < SHORT_SEARCH_MIN_MS) {
            finishSentence(provisional, lineId, startMs, endMs, durationMs, null, provisional);
            return;
        }
        mVoiceprint.search(pcm, (match, err) -> proc(() -> {
            if (err != null) {
                Log.w(TAG, "short sentence search failed: " + err);
            }
            Long finalId = match != null && mUsers.containsKey(match.userId) ? match.userId : provisional;
            finishSentence(provisional, lineId, startMs, endMs, durationMs, null, finalId);
        }));
    }

    /** 距段尾静默 {@link #TURN_GAP_MS} 后自动收段（定时器兜底，不依赖下一句到来）。 */
    private void scheduleTurnFlush() {
        final long lastEnd = mTurn.get(mTurn.size() - 1).endMs;
        try {
            mProc.schedule(() -> {
                if (!mTurn.isEmpty() && System.currentTimeMillis() - lastEnd >= TURN_GAP_MS - 100) {
                    flushTurn();
                }
            }, TURN_GAP_MS + 200, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // 服务正在销毁：句子的临时归属已是最终状态。
        }
    }

    /**
     * 说话段收口：整段 PCM 送一次 1:N 检索。命中则段内所有句子统一改标；
     * 未命中归陌生人（沿用去重窗口），并用整段音频注册该陌生人——整段通常远超 3 秒，
     * 解决以前“单句太短注册不上、陌生人永远没有声纹”的问题。
     */
    private void flushTurn() {
        if (mTurn.isEmpty() || mVoiceprint == null) {
            return;
        }
        List<TurnSentence> turn = new ArrayList<>(mTurn);
        mTurn.clear();
        mTurnBytes = 0;
        int total = 0;
        for (TurnSentence s : turn) {
            total += s.pcm.length;
        }
        if (total * 1000L / PcmTimeline.BYTES_PER_SEC < MIN_DURATION_MS) {
            return; // 整段还是太短，维持临时归属
        }
        byte[] pcm = new byte[total];
        int pos = 0;
        for (TurnSentence s : turn) {
            System.arraycopy(s.pcm, 0, pcm, pos, s.pcm.length);
            pos += s.pcm.length;
        }
        long firstStart = turn.get(0).startMs;
        long lastEnd = turn.get(turn.size() - 1).endMs;
        String firstText = turn.get(0).text;
        mVoiceprint.search(pcm, (match, err) -> proc(() -> {
            if (err != null) {
                Log.w(TAG, "voiceprint search failed: " + err);
                // 检索不可用且不会重试：维持临时归属，连临时归属都没有的句子落成“未知”。
                for (TurnSentence s : turn) {
                    if (s.provisional == null) {
                        ServiceState.relabelLine(s.lineId, getString(R.string.speaker_unknown),
                                TranscriptLine.NO_USER, TranscriptLine.COLOR_UNKNOWN);
                    }
                }
                return;
            }
            if (match != null && mUsers.containsKey(match.userId)) {
                for (TurnSentence s : turn) {
                    finishSentence(s.provisional, s.lineId, s.startMs, s.endMs, s.durationMs, s.pcm, match.userId);
                }
                return;
            }
            // 没命中。
            // 手动学习宽限期内：刚指认/合并的人，新特征还在讯飞异步入库中检索不到，跟随学习
            // 目标并顺手把这段也注册给他（自动口径，受条数上限约束），不再重复建陌生人。
            if (mLearnUserId != TranscriptLine.NO_USER && mUsers.containsKey(mLearnUserId)
                    && System.currentTimeMillis() < mLearnUntilMs) {
                long learnId = mLearnUserId;
                enrollAudioToUser(learnId, pcm, false);
                for (TurnSentence s : turn) {
                    finishSentence(s.provisional, s.lineId, s.startMs, s.endMs, s.durationMs, s.pcm, learnId);
                }
                return;
            }
            // 窗口外的连续未命中归同一位陌生人，窗口外建档新陌生人。
            long strangerId = mLastStrangerId;
            if (strangerId == TranscriptLine.NO_USER || !mUsers.containsKey(strangerId)
                    || firstStart - mLastStrangerAtMs > STRANGER_WINDOW_MS) {
                strangerId = createStranger(firstText, lastEnd);
            }
            mLastStrangerAtMs = lastEnd;
            enrollAudioToUser(strangerId, pcm, false);
            for (TurnSentence s : turn) {
                finishSentence(s.provisional, s.lineId, s.startMs, s.endMs, s.durationMs, s.pcm, strangerId);
            }
        }));
    }

    /** 句子先以临时归属上屏入库，返回界面行 id（声纹结果回来后按 id 补标）。 */
    private long displaySentence(Long userId, String text, long startMs, long endMs) {
        saveUtterance(userId, text, startMs, endMs);
        if (userId != null) {
            UserInfo info = mUsers.get(userId);
            mMain.post(() -> mLive.setLatestLine(info.name, info.color, text));
            return ServiceState.addLine(startMs, endMs, info.name, text, userId, info.color, Utterance.AI_NONE);
        }
        // 没有可沿用的临时归属：声纹结论没回来之前先标“待识别”，结论落地后统一补标。
        String speaker = getString(R.string.speaker_pending);
        mMain.post(() -> mLive.setLatestLine(speaker, TranscriptLine.COLOR_UNKNOWN, text));
        return ServiceState.addLine(startMs, endMs, speaker, text, TranscriptLine.NO_USER,
                TranscriptLine.COLOR_UNKNOWN, Utterance.AI_NONE);
    }

    /**
     * 声纹结果落地：最终归属与临时归属不同则改库改界面；会话、声音缓存和说话人状态统一更新。
     * finalUserId 为 null 表示声纹不可用（网络失败/句子太短），维持临时归属；连临时归属都没有的
     * 句子此时已确定无法归属，从“待识别”落成“未知”。
     */
    private void finishSentence(Long provisional, long lineId, long startMs, long endMs, long durationMs,
                                byte[] pcm, Long finalUserId) {
        if (finalUserId == null || !mUsers.containsKey(finalUserId)) {
            if (finalUserId == null && provisional == null) {
                ServiceState.relabelLine(lineId, getString(R.string.speaker_unknown),
                        TranscriptLine.NO_USER, TranscriptLine.COLOR_UNKNOWN);
            }
            return;
        }
        long uid = finalUserId;
        UserInfo info = mUsers.get(uid);
        if (provisional == null || provisional != uid) {
            mDb.utteranceDao().assignByTime(startMs, endMs, uid);
            ServiceState.relabelLine(lineId, info.name, uid, info.color);
        }
        mDb.userDao().touch(uid, endMs);
        mLastSpeakerId = uid;
        mLastSpeakerEndMs = endMs;
        if (!info.owner) {
            mConversations.onUtterance(uid, startMs, endMs);
            if (durationMs >= MIN_DURATION_MS && pcm != null) {
                rememberVoice(uid, pcm);
            }
        }
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

    /**
     * 把一段已抓到的 PCM 注册成该用户的云端声纹（陌生人建档、手动纠正学习共用）。
     * 3 秒以上才值得注册；自动学习（陌生人建档）条数有上限，防止特征爆炸。手动指认/合并是
     * 用户给出的强信号，不受该上限约束，只受 {@link VoiceprintLibrary#MAX_FEATURES_PER_USER}
     * 兜底；失败不重试。
     */
    private void enrollAudioToUser(long userId, byte[] pcm, boolean manual) {
        if (mVoiceprint == null || pcm == null || pcm.length < PcmTimeline.BYTES_PER_SEC * 3) {
            return;
        }
        User user = mDb.userDao().get(userId);
        if (user == null || (!manual
                && VoiceprintLibrary.parseFeatureIds(user.featureIds).size() >= AUTO_ENROLL_FEATURE_CAP)) {
            return;
        }
        mVoiceprint.enroll(userId, user.name, user.isOwner, pcm, (featureId, err) -> proc(() -> {
            if (err != null || featureId == null) {
                Log.w(TAG, "auto enroll failed: " + err);
                return;
            }
            User u = mDb.userDao().get(userId);
            if (u != null) {
                u.featureIds = VoiceprintLibrary.appendFeatureId(u.featureIds, featureId);
                mDb.userDao().update(u);
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

    private void saveUtterance(Long userId, String text, long startMs, long endMs) {
        Utterance u = new Utterance();
        u.userId = userId;
        u.text = text;
        u.startTime = startMs;
        u.endTime = endMs;
        mDb.utteranceDao().insert(u);
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
            if (!uiVisible || !mOverlay.show(userId, info.name, facts)) {
                mLive.showReminder(userId, info.name, color, facts);
            }
        });
        // 眼镜已连接时额外推一份到 Even G2 镜片上（渲染和发送在眼镜的工作线程上进行）。
        if (GlassesManager.isReady()) {
            GlassesManager.show(info.name, facts);
        }
    }

    @Override
    public void onConversationSegment(long userId, long startMs, long endMs) {
        mExtraction.enqueue(userId, startMs, endMs);
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
     * AI 提取到新事项（extraction 或 screenshot 线程）：不在本应用里时灵动岛小卡提示，在应用里上方卡片区会直接出现。
     * 从截图昵称新建的用户可能还没进声纹库，查不到时回数据库取名字和颜色。
     */
    private void onFactsAdded(long userId, List<UserFact> facts) {
        if (ServiceState.isUiVisible()) {
            return;
        }
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
            mMain.post(() -> mLive.showNewFacts(shown.name, shown.color, facts));
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
