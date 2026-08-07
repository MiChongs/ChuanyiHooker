# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 这是什么

Xposed 模块宿主，**一个目标应用一个 hooker 模块**。框架侧（`:core` / `:native` / `:app`）不认识任何
具体应用 —— 目标知识全部封在 `hookers/<name>/` 里，运行期靠 `META-INF/services` 发现，注入范围
靠各模块的 `scope.list` 合并。加一个目标不需要动框架。

`README.md` 是面向使用者的那份；本文件只写开发时要知道的。注释与文档是中文为主、密度很高的
「写清楚为什么」风格 —— 解释**为什么这样做、不这样做会怎样**，不复述代码。改动时保持一致。

## 构建

### 环境硬约束（踩到就是编不过，且报错常常指向别处）

* **JDK ≥ 25**：EzHookTool 1.1.3 是 Java 25 字节码，Kotlin 编译器和 javac 都得跑在上面。要求钉在
  `gradle/gradle-daemon-jvm.properties`（`toolchainVersion=26`），它压过 `org.gradle.java.home` 和
  IDE 的 Gradle JDK 设置。**Android Studio 每次 sync 会把这文件改回自带的 JBR 21** —— 突然报
  `invalid source release: 25` 先查它。
* **compileSdk 37 + `compileSdkMinor = 1`**（`libxposed:api:102` 要求），本机装的是 `android-37.1`。
* **NDK 30.0.15729638 + CMake 4.1.2** 编 vendored Dobby。
* Kotlin 抬到 2.4.10：AGP 9 内建的 KGP 读不了 miuix / EzHookTool 的 2.4.10 元数据。抬的位置是**根
  `build.gradle.kts` 的 buildscript classpath**（压 stdlib 无效，要动的是编译器），版本需与
  `gradle/libs.versions.toml` 里的 `kotlin` 手工保持一致。

### 命令

```powershell
.\gradlew.bat :app:assembleDebug                     # preBuild 会先跑 :checkHookerScope
.\gradlew.bat :app:assembleRelease                   # 开 R8，见下方「不可动的不变量」
.\gradlew.bat -q :app:versionInfo                    # 不构建也能问出这次会打的版本号
.\gradlew.bat :app:assembleRelease -PstampNow        # 干净工作区也按构建时刻打戳
.\gradlew.bat :app:assembleRelease -PcomposeReports  # 稳定性报告 -> app/build/compose_reports/
.\gradlew.bat :checkHookerScope                      # 单跑 hooker 声明一致性校验
.\gradlew.bat :app:exportLibraryDefinitionsRelease   # 导出开源许可清单（合规审计用）
```

产物：`app/build/outputs/apk/<variant>/ChuanyiHooker-<版本>-<variant>.apk`

核验签名（`--min-sdk-version` 不能省，不带它 apksigner 只校验 v3 并把 v2 那行报成 `false`）：

```powershell
apksigner verify --print-certs --min-sdk-version 24 (Get-ChildItem app\build\outputs\apk\release\*.apk)
```

### 没有自动化测试

仓库里没有 `src/test` / `src/androidTest`，也没有 CI。验证靠实机：装 APK → LSPosed 里勾选 →
重启或强停目标 → 看模块状态页，以及**首页顶栏的日志入口**（齿轮左边那个；被注入的进程把日志广播
回模块应用，见下方「日志」一节）。同一批行也在 logcat 里，tag `ChuanyiHooker`。门槛由
设置 → 日志 → 等级 定，默认 `信息` —— `d()` / `v()` 要把那一档往下拨才出，且改完要重启目标或热重载。

改 `app/proguard-rules.pro` 后**必须**按该文件末尾那五条清单实机走一遍：R8 会破坏的四处名字依赖
全都表现为「编译通过、装上没反应、日志里什么都没有」。

## 架构

### 模块划分

```
core/            SPI 与运行时：AppHooker / HookFeature / HookOption / HookPreset / HookScope、
                 HookerRegistry（ServiceLoader）、HookerRuntime（认领与安装）、设置读侧
native/          Dobby + JNI：符号查找、内存读写、常量返回桩、内存搜索替换、JNI 注册监视
hookers/<name>/  一个目标应用一个模块，只依赖 :core（按需 :native / dexkit）
app/             模块 APK：libxposed 入口 HookerEntry + Miuix 界面 + 设置写侧
patcher/         独立的静态改包链路（Python + patcher/runtime 注入 dex），不参与 :app 构建
```

