LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under, app/src/main/java) \
    $(call all-named-files-under,*.kt,app/src/main/java)
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/app/src/main/res
LOCAL_ASSET_DIR := $(LOCAL_PATH)/app/src/main/assets
LOCAL_MANIFEST_FILE := app/src/main/AndroidManifest.xml

LOCAL_USE_AAPT2 := true
LOCAL_STATIC_ANDROID_LIBRARIES := \
    androidx.appcompat_appcompat \
    androidx.recyclerview_recyclerview \
    androidx.lifecycle_lifecycle-livedata \
    androidx.room_room-runtime \
    com.google.android.material_material
LOCAL_STATIC_JAVA_LIBRARIES := \
    okhttp-norepackage \
    gson \
    kotlin-stdlib \
    whisper-sherpa-onnx
LOCAL_ANNOTATION_PROCESSORS := androidx.room_room-compiler-plugin
LOCAL_ANNOTATION_PROCESSOR_CLASSES := androidx.room.RoomProcessor

LOCAL_JNI_SHARED_LIBRARIES := \
    libonnxruntime \
    libsherpa-onnx-jni \
    libsherpa-onnx-c-api \
    libsherpa-onnx-cxx-api \
    libwhisper-ort-jni
LOCAL_AAPT_FLAGS := -0 .onnx -0 .txt

LOCAL_PACKAGE_NAME := Whisper
LOCAL_CERTIFICATE := platform
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_PROGUARD_FLAG_FILES := app/proguard-rules.pro
include $(BUILD_PACKAGE)

include $(CLEAR_VARS)
LOCAL_MODULE := libwhisper-ort-jni
LOCAL_SRC_FILES := app/src/main/cpp/ort_jni.cpp
LOCAL_C_INCLUDES := $(LOCAL_PATH)/app/src/main/cpp/include
LOCAL_CPPFLAGS := -std=c++17
LOCAL_LDLIBS := -ldl
LOCAL_SDK_VERSION := 28
LOCAL_NDK_STL_VARIANT := c++_static
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := whisper-sherpa-onnx
LOCAL_MODULE_CLASS := JAVA_LIBRARIES
LOCAL_SRC_FILES := app/libs/sherpa-onnx-1.13.8.aar
LOCAL_UNINSTALLABLE_MODULE := true
LOCAL_SDK_VERSION := current
include $(BUILD_PREBUILT)
