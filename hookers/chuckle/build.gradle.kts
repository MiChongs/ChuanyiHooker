plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chuanyi.hooker.hookers.chuckle"
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

    // 目标自带一套原生完整性探针（lib.android.security.SecureValueNative，
    // diagnoseProbe / wasPathForged 都是 native 方法，Xposed 够不着方法体）。
    // 「放行原生完整性探针」那一项靠 Dobby 在符号层把它按成常量，默认关着 ——
    // 本机 Shamiko + tricky_store 在位时目标本来就判得过，无谓多改一处。
    implementation(project(":native"))

    compileOnly(libs.libxposed.api)

    // 类名方法名被 R8 重排过一轮（d1e / iqd / a4 / fpg 这种），写死必然活不过下个版本。
    // 改按目标自己的存储键与 kotlinx.serialization 的模型全限定名扫，见 ChuckleDex。
    implementation(libs.dexkit)

    // 目标是 Kotlin 写的，落点几乎全是 object 的静态成员、companion、以及继承来的
    // 存储基类方法。反射统一走 KavaRef：`superclass()` 一句话覆盖继承链，
    // `optional(silent = true)` 让「找不到」变成 null 而不是异常 ——
    // 这里的定位跑在目标 Application 的调用栈上，漏一个异常就是一次启动崩溃。
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.extension)
    implementation(libs.kavaref.android)
}
