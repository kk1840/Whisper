package com.stepos.whisper.screenshot;

import android.graphics.Rect;

import com.stepos.whisper.ocr.OcrResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把聊天截图的 OCR 结果写成给大模型看的文本：OCR 丢掉了气泡的左右排版，而聊天界面里靠右的是机主自己的消息、
 * 靠左的是对方的，所以每行前面补上 [左]/[右]/[中] 标记，消息之间的大间隔用空行表示。
 * <p>
 * 左右的判断以“一条消息”（纵向挨在一起的几行）为单位，同一条消息的几行标记一致，多行气泡最后一行短一截
 * 不会被单独判成居中。先从整张截图里学出左侧气泡的起始列和右侧气泡的结束列（头像旁边，各条消息都对齐），
 * 一条消息只贴着其中一侧就按那一侧算；两侧都贴着或都不贴（很宽的气泡、只有一两条消息）再按固定比例兜底。
 */
final class ChatLayoutText {
    /** 状态栏区域，时间、电量等不是聊天内容。 */
    private static final float STATUS_BAR_RATIO = 0.04f;
    /** 兜底：左边界落在屏幕最左这一段的是左侧气泡（含头像后的起始位置）或发送者昵称。 */
    private static final float LEFT_EDGE_RATIO = 0.17f;
    /** 兜底：右边界超过这里的是右侧气泡。 */
    private static final float RIGHT_EDGE_RATIO = 0.78f;
    /** 相邻两行的纵向间距超过上一行高度的这个倍数，视为两条消息。 */
    private static final float GAP_RATIO = 0.8f;
    /** 单个字符且置信度低的多半是图标被认成了字。 */
    private static final float ICON_SCORE = 0.6f;
    /** 学习对齐列时只看紧贴左 / 右头像列这一段里的行，居中的时间、系统提示不会被学成对齐列。 */
    private static final float ANCHOR_LEFT_MAX = 0.22f;
    private static final float ANCHOR_RIGHT_MIN = 0.78f;
    /** 对齐列的分桶宽度和“贴着对齐列”的容差（相对屏宽）。 */
    private static final float ANCHOR_BUCKET = 0.01f;
    private static final float ANCHOR_TOLERANCE = 0.02f;
    /** 至少这么多行对齐到同一列才算学到了对齐列。 */
    private static final int ANCHOR_MIN_COUNT = 2;

    private ChatLayoutText() {
    }

    /** 没有可用文字时返回 null。 */
    static String build(OcrResult result, int width, int height) {
        List<OcrResult.Line> lines = new ArrayList<>();
        for (OcrResult.Line line : result.lines) {
            String text = line.text.trim();
            if (text.isEmpty() || line.box.bottom < height * STATUS_BAR_RATIO
                    || (text.codePointCount(0, text.length()) == 1 && line.score < ICON_SCORE)) {
                continue;
            }
            lines.add(line);
        }
        if (lines.isEmpty()) {
            return null;
        }

        // 按纵向间隔切成消息块。
        List<List<OcrResult.Line>> blocks = new ArrayList<>();
        List<OcrResult.Line> current = new ArrayList<>();
        Rect prev = null;
        for (OcrResult.Line line : lines) {
            if (prev != null && line.box.top - prev.bottom > prev.height() * GAP_RATIO) {
                blocks.add(current);
                current = new ArrayList<>();
            }
            current.add(line);
            prev = line.box;
        }
        blocks.add(current);

        float leftAnchor = anchor(lines, width, true);
        float rightAnchor = anchor(lines, width, false);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            List<OcrResult.Line> block = blocks.get(i);
            if (i > 0) {
                sb.append('\n');
            }
            String side = sideOf(block, width, leftAnchor, rightAnchor);
            for (OcrResult.Line line : block) {
                sb.append('[').append(side).append("] ").append(line.text.trim()).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 学出左侧（或右侧）的对齐列：靠左那一段里出现次数最多的左边界（靠右则是右边界）。
     * 没有足够多的行对齐到同一列时返回 NaN。
     */
    private static float anchor(List<OcrResult.Line> lines, int width, boolean left) {
        Map<Integer, Integer> buckets = new HashMap<>();
        int bestBucket = Integer.MIN_VALUE;
        int bestCount = 0;
        for (OcrResult.Line line : lines) {
            float edge = left ? (float) line.box.left / width : (float) line.box.right / width;
            if (left ? edge > ANCHOR_LEFT_MAX : edge < ANCHOR_RIGHT_MIN) {
                continue;
            }
            int bucket = Math.round(edge / ANCHOR_BUCKET);
            int count = buckets.merge(bucket, 1, Integer::sum);
            if (count > bestCount) {
                bestCount = count;
                bestBucket = bucket;
            }
        }
        return bestCount >= ANCHOR_MIN_COUNT ? bestBucket * ANCHOR_BUCKET : Float.NaN;
    }

    private static String sideOf(List<OcrResult.Line> block, int width, float leftAnchor, float rightAnchor) {
        int minLeft = Integer.MAX_VALUE;
        int maxRight = Integer.MIN_VALUE;
        for (OcrResult.Line line : block) {
            minLeft = Math.min(minLeft, line.box.left);
            maxRight = Math.max(maxRight, line.box.right);
        }
        float left = (float) minLeft / width;
        float right = (float) maxRight / width;
        boolean nearLeft = !Float.isNaN(leftAnchor) && Math.abs(left - leftAnchor) <= ANCHOR_TOLERANCE;
        boolean nearRight = !Float.isNaN(rightAnchor) && Math.abs(right - rightAnchor) <= ANCHOR_TOLERANCE;
        if (nearLeft && !nearRight) {
            return "左";
        }
        if (nearRight && !nearLeft) {
            return "右";
        }
        return left < LEFT_EDGE_RATIO ? "左" : right > RIGHT_EDGE_RATIO ? "右" : "中";
    }
}
