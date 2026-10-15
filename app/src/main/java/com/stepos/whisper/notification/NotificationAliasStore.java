package com.stepos.whisper.notification;

import com.stepos.whisper.data.AliasWorker;
import com.stepos.whisper.data.AppDatabase;
import com.stepos.whisper.data.ChatAlias;
import com.stepos.whisper.data.NotificationJob;
import com.stepos.whisper.data.PendingAlias;
import com.stepos.whisper.data.User;
import com.stepos.whisper.data.UserColors;
import com.stepos.whisper.data.Utterance;
import com.stepos.whisper.llm.NameMatcher;
import com.stepos.whisper.service.RecordingService;
import com.stepos.whisper.service.ServiceState;
import com.stepos.whisper.service.TranscriptLine;

import java.util.ArrayList;
import java.util.List;

/** 通知昵称的本地存储；不启动录音服务，也不创建联网处理线程。 */
public final class NotificationAliasStore implements AliasWorker {
    public interface Listener {
        /** 数据库写入和卡片刷新完成后，在 AppDatabase.IO 上调用。 */
        void onAliasSaved(boolean userCreated);
    }

    private final AppDatabase mDb;
    private final Listener mListener;

    public NotificationAliasStore(AppDatabase db) {
        this(db, userCreated -> {
            RecordingService service = RecordingService.getInstance();
            if (service != null) {
                if (userCreated) service.reloadUsers();
                service.kickNotifications();
            }
        });
    }

    public NotificationAliasStore(AppDatabase db, Listener listener) {
        mDb = db;
        mListener = listener;
    }

    @Override
    public PendingAlias findPendingAlias(long jobId) {
        List<NotificationJob> jobs = mDb.notificationJobDao().getAll();
        NotificationJob preferred = mDb.notificationJobDao().get(jobId);
        if (preferred != null && unresolved(preferred)) {
            return pendingOf(preferred, jobs);
        }
        for (NotificationJob job : jobs) {
            if (unresolved(job)) return pendingOf(job, jobs);
        }
        return null;
    }

    private boolean unresolved(NotificationJob job) {
        return mDb.chatAliasDao().get(NameMatcher.normalize(job.sender)) == null;
    }

    private PendingAlias pendingOf(NotificationJob job, List<NotificationJob> jobs) {
        List<String> messages = new ArrayList<>();
        for (String line : job.text.split("\n")) {
            if (!line.isEmpty()) messages.add(line);
        }
        int remaining = 0;
        for (NotificationJob other : jobs) {
            if (unresolved(other)) remaining++;
        }
        return new PendingAlias(job.id, job.sender, messages, Math.max(1, remaining),
                suggestUserFor(job.sender));
    }

    @Override
    public void setAlias(String name, Long userId) {
        AppDatabase.IO.execute(() -> {
            putAlias(name, userId);
            aliasSaved(name, false);
        });
    }

    @Override
    public void createUserForAlias(String name, String userName) {
        AppDatabase.IO.execute(() -> {
            mDb.runInTransaction(() -> {
                long now = System.currentTimeMillis();
                User user = new User();
                user.name = userName;
                user.createdAt = now;
                user.lastSeenAt = now;
                user.color = UserColors.pickUnused(mDb.userDao().getAllColors());
                putAlias(name, mDb.userDao().insert(user));
            });
            aliasSaved(name, true);
        });
    }

    private void putAlias(String name, Long userId) {
        ChatAlias alias = new ChatAlias();
        alias.name = NameMatcher.normalize(name);
        alias.userId = userId;
        alias.createdAt = System.currentTimeMillis();
        mDb.chatAliasDao().put(alias);
    }

    private void aliasSaved(String name, boolean userCreated) {
        String normalized = NameMatcher.normalize(name);
        ChatAlias alias = mDb.chatAliasDao().get(normalized);
        for (NotificationJob job : mDb.notificationJobDao().getAll()) {
            if (normalized.equals(NameMatcher.normalize(job.sender))) {
                if (alias.userId == null) mDb.notificationJobDao().delete(job.id);
                ServiceState.upsertNotificationLine(lineFor(mDb, job,
                        alias.userId == null ? Utterance.AI_NONE : Utterance.AI_QUEUED));
            }
        }
        mListener.onAliasSaved(userCreated);
    }

    /** 只推荐相似名字或已指定的昵称，绝不自动写入对应关系。 */
    private long suggestUserFor(String name) {
        long best = -1;
        float bestScore = NameMatcher.SUGGEST_THRESHOLD;
        for (User user : mDb.userDao().getAll()) {
            if (user.isOwner || user.isStranger) continue;
            float score = NameMatcher.similarity(name, user.name);
            if (score > bestScore) {
                bestScore = score;
                best = user.id;
            }
        }
        for (ChatAlias alias : mDb.chatAliasDao().getAll()) {
            if (alias.userId == null) continue;
            float score = NameMatcher.similarity(name, alias.name);
            if (score > bestScore) {
                bestScore = score;
                best = alias.userId;
            }
        }
        return best;
    }

    static TranscriptLine lineFor(AppDatabase db, NotificationJob job, int aiState) {
        ChatAlias alias = db.chatAliasDao().get(NameMatcher.normalize(job.sender));
        User user = alias == null || alias.userId == null ? null : db.userDao().get(alias.userId);
        return TranscriptLine.notification(job.id, job.postTime,
                user == null ? job.sender : user.name, job.text,
                user == null ? TranscriptLine.NO_USER : user.id,
                user == null ? TranscriptLine.COLOR_UNKNOWN : UserColors.orDefault(user.color, user.id),
                aiState, alias == null);
    }
}
