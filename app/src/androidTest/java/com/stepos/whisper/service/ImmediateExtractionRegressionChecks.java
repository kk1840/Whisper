package com.stepos.whisper.service;

import android.content.Context;

import androidx.room.Room;

import com.stepos.whisper.data.AppDatabase;
import com.stepos.whisper.data.PendingExtraction;
import com.stepos.whisper.data.User;
import com.stepos.whisper.data.UserFact;
import com.stepos.whisper.data.Utterance;
import com.stepos.whisper.llm.ExtractionWorker;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Offline checks callable by the parent runner; never uses the live database or network. */
public final class ImmediateExtractionRegressionChecks {
    public static void runChecks(Context context) throws Exception {
        readinessChecks();
        AppDatabase db = Room.inMemoryDatabaseBuilder(context, AppDatabase.class)
                .allowMainThreadQueries().build();
        try {
            queueChecks(context, db);
        } finally {
            db.close();
        }
    }

    private static void readinessChecks() {
        UtteranceExtractionState state = new UtteranceExtractionState();
        Utterance first = row(1000, 2000, "第一句", null);
        Utterance second = row(2000, 3000, "第二句", null);
        state.pending(first.startTime, first.endTime);
        require(state.snapshot(Collections.singletonList(first)) == null, "unresolved sentence waits");
        state.ready(first.startTime, first.endTime);
        UtteranceExtractionState.Snapshot snapshot = state.snapshot(Collections.singletonList(first));
        state.pending(second.startTime, second.endTime);
        require(state.isValid(snapshot), "new voiceprint must not invalidate resolved sentence");
        require(state.snapshot(Arrays.asList(first, second)) == null, "legacy range waits on its own row");
        long manual = state.pending(first.startTime, first.endTime);
        require(!state.isValid(snapshot), "manual intent invalidates before assignment is processed");
        long newer = state.pending(first.startTime, first.endTime);
        require(!state.isCurrent(first.startTime, first.endTime, manual), "older assignment cannot write or learn");
        require(state.isCurrent(first.startTime, first.endTime, newer), "latest assignment owns side effects");
        require(!state.ready(first.startTime, first.endTime, manual), "older assignment cannot unlock newer intent");
        require(state.ready(first.startTime, first.endTime, newer), "latest assignment releases gate");
        snapshot = state.snapshot(Collections.singletonList(first));
        state.clear();
        require(!state.isValid(snapshot), "clearing chats invalidates in-flight results");
    }

    private static void queueChecks(Context context, AppDatabase db) throws Exception {
        User owner = new User();
        owner.name = "测试机主";
        owner.isOwner = true;
        owner.id = db.userDao().insert(owner);
        Utterance first = row(10_000, 11_000, "之前的事实", owner.id);
        Utterance second = row(11_000, 12_000, "这次新句", null);
        first.id = db.utteranceDao().insert(first);
        second.id = db.utteranceDao().insert(second);
        UtteranceExtractionState state = new UtteranceExtractionState();
        ExtractionWorker worker = new ExtractionWorker(context, db, state, new ExtractionWorker.Listener() {
            @Override public void onAiStateChanged(long from, long to, int value, boolean reset) {
                require(!db.inTransaction(), "AI callback after commit");
            }
            @Override public void onFactsAdded(long id, List<UserFact> facts) {
                require(!db.inTransaction(), "facts callback after commit");
            }
        });
        worker.shutdown(); // Persist synchronously but do not start any API request.
        state.pending(second.startTime, second.endTime);
        worker.enqueueSentence(second);
        require(db.pendingExtractionDao().count() == 0, "attribution pending cannot queue");
        state.ready(second.startTime, second.endTime);
        Thread a = new Thread(() -> worker.enqueueSentence(second));
        Thread b = new Thread(() -> worker.enqueueSentence(second));
        a.start(); b.start(); a.join(); b.join();
        require(db.pendingExtractionDao().count() == 1, "concurrent duplicate enqueue is atomic");
        require(db.utteranceDao().getExact(first.startTime, first.endTime).get(0).aiState == Utterance.AI_NONE,
                "touching boundary does not queue previous sentence");
        PendingExtraction job = db.pendingExtractionDao().getAll().get(0);
        require(job.userId == owner.id, "unknown sentence falls back to owner");
        Method rows = ExtractionWorker.class.getDeclaredMethod("jobRows", PendingExtraction.class);
        rows.setAccessible(true);
        require(((List<?>) rows.invoke(worker, job)).size() == 1, "exact pair preferred over overlap");
        resultSnapshotChecks(db, worker, state, job, rows);
        Method complete = ExtractionWorker.class.getDeclaredMethod("completeExtraction",
                PendingExtraction.class, List.class, long.class,
                com.stepos.whisper.llm.ExtractionResult.class);
        complete.setAccessible(true);
        com.stepos.whisper.llm.ExtractionResult result = new com.stepos.whisper.llm.ExtractionResult();
        com.stepos.whisper.llm.ExtractionResult.Fact fact = new com.stepos.whisper.llm.ExtractionResult.Fact();
        fact.content = "事务后通知测试事实";
        result.newFacts.add(fact);
        complete.invoke(worker, job, rows.invoke(worker, job), owner.id, result);
        worker.enqueueSentence(second);
        require(db.pendingExtractionDao().count() == 0, "done sentence cannot queue again");
        require(db.utteranceDao().getExact(first.startTime, first.endTime).get(0).aiState == Utterance.AI_NONE,
                "completion does not mark boundary context done");
        legacyChecks(db, worker, rows, first, second);
    }

