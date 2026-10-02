package com.stepos.whisper.ocr;

import android.graphics.Rect;

import java.util.List;

public class OcrResult {
    public static class Line {
        public final String text;
        /** CTC 逐字最大概率的平均值，0~1。 */
        public final float score;
        /** 原图坐标。 */
        public final Rect box;

        Line(String text, float score, Rect box) {
            this.text = text;
            this.score = score;
            this.box = box;
        }
    }

    /** 按阅读顺序（从上到下、同一行从左到右）排列。 */
    public final List<Line> lines;
    public final long detectMs;
    public final long recognizeMs;

    OcrResult(List<Line> lines, long detectMs, long recognizeMs) {
        this.lines = lines;
        this.detectMs = detectMs;
        this.recognizeMs = recognizeMs;
    }

    /** 拼成纯文本：中心落在上一个框纵向范围内的视为同一行，用空格隔开，否则换行。 */
    public String getText() {
        StringBuilder sb = new StringBuilder();
        Rect prev = null;
        for (Line line : lines) {
            if (prev != null) {
                int cy = line.box.centerY();
                sb.append(cy > prev.top && cy < prev.bottom ? ' ' : '\n');
            }
            sb.append(line.text);
            prev = line.box;
        }
        return sb.toString();
    }
}
