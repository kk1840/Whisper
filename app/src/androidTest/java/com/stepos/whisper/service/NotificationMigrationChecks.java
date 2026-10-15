package com.stepos.whisper.service;

import android.app.Instrumentation;
import android.database.Cursor;
import androidx.room.Room;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import com.stepos.whisper.data.AppDatabase;
import com.stepos.whisper.data.NotificationJob;
import com.stepos.whisper.notification.NotificationSource;
import java.lang.reflect.Field;

/** Checks migration SQL and nullable source roundtrips using an isolated in-memory v9 table fixture. */
public final class NotificationMigrationChecks {
    public static void runChecks(Instrumentation runner) throws Exception {
        AppDatabase db = Room.inMemoryDatabaseBuilder(runner.getTargetContext(), AppDatabase.class)
                .allowMainThreadQueries().build();
        try {
            SupportSQLiteDatabase sql = db.getOpenHelper().getWritableDatabase();
            sql.execSQL("DROP TABLE notification_jobs");
            sql.execSQL("CREATE TABLE notification_jobs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "sender TEXT NOT NULL, text TEXT NOT NULL, postTime INTEGER NOT NULL, "
                    + "attempts INTEGER NOT NULL, createdAt INTEGER NOT NULL)");
            sql.execSQL("INSERT INTO notification_jobs VALUES (11, 'Synthetic sender', 'Synthetic body', 123, 2, 100)");
            Field field = AppDatabase.class.getDeclaredField("MIGRATION_9_10");
            field.setAccessible(true);
            ((Migration) field.get(null)).migrate(sql);
            NotificationJob old = db.notificationJobDao().get(11);
            check(old != null && old.packageName == null && old.attempts == 2 && old.postTime == 123
                    && "Synthetic body".equals(old.text), "migration preserves old job with unknown source");
            NotificationJob fresh = new NotificationJob();
            fresh.packageName = NotificationSource.FEISHU; fresh.sender = "Synthetic Lark";
            fresh.text = "Synthetic message";
            fresh.id = db.notificationJobDao().insert(fresh);
            check(NotificationSource.FEISHU.equals(db.notificationJobDao().get(fresh.id).packageName),
                    "source survives database roundtrip");
            try (Cursor cursor = sql.query("PRAGMA table_info(notification_jobs)")) {
                boolean nullable = false;
                while (cursor.moveToNext()) {
                    if ("packageName".equals(cursor.getString(1))) nullable = cursor.getInt(3) == 0;
                }
                check(nullable, "legacy package column nullable");
            }
        } finally {
            db.close();
        }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