    @SuppressWarnings("unchecked")
    private static void resultSnapshotChecks(AppDatabase db, ExtractionWorker worker,
                                             UtteranceExtractionState state, PendingExtraction job,
                                             Method rowsMethod) throws Exception {
        List<Utterance> rows = (List<Utterance>) rowsMethod.invoke(worker, job);
        UtteranceExtractionState.Snapshot snapshot = state.snapshot(rows);
        User owner = db.userDao().get(job.userId);
        Method valid = ExtractionWorker.class.getDeclaredMethod("isSnapshotValid", PendingExtraction.class,
                UtteranceExtractionState.Snapshot.class, List.class, User.class);
        valid.setAccessible(true);
        state.pending(20_000, 21_000);
        require((Boolean) valid.invoke(worker, job, snapshot, rows, owner), "unrelated pending leaves result valid");
        long manual = state.pending(job.startTime, job.endTime);
        require(!(Boolean) valid.invoke(worker, job, snapshot, rows, owner), "manual queued before DB write rejects result");
        state.ready(job.startTime, job.endTime, manual);
        snapshot = state.snapshot(rows);
        User other = new User();
        other.name = "测试人物";
        other.id = db.userDao().insert(other);
        db.utteranceDao().assignByTime(job.startTime, job.endTime, other.id);
        require(!(Boolean) valid.invoke(worker, job, snapshot, rows, owner), "changed persisted attribution rejects result");
        db.utteranceDao().assignByTime(job.startTime, job.endTime, owner.id);
    }

    private static void legacyChecks(AppDatabase db, ExtractionWorker worker, Method rows,
                                     Utterance first, Utterance second) throws Exception {
        PendingExtraction legacy = new PendingExtraction();
        legacy.userId = first.userId;
        legacy.startTime = first.startTime - 30_000;
        legacy.endTime = second.endTime + 1000;
        legacy.id = db.pendingExtractionDao().insert(legacy);
        db.utteranceDao().raiseAiStateByIds(Collections.singletonList(first.id), Utterance.AI_QUEUED);
        worker.enqueueSentence(first);
        require(db.pendingExtractionDao().count() == 1, "recovery retains old range without duplicate sentence job");
        require(((List<?>) rows.invoke(worker, legacy)).size() == 2, "legacy non-exact ranges still load full dialogue");
        Method transcript = ExtractionWorker.class.getDeclaredMethod("sentenceTranscript", List.class, User.class, String.class);
        transcript.setAccessible(true);
        String prompt = (String) transcript.invoke(worker, Collections.singletonList(first),
                db.userDao().get(first.userId), "这次新句\n");
        require(prompt.contains("CONTEXT_ONLY") && prompt.indexOf(first.text) < prompt.indexOf("【NEW：本次新句】\n"),
                "previous 30s is context-only, not new content");
        require(prompt.contains("【NEW：本次新句】\n这次新句"), "new sentence explicitly delimited");
    }

    private static Utterance row(long start, long end, String text, Long userId) {
        Utterance row = new Utterance();
        row.startTime = start;
        row.endTime = end;
        row.text = text;
        row.userId = userId;
        return row;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
