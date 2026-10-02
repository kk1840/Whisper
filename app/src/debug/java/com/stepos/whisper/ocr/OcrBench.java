package com.stepos.whisper.ocr;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 调试用的命令行入口（只在 debug 包里）：adb shell 用 app_process 直接跑 OCR，对比模型、线程数、
 * 检测分辨率和阈值。参数形如 --threads 8 --short 640 --thresh 0.3 图片路径...
 * --short 是检测时短边缩到的像素数（0 表示原图分辨率），--scale 是在此之前对整张图的额外缩放。
 */
public final class OcrBench {
    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new HashMap<>();
        List<String> images = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                opt.put(args[i].substring(2), args[++i]);
            } else {
                images.add(args[i]);
            }
        }
        String dir = OcrEngine.DIR;
        PaddleConfig detConfig = new PaddleConfig(dir + "det/inference.yml");
        PaddleConfig recConfig = new PaddleConfig(dir + "rec/inference.yml");
        int threads = Integer.parseInt(opt.getOrDefault("threads", "4"));
        float scale = Float.parseFloat(opt.getOrDefault("scale", "1"));
        int repeat = Integer.parseInt(opt.getOrDefault("repeat", "1"));
        float thresh = Float.parseFloat(opt.getOrDefault("thresh", String.valueOf(detConfig.getFloat("thresh"))));
        float boxThresh = Float.parseFloat(opt.getOrDefault("box", String.valueOf(detConfig.getFloat("box_thresh"))));
        float unclip = Float.parseFloat(opt.getOrDefault("unclip", String.valueOf(detConfig.getFloat("unclip_ratio"))));
        int shortSide = Integer.parseInt(opt.getOrDefault("short", String.valueOf(TextDetector.DEFAULT_SHORT_SIDE)));

        long t0 = SystemClock.elapsedRealtime();
        OnnxModel detModel = new OnnxModel(opt.getOrDefault("det", dir + "det/inference.onnx"), threads);
        OnnxModel recModel = new OnnxModel(opt.getOrDefault("rec", dir + "rec/inference.onnx"), threads);
        System.out.printf(Locale.ROOT,
                "load %d ms, threads %d, short %d, scale %.2f, thresh %.2f box %.2f unclip %.2f%n",
                SystemClock.elapsedRealtime() - t0, threads, shortSide, scale, thresh, boxThresh, unclip);
        TextDetector detector = new TextDetector(detModel, thresh, boxThresh, unclip,
                detConfig.getInt("max_candidates"), shortSide);
        TextRecognizer recognizer = new TextRecognizer(recModel, recConfig);

        for (String path : images) {
            Bitmap image = BitmapFactory.decodeFile(path);
            for (int r = 0; r < repeat; r++) {
                long d0 = SystemClock.elapsedRealtime();
                List<Rect> boxes = detect(detector, image, scale);
                OcrEngine.sortBoxes(boxes);
                long d1 = SystemClock.elapsedRealtime();
                StringBuilder out = new StringBuilder();
                for (Rect box : boxes) {
                    Bitmap crop = Bitmap.createBitmap(image, box.left, box.top, box.width(), box.height());
                    TextRecognizer.Result res = recognizer.recognize(crop);
                    out.append(String.format(Locale.ROOT, "  %4d,%4d %4dx%3d %.3f %s%n",
                            box.left, box.top, box.width(), box.height(), res.score, res.text));
                }
                long d2 = SystemClock.elapsedRealtime();
                System.out.printf(Locale.ROOT, "== %s: %d boxes, det %d ms, rec %d ms%n",
                        path, boxes.size(), d1 - d0, d2 - d1);
                if (r == repeat - 1) {
                    System.out.print(out);
                }
            }
        }
        detModel.close();
        recModel.close();
    }

    private static List<Rect> detect(TextDetector detector, Bitmap image, float scale) {
        if (scale == 1f) {
            return detector.detect(image);
        }
        Bitmap scaled = Bitmap.createScaledBitmap(image, Math.round(image.getWidth() * scale),
                Math.round(image.getHeight() * scale), true);
        List<Rect> boxes = detector.detect(scaled);
        for (Rect b : boxes) {
            b.set(Math.round(b.left / scale), Math.round(b.top / scale),
                    Math.min(Math.round(b.right / scale), image.getWidth()),
                    Math.min(Math.round(b.bottom / scale), image.getHeight()));
        }
        return boxes;
    }
}
