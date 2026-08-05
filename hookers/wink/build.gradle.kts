plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chuanyi.hooker.hookers.wink"
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

    // 会员体系整层都被 R8 重排过（`rg0.b`、`er.i2`、`tg0.d` 这种两三个字母的包名就是
    // 它的产物），写死类名下一版必失效。改按目标自己的日志串扫，见 WinkDex。
    implementation(libs.dexkit)

    // 没有引 :native —— 这个目标的会员判定从头到尾都在托管层，原生库里没有任何一处
    // 参与授权（详见 WinkHooker 的类注释）。Dobby 在这里没有活干，引进来只是多一层依赖。
}
