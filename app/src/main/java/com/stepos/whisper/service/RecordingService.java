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
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;

import com.stepos.whisper.R;
import com.stepos.whisper.Settings;
import com.stepos.whisper.asr.FinalRecognizer;
import com.stepos.whisper.asr.ModelManager;
import com.stepos.whisper.asr.SpeechChunk;
import com.stepos.whisper.asr.StreamingPreview;
import com.stepos.whisper.asr.VadSegmenter;
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
import com.stepos.whisper.screenshot.ScreenshotWatcher;
import com.stepos.whisper.screenshot.ScreenshotWorker;
import com.stepos.whisper.speaker.EmbeddingUtils;
import com.stepos.whisper.speaker.SpeakerRecognizer;
import com.stepos.whisper.ui.ReminderOverlay;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
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
 *   <li>audio 线程：AudioRecord 采集，每 100 ms 一帧投递给 asr 线程；</li>
 *   <li>asr 线程：VAD 断句 + 流式实时字幕，切出的整句投递给处理线程；</li>
 *   <li>处理线程（mProc）：Paraformer 最终识别、声纹识别、陌生人建档、会话管理、数据库写入；</li>
 *   <li>screenshot 线程（{@link ScreenshotWorker}）：聊天截图 OCR 和事项提取；
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
    /** 连续这么多帧没检测到人声就清掉实时字幕，避免噪声产生的残留文字一直挂着。 */
    private static final int PARTIAL_CLEAR_SILENT_FRAMES = 20;
    /** 短句沿用上一位说话人的最大间隔。 */
    private static final long SHORT_UTTERANCE_FOLLOW_MS = 5000;
    private static final long UTTERANCE_RETENTION_MS = 30L * 24 * 60 * 60 * 1000;
    /** 回放结束后再屏蔽这么久，避开扬声器余音。 */
    private static final long PLAYBACK_TAIL_MS = 800;

    private static volatile RecordingService sInstance;

    @Nullable
    public static RecordingService getInstance() {
        return sInstance;
    }

    private static class UserInfo {
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
    private HandlerThread mAsrThread;
    private Handler mAsr;
    private ScheduledExecutorService mProc;

    private AppDatabase mDb;
    private ModelManager mModels;
    private ExtractionWorker mExtraction;
    private ScreenshotWorker mScreenshots;
    private ScreenshotWatcher mWatcher;
    private ReminderOverlay mOverlay;
    private LiveNotifier mLive;
    private final Observer<ServiceState.AiStatus> mAiObserver = s -> mLive.onAiStatus(s);
    private PowerManager.WakeLock mWakeLock;
    private ConnectivityManager.NetworkCallback mNetworkCallback;

    // asr 线程
    private VadSegmenter mVad;
    private StreamingPreview mPreview;
    private String mLastPartial = "";
    private int mSilentFrames;
    private volatile boolean mLiveCaption;

    /** 改名对话框正在回放录音时，麦克风会录到扬声器的声音，这段时间的输入全部丢弃。 */
    private volatile boolean mPlaybackActive;
    private volatile long mSuppressUntilMs;

    // 处理线程
    private FinalRecognizer mRecognizer;
    private SpeakerRecognizer mSpeakers;
    private ConversationManager mConversations;
    private final Map<Long, ArrayDeque<float[]>> mVoices = new HashMap<>();
    private final Map<Long, UserInfo> mUsers = new HashMap<>();
    private long mLastSpeakerId = TranscriptLine.NO_USER;
    private long mLastSpeakerEndMs;

    // 主线程
    private boolean mReady;
    /** 模型加载失败过，下次“继续”时重新加载。 */
    private volatile boolean mEngineFailed;
    private boolean mWantRecording = true;
    private AudioRecord mRecord;
    private Thread mAudioThread;
    private volatile boolean mRecording;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mDb = AppDatabase.get(this);
        mModels = new ModelManager();
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
                        ServiceState.requestAliasPrompt(jobId);
                    } else {
                        mLive.showChatAlias(jobId, names);
                    }
                });
            }

            @Override
            public void onAliasesResolved() {
                mMain.post(() -> mLive.cancelChatAlias());
            }

            @Override
            public void onAliasWaiting(List<Long> jobIds) {
                ServiceState.setAliasWaiting(jobIds);
            }

            @Override
            public void onUsersChanged() {
                reloadUsers();
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

        mAsrThread = new HandlerThread("asr");
        mAsrThread.start();
        mAsr = new Handler(mAsrThread.getLooper());
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
        mLive.cancelAll();
        mWatcher.shutdown();
        mScreenshots.shutdown();
        mAsrThread.quitSafely();
        try {
            mAsrThread.join(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        mProc.execute(() -> {
            mConversations.endAll();
            mExtraction.shutdown();
            if (mPreview != null) {
                mPreview.release();
            }
            if (mSpeakers != null) {
                mSpeakers.release();
            }
            mModels.release();
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
        stopAudio();
        if (mReady) {
            ServiceState.setStatus(ServiceState.Status.PAUSED);
            updateStatusNotification();
        }
    }

    public void resume() {
        mWantRecording = true;
        if (mEngineFailed) {
            // 模型加载失败过（比如模型文件刚推进去）：点“继续”重新加载，而不是什么都不发生。
            mEngineFailed = false;
            ServiceState.setStatus(ServiceState.Status.PREPARING);
            mLive.setRecordingState(false, false);
            proc(this::initEngine);
            return;
        }
        startAudio();
    }

    /** 用户希望录音（没有点“停止”）。 */
    public boolean isRecordingWanted() {
        return mWantRecording;
    }

    public void setLiveCaptionEnabled(boolean enabled) {
        mLiveCaption = enabled;
        if (!enabled) {
            mAsr.post(() -> {
                if (mPreview != null) {
                    mPreview.reset();
                }
                mLastPartial = "";
                ServiceState.setPartial("");
            });
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

    public void setSpeakerThreshold(float threshold) {
        proc(() -> {
            if (mSpeakers != null) {
                mSpeakers.setThreshold(threshold);
            }
        });
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
            if (mSpeakers == null) {
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
     */
    public void mergeUser(long from, long to) {
        proc(() -> {
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
            if (mSpeakers != null) {
                loadUsers();
            }
            UserInfo info = mUsers.get(to);
            if (info != null) {
                ServiceState.relabelUser(from, info.name, to, info.color);
            }
        });
    }

    /**
     * 把转写里的一句话归给某人（可能是刚新建、还不在 mUsers 里的人）：没认出是谁的灰色句子，
     * 或者认错了人、长按改归属的句子。还没交给 AI 的话，之后会按这个人提取事项。
     */
    public void assignLine(long lineId, long startMs, long endMs, long userId) {
        proc(() -> {
            mDb.utteranceDao().assignByTime(startMs, endMs, userId);
            if (!mUsers.containsKey(userId) && mSpeakers != null) {
                loadUsers();
            }
            UserInfo info = mUsers.get(userId);
            if (info != null) {
                ServiceState.relabelLine(lineId, info.name, userId, info.color);
            }
        });
    }

    /** 清除全部聊天内容（转写和待识别队列），用户和事项保留。 */
    public void clearAllChats() {
        proc(() -> {
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
        try {
            mModels.load(stage -> Log.i(TAG, "loading model: " + stage));
        } catch (Throwable t) {
            Log.e(TAG, "model load failed", t);
            mEngineFailed = true;
            fail(String.valueOf(t.getMessage()));
            return;
        }
        mVad = new VadSegmenter(mModels.getVad());
        mPreview = new StreamingPreview(mModels.getOnlineRecognizer());
        mRecognizer = new FinalRecognizer(mModels.getOfflineRecognizer(), mModels.getPunctuation());
        mSpeakers = new SpeakerRecognizer(mModels.getSpeakerExtractor(),
                Settings.getSpeakerThreshold(this));
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

    private void loadUsers() {
        List<User> users = mDb.userDao().getAll();
        mSpeakers.loadUsers(users);
        mUsers.clear();
        for (User u : users) {
            mUsers.put(u.id, new UserInfo(u.name, UserColors.orDefault(u.color, u.id), u.isOwner, u.isStranger));
        }
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

    /** 保留该用户最近约 15 秒的语音并写成 WAV。 */
    private void rememberVoice(long userId, float[] samples) {
        ArrayDeque<float[]> clips = mVoices.get(userId);
        if (clips == null) {
            clips = new ArrayDeque<>();
            mVoices.put(userId, clips);
        }
        clips.addLast(samples);
        int total = 0;
        for (float[] c : clips) {
            total += c.length;
        }
        while (total > VOICE_KEEP_SAMPLES && clips.size() > 1) {
            total -= clips.removeFirst().length;
        }
        int gap = ModelManager.SAMPLE_RATE * 3 / 10;
        float[] out = new float[total + gap * (clips.size() - 1)];
        int pos = 0;
        for (float[] c : clips) {
            System.arraycopy(c, 0, out, pos, c.length);
            pos += c.length + gap;
        }
        try {
            WavWriter.write(voiceFile(this, userId), out);
        } catch (IOException e) {
            Log.w(TAG, "write voice failed", e);
        }
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
        final long startWallMs = System.currentTimeMillis();
        mAsr.post(() -> {
            mVad.start(startWallMs);
            mPreview.reset();
            mLastPartial = "";
            mSilentFrames = 0;
        });
        record.startRecording();
        mRecording = true;
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
        mAsr.post(this::flushVad);
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
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
            float[] frame = new float[n];
            for (int i = 0; i < n; i++) {
                frame[i] = buf[i] / 32768f;
            }
            mAsr.post(() -> processFrame(frame));
        }
    }

    // ---------------------------------------------------------------- 断句与实时字幕（asr 线程）

    private boolean isSuppressed() {
        return mPlaybackActive || System.currentTimeMillis() < mSuppressUntilMs;
    }

    private void processFrame(float[] frame) {
        boolean suppressed = isSuppressed();
        if (mLiveCaption && !suppressed) {
            String text = mPreview.accept(frame);
            if (!text.equals(mLastPartial)) {
                mLastPartial = text;
                ServiceState.setPartial(text);
            }
        }
        List<SpeechChunk> chunks = mVad.accept(frame);
        mSilentFrames = mVad.isSpeechDetected() ? 0 : mSilentFrames + 1;
        if (!chunks.isEmpty() || suppressed
                || (mSilentFrames > PARTIAL_CLEAR_SILENT_FRAMES && !mLastPartial.isEmpty())) {
            clearPartial();
        }
        if (!suppressed) {
            dispatch(chunks);
        }
    }

    private void flushVad() {
        if (mVad == null) {
            return;
        }
        List<SpeechChunk> chunks = mVad.flush();
        clearPartial();
        if (!isSuppressed()) {
            dispatch(chunks);
        }
    }

    private void clearPartial() {
        mPreview.reset();
        if (!mLastPartial.isEmpty()) {
            mLastPartial = "";
            ServiceState.setPartial("");
        }
    }

    private void dispatch(List<SpeechChunk> chunks) {
        for (SpeechChunk chunk : chunks) {
            proc(() -> handleChunk(chunk));
        }
    }

    // ---------------------------------------------------------------- 识别与归属（处理线程）

    private void handleChunk(SpeechChunk chunk) {
        String text;
        try {
            text = mRecognizer.recognize(chunk.samples);
        } catch (Throwable t) {
            Log.e(TAG, "recognize failed", t);
            return;
        }
        if (text.isEmpty()) {
            return;
        }

        if (chunk.durationMs() >= SpeakerRecognizer.MIN_DURATION_MS) {
            float[] embedding = mSpeakers.extract(chunk.samples);
            if (embedding != null) {
                SpeakerRecognizer.Match match = mSpeakers.identify(embedding);
                if (match != null && mUsers.containsKey(match.userId)) {
                    onKnownSpeaker(match.userId, text, chunk);
                    adaptEmbedding(match, embedding, chunk.durationMs());
                } else {
                    onUnknownSpeaker(embedding, text, chunk);
                }
                return;
            }
        }

        if (mLastSpeakerId != TranscriptLine.NO_USER && mUsers.containsKey(mLastSpeakerId)
                && chunk.startMs - mLastSpeakerEndMs < SHORT_UTTERANCE_FOLLOW_MS) {
            onKnownSpeaker(mLastSpeakerId, text, chunk);
        } else {
            saveUtterance(null, text, chunk);
            String speaker = getString(R.string.speaker_unknown);
            ServiceState.addLine(chunk.startMs, chunk.endMs, speaker, text,
                    TranscriptLine.NO_USER, TranscriptLine.COLOR_UNKNOWN, Utterance.AI_NONE);
            mMain.post(() -> mLive.setLatestLine(speaker, TranscriptLine.COLOR_UNKNOWN, text));
        }
    }

    private void onKnownSpeaker(long userId, String text, SpeechChunk chunk) {
        UserInfo info = mUsers.get(userId);
        saveUtterance(userId, text, chunk);
        mDb.userDao().touch(userId, chunk.endMs);
        mLastSpeakerId = userId;
        mLastSpeakerEndMs = chunk.endMs;
        ServiceState.addLine(chunk.startMs, chunk.endMs, info.name, text, userId, info.color, Utterance.AI_NONE);
        mMain.post(() -> mLive.setLatestLine(info.name, info.color, text));
        if (!info.owner) {
            mConversations.onUtterance(userId, chunk.startMs, chunk.endMs);
            if (chunk.durationMs() >= SpeakerRecognizer.MIN_DURATION_MS) {
                rememberVoice(userId, chunk.samples);
            }
        }
    }

    /**
     * 没认出来的声音直接建档为“陌生人N”，之后同一个声音都归到这个陌生人下。
     * 主界面在前台时直接弹出命名窗口（填名字、颜色或关联到已有用户）；不在时灵动岛弹出“以后再说 / 命名”卡片，
     * 回到主界面时还没处理的话再弹命名窗口。
     */
    private void onUnknownSpeaker(float[] embedding, String text, SpeechChunk chunk) {
        String name = nextStrangerName();
        User user = new User();
        user.name = name;
        user.isStranger = true;
        user.embedding = EmbeddingUtils.toBytes(embedding);
        user.sampleCount = 1;
        user.createdAt = System.currentTimeMillis();
        user.lastSeenAt = chunk.endMs;
        user.color = UserColors.pickUnused(mDb.userDao().getAllColors());
        long userId = mDb.userDao().insert(user);
        mSpeakers.putUser(userId, embedding, 1);
        mUsers.put(userId, new UserInfo(name, user.color, false, true));
        onKnownSpeaker(userId, text, chunk);
        int color = user.color;
        mMain.post(() -> {
            ServiceState.requestStrangerPrompt(userId);
            if (!ServiceState.isUiVisible()) {
                mLive.showStranger(userId, name, color, text);
            }
        });
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

    private void adaptEmbedding(SpeakerRecognizer.Match match, float[] embedding, long durationMs) {
        if (!mSpeakers.maybeAdapt(match, embedding, durationMs)) {
            return;
        }
        User user = mDb.userDao().get(match.userId);
        if (user != null) {
            user.embedding = EmbeddingUtils.toBytes(mSpeakers.getEmbedding(match.userId));
            List<float[]> extras = mSpeakers.getExtraEmbeddings(match.userId);
            user.extraEmbeddings = extras.isEmpty() ? null : EmbeddingUtils.joinBytes(extras);
            user.sampleCount = mSpeakers.getSampleCount(match.userId);
            mDb.userDao().update(user);
        }
    }

    private long saveUtterance(Long userId, String text, SpeechChunk chunk) {
        Utterance u = new Utterance();
        u.userId = userId;
        u.text = text;
        u.startTime = chunk.startMs;
        u.endTime = chunk.endMs;
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

    // ---------------------------------------------------------------- 常驻通知（灵动岛录音卡片）

    private void updateStatusNotification() {
        mLive.setRecordingState(mReady, mRecording);
    }
}
