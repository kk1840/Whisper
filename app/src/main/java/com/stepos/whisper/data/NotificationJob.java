package com.stepos.whisper.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 一条等待处理的微信通知消息（发送者昵称 + 文本），仿 {@link ScreenshotJob} 的队列设计：
 * 先落库再处理，断网或进程重启后接着来，提取完成或放弃时删除。
 */
@Entity(tableName = "notification_jobs")
public class NotificationJob {
    @PrimaryKey(autoGenerate = true)
    public long id;

    /** 发送者的微信昵称：单聊是联系人昵称，群聊尽量取到发言人昵称，取不到时是群名。 */
    @NonNull
    public String sender = "";

    /** 消息文本（可能被通知栏截断；短时间连发的多条已合并成一条，换行分隔）。 */
    @NonNull
    public String text = "";

    /** 通知发出的时间。 */
    public long postTime;

    /** 请求被拒绝的次数。 */
    public int attempts;

    public long createdAt;
}
