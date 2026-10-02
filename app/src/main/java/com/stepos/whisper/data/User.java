package com.stepos.whisper.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "users")
public class User {
    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public String name = "";

    /** 归一化后的平均声纹向量，float32 小端序。 */
    public byte[] embedding;

    /** 参与平均的声纹样本数。 */
    public int sampleCount;

    /** 额外的声纹（比如压低声音时的声音），多个向量首尾拼接，格式同 {@link #embedding}。 */
    public byte[] extraEmbeddings;

    public long createdAt;

    public long lastSeenAt;

    /** 机主本人。全库最多一个。 */
    @ColumnInfo(defaultValue = "0")
    public boolean isOwner;

    /** 自动建档、还没被改名的陌生人（名字为“陌生人N”）。 */
    @ColumnInfo(defaultValue = "0")
    public boolean isStranger;

    /** 界面上代表该用户的颜色（ARGB）。 */
    @ColumnInfo(defaultValue = "0")
    public int color;
}
