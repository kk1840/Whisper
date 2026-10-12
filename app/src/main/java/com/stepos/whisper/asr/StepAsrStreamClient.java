package com.stepos.whisper.asr;

import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stepos.whisper.BuildConfig;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * StepFun 双向流式语音识别客户端（wss://api.stepfun.com/v1/realtime/asr/stream，模型
 * stepaudio-2.5-asr-stream）。PCM 16k/16bit/单声道按 100ms 帧推流，服务端 VAD 断句：
 * delta 的 text 是累计全量文本（直接整体替换实时字幕），completed 即整句定稿（带字级时间戳，
 * 秒，相对本连接）。断线自动退避重连，重连后从上一句结尾回放缓冲音频，不丢句子。
 * 回调在 OkHttp 的 WebSocket 线程上触发，调用方自行投递到目标线程。
 */
public class StepAsrStreamClient {
    private static final String TAG = "StepAsrStream";
    private static final String URL = "wss://api.stepfun.com/v1/realtime/asr/stream";
    private static final String MODEL = "stepaudio-2.5-asr-stream";
    /** 帧大小：100ms 的 16k/16bit 单声道 PCM。 */
    public static final int FRAME_BYTES = PcmTimeline.BYTES_PER_SEC / 10;
    private static final int REPLAY_CHUNK = FRAME_BYTES;
    private static final long[] BACKOFF_MS = {1000, 2000, 4000, 8000, 16000, 30000};

    public interface Listener {
        /** 实时字幕的累计全量文本，整体替换。 */
        void onPartial(String text);

        /** 整句定稿。startByte/endByte 是 PcmTimeline 的全局字节偏移（服务端秒已换算）。 */
        void onSentence(String transcript, long startByte, long endByte);

        /** server_vad 检测到的累计语音时长（注册页凑时长用）。 */
        void onSpeechSeconds(long speechMs);

        void onConnectionChange(boolean connected);
    }

    private final OkHttpClient mHttp;
    private final String mApiKey;
    private final PcmTimeline mTimeline;
    private final Listener mListener;
    private final Gson mGson = new Gson();
    /** 发送队列：回放块先入队、实时帧排后面，保证字节顺序即服务端时间轴。 */
    private final ArrayDeque<byte[]> mSendQueue = new ArrayDeque<>();
    private final Object mSendLock = new Object();
    private final AtomicBoolean mRunning = new AtomicBoolean(false);
    private Thread mSender;
    private volatile WebSocket mWs;
    private volatile boolean mConnected;
    private volatile int mBackoff = 0;
    /** 上一句定稿的全局结尾，重连时从这里回放。 */
    private volatile long mLastSentenceEnd = -1;
    /** 服务端 speech_started 的 item/audio 起点缓存（item_id → 全局起点偏移）。 */
    private final Map<String, Long> mItemStarts = new HashMap<>();
    private volatile long mSpeechStartWall = -1;
    private volatile long mSpeechMs = 0;
    private int mEventSeq = 0;

    public StepAsrStreamClient(PcmTimeline timeline, Listener listener) {
        this(timeline, listener, BuildConfig.STEPFUN_API_KEY);
    }

    public StepAsrStreamClient(PcmTimeline timeline, Listener listener, String apiKey) {
        mTimeline = timeline;
        mListener = listener;
        mApiKey = apiKey;
        mHttp = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
    }

    public boolean hasApiKey() {
        return !mApiKey.isEmpty();
    }

    public synchronized void start() {
        if (!mRunning.compareAndSet(false, true)) {
            return;
        }
        mLastSentenceEnd = -1;
        mSpeechMs = 0;
        mItemStarts.clear();
        mSender = new Thread(this::senderLoop, "asr-ws-send");
        mSender.start();
        connect();
    }

    /** 停止并关闭连接，不再重连。 */
    public synchronized void stop() {
        if (!mRunning.compareAndSet(true, false)) {
            return;
        }
        WebSocket ws = mWs;
        if (ws != null) {
            ws.close(1000, "stop");
            mWs = null;
        }
        synchronized (mSendLock) {
            mSendQueue.clear();
            mSendLock.notifyAll();
        }
        Thread sender = mSender;
        mSender = null;
        if (sender != null) {
            sender.interrupt();
        }
        setConnected(false);
    }

