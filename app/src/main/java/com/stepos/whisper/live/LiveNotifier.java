package com.stepos.whisper.live;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.stepos.whisper.R;
import com.stepos.whisper.WhisperApp;
import com.stepos.whisper.data.FactCard;
import com.stepos.whisper.data.UserFact;
import com.stepos.whisper.screenshot.ScreenshotWorker;
import com.stepos.whisper.service.RecordingService;
import com.stepos.whisper.service.ServiceState;
import com.stepos.whisper.service.ServiceState.AiStatus;
import com.stepos.whisper.ui.ChatAliasActivity;
import com.stepos.whisper.ui.FactFormat;
import com.stepos.whisper.ui.MainActivity;
import com.stepos.whisper.ui.SpeakerEditActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 灵动岛（SystemUI 实况通知）：
 * <ul>
 *   <li>常驻录音状态：新录音模板（显示当前说话人）+ 说话人颜色的胶囊，一直显示，兼作前台服务通知；</li>
 *   <li>熟人隔一段时间再开口（不在本应用里时）：中卡 + 用户颜色的胶囊；</li>
 *   <li>新陌生人：双按钮中卡“以后再说 / 命名”；</li>
 *   <li>新截图的处理进度：小卡，检测到 → 识别文字 → AI 分析 → 结果，结果几秒后自动消失；</li>
 *   <li>聊天截图里出现没对应过的昵称：双按钮中卡“以后再说 / 指定”；</li>
 *   <li>AI 提取到新事项：小卡，一分钟后自动消失；</li>
 *   <li>AI 连续失败、无网络或被拒绝：单按钮中卡“立即重试”，恢复后自动消失。</li>
 * </ul>
 * 卡片标题行和摄像头挖孔同高，只放得下几个字，所以标题只写主体（应用名、人名），
 * 状态和详情放在第二行（SystemUI 显示为“状态 · 内容”）。
 * 只在主线程调用。
 */
public class LiveNotifier {
    public static final int ID_RECORDING = 1;
    static final int ID_STRANGER = 2;
    private static final int ID_NEW_FACTS = 3;
    private static final int ID_AI_ERROR = 4;
    static final int ID_CHAT_ALIAS = 5;
    private static final int ID_SCREENSHOT = 6;
    private static final int ID_REMINDER_BASE = 100000;

    private static final String EVENT_RECORDING = "SOUND_RECORDING";
    private static final String EVENT_SCHEDULE = "SCHEDULE";
    private static final String EVENT_PENDING = "LIVE_PENDING";
    private static final String EVENT_PROCESSING = "LIVE_PROCESSING";
    private static final String EVENT_DONE = "LIVE_DONE";
    private static final String EVENT_AI = "AI";
    private static final String EVENT_FAILED = "LIVE_FAILED";

    private static final int COLOR_RECORDING = 0xFFE53935;
    private static final int COLOR_PAUSED = 0xFF757575;
    private static final int COLOR_WARN = 0xFFF57C00;
    private static final int COLOR_ERROR = 0xFFE53935;
    private static final int COLOR_PROGRESS = 0xFF1E88E5;
    private static final int COLOR_DONE = 0xFF43A047;
    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_CARD_BG = 0xB21A1A1A;
    /** 标题、胶囊文字的长度上限（SystemUI 限制 28 个字符）。 */
    private static final int MAX_TEXT = 28;

