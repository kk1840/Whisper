package com.stepos.whisper.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
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

import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.stepos.whisper.R;
import com.stepos.whisper.asr.ModelManager;
import com.stepos.whisper.asr.ReadingTracker;
import com.stepos.whisper.asr.SpeechChunk;
import com.stepos.whisper.asr.VadSegmenter;
import com.stepos.whisper.data.AppDatabase;
import com.stepos.whisper.data.User;
import com.stepos.whisper.data.UserColors;
import com.stepos.whisper.databinding.ActivityVoiceEnrollBinding;
import com.stepos.whisper.service.RecordingService;
import com.stepos.whisper.speaker.EmbeddingUtils;
import com.stepos.whisper.speaker.SpeakerRecognizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 朗读一段文字录入声纹：VAD 切出有效语音后提取声纹取平均。三种用途：
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

    /** 有效语音至少这么长，声纹才可靠。正常读完全文约 20 秒。 */
    private static final long MIN_SPEECH_MS = 8_000;
    /** 原文读到这个比例（模糊匹配）即可完成。 */
    private static final int DONE_PERCENT = 90;
    private static final long MIN_CHUNK_MS = 1000;
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
    private HandlerThread mWorkerThread;
    private Handler mWorker;
    private ModelManager mModels;
    private VadSegmenter mVad;
    private SpeakerRecognizer mSpeaker;
    private boolean mModelsReady;

    // worker 线程
    private final List<float[]> mEmbeddings = new ArrayList<>();
    private final List<float[]> mSpeech = new ArrayList<>();
    private long mSpeechMs;
    private OnlineRecognizer mOnline;
    private OnlineStream mStream;
    private String mRecognized = "";

    // 主线程
    private ReadingTracker mTracker;
    private int mReadProgress;
    private boolean mFinished;
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
        mBinding.status.setText(R.string.enroll_loading);
        mBinding.record.setOnClickListener(v -> {
            if (mRecording) {
                stopRecording();
            } else {
                startRecording();
            }
        });
        mBinding.done.setOnClickListener(v -> save());

        mWorkerThread = new HandlerThread("enroll");
        mWorkerThread.start();
        mWorker = new Handler(mWorkerThread.getLooper());
        mModels = new ModelManager();
        mWorker.post(() -> {
            try {
                mModels.loadEnrollment();
                mModels.loadStreaming();
                mVad = new VadSegmenter(mModels.getVad());
                mSpeaker = new SpeakerRecognizer(mModels.getSpeakerExtractor(), 0.5f);
                mOnline = mModels.getOnlineRecognizer();
                mStream = mOnline.createStream("");
            } catch (Throwable t) {
                Log.e(TAG, "load failed", t);
                mMain.post(() -> mBinding.status.setText(getString(R.string.status_error, t.getMessage())));
                return;
            }
            mMain.post(() -> {
                mModelsReady = true;
                mBinding.record.setEnabled(true);
                mBinding.status.setText(R.string.enroll_ready);
            });
        });

        requestPermissions();
    }

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
        mWorker.post(() -> {
            if (mStream != null) {
                mStream.release();
            }
            if (mSpeaker != null) {
                mSpeaker.release();
            }
            mModels.release();
        });
        mWorkerThread.quitSafely();
        if (mResumeService) {
            RecordingService service = RecordingService.getInstance();
            if (service != null) {
                service.resume();
            }
        }
        super.onDestroy();
    }

    private void startRecording() {
        if (!mModelsReady || mRecording) {
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
        long startMs = System.currentTimeMillis();
        mWorker.post(() -> mVad.start(startMs));
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
        mWorker.post(() -> {
            handleChunks(mVad.flush());
            // 流式模型有几百毫秒的延迟，补一段静音把最后几个字吐出来。
            onFrame(new float[ModelManager.SAMPLE_RATE * 6 / 10], false);
        });
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
            float[] frame = new float[n];
            for (int i = 0; i < n; i++) {
                frame[i] = buf[i] / 32768f;
            }
            mWorker.post(() -> onFrame(frame, true));
        }
    }

    // worker 线程
    private void onFrame(float[] frame, boolean feedVad) {
        mStream.acceptWaveform(frame, ModelManager.SAMPLE_RATE);
        while (mOnline.isReady(mStream)) {
            mOnline.decode(mStream);
        }
        String text = mOnline.getResult(mStream).getText();
        boolean textChanged = !text.equals(mRecognized);
        mRecognized = text;
        boolean chunkEnded = feedVad && handleChunks(mVad.accept(frame));
        if (textChanged || chunkEnded) {
            long speechMs = mSpeechMs;
            boolean hasVoice = !mEmbeddings.isEmpty();
            mMain.post(() -> renderProgress(text, speechMs, hasVoice, chunkEnded));
        }
    }

    /** 返回是否有句子结束。 */
    private boolean handleChunks(List<SpeechChunk> chunks) {
        for (SpeechChunk chunk : chunks) {
            if (chunk.durationMs() < MIN_CHUNK_MS) {
                continue;
            }
            float[] emb = mSpeaker.extract(chunk.samples);
            if (emb == null) {
                continue;
            }
            mEmbeddings.add(emb);
            mSpeech.add(chunk.samples);
            mSpeechMs += chunk.durationMs();
        }
        return !chunks.isEmpty();
    }

    private void renderProgress(String recognized, long speechMs, boolean hasVoice, boolean sentenceEnded) {
        int progress = Math.max(mReadProgress, mTracker.progress(recognized));
        mReadProgress = progress;
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
        CharSequence text = mBinding.name.getText();
        String name = text == null ? "" : text.toString().trim();
        if (TextUtils.isEmpty(name) && mMode != MODE_ADD_VOICE) {
            mBinding.nameLayout.setError(getString(R.string.new_speaker_name_empty));
            return;
        }
        mBinding.nameLayout.setError(null);
        mBinding.done.setEnabled(false);
        mBinding.record.setEnabled(false);
        mWorker.post(() -> {
            float[] embedding = buildEmbedding();
            int count = mEmbeddings.size();
            if (mMode != MODE_OWNER) {
                AppDatabase.IO.execute(() -> saveOther(name, embedding, count));
                return;
            }
            AppDatabase.IO.execute(() -> {
                AppDatabase db = AppDatabase.get(this);
                User owner = db.userDao().getOwner();
                long now = System.currentTimeMillis();
                if (owner == null) {
                    owner = new User();
                    owner.isOwner = true;
                    owner.color = UserColors.OWNER_DEFAULT;
                    owner.createdAt = now;
                    owner.name = name;
                    owner.embedding = EmbeddingUtils.toBytes(embedding);
                    owner.sampleCount = count;
                    owner.lastSeenAt = now;
                    db.userDao().insert(owner);
                } else {
                    owner.name = name;
                    owner.embedding = EmbeddingUtils.toBytes(embedding);
                    owner.sampleCount = count;
                    db.userDao().update(owner);
                }
                mMain.post(() -> {
                    startActivity(new Intent(this, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK));
                    finish();
                });
            });
        });
    }

    /** 新用户建档，或把声纹追加为已有用户的额外声纹（db-io 线程）。 */
    private void saveOther(String name, float[] embedding, int count) {
        AppDatabase db = AppDatabase.get(this);
        long now = System.currentTimeMillis();
        String toast;
        if (mMode == MODE_NEW_USER) {
            User user = new User();
            user.name = name;
            user.embedding = EmbeddingUtils.toBytes(embedding);
            user.sampleCount = count;
            user.createdAt = now;
            user.lastSeenAt = now;
            user.color = UserColors.pickUnused(db.userDao().getAllColors());
            db.userDao().insert(user);
            toast = getString(R.string.enroll_saved_new, name);
        } else {
            User user = db.userDao().get(mTargetUserId);
            if (user == null) {
                mMain.post(this::finish);
                return;
            }
            if (EmbeddingUtils.fromBytes(user.embedding) == null) {
                user.embedding = EmbeddingUtils.toBytes(embedding);
                user.sampleCount = count;
            } else {
                user.extraEmbeddings = SpeakerRecognizer.appendExtra(user.extraEmbeddings, embedding);
            }
            db.userDao().update(user);
            toast = getString(R.string.enroll_saved_add, mTargetName);
        }
        RecordingService service = RecordingService.getInstance();
        if (service != null) {
            service.reloadUsers();
        }
        mMain.post(() -> {
            Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    /** 各段声纹的平均与整段拼接后的声纹再平均，兼顾稳定性和整体音色。 */
    private float[] buildEmbedding() {
        int dim = mEmbeddings.get(0).length;
        float[] sum = new float[dim];
        for (float[] e : mEmbeddings) {
            for (int i = 0; i < dim; i++) {
                sum[i] += e[i];
            }
        }
        float[] mean = EmbeddingUtils.normalize(sum);

        int total = 0;
        for (float[] s : mSpeech) {
            total += s.length;
        }
        float[] all = new float[total];
        int pos = 0;
        for (float[] s : mSpeech) {
            System.arraycopy(s, 0, all, pos, s.length);
            pos += s.length;
        }
        float[] whole = mSpeaker.extract(all);
        if (whole == null) {
            return mean;
        }
        float[] combined = new float[dim];
        for (int i = 0; i < dim; i++) {
            combined[i] = mean[i] + whole[i];
        }
        return EmbeddingUtils.normalize(combined);
    }
}
