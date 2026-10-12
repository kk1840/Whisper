package com.stepos.whisper.ui;

import android.animation.AnimatorSet;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;


import com.stepos.whisper.R;
import com.stepos.whisper.data.FactCard;
import com.stepos.whisper.data.UserColors;
import com.stepos.whisper.data.UserFact;
import com.stepos.whisper.databinding.ItemMergeCardBinding;
import com.stepos.whisper.databinding.OverlayMergeBinding;
import com.stepos.whisper.llm.MergeSuggestion;

/**
 * “这两条待办是同一件事？”的全屏合并确认：黑底，上下并排两张卡片（已有待办在上、微信新消息在下），
 * 点「合并成一条」后两张卡向中间靠拢、重叠时爆一圈光晕和火花、换成合并后的那张卡弹一下落定，
 * 再朝主界面卡堆的方向飞回并淡出——“回到原位置”。选「保留两条」、点空白或超时不做任何改动
 * （候选本来就已作为独立待办入库）。用悬浮窗实现，不管当前在不在本应用里都能弹出。只在主线程调用。
 */
public class MergeOverlay {
    private static final long AUTO_DISMISS_MS = 60_000;
    /** 上下两张卡离舞台中心的初始偏移（dp），也是两张卡的间距的一半。 */
    private static final float CARD_OFFSET_DP = 90f;
    /** 初始倾角：上下两张卡反向微倾，融合时转正。 */
    private static final float CARD_TILT = 3f;
    /** 动画各阶段时长。 */
    private static final long T_CLEAR = 150L;
    private static final long T_CONVERGE = 240L;
    private static final long T_BURST = 260L;
    private static final long T_SQUASH = 120L;
    private static final long T_SETTLE = 300L;
    private static final long T_FLY_BACK = 420L;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mDismissRunnable = this::dismiss;
    private OverlayMergeBinding mView;
    private boolean mFusing;

    public MergeOverlay(Context context) {
        mContext = new ContextThemeWrapper(context.getApplicationContext(), R.style.Theme_Whisper);
        mWindowManager = mContext.getSystemService(WindowManager.class);
    }

    /** 没有悬浮窗权限时返回 false，调用方保留两条待办即可（数据本来就不丢）。 */
    public boolean show(MergeSuggestion suggestion, Runnable onConfirm) {
        if (!android.provider.Settings.canDrawOverlays(mContext)) {
            return false;
        }
        dismiss();
        mFusing = false;

        OverlayMergeBinding b = OverlayMergeBinding.inflate(LayoutInflater.from(mContext));
        mView = b;
        int color = UserColors.orDefault(suggestion.target.userColor, suggestion.userId);
        bindCard(b.cardA, suggestion.target, mContext.getString(R.string.merge_tag_existing), null);
        bindCard(b.cardB, suggestion.incoming, mContext.getString(R.string.merge_tag_incoming), null);
        bindCard(b.cardMerged, suggestion.target, mContext.getString(R.string.merge_tag_merged), suggestion);

        float offset = CARD_OFFSET_DP * mContext.getResources().getDisplayMetrics().density;
        float density = mContext.getResources().getDisplayMetrics().density;
        tilt(b.cardA, -offset, -CARD_TILT);
        tilt(b.cardB, offset, CARD_TILT);
        b.cardA.getRoot().setTranslationZ(8f * density);
        b.cardB.getRoot().setTranslationZ(8f * density);
        b.cardMerged.getRoot().setAlpha(0f);
        b.cardMerged.getRoot().setScaleX(1.12f);
        b.cardMerged.getRoot().setScaleY(1.12f);
        b.cardMerged.getRoot().setTranslationZ(0f);
        // 特效层（光晕和火花）要压在卡片上面：给它比卡片更高的 Z。
        b.fx.setTranslationZ(16f * density);

        b.scrim.setOnClickListener(v -> dismiss());
        b.keep.setOnClickListener(v -> dismiss());
        b.merge.setOnClickListener(v -> startFusion(b, color, onConfirm));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

        View root = b.getRoot();
        root.setAlpha(0f);
        b.content.setScaleX(0.9f);
        b.content.setScaleY(0.9f);
        mWindowManager.addView(root, lp);
        root.animate().alpha(1f).setDuration(200).start();
        b.content.animate().scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(new DecelerateInterpolator(1.4f)).start();
        mHandler.removeCallbacks(mDismissRunnable);
        mHandler.postDelayed(mDismissRunnable, AUTO_DISMISS_MS);
        return true;
    }

    public void dismiss() {
        mHandler.removeCallbacks(mDismissRunnable);
        if (mView != null) {
            try {
                mWindowManager.removeView(mView.getRoot());
            } catch (IllegalArgumentException ignored) {
                // 系统已经摘掉了这个窗口
            }
            mView = null;
            mFusing = false;
        }
    }

    // ---------------------------------------------------------------- 融合动画

