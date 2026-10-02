package com.stepos.whisper.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 聊天截图里出现的昵称对应到哪个用户。机主指定一次后，之后的截图里同一个昵称直接对应。
 * 机主本人在截图里固定是“我”，不在这里记录。
 */
@Entity(tableName = "chat_aliases",
        foreignKeys = @ForeignKey(entity = User.class, parentColumns = "id", childColumns = "userId",
                onDelete = ForeignKey.CASCADE),
        indices = {@Index("userId")})
public class ChatAlias {
    /** 截图里显示的昵称。 */
    @PrimaryKey
    @NonNull
    public String name = "";

    /** 为空表示机主选择了“忽略”，这个昵称的消息按旁人处理。 */
    public Long userId;

    public long createdAt;
}
