plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 打包 ABI 单一定义: ndk.abiFilters 与下面的 ijk so 依赖共用, 改这里即可增减架构.
// 当前仅出 arm64 真机包.
val abiList = listOf("arm64-v8a")

android {
    namespace = "com.silm.live"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.silm.live"
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = "1.0.5"
        ndk {
            abiFilters += abiList
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.2")

    implementation("io.coil-kt:coil-compose:2.7.0")

    // IjkPlayer (GSY 维护的完整编译版, 发布在 mavenCentral): 唯一播放内核.
    // ffmpeg 原生支持 HLS/m3u8、FLV、RTMP, 四平台所有线路均可播, 已移除 ExoPlayer/media3;
    // so 是完整 ffmpeg 编译 (每个 ABI ~12MB, 协议/demuxer 不裁剪), 比官方 0.8.8 兼容性好得多.
    implementation("io.github.carguo:gsyijkjava:1.0.0")
    // so 按 ABI 条件引入 (与顶部 abiList 同源); 需要模拟器时再加 x86_64 对应项即可.
    if (abiList.contains("arm64-v8a")) implementation("io.github.carguo:gsyvideoplayer-arm64:14.0.0")
    if (abiList.contains("armeabi-v7a")) implementation("io.github.carguo:gsyvideoplayer-armv7a:14.0.0")
    if (abiList.contains("x86_64")) implementation("io.github.carguo:gsyvideoplayer-x64:14.0.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.brotli:dec:0.1.2")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