    /** 录音卡片随最新发言更新，最快这么久一次，避免频繁刷新 SystemUI。 */
    private static final long LINE_UPDATE_INTERVAL_MS = 2000;
    /** 胶囊显示说话人的时长，之后没人说话就变回“录音中”。 */
    private static final long SPEAKER_RECENT_MS = 30_000;
    private static final long NEW_FACTS_TIMEOUT_MS = 60_000;
    private static final long SCREENSHOT_RESULT_TIMEOUT_MS = 5_000;
    /** 截图写完才开始识别；用户在系统截图预览里直接删掉时，“检测到截图”最多留这么久。 */
    private static final long SCREENSHOT_DETECTED_TIMEOUT_MS = 15_000;
    /** SystemUI 弹出卡片期间（约 3 秒）收到的更新不刷新胶囊，过这么久再发一次最新状态。 */
    private static final long SCREENSHOT_REPOST_MS = 4_500;
    /** 截图识别中途进程被杀时，进度卡片最多留这么久。 */
    private static final long SCREENSHOT_STUCK_TIMEOUT_MS = 3 * 60_000;
    private static final long REMINDER_TIMEOUT_MS = 10 * 60_000;
    /** 重试间隔达到这么多秒（已连续失败两次以上）才弹 AI 异常卡片，偶发失败不打扰。 */
    private static final int AI_ERROR_MIN_RETRY_S = 20;

    private final Context mContext;
    private final NotificationManager mNm;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Icon mBlankIcon;

    // 录音卡片
    private boolean mReady;
    private boolean mRecording;
    /** 录音无法进行的原因；为 null 表示正常。 */
    private String mError;
    private String mSpeaker;
    private int mSpeakerColor;
    private String mSpeakerText;
    private long mSpokeAtMs;
    private long mLastRecordingPostMs;
    private final Runnable mPostRecording = this::postRecording;

    private Notification mScreenshotLatest;
    private final Runnable mRepostScreenshot = this::repostScreenshot;

    private long mStrangerUserId = -1;
    private boolean mAiErrorShown;
    private final Set<Integer> mReminderIds = new HashSet<>();

    public LiveNotifier(Context context) {
        mContext = context;
        mNm = context.getSystemService(NotificationManager.class);
        mBlankIcon = Icon.createWithResource(context, R.color.transparent);
    }

    // ---------------------------------------------------------------- 常驻录音状态

    /**
     * 前台服务通知，录音状态变化时也用它刷新。不显示录音时长：左下角（计时器的位置）显示当前说话人，
     * 副文本是他刚说的话；胶囊显示说话人的名字和颜色，{@link #SPEAKER_RECENT_MS} 内没人说话就显示录音状态。
     * 胶囊的标题和扩展文本填同一句（窄屏竖屏只显示其中一个）。
     */
    public Notification buildRecording() {
        Context c = mContext;
        boolean speaking = mRecording && mSpeaker != null
                && SystemClock.uptimeMillis() - mSpokeAtMs < SPEAKER_RECENT_MS;
        String title = c.getString(R.string.app_name);
        String state;
        String content;
        String bottom;
        int color;
        if (mError != null) {
            state = c.getString(R.string.live_rec_state_error);
            content = mError;
            bottom = c.getString(R.string.live_rec_error_hint);
            color = COLOR_ERROR;
        } else if (!mReady) {
            state = c.getString(R.string.live_rec_state_preparing);
            content = c.getString(R.string.status_preparing);
            bottom = state;
            color = COLOR_PAUSED;
        } else if (mRecording) {
            state = c.getString(R.string.live_rec_state);
            content = mSpeaker != null ? "“" + mSpeakerText + "”" : c.getString(R.string.live_rec_idle);
            bottom = mSpeaker != null ? mSpeaker : c.getString(R.string.live_rec_waiting);
            color = COLOR_RECORDING;
        } else {
            state = c.getString(R.string.live_rec_state_paused);
            content = c.getString(R.string.live_rec_paused_hint);
            bottom = state;
            color = COLOR_PAUSED;
        }

        PendingIntent open = openApp(0, -1);
        Notification.Builder b = new Notification.Builder(c, WhisperApp.CHANNEL_LIVE_RECORDING)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);

