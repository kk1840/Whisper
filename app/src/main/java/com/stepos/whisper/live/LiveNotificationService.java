package com.stepos.whisper.live;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** 只用于在 Manifest 中向 SystemUI 声明本应用支持实况通知，没有任何逻辑。 */
public class LiveNotificationService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        stopSelf(startId);
        return START_NOT_STICKY;
    }
}
