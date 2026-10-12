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
 * 只读排查：用一段 PCM 对 whisper_demo 分组跑 searchFea，打印分数。
 * 用法：java scripts/XfSearch.java <wav路径>
 */
public class XfSearch {
    static final String HOST = "api.xf-yun.com";
    static final String PATH = "/v1/private/s1aa729d0";
    static final String ENDPOINT = "https://" + HOST + PATH;

    public static void main(String[] args) throws Exception {
        Properties props = new Properties();
        try (InputStream in = new FileInputStream("local.properties")) {
            props.load(in);
        }
        String appId = props.getProperty("xfyun.appid", "");
        String apiKey = props.getProperty("xfyun.apiKey", "");
        String apiSecret = props.getProperty("xfyun.apiSecret", "");

        java.nio.file.Path wav = java.nio.file.Path.of(args.length > 0 ? args[0] : "build/smoke_b.wav");
        byte[] pcm = loadPcm16k(wav);
        String b64 = Base64.getEncoder().encodeToString(pcm);

        String body = "{\"header\":{\"app_id\":\"" + appId + "\",\"status\":3},"
                + "\"parameter\":{\"s1aa729d0\":{\"func\":\"searchFea\",\"groupId\":\"whisper_demo\",\"topK\":5"
                + ",\"searchFeaRes\":{\"encoding\":\"utf8\",\"compress\":\"raw\",\"format\":\"json\"}}}"
                + ",\"payload\":{\"resource\":{\"audio\":\"" + b64
                + "\",\"encoding\":\"raw\",\"sample_rate\":16000,\"channels\":1,\"bit_depth\":16,\"status\":3}}}";

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
        System.out.println("HTTP " + code);
        int i = resp.indexOf("\"searchFeaRes\":{\"text\":\"");
        if (i >= 0) {
            int start = i + "\"searchFeaRes\":{\"text\":\"".length();
            int end = resp.indexOf('"', start);
            System.out.println(new String(Base64.getDecoder().decode(resp.substring(start, end)), StandardCharsets.UTF_8));
        } else {
            System.out.println(resp);
        }
    }

    static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }

    /** 读 16bit 单声道 PCM WAV，线性重采样到 16k，返回裸 PCM16LE。与 XfSmokeTest 同逻辑。 */
    static byte[] loadPcm16k(java.nio.file.Path wav) throws Exception {
        byte[] all = java.nio.file.Files.readAllBytes(wav);
        int pos = 12, srcRate = 0, channels = 0, bits = 0;
        byte[] data = null;
        while (pos + 8 <= all.length) {
            String id = new String(all, pos, 4, StandardCharsets.US_ASCII);
            int size = le32(all, pos + 4);
            if ("fmt ".equals(id)) {
                channels = le16(all, pos + 10);
                srcRate = le32(all, pos + 12);
                bits = le16(all, pos + 22);
            } else if ("data".equals(id)) {
                data = new byte[size];
                System.arraycopy(all, pos + 8, data, 0, Math.min(size, all.length - pos - 8));
            }
            pos += 8 + size + (size % 2);
        }
        if (data == null || srcRate == 0 || bits != 16 || channels != 1) {
            throw new IllegalStateException("bad wav " + wav);
        }
        if (srcRate == 16000) {
            return data;
        }
        short[] src = new short[data.length / 2];
        for (int i = 0; i < src.length; i++) {
            src[i] = (short) ((data[2 * i] & 0xff) | (data[2 * i + 1] << 8));
        }
        double ratio = srcRate / 16000.0;
        int outLen = (int) (src.length / ratio);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(outLen * 2);
        for (int i = 0; i < outLen; i++) {
            double x = i * ratio;
            int i0 = (int) x;
            double frac = x - i0;
            short s = (short) (src[i0] * (1 - frac) + (i0 + 1 < src.length ? src[i0 + 1] : src[i0]) * frac);
            out.write(s & 0xff);
            out.write((s >> 8) & 0xff);
        }
        return out.toByteArray();
    }

    static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8) | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    static int le16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }
}
