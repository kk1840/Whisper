package com.stepos.whisper.llm;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.stepos.whisper.BuildConfig;
import com.stepos.whisper.data.UserFact;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 通过标准 API 通道调用 Chat Completions：从一段对话（录音转写或聊天截图）里提取与某个用户相关的待办和事实，
 * 以及把聊天截图的 OCR 文字整理成参与者和对话。
 */
public class StepFunChatClient {
    private static final String TAG = "StepFunChatClient";
    private static final String URL = "https://api.stepfun.com/v1/chat/completions";
    private static final String MODEL = "step-5-preview";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** 对话的来源，决定提示词里对文本的说明。 */
    public enum Source {
        /** 全天录音的转写。 */
        SPEECH,
        /** 聊天软件截图经 OCR 后整理出的对话。 */
        SCREENSHOT
    }

    private static final String INTRO_SPEECH =
            "你是机主的私人记忆助手。机主的手机全天录音，下面给你的是机主与某位熟人的一段对话转写，"
                    + "标注为“机主”的是机主本人。";
    private static final String INTRO_SCREENSHOT =
            "你是机主的私人记忆助手。下面给你的是机主手机上一张聊天软件截图里的对话——这一定是聊天场景"
                    + "（微信、QQ、飞书等的聊天界面），已经按发送者整理好，标注为“机主”的是机主本人发出的消息。";
    private static final String TASK =
            "你要从中整理两类内容，下次和这位熟人见面时提醒机主：\n"
                    + "一、待办：机主或对方答应要做的事（待办）、双方的约定或约好的见面（约定）。"
                    + "每条拆成四个字段：time（时间）、location（地点）、people（涉及的人物，用顿号分隔）、"
                    + "event（要做的事，一句简洁中文，不超过 40 字）。对话里没提到的字段填 null，不要编造。\n"
                    + "二、事实：对话里透露的关于这位熟人（没有熟人时是机主本人）的事实，包括身体状况、工作和家庭上的变化等"
                    + "（近况），喜好、忌口、习惯（偏好），生日、家人、职业等个人信息（信息）。"
                    + "比如对方说最近胃不舒服，就记一条事实“最近胃不舒服”。每条事实写成 content："
                    + "一句能单独看懂的简洁中文，不超过 40 字，主语是这位熟人时省略主语，提到的时间写进句子里。\n"
                    + "规则：\n"
                    + "1. 只记有后续价值的内容，寒暄和无关的闲聊不要记；只是事实、没有要做的事时不要硬凑成待办。\n"
                    + "2. 不要重复“已有待办”“已有事实”中已经记过的内容。\n"
                    + "3. 对话表明某条已有待办已经完成或取消，把它的 id 放进 completed_todo_ids；"
                    + "某条已有事实已经不再成立（比如胃已经好了），把它的 id 放进 outdated_fact_ids，有新情况就再记一条新事实。\n";
    private static final String RULE_TYPOS_SPEECH = "4. 转写可能有错别字，按语义理解；没有值得记录的内容就返回空数组。\n";
    private static final String RULE_TYPOS_SCREENSHOT =
            "4. 文字来自截图 OCR，可能有错别字或漏字，按语义理解；没有值得记录的内容就返回空数组。\n";
    private static final String FORMAT =
            "只输出 JSON，格式：{\"new_todos\":[{\"time\":\"...或 null\",\"location\":\"...或 null\","
                    + "\"people\":\"...或 null\",\"event\":\"...\",\"type\":\"待办|约定\"}],"
                    + "\"new_facts\":[{\"content\":\"...\",\"type\":\"近况|偏好|信息\"}],"
                    + "\"completed_todo_ids\":[整数 id],\"outdated_fact_ids\":[整数 id]}";

