package com.stepos.whisper.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** 一条记录：待办（时间、地点、人物、事情），或从聊天里得知的关于这个人的事实。 */
@Entity(tableName = "user_facts",
        foreignKeys = @ForeignKey(entity = User.class, parentColumns = "id", childColumns = "userId",
                onDelete = ForeignKey.CASCADE),
        indices = {@Index("userId")})
public class UserFact {
    public static final int KIND_TODO = 0;
    public static final int KIND_FACT = 1;

    /** 待办的类型。 */
    public static final String TYPE_TODO = "待办";
    public static final String TYPE_PROMISE = "约定";

    /** 事实的类型。 */
    public static final String TYPE_RECENT = "近况";
    public static final String TYPE_PREFERENCE = "偏好";
    public static final String TYPE_INFO = "信息";

    @PrimaryKey(autoGenerate = true)
    public long id;

    public long userId;

    /** {@link #KIND_TODO} 或 {@link #KIND_FACT}。 */
    @ColumnInfo(defaultValue = "0")
    public int kind;

    /** 待办是要做的事情，事实是事实本身。 */
    @NonNull
    public String content = "";

    @NonNull
    public String type = TYPE_INFO;

    /** 时间，如 "10月8日 晚上7点" 或 "下周三"，可为空。事实不填。 */
    public String dueTime;

    /** 地点，可为空。事实不填。 */
    public String location;

    /** 涉及的人物，可为空。事实不填。 */
    public String people;

    /** 待办已完成或取消；事实已不再成立。 */
    public boolean done;

    public long createdAt;

    public long updatedAt;
}
