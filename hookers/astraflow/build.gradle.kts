plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chuanyi.hooker.hookers.astraflow"
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
    // 目标的授权原生库把方法名都藏在运行时，只有 RegisterNatives 那一刻名字和
    // 指针才同时存在 —— 拿它要走 :native 的 JNI 注册监视。
    implementation(project(":native"))
    compileOnly(libs.libxposed.api)

    // 目标几乎全是 Kotlin，且关键类被 R8 重命名过，只能按形状找成员 ——
    // 反射统一走 KavaRef。kavaref-android 是 1.1.0 拆出来的运行时，不带会在
    // 目标进程 clinit 抛 NoClassDefFoundError。
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.extension)
    implementation(libs.kavaref.android)

    // 签名函数所在的类被 R8 扔进了默认包，名字每次构建都会变；改按特征串扫。
    implementation(libs.dexkit)
}
