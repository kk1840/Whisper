package com.stepos.whisper.ocr;

import android.graphics.Bitmap;

import java.util.List;

/** PP-OCR 文字识别（CTC）。每行单独推理，与 PaddleOCR 默认的 batch size 1 一致。 */
final class TextRecognizer {
    private static final int IMG_H = 48;
    private static final int MIN_W = 320;
    private static final int MAX_W = 3200;
    private static final float[] MEAN = {0.5f, 0.5f, 0.5f};
    private static final float[] STD = {0.5f, 0.5f, 0.5f};

    static final class Result {
        final String text;
        final float score;

        Result(String text, float score) {
            this.text = text;
            this.score = score;
        }
    }

    private final OnnxModel mModel;
    /** 下标 0 是 CTC 空白符，末尾是 PaddleOCR 默认追加的空格。 */
    private final String[] mChars;

    TextRecognizer(OnnxModel model, PaddleConfig config) {
        mModel = model;
        List<String> dict = config.getCharacterDict();
        mChars = new String[dict.size() + 2];
        mChars[0] = "";
        for (int i = 0; i < dict.size(); i++) {
            mChars[i + 1] = dict.get(i);
        }
        mChars[mChars.length - 1] = " ";
    }

    Result recognize(Bitmap line) {
        double ratio = (double) line.getWidth() / line.getHeight();
        int imgW = Math.max(MIN_W, (int) (IMG_H * ratio));
        int resizedW;
        if (imgW > MAX_W) {
            imgW = MAX_W;
            resizedW = MAX_W;
        } else {
            resizedW = Math.max(1, Math.min(imgW, (int) Math.ceil(IMG_H * ratio)));
        }
        Bitmap resized = Bitmap.createScaledBitmap(line, resizedW, IMG_H, true);
        float[] input = new float[3 * IMG_H * imgW];
        ImageTensor.fill(resized, input, imgW, MEAN, STD);
        if (resized != line) {
            resized.recycle();
        }

        long[] outShape = new long[3];
        float[] probs = mModel.run(input, new long[]{1, 3, IMG_H, imgW}, outShape);
        int steps = (int) outShape[1];
        int classes = (int) outShape[2];
        if (classes != mChars.length) {
            throw new IllegalStateException("识别模型输出 " + classes + " 类，字典加空白符和空格共 " + mChars.length + " 项");
        }

        StringBuilder text = new StringBuilder();
        double scoreSum = 0;
        int count = 0;
        int prev = 0;
        for (int t = 0; t < steps; t++) {
            int base = t * classes;
            int best = 0;
            float bestProb = probs[base];
            for (int c = 1; c < classes; c++) {
                if (probs[base + c] > bestProb) {
                    bestProb = probs[base + c];
                    best = c;
                }
            }
            if (best != 0 && best != prev) {
                text.append(mChars[best]);
                scoreSum += bestProb;
                count++;
            }
            prev = best;
        }
        return new Result(text.toString(), count == 0 ? 0f : (float) (scoreSum / count));
    }
}
