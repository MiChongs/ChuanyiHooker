plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chuanyi.hooker.hookers.paisa"
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
}