        Bundle e = baseExtras(LiveExtras.TYPE_SOUND_RECORD, EVENT_RECORDING, false, title, content);
        e.putString(LiveExtras.TASK_STATE_DESC, state);
        e.putInt(LiveExtras.TASK_STATE_DESC_COLOR, color);
        e.putInt(LiveExtras.BG_COLOR_OVERLAY, COLOR_CARD_BG);
        e.putString(LiveExtras.BOTTOM_TEXT, clip(bottom));
        if (mReady || mError != null) {
            // 录音模板的两个按钮：图标走 extendImage，点击走同序号的 Action。出错时第一个按钮是“继续”（重试）。
            PendingIntent toggle = PendingIntent.getService(c, 1,
                    new Intent(c, RecordingService.class).setAction(
                            mError != null ? RecordingService.ACTION_RESUME : RecordingService.ACTION_TOGGLE),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            boolean showPause = mRecording && mError == null;
            b.addAction(action(c.getString(showPause ? R.string.live_action_pause : R.string.action_resume),
                    toggle));
            b.addAction(action(c.getString(R.string.live_action_open), open));
            ArrayList<Icon> buttons = new ArrayList<>();
            buttons.add(Icon.createWithResource(c, showPause ? R.drawable.ic_live_pause : R.drawable.ic_live_play));
            buttons.add(Icon.createWithResource(c, R.drawable.ic_live_open));
            e.putParcelableArrayList(LiveExtras.EXTEND_IMAGE, buttons);
        }

        String capsule = speaking ? mSpeaker : state;
        e.putBundle(LiveExtras.CAPSULE, textCapsule(R.drawable.ic_mic, speaking ? mSpeakerColor : color,
                capsule, capsule, mError != null));
        b.addExtras(e);
        return b.build();
    }

    public void setRecordingState(boolean ready, boolean recording) {
        if (recording && !mRecording) {
            mSpeaker = null;
        }
        mReady = ready;
        mRecording = recording;
        // 重新开始录音或正常暂停，说明之前的错误已经过去。
        mError = null;
        postRecording();
    }

    /** 录音无法进行（模型缺失、没有权限、麦克风打不开）：胶囊变红显示“出错”，卡片写明原因，按钮变成“继续”重试。 */
    public void setRecordingError(String message) {
        mError = message == null || message.isEmpty() ? mContext.getString(R.string.live_rec_state_error) : message;
        mRecording = false;
        mSpeaker = null;
        postRecording();
    }

    /** 有人说完一句话。卡片和胶囊换成这个人；{@link #SPEAKER_RECENT_MS} 后没人再说话，胶囊变回“录音中”。 */
    public void setLatestLine(String speaker, int color, String text) {
        mSpeaker = speaker;
        mSpeakerColor = 0xFF000000 | color;
        mSpeakerText = text;
        mSpokeAtMs = SystemClock.uptimeMillis();
        mMain.removeCallbacks(mPostRecording);
        long wait = mLastRecordingPostMs + LINE_UPDATE_INTERVAL_MS - SystemClock.uptimeMillis();
        if (wait <= 0) {
            postRecording();
        } else {
            mMain.postDelayed(mPostRecording, wait);
        }
    }

    private void postRecording() {
        mMain.removeCallbacks(mPostRecording);
        mLastRecordingPostMs = SystemClock.uptimeMillis();
        mNm.notify(ID_RECORDING, buildRecording());
        if (mRecording && mSpeaker != null) {
            long left = mSpokeAtMs + SPEAKER_RECENT_MS - SystemClock.uptimeMillis();
            if (left > 0) {
                mMain.postDelayed(mPostRecording, left + 100);
            }
        }
    }

    // ---------------------------------------------------------------- 熟人事项提醒

    /** 不在本应用里时，熟人隔一段时间再开口：中卡列出事项数和第一条，点“查看”回到应用弹出全部卡片。 */
    public void showReminder(long userId, String name, int color, List<FactCard> facts) {
        Context c = mContext;
        UserFact first = facts.get(0).fact;
        String content = c.getString(R.string.live_fact_line, first.type, FactFormat.oneLine(first));
        PendingIntent view = openApp(1000 + (int) userId, userId);
        Notification.Builder b = alertBuilder(name, content, view)
                .setTimeoutAfter(REMINDER_TIMEOUT_MS)
                .addAction(action(c.getString(R.string.live_action_view), view));
        Bundle e = baseExtras(LiveExtras.TYPE_CARD, EVENT_SCHEDULE, true, name, content);
        e.putString(LiveExtras.TASK_STATE_DESC, c.getString(R.string.live_reminder_state, facts.size()));
        e.putInt(LiveExtras.TASK_STATE_DESC_COLOR, color);
        e.putBundle(LiveExtras.CAPSULE, textCapsule(R.drawable.ic_live_person, color,
                c.getString(R.string.live_reminder_capsule, name, facts.size()), first.content, true));
        int id = ID_REMINDER_BASE + (int) userId;
        mReminderIds.add(id);
        mNm.notify(id, b.addExtras(e).build());
    }

