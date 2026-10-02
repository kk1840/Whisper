package com.stepos.whisper;

import android.content.Context;
import android.content.SharedPreferences;

public final class Settings {
    public static final float DEFAULT_SPEAKER_THRESHOLD = 0.5f;

    private static final String PREFS = "settings";
    private static final String KEY_SPEAKER_THRESHOLD = "speaker_threshold";
    private static final String KEY_LIVE_CAPTION = "live_caption";
    private static final String KEY_SCREENSHOT_WATCH = "screenshot_watch";
    private static final String KEY_LAST_SCREENSHOT_ID = "last_screenshot_id";
    private static final String KEY_GLASSES_PUSH = "glasses_push";
    private static final String KEY_GLASSES_LEFT_ADDR = "glasses_left_addr";
    private static final String KEY_GLASSES_RIGHT_ADDR = "glasses_right_addr";
    private static final String KEY_GLASSES_LEFT_NAME = "glasses_left_name";
    private static final String KEY_GLASSES_RIGHT_NAME = "glasses_right_name";

    private Settings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static float getSpeakerThreshold(Context context) {
        return prefs(context).getFloat(KEY_SPEAKER_THRESHOLD, DEFAULT_SPEAKER_THRESHOLD);
    }

    public static void setSpeakerThreshold(Context context, float value) {
        prefs(context).edit().putFloat(KEY_SPEAKER_THRESHOLD, value).apply();
    }

    public static boolean isLiveCaptionEnabled(Context context) {
        return prefs(context).getBoolean(KEY_LIVE_CAPTION, true);
    }

    public static void setLiveCaptionEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_LIVE_CAPTION, enabled).apply();
    }

    /** 是否监听系统截图目录，识别聊天截图里的事项。 */
    public static boolean isScreenshotWatchEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SCREENSHOT_WATCH, true);
    }

    public static void setScreenshotWatchEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SCREENSHOT_WATCH, enabled).apply();
    }

    /** 已处理过的最大截图 MediaStore id；没有记录时为 -1。 */
    public static long getLastScreenshotId(Context context) {
        return prefs(context).getLong(KEY_LAST_SCREENSHOT_ID, -1);
    }

    public static void setLastScreenshotId(Context context, long id) {
        prefs(context).edit().putLong(KEY_LAST_SCREENSHOT_ID, id).apply();
    }

    // ---------------------------------------------------------------- Even G2 眼镜

    /** 是否把熟人事项推送到 Even G2 眼镜。 */
    public static boolean isGlassesPushEnabled(Context context) {
        return prefs(context).getBoolean(KEY_GLASSES_PUSH, true);
    }

    public static void setGlassesPushEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_GLASSES_PUSH, enabled).apply();
    }

    /** 记住的左镜腿蓝牙地址；没配对过为 null。 */
    public static String getGlassesLeftAddress(Context context) {
        return prefs(context).getString(KEY_GLASSES_LEFT_ADDR, null);
    }

    /** 记住的右镜腿蓝牙地址；没配对过为 null。 */
    public static String getGlassesRightAddress(Context context) {
        return prefs(context).getString(KEY_GLASSES_RIGHT_ADDR, null);
    }

    /** 记住的左镜腿广播名（地址变化时按名字兜底）。 */
    public static String getGlassesLeftName(Context context) {
        return prefs(context).getString(KEY_GLASSES_LEFT_NAME, null);
    }

    public static String getGlassesRightName(Context context) {
        return prefs(context).getString(KEY_GLASSES_RIGHT_NAME, null);
    }

    /** 配对成功后记住两条镜腿的地址和名字，之后按地址 / 名字定向扫描重连。 */
    public static void setGlassesArms(Context context, String leftAddr, String leftName,
                                      String rightAddr, String rightName) {
        prefs(context).edit()
                .putString(KEY_GLASSES_LEFT_ADDR, leftAddr)
                .putString(KEY_GLASSES_LEFT_NAME, leftName)
                .putString(KEY_GLASSES_RIGHT_ADDR, rightAddr)
                .putString(KEY_GLASSES_RIGHT_NAME, rightName)
                .apply();
    }

    /** 忘记已配对的眼镜。 */
    public static void clearGlassesArms(Context context) {
        prefs(context).edit()
                .remove(KEY_GLASSES_LEFT_ADDR).remove(KEY_GLASSES_LEFT_NAME)
                .remove(KEY_GLASSES_RIGHT_ADDR).remove(KEY_GLASSES_RIGHT_NAME)
                .apply();
    }
}
