package com.stepos.whisper.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.stepos.whisper.R;
import com.stepos.whisper.asr.ChineseTextFilter;
import com.stepos.whisper.asr.ModelManager;
import com.stepos.whisper.asr.PcmTimeline;
import com.stepos.whisper.asr.ReadingTracker;
import com.stepos.whisper.asr.StepAsrStreamClient;
import com.stepos.whisper.data.AppDatabase;
import com.stepos.whisper.data.User;
import com.stepos.whisper.data.UserColors;
import com.stepos.whisper.databinding.ActivityVoiceEnrollBinding;
import com.stepos.whisper.service.RecordingService;
import com.stepos.whisper.voiceprint.VoiceprintLibrary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 朗读一段文字录入云端声纹：Step 流式识别跟读进度，server_vad 累计语音时长，
 * 读够后把切出的句子（每条 3~8 秒）注册成讯飞声纹特征。三种用途：
 * <ul>
 *   <li>机主：首次进入应用时弹窗询问，可以直接录或稍后再说，也可重新录入；</li>
 *   <li>新用户：主动给身边的人建档；</li>
 *   <li>补录：给已有用户再录一条声纹（比如压低声音时），作为额外声纹保存。</li>
 * </ul>
 */
public class VoiceEnrollActivity extends AppCompatActivity {
    private static final String TAG = "VoiceEnroll";
    private static final String EXTRA_MODE = "mode";
    private static final String EXTRA_REENROLL = "reenroll";
    private static final String EXTRA_USER_ID = "user_id";
    private static final int MODE_OWNER = 0;
    private static final int MODE_NEW_USER = 1;
    private static final int MODE_ADD_VOICE = 2;

    /** 有效语音至少这么长，声纹才可靠。正常读完全文约 12 秒。 */
    private static final long MIN_SPEECH_MS = 6_000;
    /** 原文读到这个比例（模糊匹配）即可完成。 */
    private static final int DONE_PERCENT = 90;
    /** 单句短于这个时长不入库（切句用）。 */
    private static final long MIN_CLIP_MS = 1500;
    /** 注册云端时每条声纹的时长窗口：3~8 秒，最多注册 3 条。 */
    private static final long ENROLL_MIN_MS = 3000;
    private static final long ENROLL_MAX_MS = 8000;
    private static final int ENROLL_MAX_CLIPS = 3;
    private static final int FRAME_SAMPLES = ModelManager.SAMPLE_RATE / 10;

    public static Intent createOwnerIntent(Context context, boolean reenroll) {
        return new Intent(context, VoiceEnrollActivity.class)
                .putExtra(EXTRA_MODE, MODE_OWNER)
                .putExtra(EXTRA_REENROLL, reenroll);
    }

    public static Intent createNewUserIntent(Context context) {
        return new Intent(context, VoiceEnrollActivity.class).putExtra(EXTRA_MODE, MODE_NEW_USER);
    }

    public static Intent createAddVoiceIntent(Context context, long userId) {
        return new Intent(context, VoiceEnrollActivity.class)
                .putExtra(EXTRA_MODE, MODE_ADD_VOICE)
                .putExtra(EXTRA_USER_ID, userId);
    }

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private ActivityVoiceEnrollBinding mBinding;
    private int mMode;
    private long mTargetUserId;
    private String mTargetName = "";
    /** 录入别人时暂停了录音服务，结束后恢复。 */
    private boolean mResumeService;

    private PcmTimeline mTimeline;
    private StepAsrStreamClient mStream;
    private VoiceprintLibrary mLibrary;

    // 流式回调线程写、主线程读
    private final List<byte[]> mClips = new ArrayList<>();
    private volatile long mSpeechMs;
    private volatile long mLastReportMs;
    private volatile String mRecognized = "";
    /** 本连接内上一条累计字幕，onPartial 用来截增量；续录/重连后字幕重新从头累计，
     *  前缀对不上时增量记 0（那段音频识别过，重复喂反而可能错位）。仅流式回调线程访问。 */
    private String mPrevPartial = "";
    // 主线程
    private ReadingTracker mTracker;
    private boolean mFinished;
    private boolean mReady;
    private boolean mSaving;
    private int mHighlightFg;
    private int mHighlightBg;

    private AudioRecord mRecord;
    private Thread mAudioThread;
    private volatile boolean mRecording;

