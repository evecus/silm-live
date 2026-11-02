plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 可通过 -Pabi=arm64-v8a 或 -Pabi=armeabi-v7a 覆盖；默认仅 arm64。
// 只打包所选架构的 so (libmpv 的 AAR 含全部架构, 体积较大, 务必只选需要的).
val abiList: List<String> = (findProperty("abi") as String?)
    ?.split(",")
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?: listOf("arm64-v8a")

android {
    namespace = "com.silm.live"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.silm.live"
        minSdk = 24
        targetSdk = 35
        versionCode = 114
        versionName = "1.1.4"
        ndk {
            abiFilters.clear()
            abiFilters += abiList
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
        // 压缩 native so，减小 APK 体积（安装时由系统解压）
        jniLibs {
            useLegacyPackaging = true
        }
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

    // mpv 内核 (libmpv for Android, 含完整 FFmpeg + 各 ABI 的 so; 实际打进 APK 的架构由上面的 abiFilters 决定)
    implementation("dev.jdtech.mpv:libmpv:1.0.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.brotli:dec:0.1.2")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
