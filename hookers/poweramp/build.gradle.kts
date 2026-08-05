plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chuanyi.hooker.hookers.poweramp"
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

    // 权益的另一半在一个 Java 与原生共享的 direct ByteBuffer 里，而 Java 拿到的是
    // `asReadOnlyBuffer()` 视图——写不进去。要写它得先取到那块内存的真实地址，
    // 走 NativeHook.directBufferAddress + writeMemory。见 Entitlement。
    implementation(project(":native"))
    compileOnly(libs.libxposed.api)

    // 授权链路上的类全部被 R8 重命名过（`ׅ.i30` / `ׅ.zy0` / `ׅ.p5` 这种），且每次
    // 构建都会变，没有一个名字可以写死。锚点改用日志 TAG 与协议字段名，见 PowerampDex。
    implementation(libs.dexkit)
}