### 运行期链路

`app/src/main/resources/META-INF/xposed/java_init.list` 里唯一那行指向 `HookerEntry`，它是
libxposed 的入口且**不含任何目标知识**：接线 EzHookTool，问 `HookerRuntime` 当前进程要不要管，
把结论交回 EzHookTool。

```
onModuleLoaded   -> HookerRuntime.attach() + EzXposed.onTargetReady { HookerRuntime.install() }
onPackageLoaded  -> HookerRuntime.onPackageLoaded() -> Decision
onPackageReady   -> HookerRuntime.onPackageReady()  -> Decision
target-ready     -> install()：每个 hooker 拿一个 HookScope，逐个装 enabled 的 feature
```

`Decision.IGNORE` 会让 entry 调 `detachCurrentEntry()`，此后 framework 不再向它分发任何回调
——**包括 `onHotReloading`**。所以只有「这个包我们根本没有 hooker」才返回 IGNORE；总开关关着、
或某个应用被单独关掉时仍要认领下来（装什么留给 `install()` 按当时的设置决定），否则那个进程
永久失去热重载能力。

失败是分层隔离的：`onHook()` 抛异常 → 该 hooker 整体跳过，其余 hooker 照常；单个 feature 的
`install` 抛异常 → 只丢那一个功能。写新 hooker 时沿用这个粒度，别把可选定位失败写成 `error()`。

### 热重载（改这块之前先读 `HookerEntry` 的类注释）

`module.prop` 里 `autoHotReload=true`，覆盖安装模块即原地换 hook，不用强停目标。前提很容易漏、
且漏了是静默的：

* 热重载给模块一个**全新的 classloader**，`:core` / `:native` 里的 object 在新一代里全是初始状态；
* framework 只重放 `onHotReloaded`，**不重放** `onModuleLoaded` / `onPackageLoaded` / `onPackageReady`
  —— 原本负责接线和认领的三个回调一个都不会再来。

因此：一代模块要做的接线收在 `HookerEntry.prepareGeneration()` 里，初次加载和热重载都走它；
认领结果通过 saved state 跨代传递（`HookerRuntime.crossGenerationState()` / `restoreClaim()`）。
saved state 数组**只能放 bootclasspath 类型**（String / Boolean / ClassLoader / ApplicationInfo），
放模块 classloader 创建的对象 framework 会直接拒掉这次热重载。不传 hooker 列表是有意的 ——
新一代按当前的注册表和设置重新挑一遍，那正是热重载的意义。

`onHotReloaded` 里任何异常都要吃掉：抛出去 LSPosed 会当成「模块类加载失败」，把整个模块从这个
进程里丢掉，比不支持热重载还糟。

### 设置

两侧一套键，键布局在 `core/.../HookerSettings.kt` 的 `SettingsKeys`：

| | |
|---|---|
| 写侧 | `app/.../data/ModuleSettings.kt`，`XposedService.getRemotePreferences` |
| 读侧 | `core/.../RemoteHookerSettings`，`XposedInterface.getRemotePreferences` |

**写入一律走 `ModuleSettings.write()`，永远不要用 `apply()`。** 远端那份是 libxposed 的
`RemotePreferences`，它的 `apply()` 只把新值写进进程内内存副本，送去框架那一步丢给自己的后台
线程池，而那个线程池不在 `QueuedWork` 里 —— 用户划掉后台就丢，界面全程显示成功。

binder 到达前界面走本地存储并显示「未同步」，连上后把改动迁过去。界面自己的偏好（主题、毛玻璃、
启动页签）另存本地，不占框架存储。

### 日志

分五级（`core/.../LogLevel.kt`：详细/调试/信息/警告/错误），门槛存在 `SettingsKeys.LOG_LEVEL`，
默认 `信息`。`HookerLog` 在**每一代模块加载时**读一次门槛就定死了 —— 改完等同改功能开关，
要重启目标或热重载。没写过该键时回落到旧的 `VERBOSE_LOG` 开关（开 = 调试），两侧的折算必须一致，
否则界面显示的档和实际生效的档会对不上。

