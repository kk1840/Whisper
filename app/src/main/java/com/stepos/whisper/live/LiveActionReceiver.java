package com.stepos.whisper.live;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.stepos.whisper.service.RecordingService;

/** 灵动岛卡片上的按钮：新陌生人“以后再说”、聊天截图新昵称“以后再说”、AI 异常“立即重试”。 */
public class LiveActionReceiver extends BroadcastReceiver {
    static final String ACTION_STRANGER_LATER = "com.stepos.whisper.live.STRANGER_LATER";
    static final String ACTION_ALIAS_LATER = "com.stepos.whisper.live.ALIAS_LATER";
    static final String ACTION_AI_RETRY = "com.stepos.whisper.live.AI_RETRY";
    static final String EXTRA_USER_ID = "user_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        RecordingService service = RecordingService.getInstance();
        if (ACTION_STRANGER_LATER.equals(intent.getAction())) {
            if (service != null) {
                service.getLive().cancelStranger(intent.getLongExtra(EXTRA_USER_ID, -1));
            } else {
                context.getSystemService(NotificationManager.class).cancel(LiveNotifier.ID_STRANGER);
            }
        } else if (ACTION_ALIAS_LATER.equals(intent.getAction())) {
            context.getSystemService(NotificationManager.class).cancel(LiveNotifier.ID_CHAT_ALIAS);
        } else if (ACTION_AI_RETRY.equals(intent.getAction()) && service != null) {
            service.extractAllNotExtracted();
        }
    }
}
