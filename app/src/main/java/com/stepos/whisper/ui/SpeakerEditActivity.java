package com.stepos.whisper.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.stepos.whisper.R;
import com.stepos.whisper.data.AppDatabase;
import com.stepos.whisper.data.User;
import com.stepos.whisper.data.UserColors;
import com.stepos.whisper.data.UserMerger;
import com.stepos.whisper.databinding.ActivitySpeakerEditBinding;
import com.stepos.whisper.databinding.ItemColorSwatchBinding;
import com.stepos.whisper.service.RecordingService;
import com.stepos.whisper.service.TranscriptLine;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话框样式：给“陌生人N”（或任一用户）改名、改颜色、关联到已有用户、删除。
 * 新陌生人出现时由主界面直接弹出，或从灵动岛卡片的“命名”打开（prompt 模式）。点“播放声音”循环播放这个人最近的声音片段，方便辨认是谁。
 * prompt 模式下 {@link #PROMPT_TIMEOUT_MS} 内没有任何操作就自动收起，之后点转写里这个人的发言还能再打开命名。
 * 保存后界面上这个人的所有发言一起更新。
 * 点转写里没认出是谁的一句话（灰色“陌生人”，不带编号）时进入 assign 模式：输入新名字新建一个人，
 * 或点已有的人（包括“陌生人N”），只把这一句归过去。
 */
public class SpeakerEditActivity extends AppCompatActivity {
    private static final String TAG = "SpeakerEdit";
    private static final String EXTRA_USER_ID = "user_id";
    private static final String EXTRA_PROMPT = "prompt";
    private static final String EXTRA_LINE_ID = "line_id";
    private static final String EXTRA_LINE_START = "line_start";
    private static final String EXTRA_LINE_END = "line_end";
    private static final String EXTRA_LINE_TEXT = "line_text";
    private static final int SAMPLE_TEXTS = 4;
    private static final long PROMPT_TIMEOUT_MS = 15_000;

    public static Intent createIntent(Context context, long userId) {
        return new Intent(context, SpeakerEditActivity.class).putExtra(EXTRA_USER_ID, userId);
    }

    /** 新陌生人出现时：主界面直接弹出，或灵动岛卡片上的“命名”。 */
    public static Intent createPromptIntent(Context context, long userId) {
        return createIntent(context, userId).putExtra(EXTRA_PROMPT, true);
    }

    /** 转写里没认出是谁的一句话。 */
    public static Intent createAssignIntent(Context context, TranscriptLine line) {
        return new Intent(context, SpeakerEditActivity.class)
                .putExtra(EXTRA_LINE_ID, line.id)
                .putExtra(EXTRA_LINE_START, line.timeMs)
                .putExtra(EXTRA_LINE_END, line.endMs)
                .putExtra(EXTRA_LINE_TEXT, line.text);
    }

    private ActivitySpeakerEditBinding mBinding;
    private AppDatabase mDb;
    private long mUserId;
    private boolean mPrompt;
    private boolean mAssign;
    private User mUser;
    private int mColor;
    private final List<ItemColorSwatchBinding> mSwatches = new ArrayList<>();
    private MediaPlayer mPlayer;
    private final Runnable mAutoDismiss = this::finish;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBinding = ActivitySpeakerEditBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());
        mDb = AppDatabase.get(this);
        mUserId = getIntent().getLongExtra(EXTRA_USER_ID, -1);
        mPrompt = getIntent().getBooleanExtra(EXTRA_PROMPT, false);
        mAssign = getIntent().hasExtra(EXTRA_LINE_ID);
        setFinishOnTouchOutside(!mPrompt);
        RecordingService service = RecordingService.getInstance();
        if (mPrompt && service != null) {
            service.getLive().cancelStranger(mUserId);
        }
        if (mPrompt) {
            mBinding.getRoot().postDelayed(mAutoDismiss, PROMPT_TIMEOUT_MS);
        }

        for (int c : UserColors.PALETTE) {
            ItemColorSwatchBinding swatch = ItemColorSwatchBinding.inflate(getLayoutInflater(),
                    mBinding.colors, false);
            swatch.swatch.setBackgroundTintList(ColorStateList.valueOf(c));
            swatch.getRoot().setOnClickListener(v -> selectColor(c));
            swatch.getRoot().setTag(c);
            mBinding.colors.addView(swatch.getRoot());
            mSwatches.add(swatch);
        }

        mBinding.play.setOnClickListener(v -> {
            if (mPlayer != null) {
                stopPlayback();
            } else {
                startPlayback();
            }
        });
        mBinding.cancel.setText(mPrompt ? R.string.action_later : R.string.action_cancel);
        mBinding.cancel.setOnClickListener(v -> finish());
        mBinding.save.setOnClickListener(v -> save());
        mBinding.delete.setOnClickListener(v -> confirmDelete());
        mBinding.name.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save();
                return true;
            }
            return false;
        });
        mBinding.play.setVisibility(View.GONE);

        if (mAssign) {
            AppDatabase.IO.execute(() -> {
                List<User> users = mDb.userDao().getAll();
                runOnUiThread(() -> bindAssign(users));
            });
            return;
        }
        AppDatabase.IO.execute(() -> {
            User user = mDb.userDao().get(mUserId);
            List<String> texts = mDb.utteranceDao().getRecentTexts(mUserId, SAMPLE_TEXTS);
            List<User> others = mDb.userDao().getAll();
            boolean hasVoice = RecordingService.voiceFile(this, mUserId).canRead();
            runOnUiThread(() -> bind(user, texts, others, hasVoice));
        });
    }

    private void bind(User user, List<String> texts, List<User> others, boolean hasVoice) {
        if (user == null || isFinishing()) {
            finish();
            return;
        }
        mUser = user;
        mBinding.title.setText(getString(mPrompt ? R.string.stranger_prompt_title : R.string.speaker_edit_title,
                user.name));
        StringBuilder sb = new StringBuilder();
        for (String t : texts) {
            sb.append("“").append(t).append("”\n");
        }
        mBinding.samples.setText(sb.toString().trim());
        mBinding.samples.setVisibility(texts.isEmpty() ? View.GONE : View.VISIBLE);
        mBinding.play.setVisibility(hasVoice ? View.VISIBLE : View.GONE);
        if (!user.isStranger) {
            mBinding.name.setText(user.name);
            mBinding.name.setSelection(user.name.length());
        }
        mBinding.delete.setVisibility(user.isOwner ? View.GONE : View.VISIBLE);
        selectColor(UserColors.orDefault(user.color, user.id));
        bindMergeTargets(user, others);
    }

    private void bindAssign(List<User> users) {
        if (isFinishing()) {
            return;
        }
        mBinding.title.setText(R.string.assign_title);
        mBinding.samples.setText("“" + getIntent().getStringExtra(EXTRA_LINE_TEXT) + "”");
        mBinding.samples.setVisibility(View.VISIBLE);
        mBinding.delete.setVisibility(View.GONE);
        mBinding.nameLayout.setHelperText(getString(R.string.assign_hint));
        List<Integer> used = new ArrayList<>();
        for (User u : users) {
            used.add(u.color);
        }
        selectColor(UserColors.pickUnused(used));
        bindMergeTargets(null, users);
    }

    /** self 为 null 时是 assign 模式：点了直接把这句话归给那个人。 */
    private void bindMergeTargets(User self, List<User> users) {
        mBinding.mergeUsers.removeAllViews();
        if (self != null && self.isOwner) {
            return;
        }
        // 机主排第一，其余按最近出现排序。陌生人可以关联到任何人（包括另一个陌生人）；
        // 已命名的人只列已命名的人，免得并进陌生人后名字丢了。
        List<User> targets = new ArrayList<>();
        for (User u : users) {
            if (self == null || (u.id != self.id && (self.isStranger || !u.isStranger))) {
                targets.add(u);
            }
        }
        targets.sort((a, b) -> a.isOwner != b.isOwner ? (a.isOwner ? -1 : 1)
                : Long.compare(b.lastSeenAt, a.lastSeenAt));
        for (User u : targets) {
            int color = UserColors.orDefault(u.color, u.id);
            Chip chip = new Chip(this);
            chip.setText(u.isOwner ? getString(R.string.chip_owner, u.name) : u.name);
            chip.setChipBackgroundColor(ColorStateList.valueOf(color));
            chip.setTextColor(0xFFFFFFFF);
            chip.setChipStrokeWidth(0f);
            chip.setOnClickListener(v -> {
                if (self == null) {
                    assignLine(u.id);
                    finish();
                } else {
                    confirmMerge(u);
                }
            });
            mBinding.mergeUsers.addView(chip);
        }
        int vis = targets.isEmpty() ? View.GONE : View.VISIBLE;
        mBinding.mergeLabel.setVisibility(vis);
        mBinding.mergeScroll.setVisibility(vis);
    }

    private void confirmMerge(User target) {
        if (mUser == null) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.merge_confirm, mUser.name, target.name))
                .setPositiveButton(R.string.action_merge, (d, w) -> {
                    RecordingService service = RecordingService.getInstance();
                    if (service != null) {
                        service.mergeUser(mUserId, target.id);
                    } else {
                        AppDatabase.IO.execute(() -> UserMerger.merge(mDb, mUserId, target.id));
                    }
                    finish();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void selectColor(int color) {
        mColor = color;
        for (ItemColorSwatchBinding s : mSwatches) {
            s.check.setVisibility(((Integer) s.getRoot().getTag()) == color ? View.VISIBLE : View.GONE);
        }
    }

    /** 用户碰过命名窗口（点输入框、选颜色等）就当作在处理，不再自动收起。 */
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        mBinding.getRoot().removeCallbacks(mAutoDismiss);
    }

    @Override
    protected void onStop() {
        stopPlayback();
        // 新陌生人的命名窗口切走就收起，不留在后台。
        if (mPrompt && !isChangingConfigurations()) {
            finish();
        }
        super.onStop();
    }

    private void startPlayback() {
        File wav = RecordingService.voiceFile(this, mUserId);
        if (mPlayer != null || !wav.canRead()) {
            return;
        }
        MediaPlayer player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
        try {
            player.setDataSource(wav.getAbsolutePath());
            player.setLooping(true);
            player.prepare();
        } catch (IOException e) {
            Log.w(TAG, "cannot play " + wav, e);
            player.release();
            return;
        }
        RecordingService service = RecordingService.getInstance();
        if (service != null) {
            service.setPlaybackActive(true);
        }
        player.start();
        mPlayer = player;
        mBinding.play.setText(R.string.new_speaker_stop_play);
        mBinding.play.setIconResource(R.drawable.ic_pause);
    }

    private void stopPlayback() {
        if (mPlayer == null) {
            return;
        }
        mPlayer.stop();
        mPlayer.release();
        mPlayer = null;
        mBinding.play.setText(R.string.new_speaker_play);
        mBinding.play.setIconResource(R.drawable.ic_play);
        RecordingService service = RecordingService.getInstance();
        if (service != null) {
            service.setPlaybackActive(false);
        }
    }

    private void save() {
        if (mAssign) {
            saveAssign();
            return;
        }
        if (mUser == null) {
            return;
        }
        CharSequence text = mBinding.name.getText();
        String name = text == null ? "" : text.toString().trim();
        if (TextUtils.isEmpty(name) && !mUser.isStranger) {
            mBinding.nameLayout.setError(getString(R.string.new_speaker_name_empty));
            return;
        }
        int color = mColor;
        AppDatabase.IO.execute(() -> {
            if (!TextUtils.isEmpty(name) && !name.equals(mUser.name)) {
                mDb.userDao().rename(mUserId, name);
            }
            mDb.userDao().setColor(mUserId, color);
            notifyServiceUsersChanged();
        });
        finish();
    }

    /** assign 模式输入了名字：已有同名的人就归给他，否则新建这个人（没有声纹），只把这一句归过去。 */
    private void saveAssign() {
        CharSequence text = mBinding.name.getText();
        String name = text == null ? "" : text.toString().trim();
        if (TextUtils.isEmpty(name)) {
            mBinding.nameLayout.setError(getString(R.string.new_speaker_name_empty));
            return;
        }
        int color = mColor;
        AppDatabase.IO.execute(() -> {
            User existing = mDb.userDao().findByName(name);
            if (existing != null) {
                assignLine(existing.id);
                return;
            }
            User user = new User();
            user.name = name;
            user.color = color;
            user.createdAt = System.currentTimeMillis();
            user.lastSeenAt = user.createdAt;
            assignLine(mDb.userDao().insert(user));
        });
        finish();
    }

    private void assignLine(long userId) {
        Intent intent = getIntent();
        long lineId = intent.getLongExtra(EXTRA_LINE_ID, -1);
        long start = intent.getLongExtra(EXTRA_LINE_START, 0);
        long end = intent.getLongExtra(EXTRA_LINE_END, 0);
        RecordingService service = RecordingService.getInstance();
        if (service != null) {
            service.assignLine(lineId, start, end, userId);
        } else {
            AppDatabase.IO.execute(() -> mDb.utteranceDao().assignByTime(start, end, userId));
        }
    }

    private void confirmDelete() {
        if (mUser == null) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.user_delete_confirm, mUser.name))
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    AppDatabase.IO.execute(() -> {
                        mDb.userDao().delete(mUserId);
                        notifyServiceUsersChanged();
                    });
                    finish();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    @Override
    protected void onDestroy() {
        mBinding.getRoot().removeCallbacks(mAutoDismiss);
        stopPlayback();
        super.onDestroy();
    }

    private static void notifyServiceUsersChanged() {
        RecordingService service = RecordingService.getInstance();
        if (service != null) {
            service.reloadUsers();
        }
    }
}
