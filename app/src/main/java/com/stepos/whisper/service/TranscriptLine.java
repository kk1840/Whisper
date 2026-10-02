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

    public TranscriptLine(long id, long timeMs, long endMs, String speaker, String text, long userId, int color,
                          int aiState) {
        this.id = id;
        this.timeMs = timeMs;
        this.endMs = endMs;
        this.speaker = speaker;
        this.text = text;
        this.userId = userId;
        this.color = color;
        this.aiState = aiState;
    }

    public TranscriptLine withSpeaker(String newSpeaker, long newUserId, int newColor) {
        return new TranscriptLine(id, timeMs, endMs, newSpeaker, text, newUserId, newColor, aiState);
    }

    public TranscriptLine withAiState(int state) {
        return new TranscriptLine(id, timeMs, endMs, speaker, text, userId, color, state);
    }
}
