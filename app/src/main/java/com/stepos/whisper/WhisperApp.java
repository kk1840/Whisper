package com.stepos.whisper;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;

import com.stepos.whisper.glasses.GlassesManager;

public class WhisperApp extends Application {
    /** 常驻录音状态（灵动岛计时胶囊）。实况通知需要高重要级别，但不响铃不振动。 */
    public static final String CHANNEL_LIVE_RECORDING = "live_recording";
    /** 熟人事项、新陌生人、新事项、AI 异常等提醒类实况通知。 */
    public static final String CHANNEL_LIVE_ALERT = "live_alert";
    /** 截图识别进度。每张截图都会弹，不响铃不振动。 */
    public static final String CHANNEL_LIVE_PROGRESS = "live_progress";

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        // 改用实况通知前的旧渠道。
        nm.deleteNotificationChannel("recording");
        nm.deleteNotificationChannel("alert");

        NotificationChannel recording = new NotificationChannel(CHANNEL_LIVE_RECORDING,
                getString(R.string.notif_channel_recording), NotificationManager.IMPORTANCE_HIGH);
        recording.setSound(null, null);
        recording.enableVibration(false);
        recording.setShowBadge(false);
        nm.createNotificationChannel(recording);

        NotificationChannel alert = new NotificationChannel(CHANNEL_LIVE_ALERT,
                getString(R.string.notif_channel_alert), NotificationManager.IMPORTANCE_HIGH);
        alert.enableVibration(true);
        alert.setShowBadge(true);
        nm.createNotificationChannel(alert);

        NotificationChannel progress = new NotificationChannel(CHANNEL_LIVE_PROGRESS,
                getString(R.string.notif_channel_progress), NotificationManager.IMPORTANCE_HIGH);
        progress.setSound(null, null);
        progress.enableVibration(false);
        progress.setShowBadge(false);
        nm.createNotificationChannel(progress);

        // Even G2 眼镜连接归应用级管理：录音服务和配对界面共用同一条连接。
        GlassesManager.init(this);
    }
}
