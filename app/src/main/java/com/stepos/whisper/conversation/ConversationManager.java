package com.stepos.whisper.conversation;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 按用户切分会话：某用户距上次说话超过 {@link #SESSION_TIMEOUT_MS}（或第一次说话）时开始一段新会话并触发事项提醒；
 * 超时没再听到该用户就结束会话，交给大模型提取事项。
 * 会话进行中也可以用 {@link #checkpoint} 把已经说过的部分先交给大模型，会话本身继续（不会再次提醒）。
 * 只在处理线程上调用。
 */
public class ConversationManager {
    public static final long SESSION_TIMEOUT_MS = 5 * 60 * 1000L;
    /** 提取时向前多带一点上下文，覆盖对方开口前机主说的话。 */
    private static final long CONTEXT_BEFORE_MS = 30 * 1000L;

    public interface Listener {
        /** 新会话开始，此时应查询该用户未完成的事项并提醒。 */
        void onConversationStarted(long userId);

        /** [startMs, endMs] 内的对话应交给大模型提取事项（会话结束或会话中途定时提取）。 */
        void onConversationSegment(long userId, long startMs, long endMs);
    }

    private static class Session {
        long lastHeardMs;
        /** 下一段交给大模型的起点。 */
        long extractFromMs;
        /** 上一段交给大模型时该用户最后说话的时间；之后没再说话就不用再提取。 */
        long extractedHeardMs = Long.MIN_VALUE;

        Session(long startMs) {
            this.lastHeardMs = startMs;
            this.extractFromMs = startMs - CONTEXT_BEFORE_MS;
        }

        boolean hasNewSpeech() {
            return lastHeardMs > extractedHeardMs;
        }
    }

    private final Listener mListener;
    private final Map<Long, Session> mSessions = new HashMap<>();

    public ConversationManager(Listener listener) {
        mListener = listener;
    }

    public void onUtterance(long userId, long startMs, long endMs) {
        Session session = mSessions.get(userId);
        if (session != null && startMs - session.lastHeardMs > SESSION_TIMEOUT_MS) {
            // 定时检查还没来得及结束的旧会话，这里立即结束，保证隔够时间再开口一定会提醒。
            mSessions.remove(userId);
            if (session.hasNewSpeech()) {
                mListener.onConversationSegment(userId, session.extractFromMs,
                        session.lastHeardMs + SESSION_TIMEOUT_MS);
            }
            session = null;
        }
        if (session == null) {
            session = new Session(startMs);
            mSessions.put(userId, session);
            mListener.onConversationStarted(userId);
        }
        session.lastHeardMs = Math.max(session.lastHeardMs, endMs);
    }

    /**
     * 服务重启后恢复最近说过话的用户的会话，避免刚重启就把还在聊的人当成隔了很久再开口。
     * 之前的内容已在服务退出时交给大模型，这里不再提取。
     */
    public void restore(long userId, long lastHeardMs) {
        if (mSessions.containsKey(userId)) {
            return;
        }
        Session s = new Session(lastHeardMs);
        s.extractFromMs = lastHeardMs;
        s.extractedHeardMs = lastHeardMs;
        mSessions.put(userId, s);
    }

    /** 定时调用，结束超时的会话。 */
    public void tick(long now) {
        Iterator<Map.Entry<Long, Session>> it = mSessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Session> e = it.next();
            Session s = e.getValue();
            if (now - s.lastHeardMs > SESSION_TIMEOUT_MS) {
                it.remove();
                if (s.hasNewSpeech()) {
                    mListener.onConversationSegment(e.getKey(), s.extractFromMs,
                            s.lastHeardMs + SESSION_TIMEOUT_MS);
                }
            }
        }
    }

    /** 把所有进行中会话里新说的部分交给大模型，会话继续。 */
    public void checkpoint(long now) {
        for (Map.Entry<Long, Session> e : mSessions.entrySet()) {
            Session s = e.getValue();
            if (!s.hasNewSpeech()) {
                continue;
            }
            mListener.onConversationSegment(e.getKey(), s.extractFromMs, now);
            s.extractFromMs = now;
            s.extractedHeardMs = s.lastHeardMs;
        }
    }

    /** 服务退出时结束所有会话。 */
    public void endAll() {
        for (Map.Entry<Long, Session> e : mSessions.entrySet()) {
            Session s = e.getValue();
            if (s.hasNewSpeech()) {
                mListener.onConversationSegment(e.getKey(), s.extractFromMs, s.lastHeardMs);
            }
        }
        mSessions.clear();
    }

    /** 清空聊天记录时丢弃所有会话，不再提取。 */
    public void clear() {
        mSessions.clear();
    }

    /** 用户被删除时丢弃其会话，不再提取。 */
    public void forget(long userId) {
        mSessions.remove(userId);
    }

    /** 用户 from 并入 to：会话合并到 to 名下，不再单独提醒。 */
    public void merge(long from, long to) {
        Session src = mSessions.remove(from);
        if (src == null) {
            return;
        }
        Session dst = mSessions.get(to);
        if (dst == null) {
            mSessions.put(to, src);
        } else {
            dst.lastHeardMs = Math.max(dst.lastHeardMs, src.lastHeardMs);
        }
    }
}
