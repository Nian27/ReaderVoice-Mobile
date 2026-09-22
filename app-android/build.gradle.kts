plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Jetpack Compose（Kotlin 2.x 编译器插件）
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.readervoice.app"
    // compileSdk 36：Compose 1.9.3 / activity 1.11.0 的 AAR 元数据要求（仅编译期 API 级别；
    // targetSdk 仍为 35，运行行为不变）
    compileSdk = 36

    defaultConfig {
        applicationId = "com.readervoice.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main").assets.srcDir("../parser/src/main/resources")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    packaging {
        jniLibs {
            // Extract .so to nativeLibraryDir so ProcessBuilder can exec native tools
            // (e.g. libcosy_conditioner_exec.so) and their dynamic deps resolve.
            useLegacyPackaging = true
            // P16-APP: DSP skel 不是 Android .so，而是交给 ADSP 侧加载的固件 blob。
            // 默认 strip 会把 jniLibs 里已验证的 4,597,776 B 变成 3,016,284 B（MD5 不同），
            // 使"APK 内的 skel == 已验证产物"这一条无法核对。这里保留原始字节。
            keepDebugSymbols += "**/libMNN_htpops_skel.so"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":parser-core"))
    implementation(project(":semantic-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Jetpack Compose（版本与本地 Gradle 缓存对齐：UI 1.9.3 / M3 1.4.0 / activity-compose 1.11.0）
    val composeUi = "1.9.3"
    implementation("androidx.compose.ui:ui:$composeUi")
    implementation("androidx.compose.ui:ui-graphics:$composeUi")
    implementation("androidx.compose.foundation:foundation:$composeUi")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.activity:activity-compose:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

