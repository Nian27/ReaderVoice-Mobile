plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.10" apply false
    id("org.jetbrains.kotlin.jvm") version "2.3.10" apply false
    // Kotlin 2.x：Compose 编译器随 Kotlin 版本发布（与 2.3.10 对齐）
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10" apply false
}
