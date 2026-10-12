import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Properties;

/**
 * 讯飞声纹识别（新版 isv_new, /v1/private/s1aa729d0）联调冒烟，纯 JDK 零依赖。
 * 用法：java scripts/XfSmokeTest.java
 * 依次执行 createGroup → createFeature(raw PCM) → searchFea → queryFeatureList → deleteFeature。
 * 凭证从 local.properties 读取（xfyun.appid / xfyun.apiKey / xfyun.apiSecret）。
 */
public class XfSmokeTest {
    static final String HOST = "api.xf-yun.com";
    static final String PATH = "/v1/private/s1aa729d0";
    static final String ENDPOINT = "https://" + HOST + PATH;
    static final String GROUP_ID = "whisper_demo";

    static String appId;
    static String apiKey;
    static String apiSecret;

    public static void main(String[] args) throws Exception {
        Properties props = new Properties();
        try (InputStream in = new FileInputStream("local.properties")) {
            props.load(in);
        }
        appId = props.getProperty("xfyun.appid", "");
        apiKey = props.getProperty("xfyun.apiKey", "");
        apiSecret = props.getProperty("xfyun.apiSecret", "");
        // 调试时可传参覆盖：args[0]=appId args[1]=apiKey args[2]=apiSecret
        if (args.length >= 3) {
            appId = args[0];
            apiKey = args[1];
            apiSecret = args[2];
        }
        if (appId.isEmpty() || apiKey.isEmpty() || apiSecret.isEmpty()) {
            throw new IllegalStateException("local.properties 缺少 xfyun.* 配置");
        }

        byte[] pcmA = loadPcm16k(Path.of("build/smoke_a.wav"));
        byte[] pcmB = loadPcm16k(Path.of("build/smoke_b.wav"));
        System.out.println("pcmA=" + pcmA.length + "B (" + pcmA.length / 32000.0 + "s), pcmB=" + pcmB.length + "B");

        call("createGroup",
                "\"groupId\":\"" + GROUP_ID + "\",\"groupName\":\"whisper demo\",\"groupInfo\":\"smoke\"", null,
                "createGroupRes");
        call("createFeature",
                "\"groupId\":\"" + GROUP_ID + "\",\"featureId\":\"smoke01\",\"featureInfo\":\"smoke|test\"",
                pcmA, "createFeatureRes");
        call("searchFea",
                "\"groupId\":\"" + GROUP_ID + "\",\"topK\":5", pcmB, "searchFeaRes");
        call("queryFeatureList",
                "\"groupId\":\"" + GROUP_ID + "\"", null, "queryFeatureListRes");
        call("deleteFeature",
                "\"groupId\":\"" + GROUP_ID + "\",\"featureId\":\"smoke01\"", null, "deleteFeatureRes");
        System.out.println("SMOKE OK");
    }

    /** 发一次请求并打印解码后的业务结果。funcParams 形如 "\"k\":\"v\",..."（不含 func 本身）。 */
    static void call(String func, String funcParams, byte[] audioPcm, String resKey) throws Exception {
        String audioField = "";
        if (audioPcm != null) {
            String b64 = Base64.getEncoder().encodeToString(audioPcm);
            audioField = "\"payload\":{\"resource\":{\"audio\":\"" + b64
                    + "\",\"encoding\":\"raw\",\"sample_rate\":16000,\"channels\":1,\"bit_depth\":16,\"status\":3}}";
        }
        String body = "{\"header\":{\"app_id\":\"" + appId + "\",\"status\":3},"
                + "\"parameter\":{\"s1aa729d0\":{\"func\":\"" + func + "\"," + funcParams
                + ",\"" + resKey + "\":{\"encoding\":\"utf8\",\"compress\":\"raw\",\"format\":\"json\"}}}"
                + (audioField.isEmpty() ? "" : "," + audioField)
                + "}";

        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneId.of("GMT")));
        String signatureOrigin = "host: " + HOST + "\ndate: " + date + "\nPOST " + PATH + " HTTP/1.1";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getEncoder().encodeToString(mac.doFinal(signatureOrigin.getBytes(StandardCharsets.UTF_8)));
        String authOrigin = "api_key=\"" + apiKey + "\", algorithm=\"hmac-sha256\","
                + " headers=\"host date request-line\", signature=\"" + signature + "\"";
        String authorization = Base64.getEncoder().encodeToString(authOrigin.getBytes(StandardCharsets.UTF_8));
        String url = ENDPOINT
                + "?host=" + enc(HOST)
                + "&date=" + enc(date)
                + "&authorization=" + enc(authorization);

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        System.out.println("== " + func + " → HTTP " + code);
        System.out.println(resp.length() > 2000 ? resp.substring(0, 2000) + "…(" + resp.length() + "B)" : resp);
        if (code != 200) {
            throw new IllegalStateException(func + " HTTP " + code);
        }
        // 业务结果在 payload.<func>Res.text，base64 套 JSON，再剥一层。
        int i = resp.indexOf("\"" + resKey + "\":{\"text\":\"");
        if (i >= 0) {
            int start = i + resKey.length() + 10;
            int end = resp.indexOf('"', start);
            String decoded = new String(Base64.getDecoder().decode(resp.substring(start, end)), StandardCharsets.UTF_8);
            System.out.println("   " + resKey + ".text = " + decoded);
        }
        System.out.println();
    }

    /** 读 16bit PCM WAV（TTS 输出通常是 22050Hz 单声道），线性重采样到 16k，返回裸 PCM16LE 字节。 */
    static byte[] loadPcm16k(Path wav) throws IOException {
        byte[] all = Files.readAllBytes(wav);
        if (all.length < 44 || all[0] != 'R' || all[1] != 'I' || all[2] != 'F' || all[3] != 'F') {
            throw new IOException("not RIFF: " + wav);
        }
        int pos = 12;
        int srcRate = 0, channels = 0, bits = 0;
        byte[] data = null;
        while (pos + 8 <= all.length) {
            String id = new String(all, pos, 4, StandardCharsets.US_ASCII);
            int size = le32(all, pos + 4);
            if ("fmt ".equals(id)) {
                int audioFormat = le16(all, pos + 8);
                channels = le16(all, pos + 10);
                srcRate = le32(all, pos + 12);
                bits = le16(all, pos + 22);
                if (audioFormat != 1) {
                    throw new IOException("not PCM: format=" + audioFormat);
                }
            } else if ("data".equals(id)) {
                data = new byte[size];
                System.arraycopy(all, pos + 8, data, 0, Math.min(size, all.length - pos - 8));
            }
            pos += 8 + size + (size % 2);
        }
        if (data == null || srcRate == 0) {
            throw new IOException("no data/fmt chunk in " + wav);
        }
        System.out.println(wav.getFileName() + ": " + srcRate + "Hz " + channels + "ch " + bits + "bit "
                + String.format("%.1fs", data.length / 2.0 / srcRate));
        if (bits != 16 || channels != 1) {
            throw new IOException("expect 16bit mono, got " + bits + "bit " + channels + "ch");
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
        ByteArrayOutputStream out = new ByteArrayOutputStream(outLen * 2);
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

    /** URL query 编码：空格用 %20 而不是 form 风格的 +。 */
    static String enc(String s) throws IOException {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }

    static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8) | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    static int le16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
