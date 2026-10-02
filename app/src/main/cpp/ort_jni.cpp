// OnnxModel 的原生实现：在 sherpa-onnx 附带的 libonnxruntime.so 上跑单输入单输出的 float 模型。
//
// onnxruntime 的导出符号带完整版本号（如 OrtGetApiBase@VERS_1.28.2），直接链接会把本库绑死在
// 编译时的那个 onnxruntime 版本上，所以运行时用 dlopen/dlsym 取入口。

#include <dlfcn.h>
#include <jni.h>

#include <mutex>
#include <string>
#include <vector>

#include "onnxruntime_c_api.h"

namespace {

const OrtApi* gApi = nullptr;
OrtEnv* gEnv = nullptr;
std::mutex gInitLock;

struct Model {
    OrtSession* session;
    std::string inputName;
    std::string outputName;
};

void throwIllegalState(JNIEnv* env, const std::string& message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message.c_str());
    }
}

// status 非空时转成 Java 异常并释放，返回 false。
bool ok(JNIEnv* env, OrtStatus* status) {
    if (status == nullptr) {
        return true;
    }
    throwIllegalState(env, std::string("onnxruntime: ") + gApi->GetErrorMessage(status));
    gApi->ReleaseStatus(status);
    return false;
}

bool ensureInit(JNIEnv* env) {
    std::lock_guard<std::mutex> lock(gInitLock);
    if (gEnv != nullptr) {
        return true;
    }
    void* lib = dlopen("libonnxruntime.so", RTLD_NOW);
    if (lib == nullptr) {
        throwIllegalState(env, std::string("dlopen libonnxruntime.so: ") + dlerror());
        return false;
    }
    using GetApiBaseFn = const OrtApiBase* (*)();
    auto getApiBase = reinterpret_cast<GetApiBaseFn>(dlsym(lib, "OrtGetApiBase"));
    if (getApiBase == nullptr) {
        throwIllegalState(env, "libonnxruntime.so 中没有 OrtGetApiBase");
        return false;
    }
    const OrtApiBase* base = getApiBase();
    const OrtApi* api = base->GetApi(ORT_API_VERSION);
    if (api == nullptr) {
        throwIllegalState(env, std::string("onnxruntime ") + base->GetVersionString()
                + " 不支持 API 版本 " + std::to_string(ORT_API_VERSION));
        return false;
    }
    gApi = api;
    OrtEnv* ortEnv = nullptr;
    if (!ok(env, gApi->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "whisper-ocr", &ortEnv))) {
        return false;
    }
    gEnv = ortEnv;
    return true;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_stepos_whisper_ocr_OnnxModel_nativeCreate(JNIEnv* env, jclass, jstring jpath, jint numThreads) {
    if (!ensureInit(env)) {
        return 0;
    }
    OrtSessionOptions* options = nullptr;
    if (!ok(env, gApi->CreateSessionOptions(&options))) {
        return 0;
    }
    OrtSession* session = nullptr;
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    // 输入尺寸每次都不同（检测分块、识别裁剪宽度不一），arena 和内存模式会为每种形状缓存大块内存且不归还，
    // 几张截图下来 native 堆就涨到数 GB；关掉后每次运行用完即还。
    bool created = ok(env, gApi->SetIntraOpNumThreads(options, numThreads))
            && ok(env, gApi->DisableCpuMemArena(options))
            && ok(env, gApi->DisableMemPattern(options))
            && ok(env, gApi->SetSessionGraphOptimizationLevel(options, ORT_ENABLE_ALL))
            && ok(env, gApi->CreateSession(gEnv, path, options, &session));
    env->ReleaseStringUTFChars(jpath, path);
    gApi->ReleaseSessionOptions(options);
    if (!created) {
        return 0;
    }

    auto* model = new Model{session, "", ""};
    OrtAllocator* allocator = nullptr;
    char* name = nullptr;
    bool named = ok(env, gApi->GetAllocatorWithDefaultOptions(&allocator))
            && ok(env, gApi->SessionGetInputName(session, 0, allocator, &name));
    if (named) {
        model->inputName = name;
        gApi->ReleaseStatus(gApi->AllocatorFree(allocator, name));
        named = ok(env, gApi->SessionGetOutputName(session, 0, allocator, &name));
        if (named) {
            model->outputName = name;
            gApi->ReleaseStatus(gApi->AllocatorFree(allocator, name));
        }
    }
    if (!named) {
        gApi->ReleaseSession(session);
        delete model;
        return 0;
    }
    return reinterpret_cast<jlong>(model);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_stepos_whisper_ocr_OnnxModel_nativeRun(JNIEnv* env, jclass, jlong handle, jfloatArray jinput,
        jlongArray jshape, jlongArray joutShape) {
    auto* model = reinterpret_cast<Model*>(handle);
    std::vector<float> input(env->GetArrayLength(jinput));
    env->GetFloatArrayRegion(jinput, 0, static_cast<jsize>(input.size()), input.data());
    std::vector<int64_t> shape(env->GetArrayLength(jshape));
    env->GetLongArrayRegion(jshape, 0, static_cast<jsize>(shape.size()), shape.data());

    const char* inputNames[] = {model->inputName.c_str()};
    const char* outputNames[] = {model->outputName.c_str()};
    OrtMemoryInfo* memInfo = nullptr;
    OrtValue* inputTensor = nullptr;
    OrtValue* outputTensor = nullptr;
    OrtTensorTypeAndShapeInfo* info = nullptr;
    float* outData = nullptr;
    size_t rank = 0;
    size_t count = 0;
    jfloatArray result = nullptr;
    if (ok(env, gApi->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &memInfo))
            && ok(env, gApi->CreateTensorWithDataAsOrtValue(memInfo, input.data(), input.size() * sizeof(float),
                    shape.data(), shape.size(), ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &inputTensor))
            && ok(env, gApi->Run(model->session, nullptr, inputNames, &inputTensor, 1, outputNames, 1,
                    &outputTensor))
            && ok(env, gApi->GetTensorTypeAndShape(outputTensor, &info))
            && ok(env, gApi->GetDimensionsCount(info, &rank))
            && ok(env, gApi->GetTensorShapeElementCount(info, &count))
            && ok(env, gApi->GetTensorMutableData(outputTensor, reinterpret_cast<void**>(&outData)))) {
        std::vector<int64_t> outShape(rank);
        if (ok(env, gApi->GetDimensions(info, outShape.data(), rank))) {
            if (static_cast<size_t>(env->GetArrayLength(joutShape)) != rank) {
                throwIllegalState(env, "模型输出是 " + std::to_string(rank) + " 维");
            } else {
                env->SetLongArrayRegion(joutShape, 0, static_cast<jsize>(rank), outShape.data());
                result = env->NewFloatArray(static_cast<jsize>(count));
                if (result != nullptr) {
                    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(count), outData);
                }
            }
        }
    }
    if (info != nullptr) {
        gApi->ReleaseTensorTypeAndShapeInfo(info);
    }
    if (outputTensor != nullptr) {
        gApi->ReleaseValue(outputTensor);
    }
    if (inputTensor != nullptr) {
        gApi->ReleaseValue(inputTensor);
    }
    if (memInfo != nullptr) {
        gApi->ReleaseMemoryInfo(memInfo);
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_stepos_whisper_ocr_OnnxModel_nativeRelease(JNIEnv*, jclass, jlong handle) {
    auto* model = reinterpret_cast<Model*>(handle);
    gApi->ReleaseSession(model->session);
    delete model;
}
