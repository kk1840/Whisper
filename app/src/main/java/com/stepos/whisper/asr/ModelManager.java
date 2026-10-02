package com.stepos.whisper.asr;

import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OfflinePunctuation;
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig;
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import java.io.File;

/**
 * 统一创建和释放 sherpa-onnx 的各个模型，全部从系统目录 {@link #DIR} 加载（不打包进 APK）：
 * silero VAD、ERes2NetV2 声纹、中文流式 zipformer（实时字幕）、Paraformer-zh（整句识别）、
 * CT-Transformer 标点。
 */
public class ModelManager {
    private static final String TAG = "ModelManager";

    public static final int SAMPLE_RATE = 16000;

    public static final String DIR = "/system/etc/whisper/models/";
    private static final String VAD_MODEL = DIR + "silero_vad.onnx";
    private static final String SPEAKER_MODEL = DIR + "3dspeaker_eres2netv2_zh_16k.onnx";
    private static final String ASR_DIR = DIR + "paraformer/";
    private static final String STREAMING_DIR = DIR + "streaming/";
    private static final String PUNCT_MODEL = DIR + "punct/model.int8.onnx";

    private static final String[] ENROLLMENT_FILES = {VAD_MODEL, SPEAKER_MODEL};
    private static final String[] STREAMING_FILES = {
            STREAMING_DIR + "encoder.int8.onnx", STREAMING_DIR + "decoder.onnx",
            STREAMING_DIR + "joiner.int8.onnx", STREAMING_DIR + "tokens.txt"};
    private static final String[] FINAL_FILES = {
            ASR_DIR + "model.int8.onnx", ASR_DIR + "tokens.txt", PUNCT_MODEL};

    public interface ProgressListener {
        void onProgress(String stage);
    }

    private Vad mVad;
    private OnlineRecognizer mOnlineRecognizer;
    private OfflineRecognizer mOfflineRecognizer;
    private SpeakerEmbeddingExtractor mSpeakerExtractor;
    private OfflinePunctuation mPunctuation;

    /** 模型文件缺失或不可读时抛出，消息直接显示在界面上。 */
    private static void requireFiles(String[] paths) {
        for (String p : paths) {
            if (!new File(p).canRead()) {
                throw new IllegalStateException("缺少模型文件 " + p + "，请先运行 scripts/push-models.ps1");
            }
        }
    }

    /** 只加载 VAD 和声纹模型，用于录入机主声纹。耗时，必须在后台线程调用。 */
    public synchronized void loadEnrollment() {
        requireFiles(ENROLLMENT_FILES);
        if (mVad == null) {
            mVad = new Vad(null, buildVadConfig());
        }
        if (mSpeakerExtractor == null) {
            mSpeakerExtractor = new SpeakerEmbeddingExtractor(null,
                    new SpeakerEmbeddingExtractorConfig(SPEAKER_MODEL, 2, false, "cpu"));
        }
    }

    /** 加载流式识别模型（实时字幕、录入时跟读进度）。耗时，必须在后台线程调用。 */
    public synchronized void loadStreaming() {
        requireFiles(STREAMING_FILES);
        if (mOnlineRecognizer == null) {
            mOnlineRecognizer = new OnlineRecognizer(null, buildOnlineConfig());
        }
    }

    /** 加载全部模型。耗时数秒，必须在后台线程调用。 */
    public synchronized void load(ProgressListener listener) {
        long t0 = System.currentTimeMillis();
        requireFiles(FINAL_FILES);
        listener.onProgress("VAD / speaker");
        loadEnrollment();

        listener.onProgress("streaming");
        loadStreaming();

        listener.onProgress("Paraformer");
        mOfflineRecognizer = new OfflineRecognizer(null, buildOfflineConfig());

        listener.onProgress("punctuation");
        OfflinePunctuationModelConfig pm = new OfflinePunctuationModelConfig();
        pm.setCtTransformer(PUNCT_MODEL);
        pm.setNumThreads(1);
        pm.setProvider("cpu");
        mPunctuation = new OfflinePunctuation(null, new OfflinePunctuationConfig(pm));

        Log.i(TAG, "models loaded in " + (System.currentTimeMillis() - t0) + " ms");
    }

    public synchronized boolean isLoaded() {
        return mOfflineRecognizer != null;
    }

    public Vad getVad() {
        return mVad;
    }

    public OnlineRecognizer getOnlineRecognizer() {
        return mOnlineRecognizer;
    }

    public OfflineRecognizer getOfflineRecognizer() {
        return mOfflineRecognizer;
    }

    public SpeakerEmbeddingExtractor getSpeakerExtractor() {
        return mSpeakerExtractor;
    }

    public OfflinePunctuation getPunctuation() {
        return mPunctuation;
    }

    public synchronized void release() {
        if (mPunctuation != null) {
            mPunctuation.release();
            mPunctuation = null;
        }
        if (mVad != null) {
            mVad.release();
            mVad = null;
        }
        if (mOnlineRecognizer != null) {
            mOnlineRecognizer.release();
            mOnlineRecognizer = null;
        }
        if (mOfflineRecognizer != null) {
            mOfflineRecognizer.release();
            mOfflineRecognizer = null;
        }
        if (mSpeakerExtractor != null) {
            mSpeakerExtractor.release();
            mSpeakerExtractor = null;
        }
    }

    private static VadModelConfig buildVadConfig() {
        SileroVadModelConfig silero = new SileroVadModelConfig();
        silero.setModel(VAD_MODEL);
        silero.setThreshold(0.5f);
        silero.setMinSilenceDuration(0.5f);
        silero.setMinSpeechDuration(0.25f);
        silero.setMaxSpeechDuration(20f);
        silero.setWindowSize(VadSegmenter.WINDOW_SIZE);

        VadModelConfig config = new VadModelConfig();
        config.setSileroVadModelConfig(silero);
        config.setSampleRate(SAMPLE_RATE);
        config.setNumThreads(1);
        config.setProvider("cpu");
        return config;
    }

    private static OnlineRecognizerConfig buildOnlineConfig() {
        OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
        transducer.setEncoder(STREAMING_DIR + "encoder.int8.onnx");
        transducer.setDecoder(STREAMING_DIR + "decoder.onnx");
        transducer.setJoiner(STREAMING_DIR + "joiner.int8.onnx");

        OnlineModelConfig model = new OnlineModelConfig();
        model.setTransducer(transducer);
        model.setTokens(STREAMING_DIR + "tokens.txt");
        model.setModelType("");
        model.setNumThreads(2);
        model.setProvider("cpu");

        OnlineRecognizerConfig config = new OnlineRecognizerConfig();
        config.setFeatConfig(new FeatureConfig(SAMPLE_RATE, 80, 0f));
        config.setModelConfig(model);
        config.setEnableEndpoint(false);
        config.setDecodingMethod("greedy_search");
        return config;
    }

    private static OfflineRecognizerConfig buildOfflineConfig() {
        OfflineParaformerModelConfig paraformer = new OfflineParaformerModelConfig();
        paraformer.setModel(ASR_DIR + "model.int8.onnx");

        OfflineModelConfig model = new OfflineModelConfig();
        model.setParaformer(paraformer);
        model.setTokens(ASR_DIR + "tokens.txt");
        model.setNumThreads(2);
        model.setProvider("cpu");

        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.setFeatConfig(new FeatureConfig(SAMPLE_RATE, 80, 0f));
        config.setModelConfig(model);
        config.setDecodingMethod("greedy_search");
        return config;
    }
}
