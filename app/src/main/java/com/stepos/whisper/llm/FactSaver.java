package com.stepos.whisper.llm;

import android.util.Log;

import com.stepos.whisper.data.AppDatabase;
import com.stepos.whisper.data.FactCard;
import com.stepos.whisper.data.User;
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

    /**
     * 把合并建议变成待处理的候选待办：每条建议先按合并后的描述插一条待办（保留两条时它就是那条新待办），
     * 返回带上下文的建议列表，由界面弹卡片让机主确认；确认后调 applyMerge 更新 target 并删除候选。
     * 不能在主线程调用。
     */
    public static List<MergeSuggestion> saveMergeCandidates(AppDatabase db, long userId,
                                                            List<ExtractionResult.MergeTodo> merges) {
        List<MergeSuggestion> out = new ArrayList<>();
        if (merges == null || merges.isEmpty()) {
            return out;
        }
        User user = db.userDao().get(userId);
        if (user == null) {
            return out;
        }
        long now = System.currentTimeMillis();
        for (ExtractionResult.MergeTodo m : merges) {
            String event = clean(m == null ? null : m.event);
            if (event == null || m.targetId <= 0) {
                continue;
            }
            // 候选待办显示新消息自己的内容（模型没单独给时退回合并句），拒绝合并时保留的也是它。
            String incoming = clean(m.newEvent);
            if (incoming == null) {
                incoming = event;
            }
            UserFact target = null;
            for (UserFact f : db.userFactDao().getPending(userId)) {
                if (f.id == m.targetId && f.kind == UserFact.KIND_TODO) {
                    target = f;
                    break;
                }
            }
            if (target == null) {
                // 目标待办已被删或已完成：合并无从谈起，直接按普通新待办插入。
                UserFact todo = newRecord(userId, UserFact.KIND_TODO, incoming, UserFact.TYPE_TODO, now);
                todo.dueTime = clean(m.time);
                todo.location = clean(m.location);
                todo.people = clean(m.people);
                db.userFactDao().insert(todo);
                continue;
            }
            UserFact candidate = newRecord(userId, UserFact.KIND_TODO, incoming, target.type, now);
            candidate.dueTime = clean(m.time);
            candidate.location = clean(m.location);
            candidate.people = clean(m.people);
            candidate.id = db.userFactDao().insert(candidate);
            out.add(new MergeSuggestion(userId, card(user, target), card(user, candidate),
                    event, candidate.dueTime, candidate.location, candidate.people));
        }
        if (!out.isEmpty()) {
            Log.i(TAG, "user " + userId + ": " + out.size() + " merge suggestion(s)");
        }
        return out;
    }

    private static FactCard card(User user, UserFact fact) {
        FactCard card = new FactCard();
        card.fact = fact;
        card.userName = user.name;
        card.userColor = user.color;
        return card;
    }

    /** 机主确认合并：保留 target 并写入合并后的内容，被吸收的候选删除。null 字段沿用 target 旧值。 */
    public static void applyMerge(AppDatabase db, MergeSuggestion s) {
        long now = System.currentTimeMillis();
        db.runInTransaction(() -> {
            db.userFactDao().applyMerge(s.target.fact.id, s.event,
                    s.time != null ? s.time : s.target.fact.dueTime,
                    s.location != null ? s.location : s.target.fact.location,
                    s.people != null ? s.people : s.target.fact.people, now);
            db.userFactDao().delete(s.incoming.fact.id);
        });
        Log.i(TAG, "merged todo " + s.incoming.fact.id + " into " + s.target.fact.id);
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
        return t.isEmpty() || "null".equalsIgnoreCase(t) || "无".equals(t)
                || t.matches("\\[\\s*\\]") ? null : t;
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
