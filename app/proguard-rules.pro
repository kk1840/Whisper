# sherpa-onnx 通过 JNI 按类名/字段名回调 Java 层，不能混淆
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Gson 反射解析的数据类
-keep class com.stepos.whisper.llm.** { *; }
-keep class com.stepos.whisper.data.** { *; }
