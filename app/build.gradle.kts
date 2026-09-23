plugins {
    // Kotlin 支持由 AGP 9 内置提供，不要再声明 org.jetbrains.kotlin.android。
    id("com.android.application")
    // Compose 编译器插件仍然需要（Kotlin 官方迁移指南要求）。
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mr5u.glovestatistics"

    // Compose 1.12.x（BOM 2026.09.00）要求 compileSdk >= 37。
    // 本机 SDK 里已有 platform android-37.0。
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mr5u.glovestatistics"
        minSdk = 26
        // targetSdk 保持与 compileSdk 一致；如果发现新系统上有行为变化，
        // 可以单独把 targetSdk 降回 35，两者不必相同。
        targetSdk = 37
        versionCode = 4
        versionName = "0.4.0"
    }

    // release 包必须签名才能安装。这里用 Android 的 debug 签名，让
    // `gradlew assembleRelease` 直接产出可安装的 APK（适合自己手机用 / 发 GitHub Release）。
    // 以后要上架应用商店时，把 signingConfig 换成自己的正式 keystore 即可。
    signingConfigs {
        create("releaseLocal") {
            storeFile = file("${rootDir}/.tooling/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("releaseLocal")
        }
    }

    // release 构建默认会跑 lintVital；本机离线时 lint 工具链不在 Gradle 缓存里，
    // 会让 assembleRelease 直接失败。这个项目没有 lint 基线，关掉不影响产物。
    lint {
        checkReleaseBuilds = false
    }

    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    // 这个 BOM 对应的就是 compose 1.12.x，即报错里要求 AGP 9.1.0 / compileSdk 37 的那一代。
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
