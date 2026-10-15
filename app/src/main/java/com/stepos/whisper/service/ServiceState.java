package com.stepos.whisper.service;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    private static final List<TranscriptLine> sLineBuffer = new ArrayList<>();
    private static final Set<Long> sVoiceprintBlocked = new HashSet<>();
    private static long sNextLineId = 1;
    private static volatile boolean sUiVisible;
    /** 独立于界面行缓冲；只在句子归属落库后解除 AI 门禁。 */
    private static boolean sPendingVoiceprints;
    private static long sVoiceprintRevision;

    /** 大模型接口状态。 */
    public static final class AiStatus {
        public enum State {
            /** 空闲或等待声纹，没有正在进行的请求 */
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

    public static synchronized boolean hasPendingVoiceprints() {
        return sPendingVoiceprints;
    }

    /** Worker 用版本校验识别期间是否有新句子或归属变更，避免使用旧快照。 */
    public static synchronized long voiceprintRevision() {
        return sVoiceprintRevision;
    }

    static synchronized void setPendingVoiceprints(boolean pending) {
        sPendingVoiceprints = pending;
        sVoiceprintRevision++;
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
        trimVoiceLines();
        publish();
        return id;
    }

    /** 任意线程；稳定任务 id 去重，完成的通知仅保留在本次运行期。 */
    public static synchronized void upsertNotificationLine(TranscriptLine line) {
        if (!line.isNotification()) {
            throw new IllegalArgumentException("Expected a notification line");
        }
        for (int i = 0; i < sLineBuffer.size(); i++) {
            TranscriptLine existing = sLineBuffer.get(i);
            if (existing.isNotification() && existing.notificationJobId == line.notificationJobId) {
                // 启动恢复或落库回调不可将已经完成的行退回排队中。
                if ((existing.aiState == com.stepos.whisper.data.Utterance.AI_DONE
                        && line.aiState != com.stepos.whisper.data.Utterance.AI_DONE)
                        || (existing.aiState == com.stepos.whisper.data.Utterance.AI_NONE
                        && line.aiState == com.stepos.whisper.data.Utterance.AI_QUEUED)) {
                    return;
                }
                sLineBuffer.set(i, line);
                publish();
                return;
            }
        }
        sLineBuffer.add(line);
        trimNotificationLines();
        publish();
    }

    private static void trimNotificationLines() {
        List<TranscriptLine> notifications = new ArrayList<>();
        for (TranscriptLine row : sLineBuffer) {
            if (row.isNotification()) notifications.add(row);
        }
        notifications.sort((a, b) -> {
            int time = Long.compare(a.timeMs, b.timeMs);
            return time != 0 ? time : Long.compare(a.notificationJobId, b.notificationJobId);
        });
        for (int i = 0; i < notifications.size() - MAX_LINES; i++) {
            sLineBuffer.remove(notifications.get(i));
        }
    }

    private static void trimVoiceLines() {
        int count = 0;
        for (TranscriptLine line : sLineBuffer) {
            if (!line.isNotification()) count++;
        }
        for (int i = 0; count > MAX_LINES && i < sLineBuffer.size();) {
            if (!sLineBuffer.get(i).isNotification()) {
                sVoiceprintBlocked.remove(sLineBuffer.remove(i).id);
                count--;
            } else {
                i++;
            }
        }
    }

    /** 录音服务恢复/清理语音时不移除运行期通知。 */
    static synchronized void clearLines() {
        sLineBuffer.removeIf(line -> !line.isNotification());
        sVoiceprintBlocked.clear();
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

    /** 只收尾仍待识别的行，不覆盖已识别或手动指认的归属。 */
    static synchronized boolean resolvePendingLine(long lineId, String pending, String unknown) {
        if (sVoiceprintBlocked.contains(lineId)) {
            return false;
        }
        for (int i = 0; i < sLineBuffer.size(); i++) {
            TranscriptLine line = sLineBuffer.get(i);
            if (!line.isNotification() && line.id == lineId && line.userId == TranscriptLine.NO_USER && pending.equals(line.speaker)) {
                sLineBuffer.set(i, line.withSpeaker(unknown, TranscriptLine.NO_USER, TranscriptLine.COLOR_UNKNOWN));
                publish();
                return true;
            }
        }
        return false;
    }

    static synchronized TranscriptLine latestLine() {
        for (int i = sLineBuffer.size() - 1; i >= 0; i--) {
            if (!sLineBuffer.get(i).isNotification()) return sLineBuffer.get(i);
        }
        return null;
    }

    static synchronized void resolveAllPending(String pending, String unknown) {
        for (int i = 0; i < sLineBuffer.size(); i++) {
            TranscriptLine line = sLineBuffer.get(i);
            if (!line.isNotification() && line.userId == TranscriptLine.NO_USER && pending.equals(line.speaker)) {
                sLineBuffer.set(i, line.withSpeaker(unknown, TranscriptLine.NO_USER, TranscriptLine.COLOR_UNKNOWN));
            }
        }
        publish();
    }

    static synchronized boolean isVoiceprintBlocked(long lineId) {
        return sVoiceprintBlocked.contains(lineId);
    }

    static synchronized void blockVoiceprint(long lineId) {
        for (TranscriptLine line : sLineBuffer) {
            if (!line.isNotification() && line.id == lineId) {
                sVoiceprintBlocked.add(lineId);
                return;
            }
        }
    }

    /** 超时变成未知仍可补标，但已有归属（包括手动指认）绝不接受声纹覆盖。 */
    static synchronized boolean canApplyVoiceprint(long lineId) {
        if (sVoiceprintBlocked.contains(lineId)) {
            return false;
        }
        for (TranscriptLine line : sLineBuffer) {
            if (!line.isNotification() && line.id == lineId) {
                return line.userId == TranscriptLine.NO_USER;
            }
        }
        return false;
    }

    static synchronized boolean applyVoiceprint(long lineId, String speaker, long userId, int color) {
        if (!canApplyVoiceprint(lineId)) {
            return false;
        }
        relabelLine(lineId, speaker, userId, color);
        return true;
    }

    static synchronized void relabelLine(long lineId, String speaker, long userId, int color) {
        for (int i = 0; i < sLineBuffer.size(); i++) {
            TranscriptLine line = sLineBuffer.get(i);
            if (!line.isNotification() && line.id == lineId) {
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
            if (line.isNotification() || line.endMs < from || line.timeMs > to) {
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
        List<TranscriptLine> lines = new ArrayList<>(sLineBuffer);
        lines.sort((a, b) -> {
            int byTime = Long.compare(a.timeMs, b.timeMs);
            return byTime != 0 ? byTime : Long.compare(a.id, b.id);
        });
        sLines.postValue(lines);
    }
}
