package com.stepos.whisper.ui;

import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.stepos.whisper.R;
import com.stepos.whisper.data.AppDatabase;
import com.stepos.whisper.data.FactCard;
import com.stepos.whisper.data.UserColors;
import com.stepos.whisper.data.UserFact;
import com.stepos.whisper.databinding.ItemFactRowBinding;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 某个用户和机主相关的未完成待办、仍成立的事实弹窗。都没有时不弹窗。 */
public final class FactsDialog {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Dialog sShowing;

    private FactsDialog() {
    }

    /**
     * @param quietIfEmpty 为 true 时没有事项就什么都不做（自动提醒）；
     *                     为 false 时给出轻提示（用户主动点击）。
     */
    public static void show(AppCompatActivity activity, long userId, String name, boolean quietIfEmpty) {
        AppDatabase db = AppDatabase.get(activity);
        AppDatabase.IO.execute(() -> {
            List<FactCard> cards = db.userFactDao().getPendingCards(userId);
            MAIN.post(() -> {
                if (!activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
                    return;
                }
                if (cards.isEmpty()) {
                    if (!quietIfEmpty) {
                        Toast.makeText(activity, activity.getString(R.string.facts_none, name),
                                Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                showDialog(activity, userId, cards);
            });
        });
    }

    private static void showDialog(AppCompatActivity activity, long userId, List<FactCard> cards) {
        if (sShowing != null && sShowing.isShowing()) {
            sShowing.dismiss();
        }
        AppDatabase db = AppDatabase.get(activity);
        LayoutInflater inflater = LayoutInflater.from(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = activity.getResources().getDimensionPixelSize(R.dimen.dialog_padding);
        list.setPadding(pad, pad / 2, pad, 0);

        FactCard first = cards.get(0);
        int color = UserColors.orDefault(first.userColor, userId);
        SimpleDateFormat timeFormat = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        for (FactCard card : cards) {
            UserFact f = card.fact;
            ItemFactRowBinding row = ItemFactRowBinding.inflate(inflater, list, false);
            row.bar.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
            row.event.setText(f.content);
            String meta = f.kind == UserFact.KIND_FACT
                    ? activity.getString(R.string.fact_recorded, timeFormat.format(new Date(f.createdAt)))
                    : activity.getString(R.string.fact_meta, FactFormat.orEmpty(f.dueTime),
                            FactFormat.orEmpty(f.location), FactFormat.orEmpty(f.people));
            row.meta.setText(meta + (TextUtils.isEmpty(f.type) ? "" : "  ·  " + f.type));
            row.done.setOnCheckedChangeListener((v, checked) -> AppDatabase.IO.execute(() ->
                    db.userFactDao().setDone(f.id, checked, System.currentTimeMillis())));
            list.addView(row.getRoot());
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(list);

        sShowing = new MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.reminder_title, first.userName))
                .setView(scroll)
                .setPositiveButton(R.string.action_close, null)
                .setNeutralButton(R.string.action_detail, (d, w) ->
                        activity.startActivity(UserDetailActivity.createIntent(activity, userId)))
                .show();
    }
}
