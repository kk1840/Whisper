package com.stepos.whisper.voiceprint;

import com.google.gson.JsonParser;
import com.stepos.whisper.data.User;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;

/** 无凭证、无网络的声纹回归检查，由独立 instrumentation 调用。 */
public final class VoiceprintRegressionChecks {
    public static void runChecks() throws Exception {
        Map<String, Long> users = new HashMap<>();
        users.put("low", 1L);
        users.put("high", 2L);
        VoiceprintLibrary.Match match = VoiceprintLibrary.bestLocalMatch(Arrays.asList(
                score("cloud-only", 0.99f), score("low", 0.3f), score("high", 0.8f),
                score("low", Float.NaN), score("low", Float.POSITIVE_INFINITY)), users, 0.6f);
        require(match != null && match.userId == 2L && match.score == 0.8f, "highest local candidate");
        require(VoiceprintLibrary.bestLocalMatch(Collections.singletonList(score("low", 0.3f)),
                users, 0.6f) == null, "below threshold");
        require(VoiceprintLibrary.bestLocalMatch(Collections.singletonList(score("high", Float.NaN)),
                users, 0.6f) == null, "NaN rejection");
        require(XfVoiceprintClient.parseSearchResult(JsonParser.parseString("{\"scoreList\":[]}"))
                .scores.isEmpty(), "valid no match");
        for (String json : Arrays.asList("null", "{}", "{\"scoreList\":null}",
                "{\"scoreList\":[{\"featureId\":\"high\"}]}",
                "{\"scoreList\":[{\"featureId\":\"high\",\"score\":\"Infinity\"}]}")) {
            try {
                XfVoiceprintClient.parseSearchResult(JsonParser.parseString(json));
                throw new AssertionError("malformed result accepted");
            } catch (IOException expected) {
                // 必须是错误，不能当作空候选。
            }
        }
        XfVoiceprintClient client = new XfVoiceprintClient("", "", "", new OkHttpClient()) {
            @Override public void createGroup(String id, String name) throws IOException {
                throw new XfException(999, "synthetic business failure", false);
            }
            @Override public SearchResult searchFea(String id, int count, byte[] pcm) throws IOException {
                throw new IOException("synthetic search failure");
            }
        };
        VoiceprintLibrary library = new VoiceprintLibrary(0.6f, client);
        User user = new User();
        user.id = 1;
        user.featureIds = "low";
        library.loadUsers(Collections.singletonList(user));
        try {
            CountDownLatch done = new CountDownLatch(2);
            AtomicInteger callbacks = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            library.ensureGroup((ok, error) -> {
                if (ok != null || error == null) failure.set(new AssertionError("group error accepted"));
                callbacks.incrementAndGet();
                done.countDown();
            });
            library.search(new byte[32000], (result, error) -> {
                if (result != null || error == null) failure.set(new AssertionError("error became no match"));
                callbacks.incrementAndGet();
                done.countDown();
            });
            require(done.await(5, TimeUnit.SECONDS), "callback timeout");
            require(callbacks.get() == 2 && failure.get() == null, "error callback contract");
        } finally {
            library.shutdown();
        }
    }

    private static XfVoiceprintClient.Score score(String id, float value) {
        XfVoiceprintClient.Score score = new XfVoiceprintClient.Score();
        score.featureId = id;
        score.score = value;
        return score;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
