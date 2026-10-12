package com.stepos.whisper.service;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 录音服务与界面之间共享的状态。 */
public final class ServiceState {
    public enum Status { STOPPED, PREPARING, RECORDING, PAUSED, ERROR }

    private static final int MAX_LINES = 300;
    public static final long NO_PROMPT = -1L;

    private static final MutableLiveData<Status> sStatus = new MutableLiveData<>(Status.STOPPED);
    private static final MutableLiveData<String> sError = new MutableLiveData<>("");
    private static final MutableLiveData<String> sPartial = new MutableLiveData<>("");
    private static final MutableLiveData<List<TranscriptLine>> sLines =
            new MutableLiveData<>(Collections.emptyList());
    private static final MutableLiveData<AiStatus> sAiStatus =
            new MutableLiveData<>(new AiStatus(AiStatus.State.IDLE, 0, ""));
    /** 等着弹出命名窗口的新陌生人，-1 表示没有。主界面在前台时取走并弹窗。 */
    private static final MutableLiveData<Long> sStrangerPrompt = new MutableLiveData<>(NO_PROMPT);
    /** 等着弹出“这个昵称是谁”窗口的任务，null 表示没有。主界面在前台时取走并弹窗。 */
    private static final MutableLiveData<AliasPrompt> sAliasPrompt = new MutableLiveData<>(null);
    /** 所有还有昵称等机主指定的任务（截图和微信通知混在一起），主界面据此显示提示条。 */
    private static final MutableLiveData<List<AliasPrompt>> sAliasWaiting =
            new MutableLiveData<>(Collections.emptyList());
    /** 微信通知链路的当前进度，null 表示没有进行中的，主界面顶部提示条据此显示。 */
    private static final MutableLiveData<NotifProgress> sNotifProgress = new MutableLiveData<>(null);

    private static final List<TranscriptLine> sLineBuffer = new ArrayList<>();
    private static long sNextLineId = 1;
    private static volatile boolean sUiVisible;

    /** 大模型接口状态。 */
    public static final class AiStatus {
        public enum State {
            /** 空闲，队列为空 */
            IDLE,
            /** 正在请求 */
            WORKING,
            /** 连接失败，稍后自动重试 */
            RETRYING,
            /** 没有网络，等待联网 */
            OFFLINE,
            /** 未配置密钥或请求被拒绝 */
            ERROR
        }

        public final State state;
        /** 队列中等待识别的聊天块数。 */
        public final int pending;
        /** RETRYING 时为下次重试的秒数；ERROR 时为错误信息。 */
        public final String detail;

        public AiStatus(State state, int pending, String detail) {
            this.state = state;
            this.pending = pending;
            this.detail = detail;
        }
    }

    /** 一个等着机主指定昵称的任务：截图或微信通知。 */
    public static final class AliasPrompt {
        public final long jobId;
        /** true = 微信通知消息（对应 NotificationWorker），false = 聊天截图（对应 ScreenshotWorker）。 */
        public final boolean notification;

        public AliasPrompt(long jobId, boolean notification) {
            this.jobId = jobId;
            this.notification = notification;
        }
    }

    /** 微信消息“识别到 → 正在 AI 识别 → 结果”的进度。 */
    public static final class NotifProgress {
        public static final int RECEIVED = 0;
        public static final int ALIAS = 1;
        public static final int DONE = 2;
        public static final int NONE = 3;
        public static final int MERGE = 4;
        public static final int IGNORED = 5;

        public final int stage;
        /** 发送者昵称；MERGE 等场景可能为空。 */
        public final String sender;
        /** DONE 为新增条数，MERGE 为建议条数。 */
        public final int count;

        public NotifProgress(int stage, String sender, int count) {
            this.stage = stage;
            this.sender = sender;
            this.count = count;
        }
    }

    private ServiceState() {
    }

    public static LiveData<Status> status() {
        return sStatus;
    }

    public static LiveData<String> error() {
        return sError;
    }

    public static LiveData<String> partial() {
        return sPartial;
    }

    public static LiveData<List<TranscriptLine>> lines() {
        return sLines;
    }

    public static LiveData<AiStatus> aiStatus() {
        return sAiStatus;
    }

    public static void setAiStatus(AiStatus status) {
        sAiStatus.postValue(status);
    }

