package com.stepos.whisper.notification;

import androidx.annotation.StringRes;

import com.stepos.whisper.R;

/** Notification origin comes from the posted package, never from message contents. */
public final class NotificationSource {
    public static final String WECHAT = "com.tencent.mm";
    public static final String FEISHU = "com.ss.android.lark";
    public static final String DEMO = "com.stepos.whisper.notificationdemo";

    private NotificationSource() { }

    public static boolean isSupported(String packageName) {
        return WECHAT.equals(packageName) || FEISHU.equals(packageName) || DEMO.equals(packageName);
    }

    @StringRes
    public static int label(String packageName) {
        if (WECHAT.equals(packageName)) return R.string.transcript_notification_wechat;
        if (FEISHU.equals(packageName)) return R.string.transcript_notification_feishu;
        if (DEMO.equals(packageName)) return R.string.transcript_notification_demo;
        return R.string.transcript_notification;
    }
}
