package com.stepos.whisper.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.GridLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.stepos.whisper.R;
import com.stepos.whisper.data.AppDatabase;
import com.stepos.whisper.data.UserColors;
import com.stepos.whisper.data.UserFact;
import com.stepos.whisper.data.Utterance;
import com.stepos.whisper.databinding.ActivityUserDetailBinding;
import com.stepos.whisper.databinding.DialogTextInputBinding;
import com.stepos.whisper.databinding.ItemColorSwatchBinding;
import com.stepos.whisper.databinding.ItemFactBinding;
import com.stepos.whisper.databinding.ItemTranscriptBinding;
import com.stepos.whisper.service.RecordingService;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 某个人的详情：头部是颜色、名字、截图昵称和常用操作（补录声纹 / 改名 / 颜色），
 * 删除（机主则是重新录入声纹）放在右上角菜单里；下面是事项列表和最近说过的话。
 */
public class UserDetailActivity extends AppCompatActivity {
    private static final String EXTRA_USER_ID = "user_id";
    private static final int RECENT_UTTERANCES = 50;
    private static final int MENU_DELETE = 1;

    public static Intent createIntent(Context context, long userId) {
        return new Intent(context, UserDetailActivity.class)
                .putExtra(EXTRA_USER_ID, userId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private final SimpleDateFormat mTimeFormat = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
    private ActivityUserDetailBinding mBinding;
    private AppDatabase mDb;
    private long mUserId;
    private String mName = "";
    private boolean mIsOwner;
    private int mColor;
    private int mPendingTodos;
    private int mPendingFacts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBinding = ActivityUserDetailBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        mDb = AppDatabase.get(this);
        mUserId = getIntent().getLongExtra(EXTRA_USER_ID, -1);

        FactAdapter factAdapter = new FactAdapter();
        mBinding.facts.setLayoutManager(new LinearLayoutManager(this));
        mBinding.facts.setAdapter(factAdapter);
        UtteranceAdapter utteranceAdapter = new UtteranceAdapter();
        mBinding.utterances.setLayoutManager(new LinearLayoutManager(this));
        mBinding.utterances.setAdapter(utteranceAdapter);

        mDb.userDao().observe(mUserId).observe(this, user -> {
            if (user == null) {
                finish();
                return;
            }
            mName = user.name;
            mIsOwner = user.isOwner;
            mColor = UserColors.orDefault(user.color, user.id);
            mBinding.name.setText(user.isOwner
                    ? getString(R.string.chip_owner, user.name) : user.name);
            mBinding.colorDot.setBackgroundTintList(ColorStateList.valueOf(mColor));
            setTitle(user.name);
            renderSummary();
            invalidateOptionsMenu();
        });
        mDb.userFactDao().observeAll(mUserId).observe(this, facts -> {
            factAdapter.submitList(facts);
            mBinding.factsEmpty.setVisibility(facts.isEmpty() ? View.VISIBLE : View.GONE);
            mPendingTodos = 0;
            mPendingFacts = 0;
            for (UserFact f : facts) {
                if (f.done) {
                    continue;
                }
                if (f.kind == UserFact.KIND_FACT) {
                    mPendingFacts++;
                } else {
                    mPendingTodos++;
                }
            }
            renderSummary();
        });
        mDb.utteranceDao().observeRecent(mUserId, RECENT_UTTERANCES).observe(this, utteranceAdapter::submitList);
        mDb.chatAliasDao().observeNames(mUserId).observe(this, this::bindAliases);

        mBinding.addVoice.setOnClickListener(v ->
                startActivity(VoiceEnrollActivity.createAddVoiceIntent(this, mUserId)));
        mBinding.rename.setOnClickListener(v -> showRenameDialog());
        mBinding.color.setOnClickListener(v -> showColorDialog());
    }

    private void renderSummary() {
        mBinding.summary.setText(mIsOwner
                ? getString(R.string.user_summary_owner, mPendingTodos, mPendingFacts)
                : getString(R.string.user_summary, mPendingTodos, mPendingFacts));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_DELETE, 0, mIsOwner ? R.string.menu_reenroll : R.string.action_delete)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem item = menu.findItem(MENU_DELETE);
        if (item != null) {
            item.setTitle(mIsOwner ? R.string.menu_reenroll : R.string.action_delete);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == MENU_DELETE) {
            if (mIsOwner) {
                startActivity(VoiceEnrollActivity.createOwnerIntent(this, true));
            } else {
                confirmDelete();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** 聊天截图里对应到这个人的昵称，点一下可以解除。 */
    private void bindAliases(List<String> names) {
        mBinding.aliases.removeAllViews();
        mBinding.aliasRow.setVisibility(names.isEmpty() ? View.GONE : View.VISIBLE);
        for (String name : names) {
            Chip chip = new Chip(this);
            chip.setText(name);
            chip.setCloseIconResource(R.drawable.ic_close);
            chip.setCloseIconVisible(true);
            chip.setOnCloseIconClickListener(v -> new MaterialAlertDialogBuilder(this)
                    .setMessage(getString(R.string.alias_remove_confirm, name, mName))
                    .setPositiveButton(R.string.alias_remove, (d, w) ->
                            AppDatabase.IO.execute(() -> mDb.chatAliasDao().delete(name)))
                    .setNegativeButton(R.string.action_cancel, null)
                    .show());
            mBinding.aliases.addView(chip);
        }
    }

    private void showColorDialog() {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(6);
        int pad = getResources().getDimensionPixelSize(R.dimen.dialog_padding);
        grid.setPadding(pad, pad / 2, pad, 0);
        AlertDialog[] holder = new AlertDialog[1];
        for (int c : UserColors.PALETTE) {
            ItemColorSwatchBinding swatch = ItemColorSwatchBinding.inflate(getLayoutInflater(), grid, false);
            swatch.swatch.setBackgroundTintList(ColorStateList.valueOf(c));
            swatch.check.setVisibility(c == mColor ? View.VISIBLE : View.GONE);
            swatch.getRoot().setOnClickListener(v -> {
                AppDatabase.IO.execute(() -> {
                    mDb.userDao().setColor(mUserId, c);
                    notifyServiceUsersChanged();
                });
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
            });
            grid.addView(swatch.getRoot());
        }
        holder[0] = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.user_pick_color)
                .setView(grid)
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void showRenameDialog() {
        DialogTextInputBinding b = DialogTextInputBinding.inflate(getLayoutInflater());
        b.inputLayout.setHint(getString(R.string.new_speaker_hint));
        b.input.setText(mName);
        b.input.setSelection(mName.length());
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_rename)
                .setView(b.getRoot())
                .setPositiveButton(R.string.action_save, (d, w) -> rename(b.input.getText()))
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        b.input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                rename(b.input.getText());
                dialog.dismiss();
                return true;
            }
            return false;
        });
        dialog.show();
    }

    private void rename(CharSequence text) {
        String name = text == null ? "" : text.toString().trim();
        if (TextUtils.isEmpty(name) || name.equals(mName)) {
            return;
        }
        AppDatabase.IO.execute(() -> {
            mDb.userDao().rename(mUserId, name);
            notifyServiceUsersChanged();
        });
    }

    private void confirmDelete() {
        if (mIsOwner) {
            Toast.makeText(this, R.string.user_owner_cannot_delete, Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.user_delete_confirm, mName))
                .setPositiveButton(R.string.action_delete, (d, w) -> AppDatabase.IO.execute(() -> {
                    mDb.userDao().delete(mUserId);
                    notifyServiceUsersChanged();
                }))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static void notifyServiceUsersChanged() {
        RecordingService service = RecordingService.getInstance();
        if (service != null) {
            service.reloadUsers();
        }
    }

    private class FactAdapter extends ListAdapter<UserFact, FactAdapter.Holder> {
        FactAdapter() {
            super(new DiffUtil.ItemCallback<UserFact>() {
                @Override
                public boolean areItemsTheSame(@NonNull UserFact a, @NonNull UserFact b) {
                    return a.id == b.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull UserFact a, @NonNull UserFact b) {
                    return a.done == b.done && a.content.equals(b.content)
                            && Objects.equals(a.dueTime, b.dueTime)
                            && Objects.equals(a.location, b.location)
                            && Objects.equals(a.people, b.people);
                }
            });
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemFactBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            UserFact fact = getItem(position);
            ItemFactBinding b = holder.binding;
            b.done.setOnCheckedChangeListener(null);
            b.done.setChecked(fact.done);
            b.content.setText(fact.content);
            int flags = b.content.getPaintFlags();
            b.content.setPaintFlags(fact.done ? flags | Paint.STRIKE_THRU_TEXT_FLAG
                    : flags & ~Paint.STRIKE_THRU_TEXT_FLAG);
            b.content.setAlpha(fact.done ? 0.5f : 1f);
            String recorded = fact.type + " · "
                    + getString(R.string.fact_recorded, mTimeFormat.format(new Date(fact.createdAt)));
            b.meta.setText(fact.kind == UserFact.KIND_FACT ? recorded
                    : getString(R.string.fact_meta, FactFormat.orEmpty(fact.dueTime),
                            FactFormat.orEmpty(fact.location), FactFormat.orEmpty(fact.people)) + "\n" + recorded);
            b.done.setOnCheckedChangeListener((v, checked) -> AppDatabase.IO.execute(() ->
                    mDb.userFactDao().setDone(fact.id, checked, System.currentTimeMillis())));
            holder.itemView.setOnClickListener(v -> b.done.toggle());
            holder.itemView.setOnLongClickListener(v -> {
                new MaterialAlertDialogBuilder(UserDetailActivity.this)
                        .setMessage(R.string.fact_delete_confirm)
                        .setPositiveButton(R.string.action_delete, (d, w) ->
                                AppDatabase.IO.execute(() -> mDb.userFactDao().delete(fact.id)))
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
                return true;
            });
        }

        class Holder extends RecyclerView.ViewHolder {
            final ItemFactBinding binding;

            Holder(ItemFactBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }

    private class UtteranceAdapter extends ListAdapter<Utterance, UtteranceAdapter.Holder> {
        UtteranceAdapter() {
            super(new DiffUtil.ItemCallback<Utterance>() {
                @Override
                public boolean areItemsTheSame(@NonNull Utterance a, @NonNull Utterance b) {
                    return a.id == b.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull Utterance a, @NonNull Utterance b) {
                    return a.text.equals(b.text);
                }
            });
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemTranscriptBinding.inflate(LayoutInflater.from(parent.getContext()),
                    parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Utterance u = getItem(position);
            holder.binding.speaker.setVisibility(View.GONE);
            holder.binding.bar.setVisibility(View.VISIBLE);
            holder.binding.bar.setBackgroundTintList(ColorStateList.valueOf(mColor));
            holder.binding.time.setText(mTimeFormat.format(new Date(u.startTime)));
            holder.binding.text.setText(u.text);
            holder.itemView.setBackground(null);
        }

        class Holder extends RecyclerView.ViewHolder {
            final ItemTranscriptBinding binding;

            Holder(ItemTranscriptBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
