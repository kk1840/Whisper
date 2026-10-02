package com.stepos.whisper.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "utterances",
        foreignKeys = @ForeignKey(entity = User.class, parentColumns = "id", childColumns = "userId",
                onDelete = ForeignKey.SET_NULL),
        indices = {@Index("userId"), @Index("startTime")})
public class Utterance {
    @PrimaryKey(autoGenerate = true)
    public long id;

    /** 为空表示说话人未知（太短无法提取声纹且无法归属）。 */
    public Long userId;

    @NonNull
    public String text = "";

    public long startTime;

    public long endTime;

    /** AI 事项提取状态：{@link #AI_NONE}、{@link #AI_QUEUED}、{@link #AI_DONE}。 */
    @ColumnInfo(defaultValue = "0")
    public int aiState;

    public static final int AI_NONE = 0;
    public static final int AI_QUEUED = 1;
    public static final int AI_DONE = 2;
}
