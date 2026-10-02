package com.stepos.whisper.asr;

/** 识别结果只保留中文、数字和中文标点。 */
public final class ChineseTextFilter {
    private static final String KEPT_PUNCTUATION = "，。？！、：；“”‘’（）《》%";

    private ChineseTextFilter() {
    }

    public static String apply(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = toChinesePunctuation(text.charAt(i));
            if (isChinese(c) || isDigit(c) || KEPT_PUNCTUATION.indexOf(c) >= 0) {
                sb.append(c);
            } else if (c == '.' && i > 0 && i + 1 < text.length()
                    && isDigit(text.charAt(i - 1)) && isDigit(text.charAt(i + 1))) {
                sb.append(c);
            }
        }
        return stripLeadingPunctuation(sb.toString());
    }

    /** 是否至少包含一个汉字或数字。 */
    public static boolean hasContent(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isChinese(c) || isDigit(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isChinese(char c) {
        return (c >= '\u4e00' && c <= '\u9fff') || (c >= '\u3400' && c <= '\u4dbf');
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static char toChinesePunctuation(char c) {
        switch (c) {
            case ',':
                return '，';
            case '?':
                return '？';
            case '!':
                return '！';
            case ':':
                return '：';
            case ';':
                return '；';
            default:
                return c;
        }
    }

    private static String stripLeadingPunctuation(String s) {
        int i = 0;
        while (i < s.length() && KEPT_PUNCTUATION.indexOf(s.charAt(i)) >= 0 && s.charAt(i) != '“'
                && s.charAt(i) != '（' && s.charAt(i) != '《') {
            i++;
        }
        return s.substring(i);
    }
}
