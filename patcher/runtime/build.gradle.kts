plugins {
    alias(libs.plugins.android.library)
}

// ---------------------------------------------------------------------------
// 注入进目标 APK 的运行时。
//
// 这个模块不参与 :app 的构建，产物也不是 AAR —— patch 脚本取它编译出来的 class，
// 用 d8 打成 classes3.dex 塞进目标包。
//
// 两条硬约束，都是「代码要跑在别人进程里」带来的：
//
//  1. **纯 Java，一行 Kotlin 都不能有。** 目标应用自带 kotlin-stdlib，我们再带一份
//     就是同名类冲突；而 dex 合并时重复类是直接报错的。Java 没有 runtime 依赖，
//     产物只有自己这几个类。
//  2. **不引任何依赖。** 同上，并且注入的 dex 越小，回编越快、越不容易撞
//     方法数上限。
//
// 目标类一律反射拿：编译期它们不存在，运行期又和我们在同一个 classloader 里，
// 所以 Class.forName 直接可用 —— 这是相对 Xposed 侧唯一变简单的地方。
// ---------------------------------------------------------------------------
android {
    namespace = "com.chuanyi.hillspatch"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        // 目标应用自己的 minSdk 是 24。
        minSdk = 24
    }

    compileOptions {
        // d8 后面按目标包的 minSdk 脱糖，这里保持保守。
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