    public static LiveData<Long> strangerPrompt() {
        return sStrangerPrompt;
    }

    /** 主线程调用。只保留最近一位。 */
    static void requestStrangerPrompt(long userId) {
        sStrangerPrompt.setValue(userId);
    }

    /** 主线程调用：命名窗口已经弹出，或这位陌生人已被命名、关联、删除、收起。 */
    public static void clearStrangerPrompt(long userId) {
        Long pending = sStrangerPrompt.getValue();
        if (pending != null && pending == userId) {
            sStrangerPrompt.setValue(NO_PROMPT);
        }
    }

    public static LiveData<AliasPrompt> aliasPrompt() {
        return sAliasPrompt;
    }

    /** 主线程调用。只保留最近一个。 */
    static void requestAliasPrompt(long jobId, boolean notification) {
        sAliasPrompt.setValue(new AliasPrompt(jobId, notification));
    }

    /** 主线程调用：指定窗口已经弹出。 */
    public static void clearAliasPrompt(AliasPrompt prompt) {
        AliasPrompt pending = sAliasPrompt.getValue();
        if (pending != null && pending.jobId == prompt.jobId
                && pending.notification == prompt.notification) {
            sAliasPrompt.setValue(null);
        }
    }

    public static LiveData<List<AliasPrompt>> aliasWaiting() {
        return sAliasWaiting;
    }

    static void setAliasWaiting(List<AliasPrompt> prompts) {
        sAliasWaiting.postValue(new ArrayList<>(prompts));
    }

    public static LiveData<NotifProgress> notifProgress() {
        return sNotifProgress;
    }

    public static void setNotifProgress(NotifProgress progress) {
        sNotifProgress.postValue(progress);
    }

    public static void setUiVisible(boolean visible) {
        sUiVisible = visible;
    }

    static boolean isUiVisible() {
        return sUiVisible;
    }

    static void setStatus(Status status) {
        sStatus.postValue(status);
    }

    static void setError(String message) {
        sError.postValue(message);
        sStatus.postValue(Status.ERROR);
    }

    static void setPartial(String text) {
        sPartial.postValue(text);
    }

    /** 返回该行的 id（声纹结果回来后按 id 补标说话人）。 */
    static synchronized long addLine(long timeMs, long endMs, String speaker, String text, long userId, int color,
                                     int aiState) {
        long id = sNextLineId++;
        sLineBuffer.add(new TranscriptLine(id, timeMs, endMs, speaker, text, userId, color, aiState));
        if (sLineBuffer.size() > MAX_LINES) {
            sLineBuffer.remove(0);
        }
        publish();
        return id;
    }

    static synchronized void clearLines() {
        sLineBuffer.clear();
        publish();
    }

    static synchronized void relabelUser(long userId, String speaker, long newUserId, int color) {
        for (int i = 0; i < sLineBuffer.size(); i++) {
            TranscriptLine line = sLineBuffer.get(i);
            if (line.userId == userId) {
                sLineBuffer.set(i, line.withSpeaker(speaker, newUserId, color));
            }
        }
        publish();
    }

    static synchronized void relabelLine(long lineId, String speaker, long userId, int color) {
        for (int i = 0; i < sLineBuffer.size(); i++) {
            TranscriptLine line = sLineBuffer.get(i);
            if (line.id == lineId) {
                sLineBuffer.set(i, line.withSpeaker(speaker, userId, color));
                publish();
                return;
            }
        }
    }

    /** 与 UtteranceDao 的规则一致：落在区间内的行只升级 AI 状态；reset 为 true 时把排队中改回未识别。 */
    static synchronized void updateAiState(long from, long to, int state, boolean reset) {
        boolean changed = false;
        for (int i = 0; i < sLineBuffer.size(); i++) {
            TranscriptLine line = sLineBuffer.get(i);
            if (line.endMs < from || line.timeMs > to) {
                continue;
            }
            int next = reset ? (line.aiState == 1 ? 0 : line.aiState) : Math.max(line.aiState, state);
            if (next != line.aiState) {
                sLineBuffer.set(i, line.withAiState(next));
                changed = true;
            }
        }
        if (changed) {
            publish();
        }
    }

    private static void publish() {
        sLines.postValue(new ArrayList<>(sLineBuffer));
    }
}
