package com.stepos.whisper.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 一张等待处理的聊天截图。原图由视觉模型整理成 {@link #parsed} 并落库，
 * 再按参与者提取事项后删除；断网或进程重启后复用已整理的对话。
 */
@Entity(tableName = "screenshot_jobs")
public class ScreenshotJob {
    @PrimaryKey(autoGenerate = true)
    public long id;

    /** MediaStore 的 content:// 地址。 */
    @NonNull
    public String uri = "";

    /** 被截图的应用包名（系统截图文件名里带），可为空。 */
    public String sourceApp;

    public long takenAt;

    /** 旧版 OCR 数据列，保留数据库结构；图片直传流程不再读写。 */
    public String layoutText;

    /** 大模型整理出的参与者和对话（JSON）；null 表示还没整理。 */
    public String parsed;

    /** 请求被拒绝的次数。 */
    public int attempts;

    public long createdAt;
}
