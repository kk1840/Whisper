package com.stepos.whisper.notification;

import android.app.Notification;
import android.app.Person;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.stepos.whisper.BuildConfig;
import com.stepos.whisper.Settings;
import com.stepos.whisper.data.AppDatabase;
import com.stepos.whisper.service.RecordingService;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 读取通知消息，收到就立刻交给 {@link NotificationWorker} 提取待办（不等、不攒批）。
 * 系统重发的相同通知（改排序、重新挂起、MessagingStyle 历史刷新等）在 {@link #DEDUP_MS} 内
 * 不重复处理。发送者解析：优先 MessagingStyle 里每条消息自带的发送者（群聊也能取准）；
 * 没有时用 title 当昵称，文本形如“昵称：内容”且前缀不长时按群聊拆出发言人
 * （启发式，拆错最多多问一次机主，不丢数据）。
 */
public class WeChatNotificationListener extends NotificationListenerService {
    private static final String TAG = "WeChatListener";
    /** 同一发送者的相同文本在这么长时间内重复出现（通知被系统重发）不再处理。 */
    private static final long DEDUP_MS = 10 * 60_000;
    /** 群聊回退启发式：文本形如“昵称：内容”，前缀超过这个字数不当群聊拆。 */
    private static final int GROUP_PREFIX_MAX = 12;

    private static final Set<String> BLOCKED_SENDERS = new HashSet<>(Arrays.asList(
            "微信", "微信团队", "WeChat", "微信支付", "微信键盘"));
    /** 微信隐私设置隐藏内容时的占位文本，提不出信息。 */
    private static final Set<String> GENERIC_TEXTS = new HashSet<>(Arrays.asList(
            "你收到一条新消息", "你有一条新消息", "你收到了一条新消息"));
    /** 测试模式（监听所有应用）下也不处理的通知来源：系统 UI 等，防噪声（本应用自己另有判断）。 */
    private static final Set<String> BLOCKED_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.systemui", "android"));

    /** 最近处理过的 [包名, 发送者, 文本] → elapsedRealtime，挡掉同来源重发。只在主线程访问。 */
    private final Map<List<String>, Long> mRecent = new LinkedHashMap<List<String>, Long>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<List<String>, Long> eldest) {
            return size() > 64;
        }
    };

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!acceptPackage(sbn.getPackageName())) {
            return;
        }
        if (!Settings.isNotificationWatchEnabled(this)) {
            return;
        }
        Notification n = sbn.getNotification();
        if (n == null || sbn.isOngoing()) {
            return;
        }
        String[] parsed = parseMessage(n);
        if (parsed == null) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "ignore notification from " + sbn.getPackageName()
                        + " (no usable sender/text)");
            }
            return;
        }
        String sender = parsed[0];
        String text = parsed[1];

        long now = SystemClock.elapsedRealtime();
        List<String> dedupKey = Arrays.asList(sbn.getPackageName(), sender, text);
        Long seen = mRecent.get(dedupKey);
        if (seen != null && now - seen < DEDUP_MS) {
            // 同文本 10 分钟内重发：多半是系统重发同一条通知，不重复处理。
            Log.i(TAG, "duplicate post ignored from " + sbn.getPackageName());
            return;
        }
        mRecent.put(dedupKey, now);
        Log.i(TAG, "got message from " + sbn.getPackageName());

        // 收到就立刻处理：每条新消息（新文本）单独成一条任务，马上交给 AI。
        RecordingService service = RecordingService.getInstance();
        NotificationWorker worker = service == null ? null : service.getNotifications();
        if (worker != null) {
            worker.enqueue(sender, text, sbn.getPostTime(), sbn.getPackageName());
        } else {
            // 录音服务被停：落库即发布通知行；服务起来后恢复同一任务继续处理。
            AppDatabase db = AppDatabase.get(this);
            AppDatabase.IO.execute(() -> NotificationWorker.insertJob(db, sender, text,
                    sbn.getPostTime(), sbn.getPackageName()));
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.i(TAG, "connected");
        RecordingService service = RecordingService.getInstance();
        if (service != null) {
            service.kickNotifications();
        }
    }

    /**
     * 默认只处理微信、飞书与独立通知演示包；调试 all 模式需显式开启。
     * 本应用自己的通知永远不处理，避免提醒再次喂给自己；系统 UI 同理。
     */
    private boolean acceptPackage(String pkg) {
        if (pkg == null || getPackageName().equals(pkg) || BLOCKED_PACKAGES.contains(pkg)) {
            return false;
        }
        if (NotificationSource.isSupported(pkg)) {
            return true;
        }
        return BuildConfig.DEBUG && Settings.isNotificationDebugAllApps(this);
    }

    // ---------------------------------------------------------------- 解析

    /** 返回 [发送者昵称, 消息文本]；不是可处理的消息通知时返回 null。 */
    private static String[] parseMessage(Notification n) {
        Bundle extras = n.extras;
        if (extras == null) {
            return null;
        }
        String title = text(extras.getCharSequence(Notification.EXTRA_TITLE));
        // MessagingStyle 里每条消息自带发送者，群聊也能取准；更新通知时历史会累积，取最后一条。
        List<Notification.MessagingStyle.Message> messages = messagingMessages(extras);
        if (messages != null && !messages.isEmpty()) {
            Notification.MessagingStyle.Message last = messages.get(messages.size() - 1);
            String body = text(last.getText());
            String sender = null;
            Person person = last.getSenderPerson();
            if (person != null) {
                sender = text(person.getName());
            }
            if (sender == null || sender.isEmpty()) {
                sender = title;
            }
            return valid(sender, body);
        }
        String body = text(extras.getCharSequence(Notification.EXTRA_TEXT));
        if (title == null || body == null) {
            return null;
        }
        String sender = title;
        // 群聊时 title 是群名、文本是“发言人：内容”；前缀太长大概率是单聊正文里带的冒号，不拆。
        int colon = colonIndex(body);
        if (colon > 0 && colon <= GROUP_PREFIX_MAX && colon < body.length() - 1) {
            sender = body.substring(0, colon);
            body = body.substring(colon + 1);
        }
        return valid(sender, body);
    }

    private static List<Notification.MessagingStyle.Message> messagingMessages(Bundle extras) {
        Parcelable[] array = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (array == null) {
            return null;
        }
        // EXTRA_MESSAGES 实际是 Bundle[]，而不是 Message 的 Parcelable 实例。
        return Notification.MessagingStyle.Message.getMessagesFromBundleArray(array);
    }

    /** 第一个中英文冒号的位置；没有返回 -1。 */
    private static int colonIndex(String s) {
        int full = s.indexOf('：');
        int half = s.indexOf(": ");
        if (full < 0) {
            return half;
        }
        if (half < 0) {
            return full;
        }
        return Math.min(full, half);
    }

    private static String[] valid(String sender, String body) {
        if (sender == null || sender.isEmpty() || body == null || body.isEmpty()) {
            return null;
        }
        sender = sender.trim();
        body = body.trim();
        if (sender.isEmpty() || body.isEmpty()) {
            return null;
        }
        if (BLOCKED_SENDERS.contains(sender) || GENERIC_TEXTS.contains(body)) {
            return null;
        }
        return new String[]{sender, body};
    }

    private static String text(CharSequence cs) {
        if (cs == null) {
            return null;
        }
        String s = cs.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
