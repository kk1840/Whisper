package com.stepos.whisper.asr;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** 读回 {@link WavWriter} 写出的 16k/16bit/单声道 WAV，返回裸 PCM16LE 字节。 */
public final class WavReader {
    private WavReader() {
    }

    public static byte[] readPcm16k(File file) throws IOException {
        byte[] all;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            all = out.toByteArray();
        }
        if (all.length < 44 || all[0] != 'R' || all[1] != 'I' || all[2] != 'F' || all[3] != 'F') {
            throw new IOException("not RIFF: " + file);
        }
        int pos = 12;
        int audioFormat = 0, channels = 0, rate = 0, bits = 0;
        byte[] data = null;
        while (pos + 8 <= all.length) {
            String id = new String(all, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = le32(all, pos + 4);
            if ("fmt ".equals(id)) {
                audioFormat = le16(all, pos + 8);
                channels = le16(all, pos + 10);
                rate = le32(all, pos + 12);
                bits = le16(all, pos + 22);
            } else if ("data".equals(id)) {
                data = new byte[size];
                System.arraycopy(all, pos + 8, data, 0, Math.min(size, all.length - pos - 8));
            }
            pos += 8 + size + (size % 2);
        }
        if (data == null || rate == 0) {
            throw new IOException("no data/fmt chunk in " + file);
        }
        if (audioFormat != 1 || channels != 1 || rate != ModelManager.SAMPLE_RATE || bits != 16) {
            throw new IOException("expect 16k/16bit/mono PCM, got " + rate + "Hz " + channels + "ch " + bits + "bit");
        }
        return data;
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8) | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }
}
