package com.stepos.whisper.ui;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.stepos.whisper.R;
import com.stepos.whisper.data.Utterance;
import com.stepos.whisper.databinding.ItemTranscriptBinding;
import com.stepos.whisper.service.TranscriptLine;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 主界面的实时转写列表：点一句话看说话人的事项（或命名陌生人），长按改这句话的归属。 */
class TranscriptAdapter extends ListAdapter<TranscriptLine, TranscriptAdapter.Holder> {
    private static final int AI_DONE_COLOR = 0xFF2E7D32;
    private static final int AI_DONE_BG = 0x332E7D32;
    private static final int AI_QUEUED_COLOR = 0xFFF57C00;
    private static final int AI_QUEUED_BG = 0x33F57C00;

    interface OnSpeakerClickListener {
        void onSpeakerClick(TranscriptLine line);
    }

    interface OnSpeakerLongClickListener {
        void onSpeakerLongClick(TranscriptLine line);
    }

    private final SimpleDateFormat mTimeFormat = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
    private final OnSpeakerClickListener mListener;
    private final OnSpeakerLongClickListener mLongListener;

    TranscriptAdapter(OnSpeakerClickListener listener, OnSpeakerLongClickListener longListener) {
        super(new DiffUtil.ItemCallback<TranscriptLine>() {
            @Override
            public boolean areItemsTheSame(@NonNull TranscriptLine a, @NonNull TranscriptLine b) {
                return a.id == b.id;
            }

            @Override
            public boolean areContentsTheSame(@NonNull TranscriptLine a, @NonNull TranscriptLine b) {
                return a.speaker.equals(b.speaker) && a.text.equals(b.text) && a.color == b.color
                        && a.aiState == b.aiState && a.userId == b.userId;
            }
        });
        mListener = listener;
        mLongListener = longListener;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemTranscriptBinding.inflate(LayoutInflater.from(parent.getContext()),
                parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        TranscriptLine line = getItem(position);
        ItemTranscriptBinding b = holder.binding;
        b.speaker.setText(line.speaker);
        b.speaker.setTextColor(line.color);
        b.bar.setBackgroundTintList(ColorStateList.valueOf(line.color));
        b.bar.setVisibility(View.VISIBLE);
        b.time.setText(mTimeFormat.format(new Date(line.timeMs)));
        b.text.setText(line.text);

        if (line.aiState == Utterance.AI_DONE) {
            b.ai.setVisibility(View.VISIBLE);
            b.ai.setText(R.string.ai_mark_done);
            b.ai.setTextColor(AI_DONE_COLOR);
            b.ai.setBackgroundTintList(ColorStateList.valueOf(AI_DONE_BG));
        } else if (line.aiState == Utterance.AI_QUEUED) {
            b.ai.setVisibility(View.VISIBLE);
            b.ai.setText(R.string.ai_mark_queued);
            b.ai.setTextColor(AI_QUEUED_COLOR);
            b.ai.setBackgroundTintList(ColorStateList.valueOf(AI_QUEUED_BG));
        } else {
            b.ai.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> mListener.onSpeakerClick(line));
        holder.itemView.setOnLongClickListener(v -> {
            if (mLongListener == null) {
                return false;
            }
            mLongListener.onSpeakerLongClick(line);
            return true;
        });
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ItemTranscriptBinding binding;

        Holder(ItemTranscriptBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