    /** 点「合并成一条」：清场 → 靠拢 → 爆发换卡（此刻提交数据库）→ 弹性落定 → 飞回卡堆。 */
    private void startFusion(OverlayMergeBinding b, int color, Runnable onConfirm) {
        if (mFusing) {
            return;
        }
        mFusing = true;
        mHandler.removeCallbacks(mDismissRunnable);
        // 动画开始后不再允许反悔：数据马上要改了。
        b.scrim.setOnClickListener(null);
        b.keep.setEnabled(false);

        fadeOut(b.title, b.subtitle);
        fadeOut(b.merge, b.keep);

        View top = b.cardA.getRoot();
        View bottom = b.cardB.getRoot();
        View merged = b.cardMerged.getRoot();
        top.animate().translationY(0f).rotation(0f).setDuration(T_CONVERGE)
                .setInterpolator(new DecelerateInterpolator(1.4f)).start();
        bottom.animate().translationY(0f).rotation(0f).setDuration(T_CONVERGE)
                .setInterpolator(new DecelerateInterpolator(1.4f)).start();

        top.postDelayed(() -> {
            // 两卡重叠：特效爆发，旧卡淡出的同时合并卡浮上来；数据库在这里提交，
            // 之后无论窗口发生什么，合并结果都已经落库。
            if (onConfirm != null) {
                onConfirm.run();
            }
            b.fx.burst(color);
            top.animate().alpha(0f).setDuration(T_BURST * 7 / 10).start();
            bottom.animate().alpha(0f).setDuration(T_BURST * 7 / 10).start();
            merged.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(T_BURST)
                    .setInterpolator(new AccelerateDecelerateInterpolator()).start();
        }, T_CONVERGE);

        top.postDelayed(() -> {
            // 落定：先压扁再弹回（squash & stretch），像两张卡的内容真被捏到一起。
            merged.animate().scaleX(1.06f).scaleY(0.94f).setDuration(T_SQUASH)
                    .setInterpolator(new AccelerateInterpolator())
                    .withEndAction(() -> merged.animate().scaleX(1f).scaleY(1f).setDuration(T_SETTLE)
                            .setInterpolator(new OvershootInterpolator(1.2f))
                            .withEndAction(() -> top.postDelayed(() -> flyBack(b), 160))
                            .start())
                    .start();
        }, T_CONVERGE + T_BURST);
    }

    /** “回到原位置”：合并卡朝主界面卡堆顶部槽位（屏幕上方约 22%）飞去，黑幕同时淡出。 */
    private void flyBack(OverlayMergeBinding b) {
        if (mView == null) {
            return;
        }
        View root = b.getRoot();
        View merged = b.cardMerged.getRoot();
        int[] rootLoc = new int[2];
        int[] cardLoc = new int[2];
        root.getLocationOnScreen(rootLoc);
        merged.getLocationOnScreen(cardLoc);
        float targetX = rootLoc[0] + root.getWidth() / 2f;
        float targetY = rootLoc[1] + root.getHeight() * 0.22f;
        float dx = targetX - (cardLoc[0] + merged.getWidth() * merged.getScaleX() / 2f);
        float dy = targetY - (cardLoc[1] + merged.getHeight() * merged.getScaleY() / 2f);
        merged.animate()
                .translationXBy(dx)
                .translationYBy(dy)
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setDuration(T_FLY_BACK)
                .setInterpolator(new DecelerateInterpolator(1.2f))
                .start();
        root.animate().alpha(0f).setDuration(T_FLY_BACK * 9 / 10)
                .withEndAction(this::dismiss)
                .start();
    }

    private static void fadeOut(View... views) {
        for (View v : views) {
            v.animate().alpha(0f).setDuration(T_CLEAR).start();
        }
    }

    private static void tilt(ItemMergeCardBinding card, float offsetPx, float rotation) {
        View root = card.getRoot();
        root.setTranslationY(offsetPx);
        root.setRotation(rotation);
        root.setAlpha(1f);
    }

    /** 卡片内容与主界面卡堆一致；merged 为 null 时按卡片自带字段，否则用合并后的字段覆盖。 */
    private void bindCard(ItemMergeCardBinding card, FactCard data, String tag,
                          MergeSuggestion merged) {
        UserFact f = data.fact;
        card.card.setCardBackgroundColor(ColorStateList.valueOf(
                0xFF000000 | UserColors.orDefault(data.userColor, f.userId)));
        card.user.setText(data.userName);
        card.type.setText(f.type);
        card.tag.setText(tag);
        card.event.setText(merged == null ? f.content : merged.event);
        card.time.setText(mContext.getString(R.string.card_time,
                FactFormat.orEmpty(merged != null && merged.time != null ? merged.time : f.dueTime)));
        card.location.setText(mContext.getString(R.string.card_location,
                FactFormat.orEmpty(merged != null && merged.location != null ? merged.location : f.location)));
    }
}
