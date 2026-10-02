package com.stepos.whisper.ui;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.stepos.whisper.R;
import com.stepos.whisper.data.UserColors;
import com.stepos.whisper.data.UserDao;
import com.stepos.whisper.databinding.ItemUserChipBinding;

import java.util.Objects;

/** 主界面上一排彩色的用户标签。点击看相关事项，长按进入详情。 */
class UserChipAdapter extends ListAdapter<UserDao.UserSummary, UserChipAdapter.Holder> {
    interface Listener {
        void onUserClick(UserDao.UserSummary user);

        void onUserLongClick(UserDao.UserSummary user);
    }

    private final Listener mListener;

    UserChipAdapter(Listener listener) {
        super(new DiffUtil.ItemCallback<UserDao.UserSummary>() {
            @Override
            public boolean areItemsTheSame(@NonNull UserDao.UserSummary a, @NonNull UserDao.UserSummary b) {
                return a.id == b.id;
            }

            @Override
            public boolean areContentsTheSame(@NonNull UserDao.UserSummary a, @NonNull UserDao.UserSummary b) {
                return Objects.equals(a.name, b.name) && a.color == b.color
                        && a.pendingTodos == b.pendingTodos;
            }
        });
        mListener = listener;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemUserChipBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        UserDao.UserSummary user = getItem(position);
        String label = user.isOwner
                ? holder.itemView.getContext().getString(R.string.chip_owner, user.name) : user.name;
        if (user.pendingTodos > 0) {
            label += "  " + user.pendingTodos;
        }
        holder.binding.chip.setText(label);
        holder.binding.chip.setChipBackgroundColor(
                ColorStateList.valueOf(UserColors.orDefault(user.color, user.id)));
        holder.binding.chip.setChipStrokeWidth(0f);
        holder.binding.chip.setOnClickListener(v -> mListener.onUserClick(user));
        holder.binding.chip.setOnLongClickListener(v -> {
            mListener.onUserLongClick(user);
            return true;
        });
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ItemUserChipBinding binding;

        Holder(ItemUserChipBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
