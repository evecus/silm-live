# ===== 简播 release 精简规则 =====

# Kotlin / Coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Brotli
-dontwarn org.brotli.**

# ExoPlayer (Media3)：consumer rules 已随 AAR 自带，无需额外 keep

# Coil
-dontwarn coil.**

# DataStore
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# Compose / 反射入口（一般可缩，保留必要）
-dontwarn androidx.compose.**

# WebView evaluateJavascript 无 JSInterface 注解需求；保留引擎类名便于排查
-keep class com.silm.live.core.sign.JsSignEngine { *; }

# 模型与 JSON 手工解析，无 Gson 反射；保留 core 数据类字段名非必须
# 若后续加序列化库再补充 -keep

# 去掉日志（可选，进一步减体积与信息泄露）
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
