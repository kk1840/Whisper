import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;

/**
 * 删除 whisper_demo 分组里的一个特征。用法：java scripts/XfDelete.java <featureId>
 */
public class XfDelete {
    static final String HOST = "api.xf-yun.com";
    static final String PATH = "/v1/private/s1aa729d0";
    static final String ENDPOINT = "https://" + HOST + PATH;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalStateException("用法: java scripts/XfDelete.java <featureId>");
        }
        Properties props = new Properties();
        try (InputStream in = new FileInputStream("local.properties")) {
            props.load(in);
        }
        String appId = props.getProperty("xfyun.appid", "");
        String apiKey = props.getProperty("xfyun.apiKey", "");
        String apiSecret = props.getProperty("xfyun.apiSecret", "");

        String body = "{\"header\":{\"app_id\":\"" + appId + "\",\"status\":3},"
                + "\"parameter\":{\"s1aa729d0\":{\"func\":\"deleteFeature\",\"groupId\":\"whisper_demo\""
                + ",\"featureId\":\"" + args[0] + "\""
                + ",\"deleteFeatureRes\":{\"encoding\":\"utf8\",\"compress\":\"raw\",\"format\":\"json\"}}}}";

        String date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(java.time.ZonedDateTime.now(java.time.ZoneId.of("GMT")));
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
        System.out.println("HTTP " + code + " " + resp);
    }

    static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }
}
