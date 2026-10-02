package com.stepos.whisper.live;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
import android.view.DisplayCutout;

/**
 * 灵动岛卡片弹在屏幕顶部时，标题行和前置摄像头挖孔在同一高度。SystemUI 的卡片模板里标题紧跟图标单行排开、
 * 没有右边界，太长就会被挖孔挡住，这里按挖孔左边缘算出标题可用宽度，超出部分省略。
 */
final class LiveTitle {
    /** SystemUI 卡片左边缘到卡片图标的距离（卡片外边距 + 内边距），SystemUI 没有公开这个尺寸，按实测取值。 */
    private static final float CARD_INSET_DP = 26;
    /** 标题末尾和挖孔之间至少留出的距离。 */
    private static final float CUTOUT_GAP_DP = 4;

    private LiveTitle() {
    }

    static String fit(Context context, String title) {
        if (TextUtils.isEmpty(title)) {
            return title;
        }
        Resources res = context.getResources();
        int maxWidth = maxWidth(context, res);
        if (maxWidth <= 0) {
            return title;
        }
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(frameworkDimen(res, "live_notification_card_title_size",
                TypedValue.COMPLEX_UNIT_SP, 17));
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return TextUtils.ellipsize(title, paint, maxWidth, TextUtils.TruncateAt.END).toString();
    }

    /** 没有顶部挖孔时返回 0，表示不限制。 */
    private static int maxWidth(Context context, Resources res) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return 0;
        }
        Display display = context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        DisplayCutout cutout = display == null ? null : display.getCutout();
        if (cutout == null) {
            return 0;
        }
        Rect hole = cutout.getBoundingRectTop();
        if (hole.isEmpty()) {
            return 0;
        }
        int center = res.getDisplayMetrics().widthPixels / 2;
        int holeLeft = hole.left;
        if (hole.left <= center && hole.right >= center) {
            // 有的设备上报的居中挖孔矩形整体偏向一侧（本机偏右一个半径），按屏幕中线对称取更靠左的边。
            holeLeft = Math.min(hole.left, 2 * center - hole.right);
        }
        float titleStart = dp(res, CARD_INSET_DP)
                + frameworkDimen(res, "live_notification_app_icon_size", TypedValue.COMPLEX_UNIT_DIP, 44)
                + frameworkDimen(res, "live_notification_icon_text_space", TypedValue.COMPLEX_UNIT_DIP, 8);
        return (int) (holeLeft - dp(res, CUTOUT_GAP_DP) - titleStart);
    }

    /** 读 framework 里灵动岛模板用的尺寸，找不到时用 fallback。 */
    private static float frameworkDimen(Resources res, String name, int fallbackUnit, float fallback) {
        int id = res.getIdentifier(name, "dimen", "android");
        return id != 0 ? res.getDimension(id)
                : TypedValue.applyDimension(fallbackUnit, fallback, res.getDisplayMetrics());
    }

    private static float dp(Resources res, float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, res.getDisplayMetrics());
    }
}
