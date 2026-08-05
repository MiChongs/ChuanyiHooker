plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chuanyi.hooker.hookers.zenneko"
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
    // 目标的原生自检（libcore.so 的 checkSecurityEnvironment）是**静态注册**的 JNI，
    // 导出符号就在符号表里 —— Dobby 直接按名字解析得到，不需要 RegisterNatives 监视。
    // 另外 DexKit 的 libdexkit.so 在 LSPosed 的模块 classloader 下要靠 :native 的
    // loadModuleLibrary 才装得起来。
    implementation(project(":native"))
    compileOnly(libs.libxposed.api)

    // 除了 io.github.wisyh.zenneko 那几个类，整包都被 R8 重命名过（会员闸门在
    // 1.0.0-alpha.7 里叫 gb.h3.i）。全部落点按特征找，不写死重命名结果。
    implementation(libs.dexkit)
}
