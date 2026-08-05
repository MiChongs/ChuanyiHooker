plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chuanyi.hooker.hookers.womic"
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

    // 两个用途，都不是为了改目标的原生代码（它的 .so 只有 opus/speex 编解码）：
    //  1. DexKit 的 libdexkit.so 在 LSPosed 的模块 classloader 下 System.loadLibrary
    //     不通，要借这里带绝对路径兜底的加载器（见 WoMicDex.scan）
    //  2. 「原生层文件访问诊断」用它的 openat 记录，排查冻结 GMS 之后应用还在读什么
    implementation(project(":native"))

    compileOnly(libs.libxposed.api)

    // 权益写入点的类名（5.3 里是 Z2.f）是 R8 产物，下个版本必然重排。
    // 改按 SharedPreferences 的持久化键扫 —— 键改不动，见 WoMicDex。
    implementation(libs.dexkit)
}
