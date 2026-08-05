plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chuanyi.hooker.hookers.capyplayer"
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
    // 权益判定全在 Dart AOT 里，Java 层碰不到 —— 解锁靠 :native 的 Dobby
    // 代码补丁，落点用 libapp.so 内的特征码扫描定位。
    implementation(project(":native"))
    compileOnly(libs.libxposed.api)

    // Play Billing 的 pigeon 实现类被 R8 重命名（1.1.3 里是 v7.h），名字每次构建
    // 都会变；改按它自带的明文错误串扫。
    implementation(libs.dexkit)
}
