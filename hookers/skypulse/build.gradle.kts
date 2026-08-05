plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chuanyi.hooker.hookers.skypulse"
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

    // 目标自己没有原生层（两个 .so 都是 AndroidX 的），引 :native 是为了反过来用它扫
    // **自己进程**的 ART 托管堆：那张硬编码的 RSA 验签公钥要在内存里等长换掉，
    // 这条路不依赖任何被 R8 重排过的类名方法名。见 Lifetime.swapPublicKeyInMemory。
    implementation(project(":native"))

    compileOnly(libs.libxposed.api)

    // 3.5.49 把会员判定、JWT 验签、加密存储的类名方法名全重排了一轮，写死必然失效。
    // 改按目标自己的存储键和 JWT 字段名扫，见 SkyPulseDex。
    implementation(libs.dexkit)
}
