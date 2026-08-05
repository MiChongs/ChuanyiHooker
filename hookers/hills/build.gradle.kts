plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chuanyi.hooker.hookers.hills"
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
    implementation(project(":native"))
    compileOnly(libs.libxposed.api)

    // 按特征串定位被 R8 改过名的类。见 HillsDex —— Flutter 目标里唯一改不动的
    // 东西是 pigeon 频道名和 JSON 键，扫描锚在那上面。
    implementation(libs.dexkit)
}
