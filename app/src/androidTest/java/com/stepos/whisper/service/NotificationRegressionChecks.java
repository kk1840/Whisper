package com.stepos.whisper.service;

import android.app.Instrumentation;

import androidx.room.Room;

import com.stepos.whisper.data.AppDatabase;
import com.stepos.whisper.data.ChatAlias;
import com.stepos.whisper.data.NotificationJob;
import com.stepos.whisper.data.User;
import com.stepos.whisper.data.UserFact;
import com.stepos.whisper.data.Utterance;
import com.stepos.whisper.llm.ExtractionResult;
import com.stepos.whisper.llm.MergeSuggestion;
import com.stepos.whisper.llm.NameMatcher;
import com.stepos.whisper.notification.NotificationWorker;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Offline standalone checks; the parent instrumentation runner calls runChecks(this). */
public final class NotificationRegressionChecks {
    private NotificationRegressionChecks() { }

    public static void runChecks(Instrumentation runner) throws Exception {
        List<TranscriptLine> buffer = buffer();
        List<TranscriptLine> saved;
        synchronized (ServiceState.class) {
            saved = new ArrayList<>(buffer);
            buffer.clear();
        }
        try {
            stateChecks(runner);
            AppDatabase db = Room.inMemoryDatabaseBuilder(runner.getTargetContext(), AppDatabase.class)
                    .allowMainThreadQueries().build();
            try {
                workerChecks(runner, db);
                aliasChecks(db);
                capacityChecks(db);
            } finally {
                db.close();
            }
        } finally {
            synchronized (ServiceState.class) {
                buffer.clear();
                buffer.addAll(saved);
                method(ServiceState.class, "publish").invoke(null);
            }
            runner.waitForIdleSync();
        }
    }

    private static void stateChecks(Instrumentation runner) throws Exception {
        TranscriptLine queued = TranscriptLine.notification(901, 300, "微信昵称", "明天开会",
                TranscriptLine.NO_USER, TranscriptLine.COLOR_UNKNOWN, Utterance.AI_QUEUED, true);
        ServiceState.upsertNotificationLine(queued);
        ServiceState.upsertNotificationLine(TranscriptLine.notification(901, 300, "真实姓名", queued.text,
                7, 123, Utterance.AI_DONE, false));
        require(buffer().size() == 1, "upsert must not append a duplicate card");
        TranscriptLine done = card(901);
        require(done.id == queued.id && done.id == -901, "notification id is stable");
        require(done.speaker.equals("真实姓名") && done.userId == 7 && done.color == 123
                && !done.aliasPending, "resolved alias displays real identity");
        ServiceState.upsertNotificationLine(queued);
        require(card(901) == done, "late queued callback cannot regress completion or identity");
        ServiceState.relabelUser(7, "改名", 7, 456);
        require(card(901).speaker.equals("改名") && card(901).color == 456, "rename updates card");
        ServiceState.relabelUser(7, "合并目标", 8, 789);
        require(card(901).userId == 8 && card(901).notificationJobId == 901
                && card(901).id == done.id && card(901).aiState == Utterance.AI_DONE,
                "merge preserves notification association and completion");
        ServiceState.relabelUser(8, "未知", TranscriptLine.NO_USER, TranscriptLine.COLOR_UNKNOWN);
        require(card(901).userId == TranscriptLine.NO_USER && card(901).color == TranscriptLine.COLOR_UNKNOWN,
                "deletion clears stale identity");
        ServiceState.upsertNotificationLine(TranscriptLine.notification(902, 100, "待识别", "早到消息",
                TranscriptLine.NO_USER, 0, Utterance.AI_QUEUED, true));
        ServiceState.updateAiState(0, 500, Utterance.AI_DONE, false);
        ServiceState.updateAiState(0, 500, Utterance.AI_NONE, true);
        require(card(902).aiState == Utterance.AI_QUEUED, "voice AI updates/reset exclude notifications");
        ServiceState.resolveAllPending("待识别", "未知");
        require(card(902).speaker.equals("待识别"), "voice pending resolution excludes notifications");
        require(!ServiceState.applyVoiceprint(-902, "语音用户", 9, 1), "voiceprint excludes notifications");
        require(ServiceState.latestLine() == null, "latest voice line excludes notifications");
        ServiceState.clearLines();
        require(buffer().size() == 2, "voice clear preserves runtime notifications");
        runner.waitForIdleSync();
        List<TranscriptLine> published = ServiceState.lines().getValue();
        require(published != null && published.get(0).notificationJobId == 902
                && published.get(1).notificationJobId == 901, "runtime cards sorted by post time");
    }

