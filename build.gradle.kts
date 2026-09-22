// 版本号取自 Android Studio 本地 maven 索引缓存
// (%LOCALAPPDATA%\Google\AndroidStudio2026.1.4\maven.google)，确认这些版本真实存在。
plugins {
    // compose-bom 2026.09.00 里的 compose 1.12.1 要求 AGP >= 9.1.0，这里用 9.4.x 最新稳定版。
    id("com.android.application") version "9.4.1" apply false

    // 注意：AGP 9.0 起 Kotlin 支持已内置，**不再声明 org.jetbrains.kotlin.android**，
    // 声明了会直接报 "The 'org.jetbrains.kotlin.android' plugin is no longer required"。
    //
    // 但 Compose 编译器插件仍然必须声明（Kotlin 官方迁移指南明确要求），
    // 否则 @Composable 不会被编译。
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
