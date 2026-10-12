package com.stepos.whisper.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import com.stepos.whisper.BuildConfig;
import com.stepos.whisper.R;
import com.stepos.whisper.Settings;
import com.stepos.whisper.data.AppDatabase;
import com.stepos.whisper.data.FactCard;
import com.stepos.whisper.data.User;
import com.stepos.whisper.data.UserFact;
import com.stepos.whisper.llm.MergeSuggestion;
import com.stepos.whisper.notification.NotificationWorker;

/**
 * 调试触发（release 构建直接忽略），演示/联调不用真拿另一台手机发微信：
 * <ul>
 *   <li>ACTION_DEBUG_NOTIF + extras sender/text：伪造一条微信通知进处理队列，走完整链路；</li>
 *   <li>ACTION_DEBUG_NOTIF_ALL：切换“监听所有应用的通知”测试模式，开了之后用 adb 发通知或收短信就能测；</li>
 *   <li>ACTION_DEBUG_MERGE：从库里挑一条已有待办，直接构造合并建议弹融合动画。</li>
 * </ul>
 * adb shell am broadcast -a com.stepos.whisper.DEBUG_NOTIF --es sender 张三 --es text 下班再带一箱牛奶
 * adb shell cmd notification post -t 张三 tag1 下班记得再带一箱牛奶   （需先开 DEBUG_NOTIF_ALL）
 */
public class DebugReceiver extends BroadcastReceiver {
    private static final String TAG = "DebugReceiver";
    public static final String ACTION_DEBUG_NOTIF = "com.stepos.whisper.DEBUG_NOTIF";
    public static final String ACTION_DEBUG_NOTIF_ALL = "com.stepos.whisper.DEBUG_NOTIF_ALL";
    public static final String ACTION_DEBUG_MERGE = "com.stepos.whisper.DEBUG_MERGE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        String action = intent == null ? null : intent.getAction();
        if (ACTION_DEBUG_NOTIF.equals(action)) {
            String sender = intent.getStringExtra("sender");
            String text = intent.getStringExtra("text");
            if (sender == null || sender.isEmpty() || text == null || text.isEmpty()) {
                Log.w(TAG, "DEBUG_NOTIF needs extras: sender, text");
                return;
            }
            RecordingService service = RecordingService.getInstance();
            if (service != null) {
                service.getNotifications().enqueue(sender, text, System.currentTimeMillis());
            } else {
                AppDatabase db = AppDatabase.get(context);
                AppDatabase.IO.execute(() -> NotificationWorker.insertJob(db, sender, text,
                        System.currentTimeMillis()));
            }
        } else if (ACTION_DEBUG_NOTIF_ALL.equals(action)) {
            boolean on = !Settings.isNotificationDebugAllApps(context);
            Settings.setNotificationDebugAllApps(context, on);
            Toast.makeText(context, on ? "通知监听：所有应用（测试模式）"
                    : "通知监听：仅微信", Toast.LENGTH_SHORT).show();
        } else if (ACTION_DEBUG_MERGE.equals(action)) {
            RecordingService service = RecordingService.getInstance();
            if (service == null) {
                Toast.makeText(context, R.string.service_not_running, Toast.LENGTH_SHORT).show();
                return;
            }
            AppDatabase db = AppDatabase.get(context);
            AppDatabase.IO.execute(() -> {
                MergeSuggestion suggestion = buildFakeSuggestion(db);
                if (suggestion == null) {
                    service.toastFromBackground(context.getString(R.string.debug_merge_none));
                    return;
                }
                service.showMergeSuggestion(suggestion);
            });
        }
    }

    /**
     * 挑第一个还有待办的用户，用 TA 最早的一条待办当 target，插入一条“下班再带一箱牛奶”当候选，
     * 合并文案把两边拼起来。谁都没有待办时给机主现造一条“下班买点韭菜”。
     */
    private static MergeSuggestion buildFakeSuggestion(AppDatabase db) {
        User user = null;
        UserFact target = null;
        for (User u : db.userDao().getAll()) {
            for (UserFact f : db.userFactDao().getPending(u.id)) {
                if (f.kind == UserFact.KIND_TODO) {
                    user = u;
                    target = f;
                    break;
                }
            }
            if (user != null) {
                break;
            }
        }
        if (user == null) {
            user = db.userDao().getOwner();
            if (user == null) {
                return null;
            }
            long now = System.currentTimeMillis();
            target = new UserFact();
            target.userId = user.id;
            target.kind = UserFact.KIND_TODO;
            target.content = "下班买点韭菜";
            target.type = UserFact.TYPE_TODO;
            target.createdAt = now;
            target.updatedAt = now;
            target.id = db.userFactDao().insert(target);
        }
        long now = System.currentTimeMillis();
        UserFact incoming = new UserFact();
        incoming.userId = user.id;
        incoming.kind = UserFact.KIND_TODO;
        incoming.content = "下班再带一箱牛奶";
        incoming.type = UserFact.TYPE_TODO;
        incoming.createdAt = now;
        incoming.updatedAt = now;
        incoming.id = db.userFactDao().insert(incoming);
        String merged = target.content.endsWith("。") || target.content.endsWith("，")
                ? target.content + "再带一箱牛奶" : target.content + "和一箱牛奶";
        return new MergeSuggestion(user.id, card(user, target), card(user, incoming),
                merged, target.dueTime, target.location, target.people);
    }

    private static FactCard card(User user, UserFact fact) {
        FactCard card = new FactCard();
        card.fact = fact;
        card.userName = user.name;
        card.userColor = user.color;
        return card;
    }
}
