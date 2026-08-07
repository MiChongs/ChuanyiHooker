plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chuanyi.hooker.hookers.osmin"
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

    // 目标整包被 R8 重命名进默认包，授权判定与权益写入两个静态方法的名字每次构建
    // 都会变（v0.3.1 是 zq0.i2 / zq0.S2），只能按特征串扫。
    implementation(libs.dexkit)

    // 不依赖 :native —— 目标是纯 Kotlin/Compose，授权链路上没有一行原生代码，
    // 也没有加固、反调试或完整性校验。Dobby 在这里没有可解决的问题，
    // 挂上只会多一条会失败的路。
}
