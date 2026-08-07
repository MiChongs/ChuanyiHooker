# ===========================================================================
# Chuanyi Hooker —— R8 规则
#
# 这个包不是普通 app。它的类由 Xposed 框架加载进**目标应用的进程**，入口类名和
# hooker 列表写在 APK 里的纯文本资源里（R8 看不见那些字符串），原生层按名字
# RegisterNatives，模块还会 hook 自己的方法做激活自检。
#
# 下面每一条都对应一处 R8 推断不出来的名字依赖。删掉任意一条的表现都是
# 「编译通过、装上去没反应、日志里什么都没有」—— 这是最难查的一类故障，
# 所以每条都写清楚它挡住的是什么。
#
# 改完务必按文件末尾的清单实机验证一遍。
# ===========================================================================

# ---------------------------------------------------------------------------
# 入口类
#
# META-INF/xposed/java_init.list 的内容就是这个类的全限定名字符串，框架照着它
# 反射实例化。R8 只看得到「没有任何代码引用 HookerEntry」，改名或删掉都合法。
#
# EzHookTool 的 consumer 规则里有 `-keep class * extends XposedModule { *; }`，
# 但 libxposed api 是 compileOnly，R8 的 classpath 上没有那个父类，继承关系
# 匹配不保证成立。所以这里按名字再钉一遍，不依赖上游。
# 保留全部成员：框架调的是 onModuleLoaded / onPackageLoaded 等覆写方法，
# 父类不可见时 R8 无从判断它们是覆写，改了名就没人调得到。
# ---------------------------------------------------------------------------
-keep class com.chuanyi.hooker.xposed.HookerEntry { *; }

# libxposed api 由框架在运行时注入（compileOnly），R8 解析不到是预期内的。
-dontwarn io.github.libxposed.api.**
-dontwarn io.github.libxposed.annotation.**

# ---------------------------------------------------------------------------
# ServiceLoader 发现链
#
# 两头都是字符串，两头都要钉：
#
#   资源文件名   META-INF/services/com.chuanyi.hooker.core.AppHooker  <- 接口全名
#   文件内容     com.chuanyi.hooker.hookers.paisa.PaisaHooker ...     <- 实现全名
#
# 接口被改名 → ServiceLoader 去找 META-INF/services/a.b.c，文件不存在，
# 一个 hooker 都发现不了，UI 上是空列表；实现被改名 → ServiceConfigurationError
# 被 HookerRegistry 吞掉记进 errors()，表现为「有功能没能加载」。
#
# R8 确实有 ServiceLoader 重写优化，但它只认 `for (x in ServiceLoader.load(...))`
# 这种固定形态；HookerRegistry.load() 是带 try/catch 的手写迭代，重写不会发生。
# ---------------------------------------------------------------------------
-keep interface com.chuanyi.hooker.core.AppHooker { *; }
-keep class * implements com.chuanyi.hooker.core.AppHooker {
    public <init>();
}

# ---------------------------------------------------------------------------
# 激活自检探针
#
# HookerRuntime.installSelfProbe 干两件 R8 都会破坏的事：
#
#  1. 按方法名字符串在 declaredMethods 里找方法（"isActivated" 等四个），
#     名字被改就找不到，日志里只有一句 "self probe: no method ..."。
#  2. 这四个方法编译期返回常量（false / "" / 0 / -1）。R8 开优化后会把调用点
#     直接折成那个常量，hook 装上了也读不到——UI 永远显示「未启用」。
#
# 用 -keep（不是 -keepclassmembers）：R8 对 -keep 命中的成员默认关闭
# optimization，内联和返回值传播一并挡住，正好两个问题一起解决。
# 类上的注释写了「不要让它们变成 const/inline」，这条规则就是它在 R8 侧的对应物。
# ---------------------------------------------------------------------------
-keep class com.chuanyi.hooker.core.ModuleStatus { *; }

# ---------------------------------------------------------------------------
# JNI
#
# jni_bridge.cpp 的 JNI_OnLoad 走 FindClass(kClassName) + RegisterNatives，
# 按「类全名 + 方法名 + 签名」三者精确匹配：
#   类名被改 → FindClass 返回 null → JNI_OnLoad 返回 JNI_ERR → System.loadLibrary 抛异常
#   方法名被改 → RegisterNatives 返回非 0 → 同上
# 两种情况 NativeHook 都会被 runCatching 吞成 isAvailable=false，静默降级成
# 「原生层不可用」。
# ---------------------------------------------------------------------------
-keep class com.chuanyi.hooker.nativehook.NativeHook {
    native <methods>;
}

# ---------------------------------------------------------------------------
# 广播接收器：这里**故意什么都不写**
#
# ActivationReceiver 和 LogReceiver 的类全名都被当字符串用（发送方在别的进程里
# setClassName，见 ActivationGuard.Broadcast.RECEIVER / LogRelay.Wire.RECEIVER），
# 看起来正该在这里 keep 一遍。不写是因为两个都在 AndroidManifest 里声明过，AGP 会把
# 它们写进 aapt_rules.txt 并喂给 R8 —— 已经钉住了，再写一遍只是多一处要维护的地方。
#
# 判据不是「应该会吧」，是 mapping.txt 里那两行必须是恒等映射：
#     com.chuanyi.hooker.data.LogReceiver -> com.chuanyi.hooker.data.LogReceiver:
# 哪天改成不在清单里注册（动态 registerReceiver），这条就不成立了，那时才需要补 keep。
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# 崩溃栈可读性
#
# hook 跑在目标应用进程里，接调试器不现实，出问题基本只能看 logcat 里
# HookerLog 打出来的栈。行号表值这个体积（几百 KB 级）。
# 类名仍然混淆，还原用 app/build/outputs/mapping/release/mapping.txt。
# ---------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ===========================================================================
# 改动本文件后的实机验证清单（编译通过不代表没坏）
#
#   1. 装 release 包，LSPosed 里勾选，重启目标应用
#   2. 模块首页状态卡显示「运行中」          -> 自检探针 + ModuleStatus 规则
#   3. 「应用」页能列出全部 6 个 hooker      -> ServiceLoader 两端规则
#   4. 「关于」->「原生层」显示「已加载」     -> JNI 规则
#   5. 目标应用里功能实际生效                -> 入口类规则
#   6. 首页顶栏「日志」里有目标进程的行       -> LogReceiver 的恒等映射（见上一节）
# ===========================================================================
