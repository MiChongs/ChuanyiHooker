plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chuanyi.hooker.core"
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
    // Re-exported: every hooker module gets the reflection + hook DSL for free.
    api(libs.ezhooktool.core)
    api(libs.ezhooktool.xposed102)
    compileOnly(libs.libxposed.api)
    implementation(libs.androidx.annotation.jvm)
}
