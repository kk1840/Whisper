package com.stepos.whisper.data;

import com.stepos.whisper.speaker.EmbeddingUtils;
import com.stepos.whisper.speaker.SpeakerRecognizer;

import java.util.ArrayList;
import java.util.List;

/**
 * 把用户 from（通常是“陌生人N”）并入已有用户 to：发言、事项、待识别队列、聊天昵称都改归 to，
 * from 的声纹作为 to 的额外声纹保留（同一个人压低声音时也能认出来），然后删掉 from。
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
            float[] dstMain = EmbeddingUtils.fromBytes(dst.embedding);
            float[] srcMain = EmbeddingUtils.fromBytes(src.embedding);
            if (dstMain == null && srcMain != null) {
                // to 是从聊天截图建的、还没有声纹的用户：直接继承 from 的声纹。
                dst.embedding = src.embedding;
                dst.sampleCount = src.sampleCount;
                dst.extraEmbeddings = src.extraEmbeddings;
            } else {
                int dim = dstMain == null ? 0 : dstMain.length;
                List<float[]> extras = new ArrayList<>(EmbeddingUtils.splitBytes(dst.extraEmbeddings, dim));
                if (srcMain != null && srcMain.length == dim) {
                    extras.add(srcMain);
                }
                extras.addAll(EmbeddingUtils.splitBytes(src.extraEmbeddings, dim));
                while (extras.size() > SpeakerRecognizer.MAX_EXTRA) {
                    extras.remove(0);
                }
                dst.extraEmbeddings = extras.isEmpty() ? null : EmbeddingUtils.joinBytes(extras);
            }
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
