package com.stepos.whisper.ui;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

import com.stepos.whisper.R;
import com.stepos.whisper.data.FactCard;
import com.stepos.whisper.databinding.OverlayReminderBinding;

import java.util.List;

/**
 * 某人隔了一段时间再开口时弹出的全屏提醒：屏幕变暗，中间是和这个人有关的全部事项卡片，
 * 可以像主界面一样拖动、甩到最后。点卡片打开这个人的详情，点空白处关闭。
 * 用悬浮窗实现，不管当前在不在本应用里都能弹出。只在主线程调用。
 */
public class ReminderOverlay {
    private static final long AUTO_DISMISS_MS = 60_000;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mDismissRunnable = this::dismiss;
    private View mView;

    public ReminderOverlay(Context context) {
        mContext = new ContextThemeWrapper(context.getApplicationContext(), R.style.Theme_Whisper);
        mWindowManager = mContext.getSystemService(WindowManager.class);
    }

    /** 没有悬浮窗权限时返回 false，调用方仍会发通知兜底。 */
    public boolean show(long userId, String name, List<FactCard> facts) {
        if (!android.provider.Settings.canDrawOverlays(mContext)) {
            return false;
        }
        dismiss();

        OverlayReminderBinding binding = OverlayReminderBinding.inflate(LayoutInflater.from(mContext));
        binding.title.setText(mContext.getString(R.string.focus_title, name, facts.size()));
        binding.scrim.setOnClickListener(v -> dismiss());
        binding.cards.setListener(new CardStackView.Listener() {
            @Override
            public void onCardClick(FactCard card) {
                dismiss();
                mContext.startActivity(UserDetailActivity.createIntent(mContext, userId));
            }

            @Override
            public void onTopChanged(int index, int count) {
                binding.index.setText(count <= 1 ? ""
                        : mContext.getString(R.string.card_index, index + 1, count));
                // 翻看卡片时重新计时自动关闭。
                mHandler.removeCallbacks(mDismissRunnable);
                mHandler.postDelayed(mDismissRunnable, AUTO_DISMISS_MS);
            }
        });
        binding.cards.submit(facts);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

        mView = binding.getRoot();
        mView.setAlpha(0f);
        binding.content.setScaleX(0.85f);
        binding.content.setScaleY(0.85f);
        mWindowManager.addView(mView, lp);
        mView.animate().alpha(1f).setDuration(200).start();
        binding.content.animate().scaleX(1f).scaleY(1f).setDuration(260).start();
        mHandler.removeCallbacks(mDismissRunnable);
        mHandler.postDelayed(mDismissRunnable, AUTO_DISMISS_MS);
        return true;
    }

    public void dismiss() {
        mHandler.removeCallbacks(mDismissRunnable);
        if (mView != null) {
            mWindowManager.removeView(mView);
            mView = null;
        }
    }
}