    private static final String PARSE_PROMPT =
            "你是机主的私人记忆助手。机主刚在手机上截了一张聊天软件的屏幕截图，下面是对这张截图做 OCR 得到的文字。"
                    + "这一定是聊天场景（微信、QQ、飞书、钉钉等的聊天界面）。"
                    + "每行开头的标记表示这行文字在屏幕上的位置：[右] 靠右显示，是机主自己发出的消息；"
                    + "[左] 靠左显示，是对方发出的消息，或消息上方的发送者昵称；[中] 居中，一般是页面标题、时间或系统提示。"
                    + "空行表示两条消息之间有间隔。\n"
                    + "你要做两件事：\n"
                    + "1. 找出参与这段聊天的所有人：机主本人（role 为 owner，name 填 null）和其他人（role 为 other）。"
                    + "单聊时对方的昵称通常是页面顶部居中的标题；群聊时每条消息上方有发送者昵称。"
                    + "昵称原样照抄，不要改写、翻译或去掉符号；页面标题明显是群名、公众号名而不是人名时不要当成参与者。\n"
                    + "2. 把消息按从上到下的顺序整理成对话 dialogue，每条包含 speaker（发送者昵称，机主发出的写“机主”）"
                    + "和 text（消息内容，OCR 错别字按语义纠正）。时间戳、“撤回了一条消息”“对方已添加你”之类的系统提示，"
                    + "以及输入框、按钮、状态栏上的文字都不是消息，去掉。\n"
                    + "同一个人前后必须用同一种昵称写法；dialogue 里出现的每个 speaker（机主除外）都要出现在 participants 里；"
                    + "认不出昵称时 speaker 写“对方”，不要编造名字。\n"
                    + "截图里没有任何聊天消息时（比如只是设置页面或列表），participants 和 dialogue 都返回空数组。\n"
                    + "只输出一个 JSON 对象，顶层必须同时有 participants 和 dialogue 两个键，例如：\n"
                    + "{\"participants\":[{\"name\":null,\"role\":\"owner\"},{\"name\":\"小王\",\"role\":\"other\"}],"
                    + "\"dialogue\":[{\"speaker\":\"小王\",\"text\":\"在吗\"},{\"speaker\":\"机主\",\"text\":\"在的\"}]}";

    /** 可重试的失败（网络问题、限流、服务端错误）。 */
    public static class RetryableException extends IOException {
        public RetryableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final OkHttpClient mHttp;
    private final String mApiKey;
    private final Gson mGson = new Gson();

    public StepFunChatClient(String apiKey) {
        mApiKey = apiKey;
        mHttp = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    public ExtractionResult extract(String userName, List<UserFact> existingFacts, String transcript)
            throws IOException {
        return extract(userName, existingFacts, transcript, Source.SPEECH);
    }

    public ExtractionResult extract(String userName, List<UserFact> existingFacts, String transcript, Source source)
            throws IOException {
        boolean screenshot = source == Source.SCREENSHOT;
        String system = (screenshot ? INTRO_SCREENSHOT : INTRO_SPEECH) + TASK
                + (screenshot ? RULE_TYPOS_SCREENSHOT : RULE_TYPOS_SPEECH) + FORMAT;
        String user = buildUserPrompt(userName, existingFacts, transcript, screenshot);
        JsonObject json = complete(system, user);
        if (!hasExtractionKeys(json)) {
            // 模型偶尔只回一个空对象 {}，对话里有内容可记时也会这样，直接重问一次。
            Log.w(TAG, "extract result missing keys, retrying: " + json);
            json = complete(system, user);
        }
        if (!json.has("new_todos") && !json.has("new_facts")) {
            // 模型偶尔直接返回单条待办或事实而不是外层结构。
            String key = json.has("event") ? "new_todos" : json.has("content") ? "new_facts" : null;
            if (key != null) {
                JsonObject wrapped = new JsonObject();
                JsonArray arr = new JsonArray();
                arr.add(json);
                wrapped.add(key, arr);
                json = wrapped;
            }
        }
        ExtractionResult result = fromJson(json, ExtractionResult.class);
        return result == null ? new ExtractionResult() : result;
    }

    /**
     * 把聊天截图的 OCR 文字整理成参与者和对话。
     *
     * @param sourceApp  被截图的应用（包名或名字），可为 null
     * @param layoutText 每行带 [左]/[右]/[中] 位置标记的 OCR 文字
     */
    public ChatParse parseChatScreenshot(String sourceApp, String layoutText) throws IOException {
        StringBuilder sb = new StringBuilder();
        if (sourceApp != null && !sourceApp.isEmpty()) {
            sb.append("截图来自应用：").append(sourceApp).append("\n\n");
        }
        sb.append("截图 OCR 文字：\n").append(layoutText);
        JsonObject json = complete(PARSE_PROMPT, sb.toString());
        if (!json.has("participants") && !json.has("dialogue")) {
            // 模型偶尔只吐出第一个参与者对象（{"name":null,"role":"owner"}）而不是整个结构，直接重问一次。
            Log.w(TAG, "parse result missing top-level keys, retrying: " + json);
            json = complete(PARSE_PROMPT, sb.toString());
        }
        ChatParse result = fromJson(json, ChatParse.class);
        return result == null ? new ChatParse() : result;
    }

    /** 发起一次对话请求，把模型回复解析成 JSON 对象。 */
    private JsonObject complete(String systemPrompt, String userPrompt) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("model", MODEL);
        body.addProperty("temperature", 0.2);
        JsonObject format = new JsonObject();
        format.addProperty("type", "json_object");
        body.add("response_format", format);

        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));
        body.add("messages", messages);

