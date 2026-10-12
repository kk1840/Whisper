package com.stepos.whisper.notification;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.util.Log;

/**
 * 通知监听权限的开关。本应用以 system uid 运行、有平台签名，可以直接写 secure 设置里的
 * enabled_notification_listeners 把自己加进监听名单（普通应用做不到，要用户去设置页开）。
 */
public final class NotificationAccessHelper {
    private static final String TAG = "NotificationAccess";
    private static final String SETTING = "enabled_notification_listeners";

    private NotificationAccessHelper() {
    }

    public static boolean isEnabled(Context context) {
        String flat = Settings.Secure.getString(context.getContentResolver(), SETTING);
        return contains(context, flat);
    }

    /** 确保监听服务在系统名单里；不在就追加并要求重绑。写不进 secure 设置时返回 false（用 adb 兜底）。 */
    public static boolean ensureEnabled(Context context) {
        String flat = Settings.Secure.getString(context.getContentResolver(), SETTING);
        if (contains(context, flat)) {
            return true;
        }
        ComponentName component = new ComponentName(context, WeChatNotificationListener.class);
        String value = flat == null || flat.trim().isEmpty()
                ? component.flattenToString()
                : flat.trim() + ":" + component.flattenToString();
        try {
            Settings.Secure.putString(context.getContentResolver(), SETTING, value);
        } catch (SecurityException e) {
            Log.w(TAG, "cannot write " + SETTING, e);
            return false;
        }
        // 改完名单主动要求重绑，不必等系统轮询。
        NotificationListenerService.requestRebind(component);
        Log.i(TAG, "notification listener enabled");
        return true;
    }

    private static boolean contains(Context context, String flat) {
        if (flat == null || flat.isEmpty()) {
            return false;
        }
        ComponentName mine = new ComponentName(context, WeChatNotificationListener.class);
        for (String entry : flat.split(":")) {
            ComponentName c = ComponentName.unflattenFromString(entry);
            if (mine.equals(c)) {
                return true;
            }
        }
        return false;
    }
}