    public void cancelReminder(long userId) {
        int id = ID_REMINDER_BASE + (int) userId;
        mReminderIds.remove(id);
        mNm.cancel(id);
    }

    // ---------------------------------------------------------------- 新陌生人

    /** 只保留最近一位待命名的陌生人。点“命名”打开命名窗口，点“以后再说”收起。 */
    public void showStranger(long userId, String name, int color, String text) {
        Context c = mContext;
        mStrangerUserId = userId;
        String content = "“" + text + "”";
        PendingIntent rename = PendingIntent.getActivity(c, 2000 + (int) userId,
                SpeakerEditActivity.createPromptIntent(c, userId).putExtra(LiveExtras.INTENT_DISMISS_PANEL, true),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent later = PendingIntent.getBroadcast(c, 3000 + (int) userId,
                new Intent(c, LiveActionReceiver.class)
                        .setAction(LiveActionReceiver.ACTION_STRANGER_LATER)
                        .putExtra(LiveActionReceiver.EXTRA_USER_ID, userId),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = alertBuilder(name, content, rename)
                .addAction(action(c.getString(R.string.action_later), later))
                .addAction(action(c.getString(R.string.live_action_name), rename));
        Bundle e = baseExtras(LiveExtras.TYPE_DIALOG_MEDIUM_BUTTON, EVENT_PENDING, true, name, content);
        e.putString(LiveExtras.TASK_STATE_DESC, c.getString(R.string.live_stranger_new));
        e.putInt(LiveExtras.TASK_STATE_DESC_COLOR, COLOR_WARN);
        e.putBundle(LiveExtras.CAPSULE, textCapsule(R.drawable.ic_live_person, color, name,
                c.getString(R.string.live_stranger_state), true));
        mNm.notify(ID_STRANGER, b.addExtras(e).build());
    }

    /** 这位陌生人已命名、关联、删除、被用户收起，或命名窗口已经打开时调用。 */
    public void cancelStranger(long userId) {
        ServiceState.clearStrangerPrompt(userId);
        if (userId == mStrangerUserId) {
            mStrangerUserId = -1;
            mNm.cancel(ID_STRANGER);
        }
    }

    // ---------------------------------------------------------------- 截图识别进度

    /**
     * 检测到截图和出结果时弹出，中间步骤只刷新卡片和胶囊；结果几秒后自动消失。
     * 提取到事项、需要指定昵称时直接收起，由新事项、聊天截图卡片接着提示。
     */
    public void showScreenshotProgress(ScreenshotWorker.Stage stage, int count) {
        Context c = mContext;
        int state;
        int capsule;
        int content;
        int color;
        String event;
        boolean done = true;
        boolean popup = true;
        long timeout = SCREENSHOT_RESULT_TIMEOUT_MS;
        switch (stage) {
            case DETECTED:
                state = capsule = R.string.live_shot_detected;
                content = R.string.live_shot_detected_content;
                color = COLOR_PROGRESS;
                event = EVENT_PROCESSING;
                done = false;
                timeout = SCREENSHOT_DETECTED_TIMEOUT_MS;
                break;
            case OCR:
                state = R.string.live_shot_ocr;
                capsule = R.string.live_shot_ocr_capsule;
                content = R.string.live_shot_ocr_content;
                color = COLOR_PROGRESS;
                event = EVENT_PROCESSING;
                done = popup = false;
                timeout = SCREENSHOT_STUCK_TIMEOUT_MS;
                break;
            case AI:
                state = R.string.live_shot_ai;
                capsule = R.string.live_shot_ai_capsule;
                content = R.string.live_shot_ai_content;
                color = COLOR_PROGRESS;
                event = EVENT_PROCESSING;
                done = popup = false;
                timeout = SCREENSHOT_STUCK_TIMEOUT_MS;
                break;
            case DONE:
                if (count > 0) {
                    cancelScreenshotProgress();
                    return;
                }
                state = R.string.live_shot_done;
                capsule = R.string.live_shot_done_capsule;
                content = R.string.live_shot_done_content;
                color = COLOR_DONE;
                event = EVENT_DONE;
                break;
            case NO_TEXT:
                state = R.string.live_shot_skipped;
                capsule = R.string.live_shot_skipped_capsule;
                content = R.string.live_shot_skipped_content;
                color = COLOR_PAUSED;
                event = EVENT_DONE;
                break;
            case DEFERRED:
                state = capsule = R.string.live_shot_deferred;
                content = R.string.live_shot_deferred_content;
                color = COLOR_WARN;
                event = EVENT_FAILED;
                break;
            case FAILED:
                state = R.string.live_shot_failed;
                capsule = R.string.live_shot_failed_capsule;
                content = R.string.live_shot_failed_content;
                color = COLOR_ERROR;
                event = EVENT_FAILED;
                break;
            default:
                cancelScreenshotProgress();
                return;
        }
        String title = c.getString(R.string.live_shot_title);
        String text = c.getString(content);
        Notification.Builder b = new Notification.Builder(c, WhisperApp.CHANNEL_LIVE_PROGRESS)
                .setSmallIcon(R.drawable.ic_live_screenshot)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openApp(6, -1))
                .setOngoing(!done)
                .setAutoCancel(done)
                .setOnlyAlertOnce(!popup)
                .setWhen(System.currentTimeMillis())
                .setTimeoutAfter(timeout);
        Bundle e = baseExtras(LiveExtras.TYPE_CARD, event, popup, title, text);
        e.putString(LiveExtras.TASK_STATE_DESC, c.getString(state));
        e.putInt(LiveExtras.TASK_STATE_DESC_COLOR, color);
        String capsuleText = c.getString(capsule);
        e.putBundle(LiveExtras.CAPSULE, textCapsule(R.drawable.ic_live_screenshot, color,
                capsuleText, capsuleText, false));
        Notification n = b.addExtras(e).build();
        mMain.removeCallbacks(mRepostScreenshot);
        mNm.notify(ID_SCREENSHOT, n);
        if (!popup) {
            mScreenshotLatest = n;
            mMain.postDelayed(mRepostScreenshot, SCREENSHOT_REPOST_MS);
        }
    }

    private void repostScreenshot() {
        mNm.notify(ID_SCREENSHOT, mScreenshotLatest);
    }

    private void cancelScreenshotProgress() {
        mMain.removeCallbacks(mRepostScreenshot);
        mNm.cancel(ID_SCREENSHOT);
    }

    // ---------------------------------------------------------------- 聊天截图新昵称

    /** 聊天截图里出现了还没对应到用户的昵称。只保留最近一张截图的；点“指定”打开对应窗口。 */
    public void showChatAlias(long jobId, List<String> names) {
        Context c = mContext;
        String shown = String.join("、", names);
        String title = c.getString(R.string.live_alias_title);
        String content = names.size() == 1 ? c.getString(R.string.live_alias_content, shown)
                : c.getString(R.string.live_alias_content_many, shown, names.size());
        PendingIntent assign = PendingIntent.getActivity(c, 5000,
                ChatAliasActivity.createIntent(c, jobId).putExtra(LiveExtras.INTENT_DISMISS_PANEL, true),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent later = PendingIntent.getBroadcast(c, 5001,
                new Intent(c, LiveActionReceiver.class).setAction(LiveActionReceiver.ACTION_ALIAS_LATER),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = alertBuilder(title, content, assign)
                .addAction(action(c.getString(R.string.action_later), later))
                .addAction(action(c.getString(R.string.live_action_assign), assign));
        Bundle e = baseExtras(LiveExtras.TYPE_DIALOG_MEDIUM_BUTTON, EVENT_PENDING, true, title, content);
        e.putString(LiveExtras.TASK_STATE_DESC, c.getString(R.string.live_alias_state));
        e.putInt(LiveExtras.TASK_STATE_DESC_COLOR, COLOR_WARN);
        e.putBundle(LiveExtras.CAPSULE, textCapsule(R.drawable.ic_live_person, COLOR_WARN, shown,
                c.getString(R.string.live_alias_state), true));
        mNm.notify(ID_CHAT_ALIAS, b.addExtras(e).build());
    }

    public void cancelChatAlias() {
        mNm.cancel(ID_CHAT_ALIAS);
    }

    // ---------------------------------------------------------------- AI 新事项

    public void showNewFacts(String name, int color, List<UserFact> facts) {
        Context c = mContext;
        UserFact first = facts.get(0);
        String content = c.getString(R.string.live_fact_line, first.type, FactFormat.oneLine(first));
        PendingIntent open = openApp(4, -1);
        Notification.Builder b = alertBuilder(name, content, open)
                .setOngoing(false)
                .setAutoCancel(true)
                .setTimeoutAfter(NEW_FACTS_TIMEOUT_MS)
                .addAction(action(c.getString(R.string.live_action_view), open));
        Bundle e = baseExtras(LiveExtras.TYPE_CARD, EVENT_AI, true, name, content);
        e.putString(LiveExtras.TASK_STATE_DESC, facts.size() == 1 ? c.getString(R.string.live_new_facts)
                : c.getString(R.string.live_new_facts_many, facts.size()));
        e.putInt(LiveExtras.TASK_STATE_DESC_COLOR, color);
        e.putBundle(LiveExtras.CAPSULE, textCapsule(R.drawable.ic_live_fact, color,
                c.getString(R.string.live_new_facts), first.content, true));
        mNm.notify(ID_NEW_FACTS, b.addExtras(e).build());
    }

    // ---------------------------------------------------------------- AI 连接异常

    /**
     * 连续失败（重试间隔达到 {@link #AI_ERROR_MIN_RETRY_S} 秒）、无网络且有待识别内容、请求被拒绝时显示；
     * 队列清空才收起。WORKING 不处理，否则每次重试前都会收起再弹出。
     */
    public void onAiStatus(AiStatus s) {
        Context c = mContext;
        String content;
        String state;
        int color;
        switch (s.state) {
            case RETRYING: {
                int delay = parseInt(s.detail);
                if (!mAiErrorShown && delay < AI_ERROR_MIN_RETRY_S) {
                    return;
                }
                content = c.getString(R.string.ai_retrying, s.detail, s.pending);
                state = c.getString(R.string.live_ai_state_retrying);
                color = COLOR_WARN;
                break;
            }
            case OFFLINE:
                if (s.pending == 0) {
                    cancelAiError();
                    return;
                }
                content = c.getString(R.string.ai_offline, s.pending);
                state = c.getString(R.string.live_ai_state_offline);
                color = COLOR_PAUSED;
                break;
            case ERROR:
                content = c.getString(R.string.ai_error, s.detail);
                state = c.getString(R.string.live_ai_state_error);
                color = COLOR_ERROR;
                break;
            case IDLE:
                cancelAiError();
                return;
            default:
                return;
        }
        String title = c.getString(R.string.live_ai_title);
        PendingIntent retry = PendingIntent.getBroadcast(c, 4000,
                new Intent(c, LiveActionReceiver.class).setAction(LiveActionReceiver.ACTION_AI_RETRY),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = alertBuilder(title, content, openApp(5, -1))
                .addAction(action(c.getString(R.string.live_action_retry), retry));
        // 只在第一次出现时悬浮弹出，之后的倒计时更新静默刷新。
        Bundle e = baseExtras(LiveExtras.TYPE_DIALOG_MEDIUM_BUTTON, EVENT_FAILED, !mAiErrorShown, title, content);
        e.putInt(LiveExtras.BUTTON_COLOR, 1);
        e.putString(LiveExtras.TASK_STATE_DESC, state);
        e.putInt(LiveExtras.TASK_STATE_DESC_COLOR, color);
        e.putBundle(LiveExtras.CAPSULE, textCapsule(R.drawable.ic_live_ai_error, color, title, state, false));
        mNm.notify(ID_AI_ERROR, b.addExtras(e).build());
        mAiErrorShown = true;
    }

    private void cancelAiError() {
        if (mAiErrorShown) {
            mAiErrorShown = false;
            mNm.cancel(ID_AI_ERROR);
        }
    }

    /** 服务退出时收起提醒类卡片（录音卡片随前台服务一起移除）。 */
    public void cancelAll() {
        mMain.removeCallbacks(mPostRecording);
        mNm.cancel(ID_STRANGER);
        mNm.cancel(ID_NEW_FACTS);
        mNm.cancel(ID_AI_ERROR);
        mNm.cancel(ID_CHAT_ALIAS);
        cancelScreenshotProgress();
        for (int id : mReminderIds) {
            mNm.cancel(id);
        }
        mReminderIds.clear();
        mStrangerUserId = -1;
        mAiErrorShown = false;
    }

    // ---------------------------------------------------------------- 公共部分

    private Notification.Builder alertBuilder(String title, String content, PendingIntent contentIntent) {
        return new Notification.Builder(mContext, WhisperApp.CHANNEL_LIVE_ALERT)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setWhen(System.currentTimeMillis());
    }

    private Bundle baseExtras(int type, String event, boolean pinned, String title, String content) {
        Bundle e = new Bundle();
        e.putInt(LiveExtras.OPERATION, LiveExtras.OPERATION_UPDATE);
        e.putInt(LiveExtras.TYPE, type);
        e.putString(LiveExtras.EVENT, event);
        e.putBoolean(LiveExtras.PINNED, pinned);
        e.putBoolean(LiveExtras.PROGRESS_HIDE, true);
        e.putString(LiveExtras.TITLE_OVERLAY, LiveTitle.fit(mContext, clip(title)));
        e.putString(LiveExtras.CONTENT_OVERLAY, content);
        e.putParcelable(LiveExtras.ICON_OVERLAY, Icon.createWithResource(mContext, R.drawable.ic_live_app));
        return e;
    }

    private Bundle textCapsule(int iconRes, int bgColor, String title, String content, boolean remind) {
        Bundle capsule = new Bundle();
        capsule.putInt(LiveExtras.CAPSULE_STATUS, 1);
        capsule.putInt(LiveExtras.CAPSULE_TYPE, LiveExtras.CAPSULE_TYPE_TEXT);
        capsule.putParcelable(LiveExtras.CAPSULE_ICON, Icon.createWithResource(mContext, iconRes));
        capsule.putInt(LiveExtras.CAPSULE_BG_COLOR, bgColor);
        capsule.putInt(LiveExtras.CAPSULE_CONTENT_COLOR, COLOR_WHITE);
        capsule.putString(LiveExtras.CAPSULE_TITLE, clip(title));
        capsule.putString(LiveExtras.CAPSULE_CONTENT, clip(content));
        capsule.putBoolean(LiveExtras.CAPSULE_REMIND, remind);
        return capsule;
    }

    private Notification.Action action(String label, PendingIntent intent) {
        return new Notification.Action.Builder(mBlankIcon, label, intent).build();
    }

    /** 打开主界面；focusUserId 有效时进入后弹出该用户的全部卡片。 */
    private PendingIntent openApp(int requestCode, long focusUserId) {
        Intent intent = new Intent(mContext, MainActivity.class)
                .putExtra(LiveExtras.INTENT_DISMISS_PANEL, true);
        if (focusUserId >= 0) {
            intent.putExtra(MainActivity.EXTRA_FOCUS_USER, focusUserId);
        }
        return PendingIntent.getActivity(mContext, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static String clip(String s) {
        return s == null || s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT - 1) + "…";
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