一条日志同时走三处：框架日志器（拿不到就退 logcat）、以及 `LogRelay`。

```
被注入的进程                              模块应用
HookerLog.write ─> LogRelay.offer ─┐
                                    └─ 攒 400ms/64 条 ─> 广播 ─> LogReceiver ─> LogStore ─> 日志页
                                                                                   └─> filesDir/logs/（轮转，两份共 1 MB）
```

**为什么要绕这一圈**：hook 跑在目标进程里，它的 logcat 行模块应用读不到（要 `READ_LOGS` 或 root），
LSPosed 的模块日志又在管理器私有目录里。和激活令牌是同一个形状 —— 被注入的进程对模块存储只读，
递不进去，只能广播。接收器因此**必须导出**（发送方是别人的 uid，`android:permission` 在这里不成立），
于是任何应用都能塞几行假日志进来；可以接受，因为收进来的东西只会被显示，不参与任何判定。

几个别踩的点：

* `LogRelay` 里**任何地方都不能调 `HookerLog`** —— 会立刻绕回 `offer` 变成自喂死循环。
* 队列满了丢**最旧的**并计数，丢了多少随下一批报上去，界面照实显示。洪峰时要看的是刚发生的那几条。
* `PACKAGE_LOADED` 阶段目标还没有 `Application`，发不了广播。那些条目留在队列里等后台线程某轮
  取到 Context 再一起送；时间戳是**入队时**记的，所以晚送不会让顺序或时间显示错乱。
* 热重载换 classloader，新一代的 `LogRelay` 是全新 object —— `HookerRuntime.attach` 里那次
  `configure` 两条路径都要跑到。

### 三层用户可配项

* `HookFeature` —— 开关。**关掉的功能根本不安装**（不是装了再在回调里判断），所以改开关默认要
  重启目标或热重载才生效；确实需要不重载就响应的，才把 hook 留着在回调里读标志。
* `HookOption` —— 取值：`Number`（滑块）/ `Choice`（档位，可带自定义）/ `Text` / `AppList`（应用
  选择器）/ `KeyMap`（可点的键盘）。hook 侧用 `scope.int()` / `scope.string()` 读。
* `HookPreset` —— 一套调好的配置，套用是**全量覆盖**（没提到的项也回到默认值）。

### 原生层

Kotlin 侧 `NativeHook`（`native/src/main/kotlin/.../NativeHook.kt`），C++ 侧用
`CHUANYI_NATIVE_HOOKER(var, "id", "描述", installFn)` 在静态初始化时注册，Kotlin 用
`NativeHook.install("id")` 装。新增 C++ hooker 要加进 `native/src/main/cpp/CMakeLists.txt` 的
源文件列表。所有 Kotlin 方法都不抛异常，失败返回无害值 —— 原生层不可用时 Java hook 照常工作。

几处容易写错、后果又很远的地方：

* **`patchMemory` 只用于指令流。** 底层 `DobbyCodePatch` 无论原本什么权限，写完都把页恢复成
  `PROT_READ|PROT_EXEC`；对数据页是致命的，应用下一次正常写那页就死于 `SEGV_ACCERR`，崩溃点离
  这里很远。改数据（标志位、缓存字段、函数指针）用 `writeMemory`。
* `Scope.NATIVE`（Dart AOT 堆、原生分配器）与 `Scope.MANAGED_HEAP`（ART 托管堆，Java
  `static final String` 字面量在这里）**不重叠**，按目标把串放在哪个堆里选。
* `findPattern` 只搜可执行段。模式要取自函数**体**、且不能覆盖将要写入的字节，否则打完第一处
  之后锚点就不存在了。装之前用 `countPattern` 断言唯一性。
* `watchJniRegistrations()` / `returnConstantOnJniRegister()` 换的是 `JNINativeMethod` 数组里的
  `fnPtr`，在 ART 绑定之前生效 —— 目标的库一个字节都没被写过，原生完整性校验看不见。必须在目标
  加载那个库**之前**装。
