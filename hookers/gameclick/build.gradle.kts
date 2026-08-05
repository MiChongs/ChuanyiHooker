plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chuanyi.hooker.hookers.gameclick"
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
    // 会员判定整个在 libgameclick.so 里，而那个库只导出 JNI_OnLoad —— 方法名和
    // 函数指针同时存在的地方只有 RegisterNatives 那一次调用，拿它要走 :native。
    implementation(project(":native"))
    compileOnly(libs.libxposed.api)

    // 改 DataConst 的静态字段、按形状挑方法，统一走 KavaRef。
    // kavaref-android 是 1.1.0 拆出来的运行时，不带会在目标进程 clinit 抛
    // NoClassDefFoundError。
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.extension)
    implementation(libs.kavaref.android)

    // 目标是 360 加固包：APK 里的 classes.dex 只有壳的 5 个类，真实 dex 运行时
    // 才解密进内存。所以扫描必须走 DexKit 的「内存 dex」模式
    // （DexKitBridge.create(classLoader, useMemoryDexFile = true)），
    // 拿 apkPath 去扫只会扫到壳。见 GameClickDex。
    implementation(libs.dexkit)
}
