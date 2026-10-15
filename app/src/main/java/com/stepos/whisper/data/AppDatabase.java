package com.stepos.whisper.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Database(entities = {User.class, Utterance.class, UserFact.class, PendingExtraction.class, ChatAlias.class,
        ScreenshotJob.class, NotificationJob.class},
        version = 10)
public abstract class AppDatabase extends RoomDatabase {
    /** 界面发起的数据库写操作统一在这里执行。 */
    public static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> new Thread(r, "db-io"));

    private static volatile AppDatabase sInstance;

    public abstract UserDao userDao();

    public abstract UtteranceDao utteranceDao();

    public abstract UserFactDao userFactDao();

    public abstract PendingExtractionDao pendingExtractionDao();

    public abstract ChatAliasDao chatAliasDao();

    public abstract ScreenshotJobDao screenshotJobDao();

    public abstract NotificationJobDao notificationJobDao();

    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE users ADD COLUMN isOwner INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE users ADD COLUMN color INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE user_facts ADD COLUMN location TEXT");
            db.execSQL("ALTER TABLE user_facts ADD COLUMN people TEXT");
        }
    };

    /** 声纹模型换成 ERes2NetV2，旧向量不再可比，清空用户重新录入。 */
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("DELETE FROM pending_extractions");
            db.execSQL("DELETE FROM user_facts");
            db.execSQL("UPDATE utterances SET userId = NULL");
            db.execSQL("DELETE FROM users");
        }
    };

    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE users ADD COLUMN isStranger INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE utterances ADD COLUMN aiState INTEGER NOT NULL DEFAULT 0");
        }
    };

    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE users ADD COLUMN extraEmbeddings BLOB");
        }
    };

    /** 待办和事实分开存，原先的“偏好”“信息”都归为事实。 */
    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE user_facts ADD COLUMN kind INTEGER NOT NULL DEFAULT 0");
            db.execSQL("UPDATE user_facts SET kind = 1 WHERE type IN ('偏好', '信息')");
        }
    };

    /** 聊天截图：昵称与用户的对应关系，以及待处理的截图队列。 */
    static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `chat_aliases` (`name` TEXT NOT NULL, `userId` INTEGER, "
                    + "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`name`), "
                    + "FOREIGN KEY(`userId`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_aliases_userId` ON `chat_aliases` (`userId`)");
            db.execSQL("CREATE TABLE IF NOT EXISTS `screenshot_jobs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`uri` TEXT NOT NULL, `sourceApp` TEXT, `takenAt` INTEGER NOT NULL, `layoutText` TEXT, "
                    + "`parsed` TEXT, `attempts` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)");
        }
    };

    /** 声纹换成讯飞云端（featureId），本地向量作废需重新注册；用户本身和事项保留。 */
    static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE users ADD COLUMN featureIds TEXT");
            db.execSQL("UPDATE users SET embedding = NULL, extraEmbeddings = NULL, sampleCount = 0");
        }
    };

    /** 微信通知消息的处理队列。 */
    static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `notification_jobs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`sender` TEXT NOT NULL, `text` TEXT NOT NULL, `postTime` INTEGER NOT NULL, "
                    + "`attempts` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)");
        }
    };

    static final Migration MIGRATION_9_10 = new Migration(9, 10) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE notification_jobs ADD COLUMN packageName TEXT");
        }
    };

    public static AppDatabase get(Context context) {
        if (sInstance == null) {
            synchronized (AppDatabase.class) {
                if (sInstance == null) {
                    sInstance = Room.databaseBuilder(context.getApplicationContext(),
                                    AppDatabase.class, "whisper.db")
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                            .build();
                }
            }
        }
        return sInstance;
    }
}
