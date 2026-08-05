plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chuanyi.hooker.hookers.flix"
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
    // 会员判定整条链都在 Dart AOT 里（libapp.so），Java 层一个字节都碰不到 ——
    // 解锁靠 :native 的 Dobby 代码补丁，落点用 libapp.so 内的特征码扫描定位。
    implementation(project(":native"))
    compileOnly(libs.libxposed.api)

    // 2.2.1 的 Java 层没开混淆，类名全是明文，DexKit 在这一版并非必需。
    // 用它是为了不把明文类名写死：目标一旦开启 R8，按「行为特征」找的落点
    // 还在，按名字找的会当场失效。见 FlixDex。
    implementation(libs.dexkit)
}
