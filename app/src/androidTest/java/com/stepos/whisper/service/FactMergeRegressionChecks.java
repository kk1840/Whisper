package com.stepos.whisper.service;

import android.app.Instrumentation;
import androidx.room.Room;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.stepos.whisper.data.*;
import com.stepos.whisper.llm.*;
import com.stepos.whisper.ui.FactFormat;
import java.util.List;

/** Synthetic AI records only, in an isolated in-memory database. */
public final class FactMergeRegressionChecks {
    public static void runChecks(Instrumentation runner) {
        AppDatabase db = Room.inMemoryDatabaseBuilder(runner.getTargetContext(), AppDatabase.class)
                .allowMainThreadQueries().build();
        try {
            User user = new User();
            user.name = "Synthetic user";
            user.id = db.userDao().insert(user);
            for (String empty : new String[]{"[]", "[ ]", "[ \t ]", "null", "无", "  "}) {
                ExtractionResult result = new ExtractionResult();
                ExtractionResult.Todo todo = new ExtractionResult.Todo();
                todo.event = "保留正文[附件]";
                todo.time = empty;
                todo.location = empty;
                todo.people = empty;
                result.newTodos.add(todo);
                UserFact saved = FactSaver.save(db, user.id, result).get(0);
                check(saved.dueTime == null && saved.location == null && saved.people == null,
                        "structured placeholder normalized");
                check(FactFormat.EMPTY.equals(FactFormat.orEmpty(empty)), "legacy placeholder hidden");
                check("保留正文[附件]".equals(FactFormat.oneLine(saved)), "bracket body preserved");
                todo.event = empty;
                check(FactSaver.save(db, user.id, result).isEmpty(), "empty AI record rejected");
                db.userFactDao().delete(db.userFactDao().getPending(user.id).get(0).id);
            }
            try {
                new Gson().fromJson("{\"new_todos\":[{\"event\":\"会议\",\"time\":[]}]}", ExtractionResult.class);
                throw new AssertionError("JSON array must not coerce to String");
            } catch (JsonSyntaxException expected) { }
            UserFact target = new UserFact();
            target.userId = user.id;
            target.content = "项目交付";
            target.dueTime = "周五15点";
            target.location = "A室";
            target.people = "张三";
            target.createdAt = 1;
            target.updatedAt = 1;
            target.id = db.userFactDao().insert(target);
            ExtractionResult.MergeTodo merge = new ExtractionResult.MergeTodo();
            merge.targetId = target.id;
            merge.event = "项目交付并补齐材料";
            merge.newEvent = "补齐材料";
            merge.time = "[ ]";
            merge.location = "B室";
            merge.people = "张三、李四";
            MergeSuggestion suggestion = FactSaver.saveMergeCandidates(db, user.id, List.of(merge)).get(0);
            check(db.userFactDao().getPending(user.id).size() == 2, "declining retains both records");
            check("项目交付".equals(db.userFactDao().getPending(user.id).get(1).content),
                    "unconfirmed suggestion does not overwrite target");
            FactSaver.applyMerge(db, suggestion);
            List<FactCard> cards = db.userFactDao().getPendingCards(user.id);
            check(cards.size() == 1 && cards.get(0).fact.id == target.id, "merge keeps old id, deletes candidate");
            UserFact saved = cards.get(0).fact;
            check("周五15点".equals(saved.dueTime) && "B室".equals(saved.location)
                    && "张三、李四".equals(saved.people), "null fallback, override and people merge");
            merge.time = "周六16点";
            merge.location = "null";
            merge.people = "[]";
            suggestion = FactSaver.saveMergeCandidates(db, user.id, List.of(merge)).get(0);
            FactSaver.applyMerge(db, suggestion);
            saved = db.userFactDao().getPendingCards(user.id).get(0).fact;
            check("周六16点".equals(saved.dueTime) && "B室".equals(saved.location)
                    && "张三、李四".equals(saved.people), "override time, retain absent location/people");
            merge.targetId = Long.MAX_VALUE;
            check(FactSaver.saveMergeCandidates(db, user.id, List.of(merge)).isEmpty(), "invalid target has no dialog");
            check(db.userFactDao().getPending(user.id).size() == 2, "invalid target saved as new todo");
            orderingChecks(db, user.id);
        } finally {
            db.close();
        }
    }

    private static void orderingChecks(AppDatabase db, long userId) {
        for (UserFact f : db.userFactDao().getPending(userId)) db.userFactDao().delete(f.id);
        UserFact a = new UserFact();
        a.userId = userId; a.content = "A"; a.createdAt = 10; a.updatedAt = 10;
        a.id = db.userFactDao().insert(a);
        UserFact b = new UserFact();
        b.userId = userId; b.content = "B"; b.createdAt = 10; b.updatedAt = 10;
        b.id = db.userFactDao().insert(b);
        check(db.userFactDao().getPendingCards(userId).get(0).fact.id == b.id, "same-ms id tie-break");
        db.userFactDao().applyMerge(a.id, "A updated", null, null, null, 20);
        check(db.userFactDao().getPendingCards(userId).get(0).fact.id == a.id, "merged record sorts first");
        UserFact f1 = new UserFact();
        f1.userId = userId; f1.content = "Fact1"; f1.kind = UserFact.KIND_FACT;
        f1.createdAt = 5; f1.updatedAt = 100;
        f1.id = db.userFactDao().insert(f1);
        UserFact f2 = new UserFact();
        f2.userId = userId; f2.content = "Fact2"; f2.kind = UserFact.KIND_FACT;
        f2.createdAt = 6; f2.updatedAt = 6;
        f2.id = db.userFactDao().insert(f2);
        check(db.userFactDao().getPendingCards(userId).get(2).fact.id == f2.id, "facts sort by creation");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
