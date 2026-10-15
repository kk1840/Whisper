package com.stepos.whisper.ui;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.stepos.whisper.notification.NotificationSource;
import android.view.ViewGroup;
import com.stepos.whisper.R;
import com.stepos.whisper.data.Utterance;
import com.stepos.whisper.service.TranscriptLine;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/** Offline checks called by a parent instrumentation; no activity, service or database is opened. */
public final class NotificationCardUiChecks {
    private NotificationCardUiChecks() { }

    /** Writes synthetic rows to targetContext.getCacheDir()/notification-cards-preview.png. */
    public static void runChecks(Instrumentation runner) throws Exception {
        AtomicReference<Bitmap> preview = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runner.runOnMainSync(() -> {
            try {
                Context context = new ContextThemeWrapper(runner.getTargetContext(),
                        R.style.Theme_Whisper_NoActionBar);
                preview.set(bindingChecks(context));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        Throwable error = failure.get();
        if (error instanceof Error) throw (Error) error;
        if (error instanceof Exception) throw (Exception) error;
        if (error != null) throw new AssertionError(error);
        Bitmap bitmap = preview.get();
        require(bitmap != null, "synthetic preview was rendered");
        try (FileOutputStream out = new FileOutputStream(new File(
                runner.getTargetContext().getCacheDir(), "notification-cards-preview.png"))) {
            require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out), "preview PNG encoded");
        } finally {
            bitmap.recycle();
        }
    }

    private static Bitmap bindingChecks(Context context) {
        FrameLayout parent = new FrameLayout(context);
        TranscriptLine queued = TranscriptLine.notification(901, 0, "Synthetic sender",
                "Synthetic notification: meet tomorrow.", TranscriptLine.NO_USER,
                TranscriptLine.COLOR_UNKNOWN, Utterance.AI_QUEUED, true, NotificationSource.WECHAT);
        TranscriptLine done = TranscriptLine.notification(902, 1000, "Synthetic resolved sender",
                "Synthetic notification: meeting confirmed.", 7, 0xFF1565C0,
                Utterance.AI_DONE, false, NotificationSource.FEISHU);
        TranscriptLine voice = new TranscriptLine(903, 2000, 3000, "Synthetic voice speaker",
                "Synthetic voice: bring a notebook.", 8, 0xFF6A1B9A, Utterance.AI_NONE);
        AtomicReference<TranscriptLine> clicked = new AtomicReference<>();
        AtomicReference<TranscriptLine> longClicked = new AtomicReference<>();
        TranscriptAdapter adapter = new TranscriptAdapter(clicked::set, longClicked::set);
        // ListAdapter's initial list is installed synchronously; no background diff is needed.
        TranscriptLine demo = TranscriptLine.notification(904, 1000, "离线演示联系人很长的姓名",
                "Synthetic demo notification with [brackets].", 7, 0xFF1565C0,
                Utterance.AI_DONE, false, NotificationSource.DEMO);
        TranscriptLine unknown = TranscriptLine.notification(905, 1000, "旧任务",
                "Synthetic legacy notification", 7, 0xFF1565C0, Utterance.AI_DONE, false);
        adapter.submitList(Arrays.asList(queued, done, voice, demo, unknown));
        require(adapter.getItemCount() == 5, "five synthetic rows installed");
        TranscriptAdapter.Holder holder = adapter.onCreateViewHolder(parent, 0);
        parent.addView(holder.itemView);
        adapter.onBindViewHolder(holder, 0);
        checkNotification(holder, queued, context, R.string.ai_mark_queued);
        require(holder.binding.aliasPending.getVisibility() == View.VISIBLE,
                "pending alias label visible");
        text(holder.binding.aliasPending, context.getString(R.string.transcript_alias_pending),
                "pending alias label");
        checkBackground(holder.itemView, true);
        require(!holder.itemView.isLongClickable() && !holder.itemView.performLongClick()
                && longClicked.get() == null, "notification cannot invoke voice reassignment");
        require(holder.itemView.performClick() && clicked.get() == queued,
                "notification click passes the bound synthetic sender");

        adapter.onBindViewHolder(holder, 1);
        checkNotification(holder, done, context, R.string.ai_mark_done);
        require(holder.binding.aliasPending.getVisibility() == View.GONE,
                "resolved notification clears pending label");

        adapter.onBindViewHolder(holder, 2);
        text(holder.binding.speaker, voice.speaker, "reused voice sender");
        text(holder.binding.text, voice.text, "reused voice body");
        require(holder.binding.notificationType.getVisibility() == View.GONE
                && holder.binding.aliasPending.getVisibility() == View.GONE,
                "voice reuse clears notification labels");
        require(holder.binding.ai.getVisibility() == View.GONE
                && holder.binding.ai.getText().length() == 0
                && holder.binding.ai.getBackgroundTintList() == null,
                "voice with no AI clears the recycled AI badge");
        checkBackground(holder.itemView, false);
        require(holder.itemView.isLongClickable() && holder.itemView.performLongClick()
                && longClicked.get() == voice, "voice reuse restores voice long-click");
        require(holder.itemView.performClick() && clicked.get() == voice,
                "voice reuse replaces click target");

        longClicked.set(null);
        adapter.onBindViewHolder(holder, 0);
        require(!holder.itemView.isLongClickable() && !holder.itemView.performLongClick()
                && longClicked.get() == null, "notification reuse removes the voice listener");
        checkBackground(holder.itemView, true);
        adapter.onBindViewHolder(holder, 3);
        checkNotification(holder, demo, context, R.string.ai_mark_done);
        adapter.onBindViewHolder(holder, 4);
        checkNotification(holder, unknown, context, R.string.ai_mark_done);
        return renderPreview(parent, adapter);
    }

    private static void checkNotification(TranscriptAdapter.Holder holder, TranscriptLine line,
                                          Context context, int aiLabel) {
        text(holder.binding.speaker, line.speaker, "notification sender is not a placeholder");
        text(holder.binding.text, line.text, "notification body is not a placeholder");
        require(holder.binding.notificationType.getVisibility() == View.VISIBLE,
                "notification type label visible");
        text(holder.binding.notificationType, context.getString(NotificationSource.label(line.notificationPackage)),
                "notification type label");
        require(holder.binding.ai.getVisibility() == View.VISIBLE, "AI state visible");
        text(holder.binding.ai, context.getString(aiLabel), "AI state text");
        require(holder.binding.speaker.getCurrentTextColor() == line.color
                && holder.binding.bar.getVisibility() == View.VISIBLE
                && holder.binding.bar.getBackgroundTintList() != null
                && holder.binding.bar.getBackgroundTintList().getDefaultColor() == line.color,
                "bound sender identity color");
    }

    private static void checkBackground(View row, boolean notification) {
        float density = row.getResources().getDisplayMetrics().density;
        int padding = Math.round((notification ? 12 : 6) * density);
        require(row.getPaddingLeft() == padding && row.getPaddingTop() == padding
                && row.getPaddingRight() == padding && row.getPaddingBottom() == padding,
                "recycled row padding");
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
        int margin = notification ? Math.round(4 * density) : 0;
        require(margins.topMargin == margin && margins.bottomMargin == margin,
                "production margins reset on voice reuse");
        require(row.getBackgroundTintList() == null, "row has no stale background tint");
        Drawable background = row.getBackground();
        require(background instanceof RippleDrawable, "row uses the expected ripple drawable");
        RippleDrawable ripple = (RippleDrawable) background;
        require(ripple.getNumberOfLayers() == (notification ? 2 : 1),
                "notification card background is replaced by voice background");
        for (int i = 0; i < ripple.getNumberOfLayers(); i++) {
            Drawable layer = ripple.getDrawable(i);
            require(layer instanceof GradientDrawable, "rounded rectangle layer");
            require(Math.abs(((GradientDrawable) layer).getCornerRadius() - 16 * density) < 0.5f,
                    "actual drawable corner radius is 16dp");
        }
        int size = Math.max(64, Math.round(64 * density));
        Bitmap pixels = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        try {
            background.setBounds(0, 0, size, size);
            background.draw(new Canvas(pixels));
            require(Color.alpha(pixels.getPixel(0, 0)) == 0, "rounded corner is transparent");
            require((Color.alpha(pixels.getPixel(size / 2, size / 2)) > 0) == notification,
                    "notification has a filled card; reused voice has no card fill");
        } finally {
            pixels.recycle();
        }
    }

    private static Bitmap renderPreview(FrameLayout parent, TranscriptAdapter adapter) {
        float density = parent.getResources().getDisplayMetrics().density;
        parent.removeAllViews();
        int width = Math.round(360 * density);
        int gap = Math.round(12 * density);
        int height = gap;
        for (int i = 0; i < adapter.getItemCount(); i++) {
            TranscriptAdapter.Holder holder = adapter.onCreateViewHolder(parent, 0);
            adapter.onBindViewHolder(holder, i);
            View row = holder.itemView;
            row.measure(View.MeasureSpec.makeMeasureSpec(width - 2 * gap, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            ViewGroup.MarginLayoutParams bound = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
            int topMargin = bound.topMargin;
            int bottomMargin = bound.bottomMargin;
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width - 2 * gap,
                    row.getMeasuredHeight());
            params.leftMargin = gap;
            params.topMargin = height + topMargin;
            parent.addView(row, params);
            height += topMargin + row.getMeasuredHeight() + bottomMargin;
        }
        parent.setBackgroundColor(parent.getResources().getColor(R.color.main_bg, parent.getContext().getTheme()));
        parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        parent.layout(0, 0, width, height);
        Bitmap preview = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        parent.draw(new Canvas(preview));
        return preview;
    }

    private static void text(TextView view, String expected, String message) {
        require(expected.contentEquals(view.getText()), message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
