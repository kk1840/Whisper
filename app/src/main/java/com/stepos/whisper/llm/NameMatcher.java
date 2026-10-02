package com.stepos.whisper.llm;

import java.util.Locale;

/**
 * 聊天截图里昵称的归一化和相似度：OCR 和大模型吐出来的昵称可能多个空格、带零宽字符、
 * 认错一两个字，直接按字符串查对应关系会漏掉以前见过的人。
 */
public final class NameMatcher {
    /** 两个名字相似到这个程度（0~1）才算“看起来是同一个人”。 */
    public static final float SUGGEST_THRESHOLD = 0.6f;

    private NameMatcher() {
    }

    /** 去掉首尾空白、零宽字符和 BOM，内部连续空白压成一个空格；null 返回空串。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        boolean pendingSpace = false;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0x2060 || cp == 0xFEFF) {
                continue;
            }
            if (Character.isWhitespace(cp) || cp == 0x00A0 || cp == 0x3000) {
                pendingSpace = sb.length() > 0;
                continue;
            }
            if (pendingSpace) {
                sb.append(' ');
                pendingSpace = false;
            }
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    /** 比较用的“骨架”：归一化后去掉所有空白、常见装饰符号，字母转小写。 */
    static String skeleton(String name) {
        String n = normalize(name).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); ) {
            int cp = n.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.OTHER_LETTER) {
                sb.appendCodePoint(cp);
            }
        }
        return sb.toString();
    }

    /**
     * 两个名字的相似度（0~1）：相等为 1；一个包含另一个按短的占长的比例算；
     * 否则用编辑距离。两边都少于两个有效字符时不算相似（单字太容易撞上）。
     */
    public static float similarity(String a, String b) {
        String x = skeleton(a);
        String y = skeleton(b);
        if (x.isEmpty() || y.isEmpty()) {
            return 0f;
        }
        if (x.equals(y)) {
            return 1f;
        }
        int lx = x.codePointCount(0, x.length());
        int ly = y.codePointCount(0, y.length());
        if (lx < 2 && ly < 2) {
            return 0f;
        }
        if (lx >= 2 && ly >= 2 && (x.contains(y) || y.contains(x))) {
            return (float) Math.min(lx, ly) / Math.max(lx, ly) * 0.5f + 0.5f;
        }
        int[] cx = x.codePoints().toArray();
        int[] cy = y.codePoints().toArray();
        int dist = editDistance(cx, cy);
        return 1f - (float) dist / Math.max(cx.length, cy.length);
    }

    private static int editDistance(int[] a, int[] b) {
        int[] prev = new int[b.length + 1];
        int[] cur = new int[b.length + 1];
        for (int j = 0; j <= b.length; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length; i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length; j++) {
                int cost = a[i - 1] == b[j - 1] ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length];
    }
}
