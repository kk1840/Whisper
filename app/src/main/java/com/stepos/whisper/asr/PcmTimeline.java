package com.stepos.whisper.asr;

import java.util.ArrayDeque;

/**
 * 已发送音频的环形缓冲，带“全局字节偏移 ↔ 墙钟”映射。
 * 全局偏移指本次录音会话从 0 开始累计的 PCM16 字节数。WebSocket 每次连上都是新的服务端
 * 时间轴（秒），{@link #startSession(long)} 记录该时间轴 0 点对应的全局偏移，之后服务端给的
 * 秒级时间戳都能换算回全局偏移，再从缓冲里切出对应音频（声纹用）或换算回墙钟（入库用）。
 * 只在 audio 线程 append、其他线程 cut/查询，方法都加了锁，帧数少（60~90 秒 × 100ms），开销可忽略。
 */
public class PcmTimeline {
    /** 16kHz、16bit、单声道：每秒 32000 字节。 */
    public static final int BYTES_PER_SEC = 32000;

    private final int mCapacityBytes;
    private final ArrayDeque<byte[]> mFrames = new ArrayDeque<>();
    /** 与 mFrames 一一对应的锚点 {全局偏移, 墙钟ms}。 */
    private final ArrayDeque<long[]> mAnchors = new ArrayDeque<>();
    private long mBase = 0;
    private long mTotal = 0;
    private long mSessionBase = -1;

    public PcmTimeline(int capacitySeconds) {
        mCapacityBytes = capacitySeconds * BYTES_PER_SEC;
    }

    public synchronized void clear() {
        mFrames.clear();
        mAnchors.clear();
        mBase = 0;
        mTotal = 0;
        mSessionBase = -1;
    }

    /** 新 WebSocket 会话开始：服务端时间轴的 0 点对应全局偏移 baseGlobal（重连回放时为回放起点）。 */
    public synchronized void startSession(long baseGlobal) {
        mSessionBase = baseGlobal;
    }

    public synchronized long sessionBase() {
        return mSessionBase;
    }

    /** audio 线程调用：追加一段已发送的 PCM。 */
    public synchronized void append(byte[] pcm, long wallMs) {
        if (pcm == null || pcm.length == 0) {
            return;
        }
        mAnchors.addLast(new long[]{mTotal, wallMs});
        mFrames.addLast(pcm);
        mTotal += pcm.length;
        while (mTotal - mBase > mCapacityBytes && mFrames.size() > 1) {
            mBase += mFrames.removeFirst().length;
            mAnchors.removeFirst();
        }
    }

    public synchronized long total() {
        return mTotal;
    }

    /** 服务端秒数（当前会话时间轴）→ 全局字节偏移。 */
    public synchronized long globalByteAtServerSec(double serverSec) {
        long base = mSessionBase < 0 ? mTotal : mSessionBase;
        return base + Math.round(serverSec * BYTES_PER_SEC);
    }

    /** 全局字节偏移 → 墙钟 ms（锚点间线性插值，精度约 ±100ms）。 */
    public synchronized long wallMsAtGlobal(long globalByte) {
        if (mAnchors.isEmpty()) {
            return System.currentTimeMillis();
        }
        if (globalByte <= mBase) {
            return mAnchors.getFirst()[1];
        }
        long[] prev = mAnchors.getFirst();
        for (long[] a : mAnchors) {
            if (a[0] > globalByte) {
                long span = a[0] - prev[0];
                double frac = span == 0 ? 0 : (double) (globalByte - prev[0]) / span;
                return prev[1] + Math.round((a[1] - prev[1]) * frac);
            }
            prev = a;
        }
        long span = mTotal - prev[0];
        double frac = span <= 0 ? 0 : (double) (globalByte - prev[0]) / span;
        return prev[1] + Math.round(80 * Math.min(1, Math.max(0, frac)));
    }

    /**
     * 墙钟 ms → 全局字节偏移（{@link #wallMsAtGlobal} 的反查，锚点间线性插值，
     * 最后一个锚点之后按字节速率外推并封顶 total）。早于缓冲窗口太多返回 -1
     * （对应音频已滚出环形缓冲，切不出来）。
     */
    public synchronized long globalByteAtWallMs(long wallMs) {
        if (mAnchors.isEmpty()) {
            return -1;
        }
        long[] first = mAnchors.getFirst();
        if (wallMs <= first[1]) {
            return wallMs < first[1] - 2000 ? -1 : mBase;
        }
        long[] prev = first;
        for (long[] a : mAnchors) {
            if (a[1] > wallMs) {
                long spanMs = a[1] - prev[1];
                double frac = spanMs == 0 ? 0 : (double) (wallMs - prev[1]) / spanMs;
                return prev[0] + Math.round((a[0] - prev[0]) * frac);
            }
            prev = a;
        }
        return Math.min(mTotal, prev[0] + (wallMs - prev[1]) * BYTES_PER_SEC / 1000);
    }

    /** 切出 [startByte, endByte) 的 PCM，超出缓冲窗口的部分自动截断；完全在窗口外返回 null。 */
    public synchronized byte[] cutGlobal(long startByte, long endByte) {
        long start = Math.max(startByte, mBase);
        long end = Math.min(endByte, mTotal);
        if (end <= start) {
            return null;
        }
        byte[] out = new byte[(int) (end - start)];
        long off = mBase;
        int pos = 0;
        for (byte[] f : mFrames) {
            long fEnd = off + f.length;
            if (fEnd > start && off < end) {
                int from = (int) Math.max(0, start - off);
                int to = (int) Math.min(f.length, end - off);
                System.arraycopy(f, from, out, pos, to - from);
                pos += to - from;
            }
            off = fEnd;
            if (off >= end) {
                break;
            }
        }
        return pos == out.length ? out : (pos == 0 ? null : trim(out, pos));
    }

    private static byte[] trim(byte[] b, int len) {
        byte[] out = new byte[len];
        System.arraycopy(b, 0, out, 0, len);
        return out;
    }
}
