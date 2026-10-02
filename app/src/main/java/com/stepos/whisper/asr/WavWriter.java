package com.stepos.whisper.asr;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** 把 16 kHz 单声道 float PCM 写成 16 bit WAV。 */
public final class WavWriter {
    private WavWriter() {
    }

    public static void write(File file, float[] samples) throws IOException {
        int sampleRate = ModelManager.SAMPLE_RATE;
        int dataSize = samples.length * 2;
        ByteBuffer buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + dataSize)
                .put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '})
                .putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16)
                .put(new byte[]{'d', 'a', 't', 'a'}).putInt(dataSize);
        for (float s : samples) {
            float clamped = Math.max(-1f, Math.min(1f, s));
            buf.putShort((short) (clamped * 32767));
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(buf.array());
        }
    }
}
