plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chuanyi.hooker.hookers.cellularpro"
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

    // 会员判定整层被 nmmp 虚拟化：方法在 dex 里只剩 `native` 声明，方法体是
    // `libQualcommAdapter.so` 里的一段字节码，由 `libkotlin.so` 的解释器执行。
    // 唯一能接管的时机是 `RegisterNatives` —— 走 :native 的注册期替换通道。
    implementation(project(":native"))
    compileOnly(libs.libxposed.api)

    // 会员管理器与会员记录都被 R8 重命名过（`A0B0.m3` / `A0B0.lg0`），且包名是
    // 构建期生成的。锚点改用会员记录构造器里那句反调用方校验的日志串，见 CellularProDex。
    implementation(libs.dexkit)
}
