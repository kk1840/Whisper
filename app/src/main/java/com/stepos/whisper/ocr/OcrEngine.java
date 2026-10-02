package com.stepos.whisper.ocr;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;

import com.stepos.whisper.asr.ModelManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PP-OCRv6 截图文字识别：只做文字检测和文字识别，不做文档方向分类、文档矫正和文本行方向分类。
 * 模型从系统目录 {@link #DIR} 加载（不打包进 APK），det、rec 两个子目录直接放 PaddleOCR 官方导出的
 * inference.onnx 和 inference.yml，阈值和字典都从 yml 读取，换模型时整目录替换即可。
 */
public class OcrEngine implements AutoCloseable {
    private static final String TAG = "OcrEngine";

    public static final String DIR = ModelManager.DIR + "ocr/";
    private static final String DET_MODEL = DIR + "det/inference.onnx";
    private static final String DET_CONFIG = DIR + "det/inference.yml";
    private static final String REC_MODEL = DIR + "rec/inference.onnx";
    private static final String REC_CONFIG = DIR + "rec/inference.yml";
    private static final String[] FILES = {DET_MODEL, DET_CONFIG, REC_MODEL, REC_CONFIG};

    private static final int NUM_THREADS = 4;
    /** 长截图分段检测时相邻两段的最小重叠高度，必须大于单行文字的高度。 */
    private static final int TILE_OVERLAP = 256;
    /** 与 PaddleOCR 排序一致：左上角纵坐标相差不到这么多像素的框按横坐标排。 */
    private static final int SAME_ROW_PX = 10;

    private final OnnxModel mDetModel;
    private final OnnxModel mRecModel;
    private final TextDetector mDetector;
    private final TextRecognizer mRecognizer;

    /** 加载模型，耗时，必须在后台线程调用。模型文件缺失时抛 IllegalStateException，消息可直接显示。 */
    public OcrEngine() throws IOException {
        for (String p : FILES) {
            if (!new File(p).canRead()) {
                throw new IllegalStateException("缺少模型文件 " + p + "，请先运行 scripts/push-models.ps1");
            }
        }
        long t0 = SystemClock.elapsedRealtime();
        PaddleConfig detConfig = new PaddleConfig(DET_CONFIG);
        PaddleConfig recConfig = new PaddleConfig(REC_CONFIG);
        mDetModel = new OnnxModel(DET_MODEL, NUM_THREADS);
        try {
            mRecModel = new OnnxModel(REC_MODEL, NUM_THREADS);
        } catch (RuntimeException e) {
            mDetModel.close();
            throw e;
        }
        mDetector = new TextDetector(mDetModel, detConfig.getFloat("thresh"), detConfig.getFloat("box_thresh"),
                detConfig.getFloat("unclip_ratio"), detConfig.getInt("max_candidates"));
        mRecognizer = new TextRecognizer(mRecModel, recConfig);
        Log.i(TAG, "models loaded in " + (SystemClock.elapsedRealtime() - t0) + " ms");
    }

    /** 耗时，必须在后台线程调用。 */
    public OcrResult recognize(Bitmap image) {
        long t0 = SystemClock.elapsedRealtime();
        List<Rect> boxes = detect(image);
        sortBoxes(boxes);
        long t1 = SystemClock.elapsedRealtime();

        List<OcrResult.Line> lines = new ArrayList<>();
        for (Rect box : boxes) {
            Bitmap crop = Bitmap.createBitmap(image, box.left, box.top, box.width(), box.height());
            TextRecognizer.Result r = mRecognizer.recognize(crop);
            if (crop != image) {
                crop.recycle();
            }
            if (!r.text.isEmpty()) {
                lines.add(new OcrResult.Line(r.text, r.score, box));
            }
        }
        long t2 = SystemClock.elapsedRealtime();
        Log.i(TAG, image.getWidth() + "x" + image.getHeight() + ": " + boxes.size() + " boxes, "
                + lines.size() + " lines, det " + (t1 - t0) + " ms, rec " + (t2 - t1) + " ms");
        return new OcrResult(lines, t1 - t0, t2 - t1);
    }

    /**
     * 长截图（缩放后高度仍超过检测上限）不再整体缩小，而是纵向分段检测。每个框只保留在它中心
     * 所在的那一段，相邻两段以重叠区中线为界，既去掉重复，也丢掉被段边界切开的半行。
     */
    private List<Rect> detect(Bitmap image) {
        int w = image.getWidth();
        int h = image.getHeight();
        // 检测器会先按短边缩放，所以一段在原图上可以有 MAX_SIDE_LIMIT / ratio 这么高。
        int tile = (int) (TextDetector.MAX_SIDE_LIMIT / mDetector.ratioFor(w, h));
        if (h <= tile || w > tile) {
            return mDetector.detect(image);
        }
        List<Integer> tops = new ArrayList<>();
        for (int top = 0; ; top += tile - TILE_OVERLAP) {
            if (top + tile >= h) {
                tops.add(h - tile);
                break;
            }
            tops.add(top);
        }
        List<Rect> result = new ArrayList<>();
        for (int i = 0; i < tops.size(); i++) {
            int top = tops.get(i);
            int ownTop = i == 0 ? 0 : (tops.get(i - 1) + tile + top) / 2;
            int ownBottom = i == tops.size() - 1 ? h : (top + tile + tops.get(i + 1)) / 2;
            Bitmap part = Bitmap.createBitmap(image, 0, top, w, tile);
            for (Rect r : mDetector.detect(part)) {
                r.offset(0, top);
                if (r.centerY() >= ownTop && r.centerY() < ownBottom) {
                    result.add(r);
                }
            }
            part.recycle();
        }
        return result;
    }

    static void sortBoxes(List<Rect> boxes) {
        boxes.sort((a, b) -> a.top != b.top ? Integer.compare(a.top, b.top) : Integer.compare(a.left, b.left));
        for (int i = 0; i < boxes.size() - 1; i++) {
            for (int j = i; j >= 0; j--) {
                Rect cur = boxes.get(j);
                Rect next = boxes.get(j + 1);
                if (Math.abs(next.top - cur.top) >= SAME_ROW_PX || next.left >= cur.left) {
                    break;
                }
                boxes.set(j, next);
                boxes.set(j + 1, cur);
            }
        }
    }

    @Override
    public void close() {
        mDetModel.close();
        mRecModel.close();
    }
}
