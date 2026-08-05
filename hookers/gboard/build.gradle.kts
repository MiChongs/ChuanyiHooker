plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chuanyi.hooker.hookers.gboard"
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
    compileOnly(libs.libxposed.api)

    // 剪贴板那几个类整包都被 R8 重命名了（ClipboardAdapter -> fki 之类），
    // 但每个类的 Flogger TAG 里留着原始全限定名 —— 按那个串扫最稳。
    implementation(libs.dexkit)
}
