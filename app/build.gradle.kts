plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.silm.live"
    // media3 1.11.x 的 aar-metadata 要求 minCompileSdk=36
    compileSdk = 36

    defaultConfig {
        applicationId = "com.silm.live"
        minSdk = 26
        targetSdk = 35
        versionCode = 114
        versionName = "1.1.4"

        // 只打包 arm64-v8a 与 armeabi-v7a 两种架构（同一个 APK 内包含两套 so）
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            // CI 通过环境变量注入；本地未配置时不启用签名
            val storePath = System.getenv("STORE_FILE")
            val storePass = System.getenv("STORE_PASSWORD")
            val alias = System.getenv("KEY_ALIAS")
            val keyPass = System.getenv("KEY_PASSWORD")
            if (!storePath.isNullOrBlank()
                && !storePass.isNullOrBlank()
                && !alias.isNullOrBlank()
                && !keyPass.isNullOrBlank()
            ) {
                storeFile = file(storePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
        debug {
            isMinifyEnabled = false
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
        resources.excludes += "META-INF/LICENSE*"
        resources.excludes += "META-INF/NOTICE*"
        resources.excludes += "META-INF/*.kotlin_module"
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
    // 仅用到少量图标；extended 体积大，改为 core + 按需
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.2")

    implementation("io.coil-kt:coil-compose:2.7.0")

    // ExoPlayer 内核 (Media3): HLS 由 hls 模块, FLV/TS 由 extractor 的 FlvExtractor/TsExtractor 支持
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.brotli:dec:0.1.2")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // QuickJS 引擎：执行抖音 a_bogus / MSSDK / 斗鱼签名脚本（对齐 Flutter dart_quickjs 机制）
    implementation("io.github.dokar3:quickjs-kt:1.0.15")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
