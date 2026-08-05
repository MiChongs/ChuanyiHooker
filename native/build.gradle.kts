plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chuanyi.hooker.nativehook"
    compileSdk = 37
    compileSdkMinor = 1
    ndkVersion = "30.0.15729638"

    defaultConfig {
        minSdk = 28

        externalNativeBuild {
            cmake {
                // Dobby ships cmake_minimum_required(VERSION 3.5); CMake 4.x
                // needs the policy escape hatch even though we bumped the
                // vendored copy (protects against a re-vendor undoing it).
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",
                )
                cFlags += listOf("-fvisibility=hidden", "-ffunction-sections", "-fdata-sections")
                cppFlags += listOf("-fvisibility=hidden", "-fvisibility-inlines-hidden", "-std=c++17")
            }
        }

        ndk {
            // Dobby supports these four; keep the list explicit so a new ABI
            // never silently ships untested.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    packaging {
        jniLibs {
            // 与 :app 保持一致，理由见 app/build.gradle.kts：Xposed 模块的 .so 要
            // 能从 APK 内直接加载，必须未压缩（extractNativeLibs=false）。
            // 真正决定最终 APK 的是 :app 的配置，这里只是不留下相反的示范。
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}
