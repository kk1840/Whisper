package com.stepos.whisper.screenshot;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import com.stepos.whisper.Settings;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 监听系统截图目录 /sdcard/Pictures/Screenshots：通过 MediaStore 的变更通知发现新截图
 * （系统截图通过 MediaStore 写入，直接写文件的也会被媒体库扫描到），按 _id 递增只处理没见过的。
 * 处理进度存在 {@link Settings} 里，服务重启后不重复处理，但补处理停机期间最近一小时内的截图。
 * 系统要花几秒把截图 PNG 写完，这期间媒体库里是一条 is_pending 的记录，看到它就先报告一次，
 * 写完后再交给处理队列。查询在自己的线程上做，不排在截图处理（OCR、大模型请求）后面。
 */
public class ScreenshotWatcher {
    private static final String TAG = "ScreenshotWatcher";

    /** 相对 /sdcard 的目录，MediaStore 的 relative_path 以斜杠结尾。 */
    public static final String RELATIVE_PATH = "Pictures/Screenshots/";
    private static final long CATCH_UP_MS = 60 * 60_000;
    /** 只把这么新的 is_pending 记录当作正在保存的截图，保存失败留下的旧记录不算。 */
    private static final long PENDING_MAX_AGE_MS = 60_000;

    public interface Listener {
        /** 系统开始保存一张新截图，文件还没写完（watcher 线程）。displayName 是文件名。 */
        void onScreenshotSaving(String displayName);

        /** 发现新截图（watcher 线程）。displayName 是文件名。 */
        void onScreenshot(Uri uri, String displayName, long takenAtMs);
    }

    private final Context mContext;
    private final ExecutorService mExecutor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "screenshot-watch"));
    private final Listener mListener;
    private final ContentObserver mObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (mStarted) {
                mExecutor.execute(ScreenshotWatcher.this::poll);
            }
        }
    };

    private boolean mStarted;
    // watcher 线程
    private long mLastId;
    private long mLastPendingId;

    public ScreenshotWatcher(Context context, Listener listener) {
        mContext = context.getApplicationContext();
        mListener = listener;
    }

    /** 主线程调用。 */
    public void start() {
        if (mStarted || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        mStarted = true;
        mExecutor.execute(() -> {
            mLastId = Settings.getLastScreenshotId(mContext);
            if (mLastId < 0) {
                // 第一次启用：从现在开始，不处理历史截图。
                mLastId = queryMaxId();
                Settings.setLastScreenshotId(mContext, mLastId);
            }
            mLastPendingId = mLastId;
            poll();
        });
        mContext.getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true, mObserver);
    }

    /** 主线程调用。 */
    public void stop() {
        if (!mStarted) {
            return;
        }
        mStarted = false;
        mContext.getContentResolver().unregisterContentObserver(mObserver);
    }

    /** 服务退出时调用（主线程）。 */
    public void shutdown() {
        stop();
        mExecutor.shutdown();
    }

    /** 媒体库里当前最大的图片 id。MediaProvider 不接受排序串里的 LIMIT，要用 Bundle 参数。 */
    private long queryMaxId() {
        Bundle args = new Bundle();
        args.putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, new String[]{MediaStore.Images.Media._ID});
        args.putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);
        args.putInt(ContentResolver.QUERY_ARG_LIMIT, 1);
        try (Cursor c = mContext.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID}, args, null)) {
            return c != null && c.moveToFirst() ? c.getLong(0) : 0;
        } catch (RuntimeException e) {
            Log.w(TAG, "query max id failed", e);
            return 0;
        }
    }

    private void poll() {
        if (!mStarted) {
            return;
        }
        pollPending();
        String[] projection = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED};
        String selection = MediaStore.Images.Media.RELATIVE_PATH + " = ? AND " + MediaStore.Images.Media._ID
                + " > ? AND " + MediaStore.Images.Media.IS_PENDING + " = 0 AND "
                + MediaStore.Images.Media.DATE_ADDED + " > ?";
        String[] args = {RELATIVE_PATH, String.valueOf(mLastId),
                String.valueOf((System.currentTimeMillis() - CATCH_UP_MS) / 1000)};
        try (Cursor c = mContext.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args, MediaStore.Images.Media._ID)) {
            if (c == null) {
                return;
            }
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String name = c.getString(1);
                long takenAt = c.getLong(2) * 1000;
                mLastId = id;
                Settings.setLastScreenshotId(mContext, id);
                Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                Log.i(TAG, "new screenshot " + name);
                mListener.onScreenshot(uri, name == null ? "" : name, takenAt);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "query screenshots failed", e);
        }
    }

    /** 正在保存的截图只报告，不推进 {@link #mLastId}，写完后由上面的查询交给处理队列。 */
    private void pollPending() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        Bundle args = new Bundle();
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, MediaStore.Images.Media.RELATIVE_PATH + " = ? AND "
                + MediaStore.Images.Media._ID + " > ? AND " + MediaStore.Images.Media.IS_PENDING + " = 1 AND "
                + MediaStore.Images.Media.DATE_ADDED + " > ?");
        args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[]{RELATIVE_PATH,
                String.valueOf(Math.max(mLastId, mLastPendingId)),
                String.valueOf((System.currentTimeMillis() - PENDING_MAX_AGE_MS) / 1000)});
        args.putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, new String[]{MediaStore.Images.Media._ID});
        args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
        try (Cursor c = mContext.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME}, args, null)) {
            while (c != null && c.moveToNext()) {
                mLastPendingId = c.getLong(0);
                String name = c.getString(1);
                Log.i(TAG, "saving screenshot " + name);
                mListener.onScreenshotSaving(name == null ? "" : name);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "query pending screenshots failed", e);
        }
    }
}
