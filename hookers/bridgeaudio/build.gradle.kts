plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chuanyi.hooker.hookers.bridgeaudio"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

dependencies {
    implementation(project(":core"))

    // 目标的 libairplay2_jni.so 是纯 Rust 写的 AirPlay 2 发送端，通篇没有一行授权
    // 逻辑（见 BridgeAudioHooker 类注释里的取证），所以这里**不装任何原生 hook**。
    // 引 :native 只为一件事：DexKit 的 .so 在 LSPosed 的 classloader 下装不起来，
    // 得靠 NativeHook.loadModuleLibrary 那条带绝对路径的兜底路径，见 BridgeAudioDex。
    implementation(project(":native"))
    compileOnly(libs.libxposed.api)

    // 应用自己的 `com.airplay.streamer.**` 目前没被 R8 重命名，直接按名字就能拿到。
    // DexKit 是这条路失效时的退路：改按落盘键名与商品 ID 扫 —— 那几个串一改，
    // 全体已购用户的解锁状态就读不出来了，比类名稳得多。见 BridgeAudioDex。
    implementation(libs.dexkit)
}
