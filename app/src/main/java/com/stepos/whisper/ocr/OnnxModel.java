package com.stepos.whisper.ocr;

/** 单输入单输出的 float ONNX 模型，跑在 sherpa-onnx 附带的 libonnxruntime.so 上。 */
final class OnnxModel implements AutoCloseable {
    static {
        System.loadLibrary("onnxruntime");
        System.loadLibrary("whisper-ort-jni");
    }

    private long mHandle;

    /** 加载模型，耗时，必须在后台线程调用。 */
    OnnxModel(String path, int numThreads) {
        mHandle = nativeCreate(path, numThreads);
    }

    /** outShape 按输出的维数分配，返回时填入实际形状。 */
    synchronized float[] run(float[] input, long[] shape, long[] outShape) {
        if (mHandle == 0) {
            throw new IllegalStateException("model closed");
        }
        return nativeRun(mHandle, input, shape, outShape);
    }

    @Override
    public synchronized void close() {
        if (mHandle != 0) {
            nativeRelease(mHandle);
            mHandle = 0;
        }
    }

    private static native long nativeCreate(String path, int numThreads);

    private static native float[] nativeRun(long handle, float[] input, long[] shape, long[] outShape);

    private static native void nativeRelease(long handle);
}
