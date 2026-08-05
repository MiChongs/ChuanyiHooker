plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chuanyi.hooker.hookers.secretshoot"
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
    // 只用来做排查项「记录文件读取」（Dobby hook libc openat）。这个目标的权益判定
    // 完全在 Java 层，原生层没有可挂的东西 —— 也正因为如此，本地那 8 个 .so 是不是
    // 被读过、加密配置落在哪个文件，反而是唯一需要原生视角才能看清的事。
    // 另外 DexKit 的 libdexkit.so 在 LSPosed 的模块 classloader 下要靠它的
    // loadModuleLibrary 才装得起来。
    implementation(project(":native"))
    compileOnly(libs.libxposed.api)

    // 权益中枢那个类被 R8 重命名过（4.3.8 里是 `ij.f`），按产品周期特征串扫。
    implementation(libs.dexkit)
}
