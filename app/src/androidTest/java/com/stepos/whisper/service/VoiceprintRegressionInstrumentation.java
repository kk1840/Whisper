package com.stepos.whisper.service;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

import com.stepos.whisper.voiceprint.VoiceprintRegressionChecks;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 独立离线回归入口，不修改现有 RegressionInstrumentation 或其 runner 配置。 */
public final class VoiceprintRegressionInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            lineChecks();
            staleCallbackChecks();
            followChecks();
            VoiceprintRegressionChecks.runChecks();
            result.putString("stream", "PASS: per-line identity, manual priority, timeout, stale callbacks, short-sentence follow priority, local ranking, malformed scores, group/search errors\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error.getClass().getSimpleName() + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    public void runChecks() throws Exception {
        Method matched = RecordingService.class.getDeclaredMethod("matchedUser",
                com.stepos.whisper.voiceprint.VoiceprintLibrary.Match.class);
        matched.setAccessible(true);
        RecordingService emptyService = new RecordingService();
        for (int i = 0; i < 3; i++) {
            require(matched.invoke(emptyService, new Object[] { null }) == null,
                    "repeated no-match stays unknown without creating users");
        }
        lineChecks();
        staleCallbackChecks();
        followChecks();
        VoiceprintRegressionChecks.runChecks();
    }

    /** 短句未命中的上下文兜底：宽限跟随 > 粘性跟随 > 未知，目标被删或过期都不跟随。 */
    private void followChecks() {
        Set<Long> known = new HashSet<>(Arrays.asList(1L, 2L));
        require(RecordingService.followShortMiss(-1, 0, 1000, -1, 0, 1000, known) == null,
                "no context stays unknown");
        require(RecordingService.followShortMiss(1, 5000, 1000, 2, 0, 1000, known) == 1L,
                "learn grace wins over sticky anchor");
        require(RecordingService.followShortMiss(3, 5000, 1000, 2, 0, 1000, known) == 2L,
                "deleted learn target falls back to anchor");
        require(RecordingService.followShortMiss(1, 2000, 2000, 2, 1000, 3000, known) == 2L,
                "expired grace falls back to anchor");
        require(RecordingService.followShortMiss(-1, 0, 0, 2, 1000, 31_000, known) == 2L,
                "sticky follow at window boundary");
        require(RecordingService.followShortMiss(-1, 0, 0, 2, 1000, 31_001, known) == null,
                "sticky window expired stays unknown");
        require(RecordingService.followShortMiss(-1, 0, 1000, 5, 0, 1000, known) == null,
                "deleted anchor stays unknown");
    }

    private void lineChecks() {
        ServiceState.clearLines();
        long first = ServiceState.addLine(1, 2, "待识别", "甲", TranscriptLine.NO_USER, 0, 0);
        long second = ServiceState.addLine(2, 3, "待识别", "乙", TranscriptLine.NO_USER, 0, 0);
        require(ServiceState.applyVoiceprint(first, "甲", 1, 1), "first match");
        require(ServiceState.latestLine().userId == TranscriptLine.NO_USER, "no cross-line attribution");
        ServiceState.blockVoiceprint(second);
        require(!ServiceState.applyVoiceprint(second, "甲", 1, 1), "manual intent wins before queued assignment");
        ServiceState.relabelLine(second, "乙", 2, 2);
        require(!ServiceState.applyVoiceprint(second, "甲", 1, 1), "late match rejected");
        require(!ServiceState.resolvePendingLine(second, "待识别", "未知"), "late error rejected");
        long timeout = ServiceState.addLine(3, 4, "待识别", "超时", TranscriptLine.NO_USER, 0, 0);
        require(ServiceState.resolvePendingLine(timeout, "待识别", "未知"), "timeout resolution");
        require(ServiceState.applyVoiceprint(timeout, "丙", 3, 3), "late success after timeout");
        ServiceState.clearLines();
        require(!ServiceState.applyVoiceprint(timeout, "甲", 1, 1), "cleared line rejected");
    }

    @SuppressWarnings("unchecked")
    private void staleCallbackChecks() throws Exception {
        // 无数据库、会话或缓存实例；错误执行副作用会直接失败。
        RecordingService service = new RecordingService();
        Field instance = RecordingService.class.getDeclaredField("sInstance");
        instance.setAccessible(true);
        Object previous = instance.get(null);
        Field pending = RecordingService.class.getDeclaredField("mPendingVoiceprints");
        pending.setAccessible(true);
        Map<Long, Long> requests = (Map<Long, Long>) pending.get(service);
        Method finish = RecordingService.class.getDeclaredMethod("finishSentence", long.class,
                long.class, long.class, long.class, byte[].class, Long.class);
        finish.setAccessible(true);
        try {
            instance.set(null, service);
            long line = ServiceState.addLine(1, 2, "待识别", "手动", TranscriptLine.NO_USER, 0, 0);
            requests.put(line, 10L);
            ServiceState.blockVoiceprint(line);
            ServiceState.relabelLine(line, "手动用户", 4, 4);
            finish.invoke(service, line, 1L, 2L, 2000L, new byte[64000], 5L);
            require(ServiceState.latestLine().userId == 4, "stale callback changed manual identity");
            require(requests.containsKey(line), "manual assignment must retain gate until database update");
            requests.remove(line); // 模拟手动指认完成数据库更新。
            ServiceState.setPendingVoiceprints(false);
            requests.put(line, 10L);
            ServiceState.clearLines();
            finish.invoke(service, line, 1L, 2L, 2000L, new byte[64000], 5L);
            require(requests.isEmpty(), "cleared callback consumed");
        } finally {
            instance.set(null, previous);
            ServiceState.clearLines();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
