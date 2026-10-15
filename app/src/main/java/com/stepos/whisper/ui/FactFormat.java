package com.stepos.whisper.ui;

import com.stepos.whisper.data.UserFact;

/** 事项的统一文字格式：时间、地点、人物、事情。 */
public final class FactFormat {
    public static final String EMPTY = "—";

    private FactFormat() {
    }

    public static String orEmpty(String s) {
        if (s == null) return EMPTY;
        String trimmed = s.trim();
        return trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed) || "无".equals(trimmed)
                || trimmed.matches("\\[\\s*\\]") ? EMPTY : s;
    }

    /** 单行摘要，用于通知。 */
    public static String oneLine(UserFact f) {
        StringBuilder sb = new StringBuilder(f.content);
        StringBuilder extra = new StringBuilder();
        append(extra, f.dueTime);
        append(extra, f.location);
        append(extra, f.people);
        if (extra.length() > 0) {
            sb.append("（").append(extra).append("）");
        }
        return sb.toString();
    }

    /** 多行详情，用于弹窗。 */
    public static String detail(UserFact f) {
        return "时间：" + orEmpty(f.dueTime) + "\n"
                + "地点：" + orEmpty(f.location) + "\n"
                + "人物：" + orEmpty(f.people) + "\n"
                + "事情：" + f.content;
    }

    private static void append(StringBuilder sb, String s) {
        if (EMPTY.equals(orEmpty(s))) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(" · ");
        }
        sb.append(s);
    }
}
