package com.stepos.whisper.asr;

import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineStream;

/**
 * 流式 zipformer，只用于界面上边说边出的实时文字。最终结果以 Paraformer 为准。
 * 非线程安全，只能在音频处理线程上调用。
 */
public class StreamingPreview {
    private final OnlineRecognizer mRecognizer;
    private OnlineStream mStream;
    private String mLastText = "";

    public StreamingPreview(OnlineRecognizer recognizer) {
        mRecognizer = recognizer;
        mStream = recognizer.createStream("");
    }

    /** 送入一帧音频，返回当前句的累计识别文本。 */
    public String accept(float[] samples) {
        mStream.acceptWaveform(samples, ModelManager.SAMPLE_RATE);
        while (mRecognizer.isReady(mStream)) {
            mRecognizer.decode(mStream);
        }
        mLastText = ChineseTextFilter.apply(mRecognizer.getResult(mStream).getText());
        return mLastText;
    }

    public String getText() {
        return mLastText;
    }

    /** 一句话结束（由 VAD 判定）后清空，开始下一句。 */
    public void reset() {
        mRecognizer.reset(mStream);
        mLastText = "";
    }

    public void release() {
        if (mStream != null) {
            mStream.release();
            mStream = null;
        }
    }
}