    private static void aliasChecks(AppDatabase db) throws Exception {
        for (NotificationJob pending : db.notificationJobDao().getAll()) db.notificationJobDao().delete(pending.id);
        NotificationJob first = job(db, "离线昵称甲");
        NotificationJob second = job(db, "离线昵称乙");
        com.stepos.whisper.notification.NotificationAliasStore store =
                new com.stepos.whisper.notification.NotificationAliasStore(db, created ->
                        require(!db.inTransaction(), "alias callback after commit"));
        require(store.findPendingAlias(first.id).remaining == 2, "multiple offline aliases");
        store.createUserForAlias(first.sender, "离线用户");
        AppDatabase.IO.submit(() -> { }).get(5, TimeUnit.SECONDS);
        ChatAlias resolved = db.chatAliasDao().get(NameMatcher.normalize(first.sender));
        require(resolved != null && resolved.userId != null
                && db.userDao().get(resolved.userId).name.equals("离线用户"), "offline user saved");
        require(!card(first.id).aliasPending && card(first.id).userId == resolved.userId,
                "offline assignment refreshes card");
        require(store.findPendingAlias(first.id).jobId == second.id, "next alias after write");
        store.setAlias(second.sender, null);
        AppDatabase.IO.submit(() -> { }).get(5, TimeUnit.SECONDS);
        require(db.notificationJobDao().get(second.id) == null, "ignored job removed without service");
        require(card(second.id).aiState == Utterance.AI_NONE && !card(second.id).aliasPending,
                "ignored card terminal");
        ServiceState.upsertNotificationLine(TranscriptLine.notification(second.id, second.postTime,
                second.sender, second.text, -1, 0, Utterance.AI_QUEUED, true));
        require(card(second.id).aiState == Utterance.AI_NONE, "late queued cannot revive ignored card");
        require(store.findPendingAlias(first.id) == null, "all offline aliases resolved");
    }

    private static void capacityChecks(AppDatabase db) throws Exception {
        synchronized (ServiceState.class) { buffer().clear(); }
        for (NotificationJob pending : db.notificationJobDao().getAll()) db.notificationJobDao().delete(pending.id);
        for (int i = 0; i < 305; i++) {
            NotificationWorker.insertJob(db, "容量测试", "消息" + i, i);
        }
        require(buffer().size() == 300, "notification cards capped at 300");
        require(db.notificationJobDao().getAll().size() == 305, "UI eviction retains pending DB jobs");
        long earliest = Long.MAX_VALUE;
        for (TranscriptLine line : buffer()) earliest = Math.min(earliest, line.timeMs);
        require(earliest == 5, "oldest notification cards evicted first");
    }

    private static void workerChecks(Instrumentation runner, AppDatabase db) throws Exception {
        List<String> events = new ArrayList<>();
        NotificationWorker worker = new NotificationWorker(runner.getTargetContext(), db,
                new NotificationWorker.Listener() {
                    private void event(String name) {
                        require(!db.inTransaction(), "callback must follow commit: " + name);
                        events.add(name);
                    }
                    @Override public void onLine(TranscriptLine line) { event("line"); }
                    @Override public void onFactsAdded(long id, List<UserFact> facts) { event("facts"); }
                    @Override public void onMergeSuggested(MergeSuggestion merge) { event("merge"); }
                    @Override public void onAliasNeeded(long id, List<String> names) {
                        event("alias");
                        ChatAlias alias = new ChatAlias();
                        alias.name = NameMatcher.normalize(names.get(0));
                        db.chatAliasDao().put(alias); // Resolve between the two drain passes, without network.
                    }
                    @Override public void onAliasesResolved() { event("resolved"); }
                    @Override public void onAliasWaiting(List<Long> ids) { event("waiting"); }
                    @Override public void onUsersChanged() { event("users"); }
                });
        worker.shutdown();
        Field executorField = NotificationWorker.class.getDeclaredField("mExecutor");
        executorField.setAccessible(true);
        ScheduledExecutorService executor = (ScheduledExecutorService) executorField.get(worker);
        require(executor.awaitTermination(5, TimeUnit.SECONDS), "startup drain terminates before test inserts");
        events.clear();
        NotificationJob ignored = job(db, "两轮竞态");
        method(NotificationWorker.class, "drain").invoke(worker);
        require(db.notificationJobDao().get(ignored.id) == null, "resolved alias handled in second pass");
        require(java.util.Collections.frequency(events, "line") == 2,
                "one queued line and one terminal line: cached job not processed twice");
        NotificationJob rejected = job(db, "拒绝测试");
        Method reject = method(NotificationWorker.class, "reject", NotificationJob.class);
        require(!(Boolean) reject.invoke(worker, rejected), "first rejection requests bounded retry");
        require(!(Boolean) reject.invoke(worker, rejected), "persisted second rejection requests retry");
        require((Boolean) reject.invoke(worker, rejected), "third rejection ends retries");
        require(db.notificationJobDao().get(rejected.id) == null, "rejected job removed at limit");
        transactionChecks(db, worker, events);
    }