    /**
     * audio 线程调用：送入一帧 PCM（先写时间轴，再排队发送）。
     * 未连接时也写入时间轴并排队；连接确认后由 {@link #rebuildQueue()} 按会话基点重建，不丢不重。
     */
    public void acceptFrame(byte[] pcm) {
        if (!mRunning.get()) {
            return;
        }
        synchronized (mSendLock) {
            mTimeline.append(pcm, System.currentTimeMillis());
            mSendQueue.addLast(pcm);
            mSendLock.notifyAll();
        }
    }

    // ---------------------------------------------------------------- 连接与发送

    private void connect() {
        if (!mRunning.get()) {
            return;
        }
        Request request = new Request.Builder().url(URL)
                .header("Authorization", "Bearer " + mApiKey)
                .header("Accept-Language", "zh")
                .build();
        mHttp.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                mWs = webSocket;
                // 服务端时间轴 0 点 = 回放起点（首连时最多回放 1 秒预卷，重连时从上一句结尾）。
                long base = mLastSentenceEnd >= 0 ? mLastSentenceEnd
                        : Math.max(0, mTimeline.total() - PcmTimeline.BYTES_PER_SEC);
                mTimeline.startSession(base);
                webSocket.send(sessionUpdateJson());
                Log.i(TAG, "open, session base=" + base);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleEvent(text);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                Log.w(TAG, "ws failure: " + t);
                onDisconnected();
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                Log.i(TAG, "ws closed " + code + " " + reason);
                onDisconnected();
            }
        });
    }

    private String sessionUpdateJson() {
        JsonObject format = new JsonObject();
        format.addProperty("type", "pcm");
        format.addProperty("codec", "pcm_s16le");
        format.addProperty("rate", 16000);
        format.addProperty("bits", 16);
        format.addProperty("channel", 1);
        JsonObject transcription = new JsonObject();
        transcription.addProperty("model", MODEL);
        transcription.addProperty("language", "zh");
        transcription.addProperty("enable_itn", true);
        transcription.addProperty("enable_timestamp_align", true);
        JsonObject vad = new JsonObject();
        vad.addProperty("type", "server_vad");
        vad.addProperty("silence_duration_ms", 800);
        vad.addProperty("threshold", 0.6);
        JsonObject input = new JsonObject();
        input.add("format", format);
        input.add("transcription", transcription);
        input.add("turn_detection", vad);
        JsonObject session = new JsonObject();
        session.add("audio", new JsonObject());
        session.getAsJsonObject("audio").add("input", input);
        JsonObject out = new JsonObject();
        out.addProperty("type", "session.update");
        out.addProperty("event_id", nextEventId());
        out.add("session", session);
        return mGson.toJson(out);
    }

    /** 发送线程：串行把队列里的 PCM 帧转 base64 发出去，连接断开时等待。积压多时限速回放。 */
    private void senderLoop() {
        try {
            while (mRunning.get()) {
                byte[] frame;
                synchronized (mSendLock) {
                    while (mRunning.get() && (mSendQueue.isEmpty() || !mConnected)) {
                        mSendLock.wait(1000);
                    }
                    if (!mRunning.get()) {
                        return;
                    }
                    frame = mSendQueue.pollFirst();
                }
                WebSocket ws = mWs;
                if (ws == null) {
                    continue;
                }
                JsonObject msg = new JsonObject();
                msg.addProperty("type", "input_audio_buffer.append");
                msg.addProperty("event_id", nextEventId());
                msg.addProperty("audio", Base64.encodeToString(frame, Base64.NO_WRAP));
                if (!ws.send(mGson.toJson(msg))) {
                    // 发送失败（连接正在关闭）：把帧塞回队首等重连后补发。
                    synchronized (mSendLock) {
                        mSendQueue.addFirst(frame);
                        mSendLock.notifyAll();
                    }
                    Thread.sleep(200);
                    continue;
                }
                // 断线积压（>1 秒的音频）时按约 4 倍速回放，既快于实时又不至于冲垮引擎。
                int backlog;
                synchronized (mSendLock) {
                    backlog = mSendQueue.size();
                }
                if (backlog > 10) {
                    Thread.sleep(25);
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void onDisconnected() {
        mWs = null;
        setConnected(false);
        if (!mRunning.get()) {
            return;
        }
        long delay = BACKOFF_MS[Math.min(mBackoff, BACKOFF_MS.length - 1)];
        mBackoff++;
        Log.w(TAG, "reconnect in " + delay + "ms");
        mHttp.dispatcher().cancelAll();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::connect, delay);
    }

    // ---------------------------------------------------------------- 事件解析

    private void handleEvent(String text) {
        JsonObject ev;
        try {
            ev = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            Log.w(TAG, "bad json: " + text);
            return;
        }
        String type = str(ev, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            case "session.updated":
                mBackoff = 0;
                setConnected(true);
                rebuildQueue();
                break;
            case "session.created":
                break;
            case "conversation.item.input_audio_transcription.delta":
                mListener.onPartial(str(ev, "text"));
                break;
            case "conversation.item.input_audio_transcription.completed": {
                String transcript = str(ev, "transcript");
                if (transcript == null || transcript.isEmpty()) {
                    return;
                }
                long start = -1;
                long end = -1;
                JsonElement wordsEl = ev.get("words");
                if (wordsEl != null && wordsEl.isJsonArray()) {
                    JsonArray words = wordsEl.getAsJsonArray();
                    if (words.size() > 0) {
                        start = mTimeline.globalByteAtServerSec(words.get(0).getAsJsonObject().get("start").getAsDouble());
                        JsonObject last = words.get(words.size() - 1).getAsJsonObject();
                        end = mTimeline.globalByteAtServerSec(last.get("end").getAsDouble());
                    }
                }
                if (start < 0) {
                    Long itemStart = mItemStarts.remove(str(ev, "item_id"));
                    start = itemStart != null ? itemStart : mTimeline.sessionBase();
                    end = Math.max(start, mTimeline.sessionBase() + (mTimeline.total() - mTimeline.sessionBase()));
                }
                if (end <= start) {
                    end = start + FRAME_BYTES;
                }
                mLastSentenceEnd = end;
                mListener.onSentence(transcript, start, end);
                break;
            }
            case "input_audio_buffer.speech_started": {
                long startMs = ev.has("audio_start_ms") ? ev.get("audio_start_ms").getAsLong() : 0;
                String itemId = str(ev, "item_id");
                if (itemId != null) {
                    mItemStarts.put(itemId, mTimeline.globalByteAtServerSec(startMs / 1000.0));
                }
                if (mSpeechStartWall < 0) {
                    mSpeechStartWall = SystemClock.elapsedRealtime();
                }
                break;
            }
            case "input_audio_buffer.speech_stopped":
                if (mSpeechStartWall >= 0) {
                    mSpeechMs += SystemClock.elapsedRealtime() - mSpeechStartWall;
                    mSpeechStartWall = -1;
                    mListener.onSpeechSeconds(mSpeechMs);
                }
                break;
            case "error": {
                JsonObject err = ev.getAsJsonObject("error");
                String code = err != null ? str(err, "code") : null;
                String message = err != null ? str(err, "message") : text;
                Log.e(TAG, "server error " + code + ": " + message);
                if ("max_idle_timeout".equals(code) || "pong_timeout".equals(code)) {
                    onDisconnected();
                }
                break;
            }
            default:
                break;
        }
    }

    /**
     * 连接确认后重建发送队列：只保留会话基点（sessionBase）之后的音频，旧帧全部丢弃、
     * 不重复。基点之后已经排队/已写入时间轴的帧按顺序重新入队，audio 线程的新帧继续排在后面。
     */
    private void rebuildQueue() {
        long base = mTimeline.sessionBase();
        if (base < 0) {
            return;
        }
        synchronized (mSendLock) {
            mSendQueue.clear();
            long from = base;
            long total = mTimeline.total();
            while (from < total) {
                long end = Math.min(from + REPLAY_CHUNK, total);
                byte[] chunk = mTimeline.cutGlobal(from, end);
                if (chunk == null || chunk.length == 0) {
                    break;
                }
                mSendQueue.addLast(chunk);
                from = end;
            }
            mSendLock.notifyAll();
        }
        Log.i(TAG, "queue rebuilt from " + base + ", frames=" + (mSendQueue.size()));
    }

    private void setConnected(boolean connected) {
        if (mConnected == connected) {
            return;
        }
        mConnected = connected;
        mListener.onConnectionChange(connected);
        synchronized (mSendLock) {
            mSendLock.notifyAll();
        }
    }

    private String nextEventId() {
        return "e" + (mEventSeq++);
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() ? e.getAsString() : null;
    }
}