* **`libdexkit.so` 必须由使用方显式加载**（DexKit 2.x 起 `DexKitBridge` 不再自己 `loadLibrary`），
  而且不能只靠 `System.loadLibrary` —— 它按调用类的 classloader 找库，模块 classloader 未必带着
  模块 APK 的原生库路径。漏掉的表现不是「找不到 so」，是第一次调原生方法时报
  `No implementation found for … nativeInitDexKit`，看着像版本不匹配。两种现成写法：依赖 `:native`
  的用 `NativeHook.loadModuleLibrary("dexkit")`（见 `BridgeAudioDex`），不依赖的自己反射
  `ClassLoader.findLibrary` 拿绝对路径兜底（见 `GboardDex.nativeReady`）。

### 界面

Miuix 0.9.3 + Navigation 3（`miuix-navigation3-ui` 是 androidx Navigation 3 的 KMP 移植，运行时
另引 `androidx.navigation3:navigation3-runtime`）。

新增页面**三处，缺一不可**：`Route.kt` 加 `@Serializable data object/data class` → `HookerNavHost`
的 `SerializersModule` 里 `subclass(...)` 注册 → `entryProvider` 里加 `entry<...> { }`。路由必须是
`data object` / `data class`：nav3 拿路由实例当 contentKey，默认的身份 `toString()` 在进程重建后
会变，页面里的 `rememberSaveable` 状态会被悄悄清空。

Compose 稳定性走 `app/compose_stability.conf`，往里加类型的判据来自 `-PcomposeReports` 的报告，
不是感觉；该文件只能用 `//` 注释，`#` 开头会被当成模式。

毛玻璃：miuix 的 `blur()` 只有一个半径，渐进模糊靠三层叠加 + `BlendMode.DstIn` 用自身 alpha 裁
模糊结果 —— 栏内容必须画在这些层**之上**，画进去会被当成遮罩形状。`miuix-blur` 的 manifest 硬写
`minSdkVersion=33`，靠 app manifest 的 `tools:overrideLibrary` 覆盖，运行时用
`isRuntimeShaderSupported()` 门控。

## 加一个 hooker

框架不用动。新建 `hookers/<name>/`，包名与 namespace 都是 `com.chuanyi.hooker.hookers.<name>`：

1. `build.gradle.kts` —— 抄一份现成的。必需的只有 `implementation(project(":core"))` +
   `compileOnly(libs.libxposed.api)`（`hookers/yamby` 就这些），`project(":native")` 与
   `libs.dexkit` 按需加（多数模块两样都要）。
2. `src/main/kotlin/.../<Name>Hooker.kt` —— 实现 `AppHooker`。
3. `src/main/resources/META-INF/services/com.chuanyi.hooker.core.AppHooker` —— 实现类全名，发现靠它。
4. `src/main/resources/META-INF/xposed/scope.list` —— 目标包名，注入范围靠它。**结尾必须留换行**：
   合并是拼接，少一个换行两个模块的包名会粘成一行，两边一起失效。
5. `src/main/AndroidManifest.xml` —— 同一个包名写进 `<queries>`。漏了不报错，但 Android 11+ 上模块
   看不见目标，应用页会把装着的应用显示成「未安装」，而且没有任何界面途径能纠正。
6. `settings.gradle.kts` 加 `include(":hookers:<name>")`。
7. `app/build.gradle.kts` 的 hookers 段加 `implementation(project(":hookers:<name>"))`。

第 4、5 步的一致性和 `scope.list` 的结尾换行由 `:checkHookerScope` 在构建期挡住（挂在
`:app:preBuild` 上）。

## hooker 代码约定

文件划分（照着 `hookers/bridgeaudio` 或 `hookers/poweramp` 抄）：