    private static NotificationJob job(AppDatabase db, String sender) {
        NotificationJob job = new NotificationJob();
        job.sender = sender;
        job.text = "离线测试消息";
        job.postTime = 1000;
        job.id = db.notificationJobDao().insert(job);
        return job;
    }

    private static void transactionChecks(AppDatabase db, NotificationWorker worker,
                                          List<String> events) throws Exception {
        User user = new User();
        user.name = "真实姓名";
        user.id = db.userDao().insert(user);
        UserFact target = new UserFact();
        target.userId = user.id;
        target.kind = UserFact.KIND_TODO;
        target.content = "已有待办";
        target.id = db.userFactDao().insert(target);
        ExtractionResult result = new ExtractionResult();
        ExtractionResult.Fact fact = new ExtractionResult.Fact();
        fact.content = "新事实";
        result.newFacts.add(fact);
        ExtractionResult.MergeTodo merge = new ExtractionResult.MergeTodo();
        merge.targetId = target.id;
        merge.event = "合并待办";
        merge.newEvent = "候选待办";
        result.mergedTodos.add(merge);
        NotificationJob job = job(db, "事务测试");
        Method complete = method(NotificationWorker.class, "completeExtraction", NotificationJob.class,
                long.class, ExtractionResult.class);
        db.getOpenHelper().getWritableDatabase().execSQL("CREATE TRIGGER fail_candidate BEFORE INSERT "
                + "ON user_facts WHEN NEW.content = '候选待办' BEGIN SELECT RAISE(ABORT, 'test rollback'); END");
        events.clear();
        try {
            complete.invoke(worker, job, user.id, result);
            throw new AssertionError("candidate write failure must propagate");
        } catch (InvocationTargetException expected) {
            require(expected.getCause() instanceof android.database.sqlite.SQLiteException,
                    "expected injected candidate failure");
        } finally {
            db.getOpenHelper().getWritableDatabase().execSQL("DROP TRIGGER fail_candidate");
        }
        require(db.userFactDao().getPending(user.id).size() == 1
                && db.notificationJobDao().get(job.id) != null, "failed candidate rolls back facts and job deletion");
        require(events.isEmpty(), "rollback emits no completion or fact/merge callbacks");
        complete.invoke(worker, job, user.id, result);
        require(db.notificationJobDao().get(job.id) == null && db.userFactDao().getPending(user.id).size() == 3,
                "facts, candidates and job deletion commit together");
        require(events.contains("facts") && events.contains("merge") && events.contains("line"),
                "committed extraction emits callbacks");
        int count = events.size();
        complete.invoke(worker, job, user.id, result);
        require(db.userFactDao().getPending(user.id).size() == 3 && events.size() == count,
                "missing persisted job prevents replay and duplicate completion");
    }

    private static List<TranscriptLine> buffer() throws Exception {
        Field field = ServiceState.class.getDeclaredField("sLineBuffer");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") List<TranscriptLine> lines = (List<TranscriptLine>) field.get(null);
        return lines;
    }

    private static TranscriptLine card(long id) throws Exception {
        synchronized (ServiceState.class) {
            for (TranscriptLine line : buffer()) {
                if (line.notificationJobId == id) return line;
            }
        }
        throw new AssertionError("missing notification " + id);
    }

    private static Method method(Class<?> type, String name, Class<?>... args) throws Exception {
        Method method = type.getDeclaredMethod(name, args);
        method.setAccessible(true);
        return method;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
