package com.stepos.whisper.asr;

import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;

import java.util.ArrayList;
import java.util.List;

/**
 * 把连续音频喂给 silero VAD，按句子切分。非线程安全，只能在音频处理线程上调用。
 */
public class VadSegmenter {
    /** silero VAD 要求每次输入固定 512 个采样点。 */
    public static final int WINDOW_SIZE = 512;

    private final Vad mVad;
    private final float[] mWindow = new float[WINDOW_SIZE];
    private int mWindowFill;

    /** VAD 内部采样序号 0 对应的墙钟时间。 */
    private long mBaseWallMs;

    public VadSegmenter(Vad vad) {
        mVad = vad;
    }

    /** 开始一段新的连续录音（首次启动或暂停后继续）。 */
    public void start(long wallMs) {
        mVad.reset();
        mWindowFill = 0;
        mBaseWallMs = wallMs;
    }

    public boolean isSpeechDetected() {
        return mVad.isSpeechDetected();
    }

    /** 送入一帧音频，返回此次已经结束的句子（通常为空）。 */
    public List<SpeechChunk> accept(float[] samples) {
        int offset = 0;
        while (offset < samples.length) {
            int n = Math.min(WINDOW_SIZE - mWindowFill, samples.length - offset);
            System.arraycopy(samples, offset, mWindow, mWindowFill, n);
            mWindowFill += n;
            offset += n;
            if (mWindowFill == WINDOW_SIZE) {
                mVad.acceptWaveform(mWindow.clone());
                mWindowFill = 0;
            }
        }
        return drain();
    }

    /** 暂停录音时调用，把正在进行中的那句话也强制切出来。 */
    public List<SpeechChunk> flush() {
        mVad.flush();
        mWindowFill = 0;
        return drain();
    }

    private List<SpeechChunk> drain() {
        List<SpeechChunk> out = new ArrayList<>(1);
        while (!mVad.empty()) {
            SpeechSegment segment = mVad.front();
            mVad.pop();
            float[] pcm = segment.getSamples();
            long start = mBaseWallMs + segment.getStart() * 1000L / ModelManager.SAMPLE_RATE;
            long end = start + pcm.length * 1000L / ModelManager.SAMPLE_RATE;
            out.add(new SpeechChunk(pcm, start, end));
        }
        return out;
    }
}