| 文件 | 放什么 |
|---|---|
| `<Name>Hooker.kt` | `AppHooker` 实现、功能/取值/预设清单、`onHook` 里的共享定位。**类 KDoc 写「这个目标是怎么破的」** —— README 明确说以那里为准，不是 README |
| `<Name>Dex.kt` | 定位。**名字优先，DexKit 锚点兜底**：类名没被混淆就直接取（不用扫 dex、启动路径零开销），取不全再扫 |
| `<Name>.kt` | 包名、类名、SharedPreferences 键等常量，以及「形状判据」扩展（`Method.isContextPredicate()` 之类） |
| 其余 | 按功能分 `internal object`，成员是 `fun HookScope.installXxx()` 扩展函数 |

* 功能挂接写成 `HookFeature(id, title, summary, install = { installXxx(refs) })`。
* **hook id 一律 `<hookerId>.<feature>[.<site>]`**（如 `bridgeaudio.lifetime.unlocked`）。这是
  EzHookTool 做原子替换时的身份，不能重复，也不能随构建变化。
* DexKit 锚点选**落盘键 / 商品 ID / 日志 TAG** 这类「改了代价大到厂商不会顺手动」的串，用
  `StringMatchType.Equals`（包含匹配会连上 R8 合并出来的共享类），命中后按方法签名形状复核 ——
  一个串常被同一个类的读、写两端同时引用，只有形状分得开。
* 定位不到时降级并 `log.w`，只有真正的硬要求才在 `onHook` 里报错。抛出会让整个 hooker 停摆，
  把其余功能一起丢掉，而那些功能往往正是目标起不来时唯一还管用的。
* 会改动目标数据的功能（写解锁标记之类）`defaultEnabled = false`，其余只作用于运行时。

## 不可动的不变量

改 `app/build.gradle.kts` 的打包配置前先看这几条，每条都对应一次静默失效：

* `packaging.jniLibs.useLegacyPackaging = false` —— LSPosed 给模块构造的 classloader 指向 APK
  **包内**路径（`base.apk!/lib/<abi>`），linker 要能从那里 mmap，`.so` 就必须未压缩且页对齐。
  设成 true 时模块自己的进程正常、目标进程里 `System.loadLibrary` 找不到库。
* `packaging.resources.merges += "META-INF/xposed/*"` —— scope.list 的合并靠它。
* `META-INF/services/**` **绝不能**进 `excludes` —— hooker 就是靠它发现的。
* `proguard-rules.pro` 里四处 keep 一个都不能删：入口类（名字只存在于 `java_init.list` 文本里）、
  ServiceLoader 两端（接口全名是资源文件名，实现全名是文件内容）、`ModuleStatus`（自检探针按方法名
  反射，且这四个方法返回编译期常量、R8 会把调用点折成常量）、`NativeHook` 的 `native <methods>`
  （`JNI_OnLoad` 按类名 + 方法名 + 签名精确 `RegisterNatives`）。

## 版本号与签名

两个值都从 HEAD 的提交算出来，不手工维护（实现在 `app/build.gradle.kts` 的「版本号」一节）：
`versionName = yyyyMMdd.HHmmss-<提交号前 8 位>`，`versionCode` = 同一时刻距 `2020-01-01T00:00:00Z`
的秒数。时间取 committer date，时区写死 `Asia/Shanghai` —— 于是同一个提交在哪台机器上编都是同一个
版本号，拿着 APK 就能 checkout 回源码。工作区脏时改用**构建时刻**并加 `-dirty` 后缀。

debug 与 release **共用同一把发布密钥**，两种构建才能互相覆盖安装（卸载会把模块设置一起带走）。
凭据从根目录 `keystore.properties` 读，CI 上用 `CHUANYI_KEYSTORE` / `_PASSWORD` / `_KEY_ALIAS` /
`_KEY_PASSWORD` 顶替（优先级更高）。拿不到凭据不打断构建：debug 退回调试密钥，release 出未签名包。

## 目录约定

* `work/`、`artifacts/` —— gitignored 的逆向作业目录，每个 case 是 jadx / apktool / unflutter 的
  全量反编译输出（单个几百 MB，可从目标 APK 重新生成）。只读参考，不在里面改代码，不要提交。
* `patcher/` —— 把 `:hookers:hills` 那套解锁静态烧进 APK 的独立链路（Python 脚本 + 注入用的
  `:patcher:runtime` dex）。**不参与 `:app` 构建**，用法见 `patcher/README.md`。