    private final ActivityResultLauncher<String[]> mPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionResult);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBinding = ActivityVoiceEnrollBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());
        mMode = getIntent().getIntExtra(EXTRA_MODE, MODE_OWNER);
        mTargetUserId = getIntent().getLongExtra(EXTRA_USER_ID, -1);

        if (mMode == MODE_OWNER) {
            // 录机主声纹时录音服务不能开着：重新录入时它会用旧声纹识别；第一次录（之前选了“稍后再说”）时
            // 它会把朗读的机主当成新陌生人。先停掉，录完或退出回到主界面时由主界面重新拉起。
            if (RecordingService.getInstance() != null) {
                stopService(new Intent(this, RecordingService.class));
            }
            if (getIntent().getBooleanExtra(EXTRA_REENROLL, false)) {
                AppDatabase.IO.execute(() -> {
                    User owner = AppDatabase.get(this).userDao().getOwner();
                    if (owner != null) {
                        mMain.post(() -> mBinding.name.setText(owner.name));
                    }
                });
            }
        } else {
            // 录别人时录音服务也会听到，会把对方当成新陌生人；先暂停，离开时恢复。
            RecordingService service = RecordingService.getInstance();
            if (service != null && service.isRecordingWanted()) {
                service.pause();
                mResumeService = true;
            }
        }
        if (mMode == MODE_NEW_USER) {
            mBinding.title.setText(R.string.enroll_title_new);
            mBinding.subtitle.setText(R.string.enroll_subtitle_new);
            mBinding.nameLayout.setHint(getString(R.string.enroll_name_hint_new));
        } else if (mMode == MODE_ADD_VOICE) {
            mBinding.nameLayout.setVisibility(android.view.View.GONE);
            AppDatabase.IO.execute(() -> {
                User user = AppDatabase.get(this).userDao().get(mTargetUserId);
                mMain.post(() -> {
                    if (user == null) {
                        finish();
                        return;
                    }
                    mTargetName = user.name;
                    mBinding.title.setText(getString(R.string.enroll_title_add, user.name));
                    mBinding.subtitle.setText(getString(R.string.enroll_subtitle_add, user.name));
                });
            });
        }

        mTracker = new ReadingTracker(getString(R.string.enroll_passage));
        mHighlightFg = getColor(R.color.read_text);
        mHighlightBg = getColor(R.color.read_bg);
        renderPassage(0);
        mBinding.record.setOnClickListener(v -> {
            if (mRecording) {
                stopRecording();
            } else {
                startRecording();
            }
        });
        mBinding.done.setOnClickListener(v -> save());

        mTimeline = new PcmTimeline(120);
        mLibrary = new VoiceprintLibrary(com.stepos.whisper.Settings.getSpeakerThreshold(this));
        mStream = new StepAsrStreamClient(mTimeline, mEnrollListener);
        if (!mStream.hasApiKey() || !mLibrary.hasCredentials()) {
            mBinding.status.setText("未配置云端密钥，无法录入");
            return;
        }
        mReady = true;
        mBinding.record.setEnabled(true);
        mBinding.status.setText(R.string.enroll_ready);

        requestPermissions();
    }

    private final StepAsrStreamClient.Listener mEnrollListener = new StepAsrStreamClient.Listener() {
        @Override
        public void onPartial(String text) {
            if (text == null || mFinished) {
                return;
            }
            // 字幕是本连接的累计全量，进度匹配只喂自上一条以来新增的部分：
            // 暂停续录/断线重连后字幕从头累计，整体重算会让进度冻结在断点前。
            String delta = text.length() > mPrevPartial.length() && text.startsWith(mPrevPartial)
                    ? text.substring(mPrevPartial.length()) : "";
            mPrevPartial = text;
            mRecognized = ChineseTextFilter.apply(text);
            mMain.post(() -> {
                if (mFinished) {
                    return;
                }
                if (!delta.isEmpty()) {
                    mTracker.advance(ChineseTextFilter.apply(delta));
                }
                renderProgress(mRecognized, mSpeechMs, !mClips.isEmpty(), false);
            });
        }

        @Override
        public void onSentence(String transcript, long startByte, long endByte) {
            byte[] pcm = mTimeline.cutGlobal(startByte, endByte);
            long ms = pcm == null ? 0 : pcm.length * 1000L / PcmTimeline.BYTES_PER_SEC;
            if (pcm != null && ms >= MIN_CLIP_MS) {
                synchronized (mClips) {
                    mClips.add(pcm);
                }
            }
            if (!mFinished) {
                mMain.post(() -> renderProgress(mRecognized, mSpeechMs, true, true));
            }
        }

        @Override
        public void onSpeechSeconds(long speechMs) {
            // 流式连接暂停/重连后服务端计数从 0 开始，这里按增量累计，读完一整段才算数。
            if (speechMs >= mLastReportMs) {
                mSpeechMs += speechMs - mLastReportMs;
            } else {
                mSpeechMs += speechMs;
            }
            mLastReportMs = speechMs;
        }

        @Override
        public void onConnectionChange(boolean connected) {
            if (!connected) {
                mMain.post(() -> mBinding.status.setText("网络断开，重连中…"));
            }
        }
    };

    private void requestPermissions() {
        List<String> wanted = new ArrayList<>();
        wanted.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        List<String> missing = new ArrayList<>();
        for (String p : wanted) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (!missing.isEmpty()) {
            mPermissionLauncher.launch(missing.toArray(new String[0]));
        }
    }

    private void onPermissionResult(Map<String, Boolean> result) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onStop() {
        stopRecording();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopRecording();
        mStream.stop();
        mLibrary.shutdown();
        if (mResumeService) {
            RecordingService service = RecordingService.getInstance();
            if (service != null) {
                service.resume();
            }
        }
        super.onDestroy();
    }

    private void startRecording() {
        if (!mReady || mRecording) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions();
            return;
        }
        int minBuf = AudioRecord.getMinBufferSize(ModelManager.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                ModelManager.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBuf, FRAME_SAMPLES * 2 * 4));
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            Toast.makeText(this, "AudioRecord init failed", Toast.LENGTH_LONG).show();
            return;
        }
        mRecord = record;
        mTimeline.clear();
        mPrevPartial = "";
        mStream.start();
        record.startRecording();
        mRecording = true;
        mAudioThread = new Thread(this::audioLoop, "enroll-audio");
        mAudioThread.start();
        mBinding.record.setText(R.string.enroll_pause);
        mBinding.status.setText(R.string.enroll_recording);
    }

    private void stopRecording() {
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
        mBinding.record.setText(R.string.enroll_continue);
        if (!mFinished) {
            mBinding.status.setText(R.string.enroll_paused);
        }
    }

    private void audioLoop() {
        short[] buf = new short[FRAME_SAMPLES];
        while (mRecording) {
            int n = mRecord.read(buf, 0, buf.length);
            if (n <= 0) {
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

    private void renderProgress(String recognized, long speechMs, boolean hasVoice, boolean sentenceEnded) {
        int progress = mTracker.position();
        renderPassage(progress);
        int percent = progress * 100 / Math.max(1, mTracker.size());
        mBinding.progress.setProgressCompat(percent, true);
        String tail = recognized.length() > 30 ? "…" + recognized.substring(recognized.length() - 30) : recognized;
        mBinding.heard.setText(tail);

        if (mFinished || !hasVoice || speechMs < MIN_SPEECH_MS) {
            return;
        }
        // 读完全文立即结束；读到大部分且刚说完一句也算完成，不要求逐字一致。
        if (progress >= mTracker.size() || (sentenceEnded && percent >= DONE_PERCENT)) {
            mFinished = true;
            stopRecording();
            mBinding.status.setText(mMode == MODE_NEW_USER ? R.string.enroll_enough_new
                    : mMode == MODE_ADD_VOICE ? R.string.enroll_enough_add : R.string.enroll_enough);
            mBinding.done.setEnabled(true);
        }
    }

    private void renderPassage(int progress) {
        String passage = mTracker.getPassage();
        int end = mTracker.passageEnd(progress);
        SpannableString s = new SpannableString(passage);
        if (end > 0) {
            s.setSpan(new ForegroundColorSpan(mHighlightFg), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new BackgroundColorSpan(mHighlightBg), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new StyleSpan(Typeface.BOLD), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        mBinding.passage.setText(s);
    }

    private void save() {
        if (mSaving) {
            return;
        }
        CharSequence text = mBinding.name.getText();
        String name = text == null ? "" : text.toString().trim();
        if (TextUtils.isEmpty(name) && mMode != MODE_ADD_VOICE) {
            mBinding.nameLayout.setError(getString(R.string.new_speaker_name_empty));
            return;
        }
        List<byte[]> clips = pickEnrollClips();
        if (clips.isEmpty()) {
            mBinding.nameLayout.setError(null);
            Toast.makeText(this, "没有录到足够的语音，请再读一遍", Toast.LENGTH_LONG).show();
            return;
        }
        mSaving = true;
        mBinding.nameLayout.setError(null);
        mBinding.done.setEnabled(false);
        mBinding.record.setEnabled(false);
        mPendingName = name;
        AppDatabase.IO.execute(() -> saveAndEnroll(name, clips));
    }

    /** save() 时在主线程记下的名字，收尾 toast 用（db-io 线程不能碰 View）。 */
    private String mPendingName = "";

    private String userNameForToast() {
        return mPendingName == null || mPendingName.isEmpty() ? mTargetName : mPendingName;
    }

    /** 挑注册用的句子：优先长的，每条 3~8 秒，最多 3 条。 */
    private List<byte[]> pickEnrollClips() {
        List<byte[]> all = new ArrayList<>();
        synchronized (mClips) {
            all.addAll(mClips);
        }
        all.sort((a, b) -> b.length - a.length);
        List<byte[]> out = new ArrayList<>();
        for (byte[] c : all) {
            int maxBytes = (int) (ENROLL_MAX_MS * PcmTimeline.BYTES_PER_SEC / 1000);
            if (c.length >= ENROLL_MIN_MS * PcmTimeline.BYTES_PER_SEC / 1000) {
                out.add(c.length > maxBytes ? copyOf(c, maxBytes) : c);
            }
            if (out.size() >= ENROLL_MAX_CLIPS) {
                break;
            }
        }
        return out;
    }

    private static byte[] copyOf(byte[] src, int len) {
        byte[] out = new byte[len];
        System.arraycopy(src, 0, out, 0, len);
        return out;
    }

    /** db-io 线程：先落库拿到 userId，再逐条注册云端声纹，全部结束后回主线程收尾。 */
    private void saveAndEnroll(String name, List<byte[]> clips) {
        AppDatabase db = AppDatabase.get(this);
        long now = System.currentTimeMillis();
        User user;
        boolean owner = mMode == MODE_OWNER;
        if (owner) {
            user = db.userDao().getOwner();
            if (user == null) {
                user = new User();
                user.isOwner = true;
                user.color = UserColors.OWNER_DEFAULT;
                user.createdAt = now;
                user.lastSeenAt = now;
                user.name = name;
                db.userDao().insert(user);
                user = db.userDao().getOwner();
            } else {
                user.name = name;
                db.userDao().update(user);
            }
        } else if (mMode == MODE_NEW_USER) {
            user = new User();
            user.name = name;
            user.createdAt = now;
            user.lastSeenAt = now;
            user.color = UserColors.pickUnused(db.userDao().getAllColors());
            user.id = db.userDao().insert(user);
        } else {
            user = db.userDao().get(mTargetUserId);
            if (user == null) {
                mMain.post(this::finish);
                return;
            }
        }
        final long userId = user.id;
        final String userName = user.name;
        final AtomicInteger remaining = new AtomicInteger(clips.size());
        final AtomicInteger okCount = new AtomicInteger(0);
        for (byte[] clip : clips) {
            mLibrary.enroll(userId, userName, owner, clip, (featureId, err) -> {
                if (err == null && featureId != null) {
                    okCount.incrementAndGet();
                    AppDatabase.IO.execute(() -> {
                        User u = AppDatabase.get(VoiceEnrollActivity.this).userDao().get(userId);
                        if (u != null) {
                            u.featureIds = VoiceprintLibrary.appendFeatureId(u.featureIds, featureId);
                            AppDatabase.get(VoiceEnrollActivity.this).userDao().update(u);
                        }
                    });
                } else {
                    Log.w(TAG, "enroll failed: " + err);
                }
                if (remaining.decrementAndGet() == 0) {
                    final int total = clips.size();
                    AppDatabase.IO.execute(() -> finishSave(okCount.get(), total));
                }
            });
        }
    }

    private void finishSave(int enrolled, int total) {
        RecordingService service = RecordingService.getInstance();
        if (service != null) {
            service.reloadUsers();
        }
        String toast = enrolled == 0 ? "已保存，但云端声纹注册失败，请检查网络后重试"
                : mMode == MODE_NEW_USER ? getString(R.string.enroll_saved_new, userNameForToast())
                : mMode == MODE_ADD_VOICE ? getString(R.string.enroll_saved_add, mTargetName)
                : "已录入 " + enrolled + "/" + total + " 段声纹";
        boolean toMain = mMode == MODE_OWNER;
        mMain.post(() -> {
            Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
            if (toMain) {
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            finish();
        });
    }
}
