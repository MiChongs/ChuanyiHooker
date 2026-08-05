plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chuanyi.hooker.hookers.airmusic"
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

    // 试用噪音的开关是 libaudioproxy.so 里的一个 .data 字节，Java 侧够不着 ——
    // 定位它、以及钉住它旁边那两个校验函数的返回值，都要走 :native 的 Dobby。
    implementation(project(":native"))

    compileOnly(libs.libxposed.api)

    // 授权裁决那个类被 R8 重命名成了 `r01`，名字每版都会变；改按「无参、返回
    // boolean、方法体里调 AudioProxy.h」这个形状扫。
    implementation(libs.dexkit)
}
