package com.stepos.whisper.asr;

import java.util.ArrayList;
import java.util.List;

/**
 * 跟读进度的模糊匹配：把识别文字和朗读原文逐字对齐，允许识别错字、漏字、多字，
 * 以及数字/同音常见字的差异，返回原文已读到的位置。
 */
public class ReadingTracker {
    /** 识别出的一个字最多向前跳过这么多个原文字去找匹配，跳过的字视为“读过但没识别准”。 */
    private static final int LOOKAHEAD = 8;

    private static final String[] SAME_SOUND = {
            "的地得", "他她它", "在再", "做作坐", "那哪", "吗嘛", "以已", "和合", "里理", "到道",
            "是事市", "后候", "带代", "医一衣", "园元圆原", "卡咖", "买卖", "点店", "饭范",
    };
    private static final String CN_DIGITS = "零一二三四五六七八九";

    private final String mPassage;
    /** 原文中有效字（汉字/数字）的规范化形式及其在原文中的下标。 */
    private final char[] mChars;
    private final int[] mIndex;

    public ReadingTracker(String passage) {
        mPassage = passage;
        List<Integer> idx = new ArrayList<>();
        StringBuilder chars = new StringBuilder();
        for (int i = 0; i < passage.length(); i++) {
            char c = passage.charAt(i);
            if (isContent(c)) {
                idx.add(i);
                chars.append(normalize(c));
            }
        }
        mChars = chars.toString().toCharArray();
        mIndex = new int[idx.size()];
        for (int i = 0; i < mIndex.length; i++) {
            mIndex[i] = idx.get(i);
        }
    }

    public String getPassage() {
        return mPassage;
    }

    public int size() {
        return mChars.length;
    }

    /** 返回原文中已读过的有效字数（0..size）。 */
    public int progress(String recognized) {
        int p = 0;
        for (int i = 0; i < recognized.length() && p < mChars.length; i++) {
            char r = recognized.charAt(i);
            if (!isContent(r)) {
                continue;
            }
            r = normalize(r);
            int end = Math.min(mChars.length, p + LOOKAHEAD);
            for (int k = p; k < end; k++) {
                if (mChars[k] == r) {
                    p = k + 1;
                    break;
                }
            }
        }
        return p;
    }

    /** 已读的有效字数换算成原文字符串中的结束下标（含其后的标点），用于高亮。 */
    public int passageEnd(int progress) {
        if (progress <= 0) {
            return 0;
        }
        if (progress >= mIndex.length) {
            return mPassage.length();
        }
        int end = mIndex[progress - 1] + 1;
        while (end < mPassage.length() && !isContent(mPassage.charAt(end))) {
            end++;
        }
        return end;
    }

    private static boolean isContent(char c) {
        return (c >= '\u4e00' && c <= '\u9fff') || (c >= '0' && c <= '9');
    }

    private static char normalize(char c) {
        if (c >= '0' && c <= '9') {
            return CN_DIGITS.charAt(c - '0');
        }
        if (c == '两') {
            return '二';
        }
        for (String group : SAME_SOUND) {
            if (group.indexOf(c) >= 0) {
                return group.charAt(0);
            }
        }
        return c;
    }
}
