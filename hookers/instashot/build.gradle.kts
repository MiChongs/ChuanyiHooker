plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chuanyi.hooker.hookers.instashot"
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
    // 解锁本身不需要原生层：48 个 .so 里没有一行授权逻辑。引 :native 只为
    // signature_check 那一项 —— libcer.so 的 cerCheck 是 RegisterNatives 动态绑定的，
    // 导出表里只有混淆过的 C++ 符号，得靠注册监视在 ART 绑定前换掉 fnPtr。
    implementation(project(":native"))
    compileOnly(libs.libxposed.api)

    // 授权链路上的类被 R8 重命名成了 `T` / `E` / `P`，字母每次构建都会变。
    // 改按落盘键名扫（"SubscribePro" / "Unlocked_" / "SubscribeProOfHw"）——
    // 那些键改一次全体老用户的购买就丢了，比任何类名都稳。见 InShotDex。
    implementation(libs.dexkit)
}
