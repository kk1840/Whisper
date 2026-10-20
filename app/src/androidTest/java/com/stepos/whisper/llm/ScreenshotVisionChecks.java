package com.stepos.whisper.llm;

import android.util.Base64;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/** 使用拦截器合成响应，验证真实客户端请求，不访问网络。 */
public final class ScreenshotVisionChecks {
    private static final String CHAT = "{\"participants\":[{\"name\":\"小王\",\"role\":\"other\"}],"
            + "\"dialogue\":[{\"speaker\":\"小王\",\"text\":\"明天见\"}]}";

    public static void runChecks() throws Exception {
        List<JsonObject> requests = new ArrayList<>();
        byte[] image = {1, 2, 3, 4, 5};
        StepFunChatClient client = client(requests, 200, CHAT);
        ChatParse parsed = client.parseChatScreenshot("微信", image, "image/png");
        require(parsed.otherNames().equals(Collections.singletonList("小王")), "participants");
        require("小王：明天见\n".equals(parsed.dialogueText()), "dialogue");
        JsonObject body = requests.get(0);
        require("step-5-preview".equals(body.get("model").getAsString()), "vision model");
        JsonArray messages = body.getAsJsonArray("messages");
        require("system".equals(messages.get(0).getAsJsonObject().get("role").getAsString()), "system role");
        JsonArray content = messages.get(1).getAsJsonObject().getAsJsonArray("content");
        require(content.size() == 2, "text and image only");
        require(content.get(0).getAsJsonObject().get("text").getAsString().contains("微信"), "source app");
        JsonObject picture = content.get(1).getAsJsonObject();
        require("image_url".equals(picture.get("type").getAsString()), "image type");
        JsonObject url = picture.getAsJsonObject("image_url");
        require("high".equals(url.get("detail").getAsString()), "high detail");
        String data = url.get("url").getAsString();
        require(data.startsWith("data:image/png;base64,"), "data URL");
        require(Arrays.equals(image, Base64.decode(data.substring(data.indexOf(',') + 1), Base64.DEFAULT)),
                "original image bytes");

        for (StepFunChatClient.Source source : StepFunChatClient.Source.values()) {
            requests.clear();
            client(requests, 200, "{\"new_todos\":[],\"new_facts\":[]}")
                    .extract("小王", Collections.emptyList(), "小王：明天见", source);
            require(requests.get(0).getAsJsonArray("messages").get(1).getAsJsonObject()
                    .get("content").isJsonPrimitive(), "extraction remains text-only");
        }
        requests.clear();
        client(requests, 200, "{\"participants\":[]}", CHAT)
                .parseChatScreenshot(null, image, "image/jpeg");
        require(requests.size() == 2, "missing dialogue retries once");
        require(requests.get(0).equals(requests.get(1)), "retry preserves image");
        requests.clear();
        require(client(requests, 200, "{\"participants\":[],\"dialogue\":[]}")
                .parseChatScreenshot(null, image, "image/webp").dialogueText() == null, "non-chat empty");
        expectFailure(client(new ArrayList<>(), 200, "{}"), image, false);
        for (int status : new int[]{400, 429, 500}) {
            expectFailure(client(new ArrayList<>(), status, CHAT), image, status != 400);
        }
        requests.clear();
        try {
            client(requests, 200, CHAT).parseChatScreenshot(null, image, "text/plain");
            throw new AssertionError("unsupported MIME accepted");
        } catch (IOException expected) {
            require(requests.isEmpty(), "invalid image never sent");
        }
    }

    private static void expectFailure(StepFunChatClient client, byte[] image, boolean retryable) throws Exception {
        try {
            client.parseChatScreenshot(null, image, "image/png");
            throw new AssertionError("invalid response accepted");
        } catch (IOException error) {
            require((error instanceof StepFunChatClient.RetryableException) == retryable, "error classification");
        }
    }

    private static StepFunChatClient client(List<JsonObject> requests, int status, String... replies) {
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            Buffer buffer = new Buffer();
            chain.request().body().writeTo(buffer);
            requests.add(JsonParser.parseString(buffer.readUtf8()).getAsJsonObject());
            JsonObject message = new JsonObject();
            message.addProperty("content", replies[Math.min(requests.size() - 1, replies.length - 1)]);
            JsonObject choice = new JsonObject();
            choice.add("message", message);
            JsonArray choices = new JsonArray();
            choices.add(choice);
            JsonObject root = new JsonObject();
            root.add("choices", choices);
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(status).message("synthetic")
                    .body(ResponseBody.create(root.toString(), okhttp3.MediaType.get("application/json"))).build();
        }).build();
        return new StepFunChatClient("synthetic-not-a-real-key", http);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
