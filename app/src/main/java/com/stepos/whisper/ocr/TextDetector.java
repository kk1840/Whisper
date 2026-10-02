package com.stepos.whisper.ocr;

import android.graphics.Bitmap;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * PP-OCR 文字检测（DB）。预处理和后处理参数与 PaddleOCR 一致；截图里的文字都是水平的，
 * 所以文字框直接取连通域的外接矩形，不做旋转矩形拟合。
 */
final class TextDetector {
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};
    /** 与 PaddleOCR 一致：短边不足 64 才放大，长边超过 4000 必须缩小。 */
    private static final int LIMIT_SIDE_LEN = 64;
    static final int MAX_SIDE_LIMIT = 4000;
    /**
     * 检测时把短边缩到这么多像素。手机截图按原分辨率（1200 宽）检测反而漏行，缩到 640 宽
     * 一行不漏，检测耗时降到三分之一；识别仍从原图裁剪，不受影响。
     */
    static final int DEFAULT_SHORT_SIDE = 640;
    private static final int MIN_SIZE = 3;

    private final OnnxModel mModel;
    private final float mThresh;
    private final float mBoxThresh;
    private final float mUnclipRatio;
    private final int mMaxCandidates;
    private final int mShortSide;

    TextDetector(OnnxModel model, float thresh, float boxThresh, float unclipRatio, int maxCandidates) {
        this(model, thresh, boxThresh, unclipRatio, maxCandidates, DEFAULT_SHORT_SIDE);
    }

    /** shortSide 不大于 0 表示不缩小，按原图分辨率检测。 */
    TextDetector(OnnxModel model, float thresh, float boxThresh, float unclipRatio, int maxCandidates,
            int shortSide) {
        mModel = model;
        mThresh = thresh;
        mBoxThresh = boxThresh;
        mUnclipRatio = unclipRatio;
        mMaxCandidates = maxCandidates;
        mShortSide = shortSide;
    }

    /** 原图送检测前的缩放比例（不含长边超过 {@link #MAX_SIDE_LIMIT} 时的强制缩小）。 */
    float ratioFor(int srcW, int srcH) {
        int minSide = Math.min(srcW, srcH);
        if (minSide < LIMIT_SIDE_LEN) {
            return (float) LIMIT_SIDE_LEN / minSide;
        }
        if (mShortSide > 0 && minSide > mShortSide) {
            return (float) mShortSide / minSide;
        }
        return 1f;
    }

    /** 返回原图坐标下的文字框。 */
    List<Rect> detect(Bitmap image) {
        int srcW = image.getWidth();
        int srcH = image.getHeight();
        float ratio = ratioFor(srcW, srcH);
        int h = (int) (srcH * ratio);
        int w = (int) (srcW * ratio);
        if (Math.max(h, w) > MAX_SIDE_LIMIT) {
            float r = (float) MAX_SIDE_LIMIT / Math.max(h, w);
            h = (int) (h * r);
            w = (int) (w * r);
        }
        h = Math.max((int) Math.rint(h / 32.0) * 32, 32);
        w = Math.max((int) Math.rint(w / 32.0) * 32, 32);

        Bitmap scaled = w == srcW && h == srcH ? image : Bitmap.createScaledBitmap(image, w, h, true);
        float[] input = new float[3 * w * h];
        ImageTensor.fill(scaled, input, w, MEAN, STD);
        if (scaled != image) {
            scaled.recycle();
        }
        long[] outShape = new long[4];
        float[] prob = mModel.run(input, new long[]{1, 3, h, w}, outShape);
        return boxes(prob, (int) outShape[3], (int) outShape[2], srcW, srcH);
    }

    /** 概率图二值化后取 8 连通域，按 DB 的 box_thresh 过滤、unclip 外扩，再映射回原图。 */
    private List<Rect> boxes(float[] prob, int mapW, int mapH, int srcW, int srcH) {
        int n = mapW * mapH;
        boolean[] visited = new boolean[n];
        int[] stack = new int[n];
        float sx = (float) srcW / mapW;
        float sy = (float) srcH / mapH;
        List<Rect> result = new ArrayList<>();
        int candidates = 0;
        for (int start = 0; start < n && candidates < mMaxCandidates; start++) {
            if (visited[start] || prob[start] <= mThresh) {
                continue;
            }
            int minX = mapW;
            int minY = mapH;
            int maxX = -1;
            int maxY = -1;
            int sp = 0;
            stack[sp++] = start;
            visited[start] = true;
            while (sp > 0) {
                int p = stack[--sp];
                int x = p % mapW;
                int y = p / mapW;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                for (int ny = Math.max(y - 1, 0); ny <= Math.min(y + 1, mapH - 1); ny++) {
                    for (int nx = Math.max(x - 1, 0); nx <= Math.min(x + 1, mapW - 1); nx++) {
                        int q = ny * mapW + nx;
                        if (!visited[q] && prob[q] > mThresh) {
                            visited[q] = true;
                            stack[sp++] = q;
                        }
                    }
                }
            }
            candidates++;

            float bw = maxX - minX;
            float bh = maxY - minY;
            if (Math.min(bw, bh) < MIN_SIZE || meanScore(prob, mapW, minX, minY, maxX, maxY) < mBoxThresh) {
                continue;
            }
            float d = bw * bh * mUnclipRatio / (2 * (bw + bh));
            Rect box = new Rect(
                    clamp(Math.round((minX - d) * sx), srcW), clamp(Math.round((minY - d) * sy), srcH),
                    clamp(Math.round((maxX + d) * sx), srcW), clamp(Math.round((maxY + d) * sy), srcH));
            if (box.width() > 0 && box.height() > 0) {
                result.add(box);
            }
        }
        return result;
    }

    private static float meanScore(float[] prob, int mapW, int minX, int minY, int maxX, int maxY) {
        double sum = 0;
        for (int y = minY; y <= maxY; y++) {
            int row = y * mapW;
            for (int x = minX; x <= maxX; x++) {
                sum += prob[row + x];
            }
        }
        return (float) (sum / ((maxX - minX + 1) * (maxY - minY + 1)));
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(v, max));
    }
}
