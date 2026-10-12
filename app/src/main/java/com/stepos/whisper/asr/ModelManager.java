package com.stepos.whisper.asr;

/**
 * 模型目录与采样率常量。语音识别和声纹已全部改为云端（Step 流式 + 讯飞声纹），
 * 本地只保留 OCR 模型（{@link com.stepos.whisper.ocr.OcrEngine} 用 {@link #DIR} 加载）。
 * 试过用本地流式 zipformer 做实时字幕（模型推到本目录 streaming/），在设备上加载会把
 * 整机拖挂（app 以 system uid 运行），已回退；不要再往设备推本地语音模型。
 */
public final class ModelManager {
    /** 全部音频的采样率（云端接口同样按 16k 提交）。 */
    public static final int SAMPLE_RATE = 16000;

    /** 设备上模型文件的系统目录（现在只剩 OCR 模型），由 scripts/push-models.ps1 推送。 */
    public static final String DIR = "/system/etc/whisper/models/";

    private ModelManager() {
    }
}
