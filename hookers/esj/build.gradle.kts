plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chuanyi.hooker.hookers.esj"
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
    // 风控缓解那一项要用 linkmap_hide / suicide_guard，默认关着。
    // 协议改写本身走 Cocos 官方的 evalString，一行原生代码都不需要。
    implementation(project(":native"))
    compileOnly(libs.libxposed.api)

    // 目标是「局部加固」包：爱加密只加密了 supersdk / ewsdk 两个 SDK，
    // com.cocos.lib.* 和游戏自己的桥接类都是明文 dex，直接按名字取即可。
    // DexKit 留给版本升级后类名变化时按形状找。
    implementation(libs.dexkit)
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.extension)
    implementation(libs.kavaref.android)
}
