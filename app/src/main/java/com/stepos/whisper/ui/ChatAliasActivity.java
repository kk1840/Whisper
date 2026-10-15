package com.stepos.whisper.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.stepos.whisper.R;
import com.stepos.whisper.data.AppDatabase;
import com.stepos.whisper.data.AliasWorker;
import com.stepos.whisper.data.PendingAlias;
import com.stepos.whisper.data.User;
import com.stepos.whisper.data.UserColors;
import com.stepos.whisper.databinding.ActivityChatAliasBinding;
import com.stepos.whisper.screenshot.ScreenshotWorker;
import com.stepos.whisper.service.RecordingService;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话框样式：聊天截图或微信通知里出现了还没对应过的昵称，让机主指定它是哪位已有用户、新建一个人，
 * 或者忽略。一批里有多个新昵称时逐个询问。指定过一次后同名昵称以后自动对应。
 * 昵称和某个人的名字、已对应过的昵称相似时会预先选中那个人，机主确认一下就行。
 */
public class ChatAliasActivity extends AppCompatActivity {
    private static final String EXTRA_JOB_ID = "job_id";
    private static final String EXTRA_FROM_NOTIFICATION = "from_notification";

    public static Intent createIntent(Context context, long jobId, boolean fromNotification) {
        return new Intent(context, ChatAliasActivity.class)
                .putExtra(EXTRA_JOB_ID, jobId)
                .putExtra(EXTRA_FROM_NOTIFICATION, fromNotification);
    }

