package com.stepos.whisper.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 一张等待处理的聊天截图。处理分三步，每步的结果落库，断网或进程重启后从中断的那步继续：
 * OCR（得到 {@link #layoutText}）、大模型整理对话（得到 {@link #parsed}）、按参与者提取事项后删除。
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

    /** OCR 结果，每行带左右位置标记；null 表示还没识别。 */
    public String layoutText;

    /** 大模型整理出的参与者和对话（JSON）；null 表示还没整理。 */
    public String parsed;

    /** 请求被拒绝的次数。 */
    public int attempts;

    public long createdAt;
}
