import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Properties;

/**
 * 只读排查：列出讯飞声纹分组 whisper_demo 内的全部特征（queryFeatureList）。
 * 用法：java scripts/XfQuery.java
 */
public class XfQuery {
    static final String HOST = "api.xf-yun.com";
    static final String PATH = "/v1/private/s1aa729d0";
    static final String ENDPOINT = "https://" + HOST + PATH;
    static final String GROUP_ID = "whisper_demo";

    public static void main(String[] args) throws Exception {
        Properties props = new Properties();
        try (InputStream in = new FileInputStream("local.properties")) {
            props.load(in);
        }
        String appId = props.getProperty("xfyun.appid", "");
        String apiKey = props.getProperty("xfyun.apiKey", "");
        String apiSecret = props.getProperty("xfyun.apiSecret", "");
        if (appId.isEmpty() || apiKey.isEmpty() || apiSecret.isEmpty()) {
            throw new IllegalStateException("local.properties 缺少 xfyun.* 配置");
        }

        String funcParams = "\"groupId\":\"" + GROUP_ID + "\"";
        String body = "{\"header\":{\"app_id\":\"" + appId + "\",\"status\":3},"
                + "\"parameter\":{\"s1aa729d0\":{\"func\":\"queryFeatureList\"," + funcParams
                + ",\"queryFeatureListRes\":{\"encoding\":\"utf8\",\"compress\":\"raw\",\"format\":\"json\"}}}}";

        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneId.of("GMT")));
        String signatureOrigin = "host: " + HOST + "\ndate: " + date + "\nPOST " + PATH + " HTTP/1.1";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getEncoder().encodeToString(mac.doFinal(signatureOrigin.getBytes(StandardCharsets.UTF_8)));
        String authOrigin = "api_key=\"" + apiKey + "\", algorithm=\"hmac-sha256\","
                + " headers=\"host date request-line\", signature=\"" + signature + "\"";
        String authorization = Base64.getEncoder().encodeToString(authOrigin.getBytes(StandardCharsets.UTF_8));
        String url = ENDPOINT + "?host=" + enc(HOST) + "&date=" + enc(date) + "&authorization=" + enc(authorization);

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        String resp;
        try (InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
            resp = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        System.out.println("HTTP " + code);
        System.out.println(resp);
        int i = resp.indexOf("\"queryFeatureListRes\":{\"text\":\"");
        if (i >= 0) {
            int start = i + "queryFeatureListRes\":{\"text\":\"".length();
            int end = resp.indexOf('"', start);
            System.out.println("decoded = " + new String(Base64.getDecoder().decode(resp.substring(start, end)), StandardCharsets.UTF_8));
        }
    }

    static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }
}