        Request request = new Request.Builder()
                .url(URL)
                .header("Authorization", "Bearer " + mApiKey)
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        int code;
        String raw;
        try (Response response = mHttp.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            code = response.code();
            raw = responseBody == null ? "" : responseBody.string();
        } catch (IOException e) {
            throw new RetryableException("network error", e);
        }
        if (code == 429 || code >= 500) {
            throw new RetryableException("HTTP " + code + ": " + raw, null);
        }
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + ": " + raw);
        }
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            String content = root.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "response: " + content);
            }
            return JsonParser.parseString(stripCodeFence(content)).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException | NullPointerException
                | IndexOutOfBoundsException e) {
            throw new IOException("unexpected response: " + raw, e);
        }
    }

    /** 外层结构或单条待办、事实都算；空对象和不认识的键不算。 */
    private static boolean hasExtractionKeys(JsonObject json) {
        return json.has("new_todos") || json.has("new_facts") || json.has("completed_todo_ids")
                || json.has("outdated_fact_ids") || json.has("event") || json.has("content");
    }

    private <T> T fromJson(JsonObject json, Class<T> type) throws IOException {
        try {
            return mGson.fromJson(json, type);
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("unexpected response: " + json, e);
        }
    }

    private static String buildUserPrompt(String userName, List<UserFact> existing, String transcript,
                                          boolean screenshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("熟人姓名：").append(userName).append("\n\n已有待办：\n");
        appendExisting(sb, existing, UserFact.KIND_TODO);
        sb.append("\n已有事实：\n");
        appendExisting(sb, existing, UserFact.KIND_FACT);
        sb.append(screenshot ? "\n聊天截图里的对话（发送者是截图里显示的昵称）：\n"
                : "\n对话转写（“陌生人N”是还没命名的说话人，“其他人”是无法辨认的旁人）：\n").append(transcript);
        return sb.toString();
    }

    private static void appendExisting(StringBuilder sb, List<UserFact> existing, int kind) {
        boolean any = false;
        for (UserFact f : existing) {
            if (f.kind != kind) {
                continue;
            }
            any = true;
            sb.append("- id=").append(f.id).append(" [").append(f.type).append("] ").append(f.content);
            appendField(sb, "时间", f.dueTime);
            appendField(sb, "地点", f.location);
            appendField(sb, "人物", f.people);
            sb.append('\n');
        }
        if (!any) {
            sb.append("（无）\n");
        }
    }

    private static void appendField(StringBuilder sb, String label, String value) {
        if (value != null && !value.isEmpty()) {
            sb.append("；").append(label).append("：").append(value);
        }
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    private static String stripCodeFence(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            int lastFence = t.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                t = t.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return t;
    }
}
