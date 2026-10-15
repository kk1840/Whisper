package com.stepos.whisper.voiceprint;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stepos.whisper.BuildConfig;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 讯飞声纹识别（新版 WebAPI）客户端，POST https://api.xf-yun.com/v1/private/s1aa729d0。
 * 请求信封：{"header":{"app_id","status":3},"parameter":{"s1aa729d0":{"func",...,"&lt;func&gt;Res":{...}}},
 * "payload":{"resource":{"audio":base64,"encoding":"raw","sample_rate":16000,"channels":1,"bit_depth":16,"status":3}}}。
 * 音频用裸 PCM16LE（16k 单声道，无 WAV 头，实测服务端接受）。业务结果在 payload.&lt;func&gt;Res.text，
 * 是 base64 套 JSON，需要二次解码。全部方法同步阻塞，必须在后台线程调用。
 */
public class XfVoiceprintClient {
    private static final String HOST = "api.xf-yun.com";
    private static final String PATH = "/v1/private/s1aa729d0";
    private static final String ENDPOINT = "https://" + HOST + PATH;
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** 讯飞业务错误（header.code != 0）或 HTTP 非 200。 */
    public static class XfException extends IOException {
        public final int code;
        /** 网络抖动/服务端 5xx 时为 true，调用方可以重试。 */
        public final boolean retryable;

        public XfException(int code, String message, boolean retryable) {
            super("xfyun " + code + ": " + message);
            this.code = code;
            this.retryable = retryable;
        }
    }

    /** 1:N 检索单个候选。 */
    public static class Score {
        public String featureId;
        public String featureInfo;
        public float score = Float.NaN;
    }

    /** 1:N 检索结果，按分数降序。 */
    public static class SearchResult {
        public final List<Score> scores = new ArrayList<>();
    }

    /** queryFeatureList 返回的单条特征。 */
    public static class FeatureEntry {
        public String featureId;
        public String featureInfo;
    }

    private final OkHttpClient mHttp;
    private final Gson mGson = new Gson();
    private final String mAppId;
    private final String mApiKey;
    private final String mApiSecret;

    public XfVoiceprintClient() {
        this(BuildConfig.XFYUN_APP_ID, BuildConfig.XFYUN_API_KEY, BuildConfig.XFYUN_API_SECRET,
                new OkHttpClient.Builder().connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                        .writeTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build());
    }

    public XfVoiceprintClient(String appId, String apiKey, String apiSecret, OkHttpClient http) {
        mAppId = appId;
        mApiKey = apiKey;
        mApiSecret = apiSecret;
        mHttp = http;
    }

    public boolean hasCredentials() {
        return !mAppId.isEmpty() && !mApiKey.isEmpty() && !mApiSecret.isEmpty();
    }

