plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.voicedesign.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.voicedesign.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

kotlin { jvmToolchain(17) }
