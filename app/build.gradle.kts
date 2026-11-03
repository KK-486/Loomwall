plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.loomwall.wallpaper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.loomwall.wallpaper"
        minSdk = 26
        targetSdk = 35
        versionCode = 101
        versionName = "1.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 本地自用：用 debug keystore 签名，产出的 release APK 可直接安装。
            // 正式分发时应换成独立 keystore（不要把它提交进仓库）。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // 阶段 0 不引入任何第三方运行时依赖：场景解析用 Android 自带的 org.json。
    // 这是刻意的选择——依赖越少，开源可维护性与包体越可控。
    //
    // 仅测试期依赖（不进 APK）：
    // - junit：单元测试框架；
    // - org.json：JVM 单元测试里没有 Android 的 org.json 实现，用官方同名实现替代。
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
