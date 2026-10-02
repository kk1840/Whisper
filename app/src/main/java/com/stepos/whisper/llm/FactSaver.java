package com.stepos.whisper.llm;

import android.util.Log;

import com.stepos.whisper.data.AppDatabase;
import com.stepos.whisper.data.UserFact;

import java.util.ArrayList;
import java.util.List;

/** 把大模型返回的提取结果写进某个用户名下：新增待办和事实，关闭已完成的待办和不再成立的事实。 */
public final class FactSaver {
    private static final String TAG = "FactSaver";

    private FactSaver() {
    }

    /** 返回新增的记录。不能在主线程调用。 */
    public static List<UserFact> save(AppDatabase db, long userId, ExtractionResult result) {
        long now = System.currentTimeMillis();
        List<UserFact> added = new ArrayList<>();
        int todos = 0;
        if (result.newTodos != null) {
            for (ExtractionResult.Todo t : result.newTodos) {
                String event = clean(t == null ? null : t.event);
                if (event == null) {
                    continue;
                }
                UserFact todo = newRecord(userId, UserFact.KIND_TODO, event, normalizeTodoType(t.type), now);
                todo.dueTime = clean(t.time);
                todo.location = clean(t.location);
                todo.people = clean(t.people);
                added.add(todo);
                todos++;
            }
        }
        if (result.newFacts != null) {
            for (ExtractionResult.Fact f : result.newFacts) {
                String content = clean(f == null ? null : f.content);
                if (content == null) {
                    continue;
                }
                added.add(newRecord(userId, UserFact.KIND_FACT, content, normalizeFactType(f.type), now));
            }
        }
        List<Long> closed = new ArrayList<>();
        if (result.completedTodoIds != null) {
            closed.addAll(result.completedTodoIds);
        }
        if (result.outdatedFactIds != null) {
            closed.addAll(result.outdatedFactIds);
        }
        db.runInTransaction(() -> {
            if (!added.isEmpty()) {
                db.userFactDao().insertAll(added);
            }
            if (!closed.isEmpty()) {
                db.userFactDao().markDone(userId, closed, now);
            }
        });
        Log.i(TAG, "user " + userId + ": +" + todos + " todos, +" + (added.size() - todos) + " facts, "
                + closed.size() + " closed");
        return added;
    }

    private static UserFact newRecord(long userId, int kind, String content, String type, long now) {
        UserFact record = new UserFact();
        record.userId = userId;
        record.kind = kind;
        record.content = content;
        record.type = type;
        record.createdAt = now;
        record.updatedAt = now;
        return record;
    }

    private static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() || "null".equalsIgnoreCase(t) || "无".equals(t) ? null : t;
    }

    private static String normalizeTodoType(String type) {
        return UserFact.TYPE_PROMISE.equals(type) ? UserFact.TYPE_PROMISE : UserFact.TYPE_TODO;
    }

    private static String normalizeFactType(String type) {
        if (UserFact.TYPE_RECENT.equals(type) || UserFact.TYPE_PREFERENCE.equals(type)) {
            return type;
        }
        return UserFact.TYPE_INFO;
    }
}
