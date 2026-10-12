package com.stepos.whisper.data;

import com.stepos.whisper.voiceprint.VoiceprintLibrary;

/**
 * 把用户 from（通常是“陌生人N”）并入已有用户 to：发言、事项、待识别队列、聊天昵称都改归 to，
 * from 的云端声纹特征 id 划转给 to（云端不动，只改本地归属），然后删掉 from。
 */
public final class UserMerger {
    private UserMerger() {
    }

    /** 返回 false 表示有一方已不存在，未做任何修改。不能在主线程调用。 */
    public static boolean merge(AppDatabase db, long from, long to) {
        if (from == to) {
            return false;
        }
        boolean[] ok = {false};
        db.runInTransaction(() -> {
            UserDao users = db.userDao();
            User src = users.get(from);
            User dst = users.get(to);
            if (src == null || dst == null) {
                return;
            }
            dst.featureIds = VoiceprintLibrary.mergeFeatureIds(dst.featureIds, src.featureIds);
            dst.lastSeenAt = Math.max(dst.lastSeenAt, src.lastSeenAt);
            users.update(dst);

            db.utteranceDao().reassignUser(from, to);
            db.userFactDao().reassignUser(from, to);
            db.pendingExtractionDao().reassignUser(from, to);
            db.chatAliasDao().reassignUser(from, to);
            users.delete(from);
            ok[0] = true;
        });
        return ok[0];
    }
}