    private ActivityChatAliasBinding mBinding;
    private long mJobId;
    private boolean mFromNotification;
    private PendingAlias mPending;
    private List<User> mUsers = new ArrayList<>();
    /** 选中的已有用户 id；没选时为 -1。 */
    private long mSelectedUserId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBinding = ActivityChatAliasBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());
        setFinishOnTouchOutside(false);
        mJobId = getIntent().getLongExtra(EXTRA_JOB_ID, -1);
        mFromNotification = getIntent().getBooleanExtra(EXTRA_FROM_NOTIFICATION, false);

        RecordingService service = RecordingService.getInstance();
        if (service != null) {
            service.getLive().cancelChatAlias();
        }

        mBinding.cancel.setOnClickListener(v -> finish());
        mBinding.save.setOnClickListener(v -> save());
        mBinding.ignore.setOnClickListener(v -> ignore());
        mBinding.name.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save();
                return true;
            }
            return false;
        });
        // 机主开始改名字，就是想新建一个人，取消已选中的用户。
        mBinding.name.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (mBinding.name.hasFocus()) {
                    mBinding.users.clearCheck();
                }
            }
        });
        mBinding.users.setOnCheckedStateChangeListener((group, ids) -> {
            mSelectedUserId = -1;
            for (int id : ids) {
                Chip chip = group.findViewById(id);
                if (chip != null && chip.getTag() instanceof Long) {
                    mSelectedUserId = (Long) chip.getTag();
                }
            }
            mBinding.save.setText(mSelectedUserId >= 0 ? R.string.action_merge : R.string.action_save);
        });
        load();
    }

    private AliasWorker worker() {
        RecordingService service = RecordingService.getInstance();
        if (service == null) {
            return mFromNotification
                    ? new com.stepos.whisper.notification.NotificationAliasStore(AppDatabase.get(this))
                    : null;
        }
        return mFromNotification ? service.getNotifications() : service.getScreenshots();
    }

    /** 用户列表和下一个待指定的昵称在同一个数据库线程任务里取，保证两者一起到、顺序正确。 */
    private void load() {
        AliasWorker worker = worker();
        if (worker == null) {
            Toast.makeText(this, R.string.service_not_running, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        mBinding.save.setEnabled(false);
        AppDatabase.IO.execute(() -> {
            List<User> users = AppDatabase.get(this).userDao().getAll();
            PendingAlias pending = worker.findPendingAlias(mJobId);
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                mUsers = users;
                if (pending == null) {
                    finish();
                    return;
                }
                bind(pending);
            });
        });
    }

    private void bind(PendingAlias pending) {
        mPending = pending;
        mBinding.save.setEnabled(true);
        mBinding.title.setText(getString(mFromNotification
                ? R.string.alias_title_notification : R.string.alias_title, pending.name));
        mBinding.remaining.setVisibility(pending.remaining > 1 ? View.VISIBLE : View.GONE);
        mBinding.remaining.setText(getString(R.string.alias_remaining, pending.remaining - 1));
        StringBuilder sb = new StringBuilder();
        for (String t : pending.messages) {
            sb.append("“").append(t).append("”\n");
        }
        mBinding.samples.setText(sb.toString().trim());
        mBinding.samples.setVisibility(pending.messages.isEmpty() ? View.GONE : View.VISIBLE);
        mBinding.name.setText(pending.name);
        mBinding.name.clearFocus();
        mBinding.nameLayout.setError(null);
        bindUsers();
    }

    private void bindUsers() {
        mBinding.users.removeAllViews();
        mSelectedUserId = -1;
        // 机主不列（截图里机主的消息靠右，不需要对应），其余按最近出现排序，陌生人排最后。
        List<User> targets = new ArrayList<>();
        for (User u : mUsers) {
            if (!u.isOwner) {
                targets.add(u);
            }
        }
        targets.sort((a, b) -> a.isStranger != b.isStranger ? (a.isStranger ? 1 : -1)
                : Long.compare(b.lastSeenAt, a.lastSeenAt));
        int matchId = View.NO_ID;
        String matchName = null;
        LayoutInflater inflater = getLayoutInflater();
        for (User u : targets) {
            int color = UserColors.orDefault(u.color, u.id);
            Chip chip = (Chip) inflater.inflate(R.layout.item_pick_chip, mBinding.users, false);
            chip.setId(View.generateViewId());
            chip.setText(u.name);
            chip.setTag(u.id);
            chip.setChipBackgroundColor(ColorStateList.valueOf(color));
            // 相似度推荐优先；没有推荐时昵称和某个人同名也默认选中，机主确认一下就行。
            boolean suggested = mPending.suggestedUserId == u.id
                    || (mPending.suggestedUserId < 0 && u.name.equals(mPending.name));
            if (suggested && matchId == View.NO_ID) {
                matchId = chip.getId();
                matchName = u.name;
            }
            mBinding.users.addView(chip);
        }
        if (matchId != View.NO_ID) {
            mBinding.users.check(matchId);
            mBinding.suggestHint.setText(getString(R.string.alias_suggest, matchName));
            mBinding.suggestHint.setVisibility(View.VISIBLE);
        } else {
            mBinding.suggestHint.setVisibility(View.GONE);
        }
        mBinding.save.setText(mSelectedUserId >= 0 ? R.string.action_merge : R.string.action_save);
        int vis = targets.isEmpty() ? View.GONE : View.VISIBLE;
        mBinding.pickLabel.setVisibility(vis);
        mBinding.pickScroll.setVisibility(vis);
    }

    private void save() {
        AliasWorker worker = worker();
        if (mPending == null || worker == null) {
            finish();
            return;
        }
        if (mSelectedUserId >= 0) {
            worker.setAlias(mPending.name, mSelectedUserId);
        } else {
            String name = mBinding.name.getText() == null ? "" : mBinding.name.getText().toString().trim();
            if (TextUtils.isEmpty(name)) {
                mBinding.nameLayout.setError(getString(R.string.alias_new_user_hint));
                return;
            }
            worker.createUserForAlias(mPending.name, name);
        }
        next();
    }

    private void ignore() {
        AliasWorker worker = worker();
        if (mPending == null || worker == null) {
            finish();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.alias_ignore_hint)
                .setPositiveButton(R.string.alias_ignore, (d, w) -> {
                    worker.setAlias(mPending.name, null);
                    next();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 这张截图里还有别的新昵称就接着问，否则关闭。 */
    private void next() {
        if (mPending.remaining > 1) {
            mBinding.nameLayout.setError(null);
            load();
        } else {
            Toast.makeText(this, R.string.alias_done, Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}