    /** 创建声纹分组；业务错误原样交给调用方，不推断为“已存在”。 */
    public void createGroup(String groupId, String groupName) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("groupId", groupId);
        params.addProperty("groupName", groupName);
        call("createGroup", params, null, "createGroupRes");
    }

    /** 注册一条声纹（featureId 由本地生成，featureInfo 建议存名字便于云端排查）。 */
    public void createFeature(String groupId, String featureId, String featureInfo, byte[] pcm16k)
            throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("groupId", groupId);
        params.addProperty("featureId", featureId);
        params.addProperty("featureInfo", featureInfo);
        call("createFeature", params, pcm16k, "createFeatureRes");
    }

    public void deleteFeature(String groupId, String featureId) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("groupId", groupId);
        params.addProperty("featureId", featureId);
        call("deleteFeature", params, null, "deleteFeatureRes");
    }

    /** 列出分组内全部特征。文档提示结果可能受限制，量大时需要核对。 */
    public List<FeatureEntry> queryFeatureList(String groupId) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("groupId", groupId);
        JsonElement decoded = call("queryFeatureList", params, null, "queryFeatureListRes");
        List<FeatureEntry> out = new ArrayList<>();
        // 实测解出来是顶层数组 [{"featureId":..,"featureInfo":..}]，兼容一下对象包字段的形态。
        JsonArray arr = decoded.isJsonArray() ? decoded.getAsJsonArray()
                : decoded.isJsonObject() && decoded.getAsJsonObject().has("featureList")
                        ? decoded.getAsJsonObject().getAsJsonArray("featureList") : new JsonArray();
        for (JsonElement e : arr) {
            FeatureEntry f = mGson.fromJson(e, FeatureEntry.class);
            if (f != null && f.featureId != null) {
                out.add(f);
            }
        }
        return out;
    }

    /** 1:N 检索，topK 最大 10。 */
    public SearchResult searchFea(String groupId, int topK, byte[] pcm16k) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("groupId", groupId);
        params.addProperty("topK", Math.max(1, Math.min(10, topK)));
        JsonElement decoded = call("searchFea", params, pcm16k, "searchFeaRes");
        return parseSearchResult(decoded);
    }

    static SearchResult parseSearchResult(JsonElement decoded) throws IOException {
        try {
            if (decoded == null || !decoded.isJsonObject()
                    || !decoded.getAsJsonObject().has("scoreList")
                    || !decoded.getAsJsonObject().get("scoreList").isJsonArray()) {
                throw new IOException("missing voiceprint scoreList");
            }
            SearchResult result = new SearchResult();
            for (JsonElement e : decoded.getAsJsonObject().getAsJsonArray("scoreList")) {
                if (!e.isJsonObject()) {
                    throw new IOException("invalid voiceprint candidate");
                }
                JsonObject candidate = e.getAsJsonObject();
                if (!candidate.has("featureId") || candidate.get("featureId").isJsonNull()
                        || !candidate.has("score") || candidate.get("score").isJsonNull()) {
                    throw new IOException("missing voiceprint candidate fields");
                }
                Score s = new Score();
                s.featureId = candidate.get("featureId").getAsString();
                s.score = candidate.get("score").getAsFloat();
                if (candidate.has("featureInfo") && !candidate.get("featureInfo").isJsonNull()) {
                    s.featureInfo = candidate.get("featureInfo").getAsString();
                }
                if (s.featureId.isEmpty() || !Float.isFinite(s.score)) {
                    throw new IOException("invalid voiceprint candidate fields");
                }
                result.scores.add(s);
            }
            result.scores.sort((a, b) -> Float.compare(b.score, a.score));
            return result;
        } catch (RuntimeException e) {
            throw new IOException("invalid voiceprint search response", e);
        }
    }

    // ---------------------------------------------------------------- 内部实现

    /**
     * 发一次请求并返回二次解码后的业务 JSON。
     *
     * @param funcParams func 以外的业务参数
     * @param pcm16k     裸 PCM16LE 16k 单声道音频，无音频的功能传 null
     * @param resKey     响应结果块名，如 "searchFeaRes"
     */
    private JsonElement call(String func, JsonObject funcParams, byte[] pcm16k, String resKey)
            throws IOException {
        JsonObject funcObj = new JsonObject();
        funcObj.addProperty("func", func);
        for (var entry : funcParams.entrySet()) {
            funcObj.add(entry.getKey(), entry.getValue());
        }
        JsonObject resDecl = new JsonObject();
        resDecl.addProperty("encoding", "utf8");
        resDecl.addProperty("compress", "raw");
        resDecl.addProperty("format", "json");
        funcObj.add(resKey, resDecl);

        JsonObject parameter = new JsonObject();
        parameter.add("s1aa729d0", funcObj);
        JsonObject body = new JsonObject();
        JsonObject header = new JsonObject();
        header.addProperty("app_id", mAppId);
        header.addProperty("status", 3);
        body.add("header", header);
        body.add("parameter", parameter);
        if (pcm16k != null) {
            JsonObject resource = new JsonObject();
            resource.addProperty("audio", Base64.getEncoder().encodeToString(pcm16k));
            resource.addProperty("encoding", "raw");
            resource.addProperty("sample_rate", 16000);
            resource.addProperty("channels", 1);
            resource.addProperty("bit_depth", 16);
            resource.addProperty("status", 3);
            JsonObject payload = new JsonObject();
            payload.add("resource", resource);
            body.add("payload", payload);
        }

        Request request = new Request.Builder().url(signedUrl()).post(RequestBody.create(body.toString(), JSON))
                .header("Accept", "application/json").build();
        String respText;
        try (Response resp = mHttp.newCall(request).execute()) {
            respText = resp.body() == null ? "" : resp.body().string();
            if (resp.code() == 401 || resp.code() == 403) {
                throw new XfException(resp.code(), trim(respText), false);
            }
            if (!resp.isSuccessful()) {
                throw new XfException(resp.code(), trim(respText), resp.code() >= 500);
            }
        }

        JsonObject root = JsonParser.parseString(respText).getAsJsonObject();
        JsonObject respHeader = root.has("header") ? root.getAsJsonObject("header") : null;
        int code = respHeader != null && respHeader.has("code") ? respHeader.get("code").getAsInt() : -1;
        if (code != 0) {
            String message = respHeader != null && respHeader.has("message")
                    ? respHeader.get("message").getAsString() : respText;
            throw new XfException(code, message, code >= 10000 && code < 20000);
        }
        JsonObject payload = root.has("payload") ? root.getAsJsonObject("payload") : null;
        if (payload == null || !payload.has(resKey)) {
            return JsonNull.INSTANCE;
        }
        String text = payload.getAsJsonObject(resKey).has("text")
                ? payload.getAsJsonObject(resKey).get("text").getAsString() : null;
        if (text == null || text.isEmpty()) {
            return JsonNull.INSTANCE;
        }
        return JsonParser.parseString(new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8));
    }

    /** 组装带 host/date/authorization 查询参数的签名 URL。 */
    private String signedUrl() throws IOException {
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneId.of("GMT")));
        String signatureOrigin = "host: " + HOST + "\ndate: " + date + "\nPOST " + PATH + " HTTP/1.1";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(mApiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = Base64.getEncoder()
                    .encodeToString(mac.doFinal(signatureOrigin.getBytes(StandardCharsets.UTF_8)));
            String authOrigin = "api_key=\"" + mApiKey + "\", algorithm=\"hmac-sha256\","
                    + " headers=\"host date request-line\", signature=\"" + signature + "\"";
            String authorization = Base64.getEncoder()
                    .encodeToString(authOrigin.getBytes(StandardCharsets.UTF_8));
            return ENDPOINT + "?host=" + enc(HOST) + "&date=" + enc(date) + "&authorization=" + enc(authorization);
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("sign failed", e);
        }
    }

    /** URL query 编码：空格用 %20 而不是 form 风格的 +（实测 + 会被服务端当成字面量）。 */
    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }

    private static String trim(String s) {
        return s != null && s.length() > 300 ? s.substring(0, 300) : s;
    }
}
