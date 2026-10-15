package com.stepos.whisper.service;

import com.stepos.whisper.data.Utterance;

/** 界面上的一行转写。不可变，改标签时整行替换。 */
public class TranscriptLine {
    public static final long NO_USER = -1;
    /** 无法归属的短句用的颜色。 */
    public static final int COLOR_UNKNOWN = 0xFF9E9E9E;

    public final long id;
    public final long timeMs;
    public final long endMs;
    public final String speaker;
    public final String text;
    public final long userId;
    public final int color;
    /** {@link Utterance#AI_NONE} / {@link Utterance#AI_QUEUED} / {@link Utterance#AI_DONE} */
    public final int aiState;
    /** 通知任务独立于语音行 id；语音为 -1。 */
    public final long notificationJobId;
    public final boolean aliasPending;
    public final String notificationPackage;

    public boolean isNotification() {
        return notificationJobId > 0;
    }

    public static TranscriptLine notification(long jobId, long timeMs, String speaker, String text,
                                              long userId, int color, int aiState, boolean aliasPending) {
        return notification(jobId, timeMs, speaker, text, userId, color, aiState, aliasPending, null);
    }

    public static TranscriptLine notification(long jobId, long timeMs, String speaker, String text,
                                              long userId, int color, int aiState, boolean aliasPending,
                                              String notificationPackage) {
        return new TranscriptLine(-jobId, timeMs, timeMs, speaker, text, userId, color,
                aiState, jobId, aliasPending, notificationPackage);
    }

    public TranscriptLine(long id, long timeMs, long endMs, String speaker, String text, long userId, int color,
                          int aiState) {
        this(id, timeMs, endMs, speaker, text, userId, color, aiState, -1, false, null);
    }

    private TranscriptLine(long id, long timeMs, long endMs, String speaker, String text, long userId, int color,
                           int aiState, long notificationJobId, boolean aliasPending, String notificationPackage) {
        this.id = id;
        this.timeMs = timeMs;
        this.endMs = endMs;
        this.speaker = speaker;
        this.text = text;
        this.userId = userId;
        this.color = color;
        this.aiState = aiState;
        this.notificationJobId = notificationJobId;
        this.aliasPending = aliasPending;
        this.notificationPackage = notificationPackage;
    }

    public TranscriptLine withSpeaker(String newSpeaker, long newUserId, int newColor) {
        return new TranscriptLine(id, timeMs, endMs, newSpeaker, text, newUserId, newColor, aiState,
                notificationJobId, aliasPending, notificationPackage);
    }

    public TranscriptLine withAiState(int state) {
        return new TranscriptLine(id, timeMs, endMs, speaker, text, userId, color, state,
                notificationJobId, aliasPending, notificationPackage);
    }
}
