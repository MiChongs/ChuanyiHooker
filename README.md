# Chuanyi Hooker

一个 Xposed 模块宿主：**一个目标应用一个 hooker 模块**，运行期靠 `META-INF/services`
发现，注入范围靠各模块的 `scope.list` 合并。框架本身不认识任何具体应用。

| | |
|---|---|
| Xposed | [libxposed API 102](https://github.com/libxposed/api) + [EzHookTool](https://github.com/lingqiqi5211/EzHookTool) `hook-xposed-102` |
| 反射 | [KavaRef](https://github.com/HighCapable/KavaRef) 1.1.0（`-core` + `-extension` + `-android`） |
| Native | [Dobby](https://github.com/LSPosed/Dobby)（LSPosed fork，vendored 静态链接） |
| dex 扫描 | [DexKit](https://github.com/LuckyPray/DexKit) 2.2.0，按需引入 |
| 界面 | [Miuix](https://github.com/compose-miuix-ui/miuix) 0.9.3 + Navigation 3 + [Coil 3](https://github.com/coil-kt/coil) |

## 结构

```
core/            SPI 与运行时：AppHooker / HookFeature / HookScope、注册表、设置桥
native/          Dobby + JNI：符号查找、内存补丁、常量返回桩、JNI 注册监视
hookers/<name>/  一个目标应用一个模块
app/             模块 APK：libxposed 入口、Miuix 界面
patcher/         独立的静态改包链路，不参与 :app 构建
```

## 加一个 hooker

三处改动，框架不用动：

1. 建 `hookers/<name>/`，实现 `AppHooker`，外加两个资源文件：
   * `META-INF/services/com.chuanyi.hooker.core.AppHooker` — 写实现类全名，发现靠它
   * `META-INF/xposed/scope.list` — 写目标包名，注入范围靠它。**结尾必须留换行**，
     合并是拼接，少一个换行两个模块的包名会粘成一行，两边一起失效
2. `settings.gradle.kts` 加 `include(":hookers:<name>")`
3. `app/build.gradle.kts` 加 `implementation(project(":hookers:<name>"))`

```kotlin
class MyHooker : AppHooker {
    override val id = "myapp"
    override val displayName = "My App"
    override val targetPackages = setOf("com.example.myapp")

    override val features = listOf(
        HookFeature("unlock", "解锁 Pro", "…") { installUnlock() },
    )

    private fun HookScope.installUnlock() {
        classOrNull("com.example.myapp.Billing")
            ?.findMethod { name("isPro"); noParams() }
            ?.createReplaceHook("myapp.unlock") { true }
    }
}
```

同一个包名还要写进模块 manifest 的 `<queries>`：漏了不报错，但 Android 11+ 上模块
看不见目标，应用页会把装着的应用显示成「未安装」，而且没有任何界面途径能纠正。
`:checkHookerScope` 在构建期挡这个。

`HookScope` 给的是目标的类加载器、`ApplicationInfo`、versionCode、按功能分的设置和
日志器；EzHookTool 的反射与 hook DSL 已经指向目标类加载器。

## 内置 hooker

一行一个。**具体怎么破的写在各自 `*Hooker.kt` 的类注释里**，那里紧挨着代码，比这儿准。

| 模块 | 目标 | 切入点 |
|---|---|---|
| paisa | `dev.hemanths.paisa` | RevenueCat 的 CustomerInfo 映射函数（判定在 Dart） |
| hills | `com.mountains.hills` | Play Billing 回包（判定在混淆过的 `libapp.so`） |
| skypulse | `com.skypulse.weather` | 本地 HMAC 激活码 → 新版换成服务端 RSA JWT 验签 |
| yamby | `com.hush.yamby` | legacy AIDL 计费 Bundle（业务包被 nmmp/dex2c 编译走了） |
| astraflow | `com.astraflow.tool` | 注入侧那道 HMAC 门 —— 用它自己的签名函数补一张凭据 |
| capyplayer | `com.feifeiduck.capyplayer` | Play 订阅 + 服务端复核 |
| flix | `com.ifreedomer.flix` | Dart 侧判定，从 Java 计费和本地状态两头切 |
| chuckle | `app.jjyy.chuckle` | 纯本地权益判定 + 服务端撤销 + 风控埋点 |
| gifshop | `com.gif.gifmaker` | Zipoapps PremiumHelper + Play Billing 7 |
| instashot | `com.camerasideas.instashot` | Play 结算与买断记录，无壳无服务端复核 |
| womic | `com.wo.voice2` | Play 订阅 + AdMob 横幅与 UMP 弹窗 |
| airmusic | `app.airmusic.trial` | 试用噪音注入 + 授权判定 + 原生校验 |
| secretshoot | `com.weixikeji.secretshoot.googleV2` | 会员判定 + 广告 + 免登录 |
| zenneko | `io.github.wisyh.zenneko` | VIP 校验 + 原生环境自检 |
| gameclick | `com.pbb.gameclick` | 登录回包 + 原生复核 + 定时联网校验 |
| esj | `com.gx.sw.qa.fkssj004.esj` | 服务端权威，只做客户端侧：协议观察台 / 离线模式 |
| gboard | `com.google.android.inputmethod.latin` | 剪贴板的三处硬限制 + 按键上下滑 |

## 构建

```
./gradlew :app:assembleDebug
```

* **JDK 25+** —— EzHookTool 1.1.3 是 Java 25 字节码，Kotlin 编译器和 javac 都得跑在
  上面。钉在 `gradle/gradle-daemon-jvm.properties`，它压过 `org.gradle.java.home` 和
  IDE 的 Gradle JDK 设置。**Android Studio 每次 sync 会把这文件改回自带的 JBR 21** ——
  突然报 `invalid source release: 25` 就是它。
* **compileSdk 37**（`libxposed:api:102` 要求），本机装的是 `android-37.1`，所以
  `compileSdkMinor = 1`。
* **NDK 30.0.15729638 + CMake 4.1.2** 编 Dobby。CMake 4 不再兼容
  `cmake_minimum_required` 低于 3.5，vendored Dobby 的声明已提到 3.22。
* AGP 9 内建的 Kotlin 版本太旧，读不了 miuix / EzHookTool 的 2.4.10 元数据，在根
  `build.gradle.kts` 的 buildscript classpath 上抬到 2.4.10 —— 压 stdlib 没用，要动的
  是编译器。
* release 开 R8。「Xposed 模块不能开 R8」针对的是没写 keep 规则的情况；入口类、
  ServiceLoader、`RegisterNatives`、自检探针这四处 R8 都看不见，`proguard-rules.pro`
  逐条钉住了，文件末尾附了实机验证清单。

## 版本号

不手工维护，两个值都从 HEAD 的提交算出来：

| | 取值 | 例 |
|---|---|---|
| `versionName` | `yyyyMMdd.HHmmss-<提交号前 8 位>` | `20260805.153045-a1b2c3d4` |
| `versionCode` | 同一时刻距 `2020-01-01T00:00:00Z` 的秒数 | `208085445` |

时间取 committer date（rebase / cherry-pick 后只有它会变新，单调性靠它），按
`Asia/Shanghai` 渲染 —— 时区写死，否则 CI 和本机会给同一个提交算出两个版本号。
于是**同一个提交在哪台机器上编都是同一个版本号**，拿着 APK 就能 checkout 回源码。

工作区脏的时候改用**构建时刻**并加后缀（`…-dirty`），不然两个内容不同的包会显示成
同一个版本，刷进手机分不出装的是哪次。不是 git 仓库时是 `…-nogit`。

```
./gradlew -q :app:versionInfo              # 不构建也能问出版本号，发版脚本用
./gradlew :app:assembleRelease -PstampNow  # 干净工作区也按构建时刻打戳
```

产物文件名带版本：`app/build/outputs/apk/<variant>/ChuanyiHooker-<版本>-<variant>.apk`。
分隔符用 `-` 不用 semver 的 `+`：`+` 在 URL 里会被解成空格，挂到 Releases 上下载名字
就变了。`versionCode` 用秒数不用 `yyMMddHH` 拼接：必须单调递增否则覆盖安装被
`INSTALL_FAILED_VERSION_DOWNGRADE` 挡掉，而拼接到分就超 int 上限了。

实现在 `app/build.gradle.kts` 的「版本号」一节。

## 签名

主体 `CN=Chuanyi Tech`，JKS，RSA 4096。凭据从根目录 `keystore.properties` 读，CI 上用
`CHUANYI_KEYSTORE` / `_PASSWORD` / `_KEY_ALIAS` / `_KEY_PASSWORD` 顶替（优先级更高）。
格式见 `keystore.properties.example`；私钥和口令都在 `.gitignore` 里。

**debug 和 release 用同一把钥匙**，两种构建才能互相覆盖安装 —— 卸载会把模块设置一起
带走。拿不到凭据不打断构建：debug 退回调试密钥，release 出未签名包，配置期给一条
warning。

只开 v2 + v3（minSdk 28 装机不看 v1）。核验：

```powershell
apksigner verify --print-certs --min-sdk-version 24 (Get-ChildItem app\build\outputs\apk\release\*.apk)
```

`--min-sdk-version` 不能省：不带它 apksigner 按 APK 自己的 minSdk 28 判定，只校验 v3
并把 v2 那行报成 `false`，看起来像没生成，其实两个都在。

## 安装

1. 装 APK，在 LSPosed 里启用。
2. `module.prop` 写了 `staticScope=true`，作用域来自合并后的 `scope.list`，不用逐个勾。
3. 重启或强停目标。状态页变绿表示框架已经把模块加载进它自己的进程。

## 几个不显然的地方

**热重载。** hook 全部注册自 `EzXposed.onTargetReady`，`module.prop` 开了
`autoHotReload=true`，更新模块是原地换 hook，不用强停目标。

**自身状态。** 模块在自己的作用域里，`HookerRuntime` 在模块进程内替换掉
`ModuleStatus.isActivated()`，所以状态页报的是**被 hook 那侧**看到的事实，不是猜的。

**设置。** 界面写 `XposedService.getRemotePreferences`，hook 读
`XposedInterface.getRemotePreferences`。binder 到达前界面走本地存储并显示「未同步」，
连上后把改动迁过去。界面自己的偏好（主题、毛玻璃、启动页签）另存本地，不占框架存储。

**Native。** `NativeHook` 覆盖不写 C++ 就能做的事：

```kotlin
val addr = NativeHook.findSymbol("libfoo.so", "check_integrity")
NativeHook.returnConstant(addr, 1)   // 可撤销，不改代码
NativeHook.patchMemory(addr, bytes)
```

`findSymbol` 走 Dobby 的符号解析器，能找到 `dlsym` 看不见的非导出符号。
`returnConstant` 背后是 32 个预编译桩，与架构无关且可 `unhook`，比补
`mov x0,#imm; ret` 干净。要真正的 inline hook 就在
`native/src/main/cpp/hookers/` 里注册一个 C++ hooker。

**动态注册的 JNI。** `RegisterNatives` 绑定的方法没有可解析的符号名。
`watchJniRegistrations()` 换掉 `JNINativeMethod` 数组里的 `fnPtr`，在 ART 绑定之前
生效 —— 目标的库一个字节都没被写过，原生完整性校验也就看不见。拦的是 `JNIEnv` 函数表
里的一个指针，布局由 JNI 规范固定，不依赖 ART 版本。

**导航。** `miuix-navigation3-ui` 是 androidx Navigation 3 的 KMP 移植，运行时要另引
`androidx.navigation3:navigation3-runtime`。路由必须是 `data object` / `data class`
（nav3 拿实例当 contentKey，默认 `toString()` 进程重建后会变，页面状态被悄悄清空），
并逐个注册进 `SerializersModule`，漏一个那页就恢复不回来。底部三个页签不进返回栈。

**毛玻璃。** miuix 的 `blur()` 只有一个半径，渐进模糊靠三层叠加（半径递增、遮罩递
窄），遮罩用 `BlendMode.DstIn` 拿自身 alpha 裁模糊结果 —— 所以栏内容必须画在这些层
**之上**，画进去会被当成遮罩形状。硬件不支持或设置里关掉时，采样层根本不建。
`miuix-blur` 的 manifest 硬写 `minSdkVersion=33`，靠
`tools:overrideLibrary` 覆盖，运行时用 `isRuntimeShaderSupported()` 门控。

**主题。** 设置页在首页右上角。配色交给 miuix 的 `ThemeController`：深浅（跟随系统 /
浅 / 深）× 取色（默认 / 跟随壁纸 Monet / 自定义种子色 + 9 种调色板风格）。深浅在
应用侧先解析成布尔值再传显式的 `Light`/`Dark`/`MonetLight`/`MonetDark`，否则「强制
浅色」会被 controller 自己那次跟随系统的判断盖掉。纯黑深色只改 `background` 一个
字段，容器色留着才分得出层次、也才不会把壁纸取色抹平。系统栏图标明暗跟着主题走，
`enableEdgeToEdge()` 只在 Activity 创建时设一次，不补这一下深色系统上强制浅色会得到
白底白图标。
