package com.stepos.whisper.asr;

import com.k2fsa.sherpa.onnx.OfflinePunctuation;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineStream;

/**
 * 用 Paraformer 对 VAD 切出的整句做最终识别，再加标点、过滤成只含中文和数字的文本。
 * 返回空串表示这句话没有有效内容。
 */
public class FinalRecognizer {
    private final OfflineRecognizer mRecognizer;
    private final OfflinePunctuation mPunctuation;

    public FinalRecognizer(OfflineRecognizer recognizer, OfflinePunctuation punctuation) {
        mRecognizer = recognizer;
        mPunctuation = punctuation;
    }

    public String recognize(float[] samples) {
        String raw;
        OfflineStream stream = mRecognizer.createStream();
        try {
            stream.acceptWaveform(samples, ModelManager.SAMPLE_RATE);
            mRecognizer.decode(stream);
            raw = mRecognizer.getResult(stream).getText();
        } finally {
            stream.release();
        }
        String text = ChineseTextFilter.apply(raw);
        if (!ChineseTextFilter.hasContent(text)) {
            return "";
        }
        if (mPunctuation != null) {
            text = ChineseTextFilter.apply(mPunctuation.addPunctuation(text));
        }
        return text;
    }
}
