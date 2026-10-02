package com.stepos.whisper.asr;

/** VAD 切出的一句话。 */
public class SpeechChunk {
    public final float[] samples;
    public final long startMs;
    public final long endMs;

    public SpeechChunk(float[] samples, long startMs, long endMs) {
        this.samples = samples;
        this.startMs = startMs;
        this.endMs = endMs;
    }

    public long durationMs() {
        return endMs - startMs;
    }
}
