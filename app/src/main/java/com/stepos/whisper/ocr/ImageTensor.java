package com.stepos.whisper.ocr;

import android.graphics.Bitmap;

/** Bitmap 转 PaddleOCR 模型输入：B、G、R 三个平面（模型按 BGR 训练），每个值 (v / 255 - mean) / std。 */
final class ImageTensor {
    private ImageTensor() {
    }

    /**
     * 写到 dst 每个平面的前 bitmap 宽度列。平面宽 planeWidth、高为 bitmap 高度，
     * planeWidth 比 bitmap 宽时，右侧保持 dst 原值（新数组即补零）。
     */
    static void fill(Bitmap bitmap, float[] dst, int planeWidth, float[] mean, float[] std) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        int plane = planeWidth * h;
        float scaleB = 1f / (255f * std[0]);
        float scaleG = 1f / (255f * std[1]);
        float scaleR = 1f / (255f * std[2]);
        float offsetB = -mean[0] / std[0];
        float offsetG = -mean[1] / std[1];
        float offsetR = -mean[2] / std[2];
        for (int y = 0; y < h; y++) {
            int src = y * w;
            int row = y * planeWidth;
            for (int x = 0; x < w; x++) {
                int c = pixels[src + x];
                int i = row + x;
                dst[i] = (c & 0xff) * scaleB + offsetB;
                dst[plane + i] = ((c >> 8) & 0xff) * scaleG + offsetG;
                dst[2 * plane + i] = ((c >> 16) & 0xff) * scaleR + offsetR;
            }
        }
    }
}
