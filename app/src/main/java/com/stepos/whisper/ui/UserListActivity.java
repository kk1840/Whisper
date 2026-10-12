package com.stepos.whisper.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.stepos.whisper.R;
import com.stepos.whisper.data.AppDatabase;
import com.stepos.whisper.data.User;
import com.stepos.whisper.data.UserColors;
import com.stepos.whisper.data.UserDao;
import com.stepos.whisper.databinding.ActivityUserListBinding;
import com.stepos.whisper.databinding.ItemUserBinding;
import com.stepos.whisper.service.RecordingService;

import com.stepos.whisper.voiceprint.VoiceprintLibrary;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 用户管理：查看、删除单个或全部用户，主动添加声纹。 */
public class UserListActivity extends AppCompatActivity {
    private static final int MENU_ADD_VOICE = 1;
    private static final int MENU_DELETE_ALL = 2;

    private AppDatabase mDb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityUserListBinding binding = ActivityUserListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        mDb = AppDatabase.get(this);

        UserAdapter adapter = new UserAdapter();
        binding.list.setLayoutManager(new LinearLayoutManager(this));
        binding.list.setAdapter(adapter);

        mDb.userDao().observeSummaries().observe(this, users -> {
            adapter.submitList(users);
            binding.empty.setVisibility(users.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_ADD_VOICE, 0, R.string.action_add_voice)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(0, MENU_DELETE_ALL, 1, R.string.action_delete_all)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == MENU_ADD_VOICE) {
            startActivity(VoiceEnrollActivity.createNewUserIntent(this));
            return true;
        }
        if (item.getItemId() == MENU_DELETE_ALL) {
            confirmDeleteAll();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void confirmDelete(UserDao.UserSummary user) {
        new MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.user_delete_confirm, user.name))
                .setPositiveButton(R.string.action_delete, (d, w) -> AppDatabase.IO.execute(() -> {
                    User full = mDb.userDao().get(user.id);
                    if (full != null) {
                        VoiceprintLibrary.deleteFeaturesQuietly(
                                VoiceprintLibrary.parseFeatureIds(full.featureIds));
                    }
                    mDb.userDao().delete(user.id);
                    notifyServiceUsersChanged();
                }))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void confirmDeleteAll() {
        boolean[] includeOwner = {false};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_all_title)
                .setMultiChoiceItems(new CharSequence[]{getString(R.string.delete_all_include_owner)},
                        includeOwner, (d, which, checked) -> includeOwner[0] = checked)
                .setPositiveButton(R.string.action_delete, (d, w) -> deleteAll(includeOwner[0]))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void deleteAll(boolean includeOwner) {
        if (includeOwner) {
            // 没有机主就无法区分说话人，先停掉录音服务，删完回到录入页。
            stopService(new Intent(this, RecordingService.class));
        }
        AppDatabase.IO.execute(() -> {
            List<User> users = mDb.userDao().getAll();
            List<String> cloudFeatureIds = new ArrayList<>();
            for (User u : users) {
                if (includeOwner || !u.isOwner) {
                    cloudFeatureIds.addAll(VoiceprintLibrary.parseFeatureIds(u.featureIds));
                }
            }
            // 云端声纹也一并清掉——卸载应用并不会清云端，这里是唯一的清除入口。
            VoiceprintLibrary.deleteFeaturesQuietly(cloudFeatureIds);
            if (includeOwner) {
                mDb.userDao().deleteAll();
            } else {
                mDb.userDao().deleteAllExceptOwner();
            }
            for (User u : users) {
                if (includeOwner || !u.isOwner) {
                    //noinspection ResultOfMethodCallIgnored
                    RecordingService.voiceFile(this, u.id).delete();
                }
            }
            runOnUiThread(() -> {
                if (includeOwner) {
                    startActivity(VoiceEnrollActivity.createOwnerIntent(this, false)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    finish();
                } else {
                    notifyServiceUsersChanged();
                }
            });
        });
    }

    private static void notifyServiceUsersChanged() {
        RecordingService service = RecordingService.getInstance();
        if (service != null) {
            service.reloadUsers();
        }
    }

    private class UserAdapter extends ListAdapter<UserDao.UserSummary, UserAdapter.Holder> {
        UserAdapter() {
            super(new DiffUtil.ItemCallback<UserDao.UserSummary>() {
                @Override
                public boolean areItemsTheSame(@NonNull UserDao.UserSummary a, @NonNull UserDao.UserSummary b) {
                    return a.id == b.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull UserDao.UserSummary a,
                                                  @NonNull UserDao.UserSummary b) {
                    return Objects.equals(a.name, b.name) && a.pendingTodos == b.pendingTodos
                            && a.pendingFacts == b.pendingFacts
                            && a.color == b.color && a.isOwner == b.isOwner;
                }
            });
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemUserBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            UserDao.UserSummary user = getItem(position);
            holder.binding.name.setText(user.isOwner ? getString(R.string.chip_owner, user.name) : user.name);
            holder.binding.colorBlock.setBackgroundTintList(
                    ColorStateList.valueOf(UserColors.orDefault(user.color, user.id)));
            holder.binding.detail.setText(getString(R.string.user_summary, user.pendingTodos, user.pendingFacts));
            holder.itemView.setOnClickListener(v ->
                    startActivity(UserDetailActivity.createIntent(UserListActivity.this, user.id)));
            // 本人不在这里删除：删掉就无法区分说话人，需要时用“删除全部”或在详情页重新录入。
            holder.binding.delete.setVisibility(user.isOwner ? View.INVISIBLE : View.VISIBLE);
            holder.binding.delete.setOnClickListener(v -> confirmDelete(user));
        }

        class Holder extends RecyclerView.ViewHolder {
            final ItemUserBinding binding;

            Holder(ItemUserBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
