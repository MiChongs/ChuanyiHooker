plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chuanyi.hooker.hookers.gifshop"
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
    // 解锁本身不需要原生层 —— 目标的四个 .so 全是 GIF 编解码，授权一行都不在里面。
    // 引 :native 只为那个按需打开的文件读取日志（Dobby 挂 libc 的 openat）。
    implementation(project(":native"))
    compileOnly(libs.libxposed.api)

    // 权益判定函数在 R8 重命名后叫 `d.z()`，字母每次构建都会变；
    // 改按它引用的键名 "has_active_purchase" 扫，见 PremiumHelperDex。
    implementation(libs.dexkit)
}
