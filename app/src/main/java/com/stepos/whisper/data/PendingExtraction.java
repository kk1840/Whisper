package com.stepos.whisper.data;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** 等待交给大模型提取事项的一段会话；断网或请求失败时留在表里稍后重试。 */
@Entity(tableName = "pending_extractions",
        foreignKeys = @ForeignKey(entity = User.class, parentColumns = "id", childColumns = "userId",
                onDelete = ForeignKey.CASCADE),
        indices = {@Index("userId")})
public class PendingExtraction {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public long userId;

    public long startTime;

    public long endTime;

    public long createdAt;

    public int attempts;
}
