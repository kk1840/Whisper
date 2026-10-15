package com.stepos.whisper.notificationdemo;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.EditText;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final String CHANNEL_ID = "demo_messages";
    private static final int REQUEST_NOTIFICATIONS = 1;
    private EditText mSender;
    private EditText mBody;
    private TextView mStatus;
    private NotificationManager mManager;
    private String mPendingSender;
    private String mPendingBody;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mSender = findViewById(R.id.sender);
        mBody = findViewById(R.id.body);
        mStatus = findViewById(R.id.status);
        mManager = getSystemService(NotificationManager.class);
        mManager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH));
        if (savedInstanceState != null) {
            mPendingSender = savedInstanceState.getString("pending_sender");
            mPendingBody = savedInstanceState.getString("pending_body");
        }
        findViewById(R.id.send).setOnClickListener(v -> sendFromInput());
        findViewById(R.id.settings).setOnClickListener(v -> openNotificationSettings());
    }

    @Override
    protected void onResume() {
        super.onResume();
        mStatus.setText(notificationsEnabled() ? R.string.ready : R.string.notifications_disabled);
    }

    private boolean notificationsEnabled() {
        NotificationChannel channel = mManager.getNotificationChannel(CHANNEL_ID);
        return mManager.areNotificationsEnabled() && channel != null
                && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private void sendFromInput() {
        String sender = mSender.getText().toString().trim();
        String body = mBody.getText().toString().trim();
        if (sender.isEmpty() || body.isEmpty()) {
            mStatus.setText(R.string.input_required);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            mPendingSender = sender;
            mPendingBody = body;
            mStatus.setText(R.string.permission_required);
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
            return;
        }
        postNotification(sender, body);
    }

    private void postNotification(String sender, String body) {
        if (!notificationsEnabled()) {
            mStatus.setText(R.string.notifications_disabled);
            return;
        }
        long now = System.currentTimeMillis();
        Person self = new Person.Builder().setName(getString(R.string.self_name)).build();
        Person from = new Person.Builder().setName(sender).build();
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(sender)
                .setContentText(body)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setStyle(new Notification.MessagingStyle(self)
                        .addMessage(new Notification.MessagingStyle.Message(body, now, from)))
                .build();
        try {
            // 固定 ID 更新单条通知，每次只包含这次输入的消息，不累积历史。
            mManager.notify(1, notification);
            mStatus.setText(R.string.sent);
        } catch (SecurityException e) {
            mStatus.setText(R.string.permission_denied);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("pending_sender", mPendingSender);
        outState.putString("pending_body", mPendingBody);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                          int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NOTIFICATIONS) {
            return;
        }
        String sender = mPendingSender;
        String body = mPendingBody;
        mPendingSender = null;
        mPendingBody = null;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (sender != null && body != null) {
                postNotification(sender, body);
            }
        } else {
            mStatus.setText(R.string.permission_denied);
        }
    }

    private void openNotificationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        } catch (ActivityNotFoundException | SecurityException e) {
            mStatus.setText(R.string.settings_unavailable);
        }
    }
}
