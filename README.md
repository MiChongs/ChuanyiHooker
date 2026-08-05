# Chuanyi Hooker

A single Xposed module that hosts **one hooker per target app**. The framework
knows nothing about any particular app; hookers are self-contained Gradle
modules discovered at runtime.

| Layer | Choice |
|---|---|
| Xposed | [libxposed API **102**](https://github.com/libxposed/api) + [EzHookTool](https://github.com/lingqiqi5211/EzHookTool) `hook-xposed-102` |
| Native | [Dobby (LSPosed fork)](https://github.com/LSPosed/Dobby), vendored and built static |
| dex 扫描 | [DexKit](https://github.com/LuckyPray/DexKit) 2.2.0，按需引入（目前只有 astraflow） |
| UI | [Miuix](https://github.com/compose-miuix-ui/miuix) `miuix-ui` + `miuix-preference` + `miuix-squircle` |
| 导航 | `miuix-navigation3-ui` + `androidx.navigation3:navigation3-runtime` |
| 图片 | [Coil 3](https://github.com/coil-kt/coil) `coil-compose-core` |

## Layout

```
core/                 the SPI + runtime: AppHooker, HookFeature, HookScope,
                      registry, settings bridge, self-activation probe
native/               Dobby + JNI: symbol lookup, memory patch, constant-return
                      stubs, C++ hooker registry
                      src/main/cpp/third_party/Dobby  (vendored)
hookers/<name>/       one concrete hooker per target app
                      paisa / hills / skypulse / yamby
app/                  module APK: libxposed entry, META-INF/xposed, Miuix UI
```

## Adding an app

Three edits, no framework changes:

1. `hookers/<name>/` — implement `AppHooker`, plus two resource files:
   * `src/main/resources/META-INF/services/com.chuanyi.hooker.core.AppHooker`
     with the implementation's FQN — this is how it gets discovered;
   * `src/main/resources/META-INF/xposed/scope.list` with the target package —
     AGP's `merges += "META-INF/xposed/*"` concatenates every module's file, so
     the module's scope is the union of its hookers' scopes.
2. `include(":hookers:<name>")` in `settings.gradle.kts`.
3. `implementation(project(":hookers:<name>"))` in `app/build.gradle.kts`.

A hooker looks like this:

```kotlin
class MyHooker : AppHooker {
    override val id = "myapp"
    override val displayName = "My App"
    override val targetPackages = setOf("com.example.myapp")

    override val features = listOf(
        HookFeature("unlock", "Unlock pro", "…") { installUnlock() },
    )

    private fun HookScope.installUnlock() {
        val clazz = classOrNull("com.example.myapp.Billing") ?: error("not found")
        clazz.findMethod { name("isPro"); noParams() }
            .createReplaceHook("myapp.unlock") { true }
    }
}
```

`HookScope` gives you the target's class loader, `ApplicationInfo`, version
code, per-feature settings and a logger. EzHookTool's reflection and hook DSL
(`findMethod`, `createHook`, `createReplaceHook`, …) is already pointed at the
target class loader.

## 导航（Navigation 3）

`miuix-navigation3-ui` 0.9.3 是 **androidx Navigation 3 的 KMP 移植**（包名就叫
`androidx.navigation3.*`），只提供 UI 层；`NavKey` / `NavBackStack` /
`entryProvider` 这些运行时要另外引 `androidx.navigation3:navigation3-runtime`。
main 分支上那套自研的 `top.yukonga.miuix.kmp.nav.*` 尚未发布，0.9.3 里没有。

链路（`ui/navigation/HookerNavHost.kt`）：

```
rememberNavBackStack(configuration, Route.Home)   栈本身，可跨进程死亡恢复
  → entryProvider<NavKey> { entry<Route.X> { } }  路由 → 页面
  → rememberDecoratedNavEntries(…)                每页独立的 rememberSaveable 状态域
  → NavDisplay(entries, onBack, transitionEffects) 渲染 + 转场 + 预测式返回
```

路由是 `@Serializable sealed interface Route : NavKey`，带参数的目的地直接写成
`data class` 构造参数 —— 这就是 nav3 的类型安全传参，不经过 Bundle。

三个容易踩的点：

* **多态注册**：栈按 `NavKey` 的多态序列化存盘，sealed 层级不会自动注册。
  `SerializersModule { polymorphic(NavKey::class) { subclass(...) } }` 里漏掉哪个
  路由，进程重建时恢复到那个页面就抛 `SerializationException`。
* **必须 `data object` / `data class`**：nav3 拿路由实例当 contentKey，用 `equals`
  判定页面身份、用 `toString` 给 `rememberSaveable` 分命名空间。默认的身份
  `toString()`（`pkg.Cls@1a2b3c`）进程重建后会变，页面内状态被悄悄清空。
* **不要再写全局 BackHandler**：`NavDisplay` 自己注册了预测式返回，且只在栈深 > 1
  时拦截，栈底时返回键正常冒泡退出 Activity。

底部三个页签**不进返回栈** —— 返回栈表达深度，页签是平级；压进去会得到「返回键在
页签间来回跳」。页签是 `Route.Home` 这一个目的地的内部状态（pager 保存），只有
首页 → hooker 详情、以及首页 → 原生层这些有深度关系的才压栈。
非首个页签时的返回由 `HomeScreen` 里的 `BackHandler` 接管回到第一页。

栈只有两层是有意的。功能项除了「开/关」和一句说明再没别的可讲，单独开一页详情
只会得到一个把父页的开关重画一遍的空页面 —— 所以 hooker 详情里的功能行本身就是
开关，点哪儿都切换。同理，两个详情页原先各有一张「信息」卡（标识 / 设置键 /
实现类 / 安装时机），那些是写代码时才用得上的东西，已经去掉。

## 渐进式模糊（顶栏 / 底栏）

`miuix-blur` 的 `blur()` 只有一个半径，而渐进模糊要的是**半径沿方向递减**。
`ui/component/ProgressiveBlur.kt` 的做法是叠三层：半径逐层加大，遮罩覆盖范围逐层
收窄，叠加后越靠边越糊。

```
第 3 层  半径 28  只盖住最上面 1/3   ┐
第 2 层  半径 19  盖住上面 2/3       ├ 叠加 => 梯度
第 1 层  半径  9  盖满整条           ┘
```

每层的遮罩靠 `contentBlendMode = BlendMode.DstIn`：这个 modifier 拿**自身内容的
alpha** 去裁模糊结果，所以每层里只放一条垂直渐变的 `Spacer`，真正的栏内容画在这
些层之上——画进去就会被当成遮罩形状而不是显示出来。

采样链路（`ui/component/BlurScaffold.kt`）：

```
rememberLayerBackdrop { drawRect(surface); drawContent() }   建采样层
  → body: Modifier.layerBackdrop(backdrop)                   标记被采样的内容
  → 两条栏: ProgressiveBlurScrim(backdrop, edge)             消费采样层
```

`drawRect(surface)` 这一笔不能省：采样层只记录它所修饰的 composable 画的东西，不含
Scaffold 自己的背景。少了它，内容里的透明区域会在模糊时把周围颜色晕开，糊成色斑。

### minSdk 冲突

`miuix-blur` 的 manifest 硬写 `minSdkVersion="33"`（整套效果都建立在 API 33 才有的
`RuntimeShader` 上），而本模块要覆盖 Android 9+。处理方式是官方文档给的那条路：

```xml
<uses-sdk tools:overrideLibrary="top.yukonga.miuix.kmp.blur" />
```

然后运行时用 `isRuntimeShaderSupported()` 门控。API 28-32 上所有 blur 路径都不执行，
退化成不透明底色。合并后的 APK `minSdk` 仍是 28（`aapt dump badging` 可验证）。

设置页里还有一个手动开关（`UiPreferences.blurEnabled`）。关掉和硬件不支持走的是同一
条路：`rememberBlurBackdrop` 返回 null，采样层根本不建 —— 那是个整屏 `GraphicsLayer`，
每帧要把 body 重录一遍，建了不用纯属白烧。

## 主题与设置

设置页在首页右上角的齿轮里（`Route.Settings`）。偏好存在**本地** SharedPreferences
（`UiPreferences`，`hooker_ui`），不进 `ModuleSettings` 那份框架远程存储：界面长什么样
跟被注入的目标进程没关系，塞进去只会让每个被 hook 的应用都存一份没人读的键。

配色交给 miuix 的 `ThemeController`，两个维度拼出来：

| 深浅 | 取色 | 结果 |
|---|---|---|
| 跟随系统 / 浅色 / 深色 | 默认 | miuix 内置那套蓝 |
| 同上 | 跟随壁纸 | `platformDynamicColors`：Android 13 起读系统调色板里的种子色与风格，12 读 `system_*` 颜色角色 |
| 同上 | 自定义 | materialkolor 按你选的种子色现算，可挑 9 种调色板风格和 2021 / 2025 两代色彩规范 |

几个实现上的决定：

* **深浅在这一侧解析完再交给 controller**，用显式的 `Light`/`Dark`/`MonetLight`/
  `MonetDark`，不用 `System`/`MonetSystem`。否则「强制浅色」在深色系统上会被它自己那次
  跟随系统的判断盖掉。
* **走 `MiuixTheme(colors = …)` 而不是 `MiuixTheme(controller = …)`**：纯黑深色要在配色
  算完之后改字段，controller 那个重载中间插不进手。代价是少一个 `LocalColorSchemeMode`，
  而整个 miuix 只有 `MiuixTheme.isDynamicColor` 这个便利属性读它。
* **纯黑只改 `background` 一个字段。** 卡片和栏用的是 `surfaceContainer` / `surface`，
  保持原样才在纯黑底上分得出层次；Monet 配色里那些容器色带着壁纸色相，一起压黑等于把
  取色的效果抹掉。
* **系统栏图标明暗跟着主题走**（`WindowCompat` + `SideEffect`）。`enableEdgeToEdge()` 只在
  Activity 创建时按系统深浅设一次，改主题不会回头通知它 —— 不补这一下，深色系统上强制
  浅色主题会得到白底白图标，状态栏的时间和信号格当场消失。
* **枚举按名字存，不存序号**：序号会随枚举增删漂移，改天在中间插一个值，所有人的设置就
  悄悄变成别的选项。

改设置即时生效：`UiPreferences` 的属性是 snapshot state，主题直接读它，所以既不需要
「保存」按钮，也不需要重建 Activity。

## How the pieces fit

**Entry.** `app/…/xposed/HookerEntry.kt` is the only class in
`META-INF/xposed/java_init.list`. It wires up EzHookTool, asks `HookerRuntime`
whether the current process is interesting, and detaches from processes that are
not — so the module class loader can be collected everywhere else.

**Hot reload.** Every hook is registered from `EzXposed.onTargetReady`, and
`module.prop` sets `autoHotReload=true`. Updating the module swaps the live
hooks in place instead of requiring a force-stop of the target.

**Settings.** The UI writes through `XposedService.getRemotePreferences`; hooks
read through `XposedInterface.getRemotePreferences`. Before the service binder
arrives the UI falls back to a local store and shows a "not synced" warning,
then migrates those edits across once connected.

**Self status.** The module is in its own scope, and `HookerRuntime` replaces
`ModuleStatus.isActivated()` / `frameworkName()` inside the module's own
process. So the Status screen reports what the *hooked* side sees, not a guess.

**Native.** `NativeHook` (Kotlin) covers what you can do without writing C++:

```kotlin
val addr = NativeHook.findSymbol("libfoo.so", "check_integrity")
NativeHook.returnConstant(addr, 1)          // reversible, no code patching
NativeHook.patchMemory(addr, byteArrayOf(…))
NativeHook.readMemory(addr, 16)
```

`findSymbol` goes through Dobby's symbol resolver, so it finds non-exported
symbols that `dlsym` misses. `returnConstant` is backed by a pool of 32
pre-compiled stubs — architecture independent and undoable with `unhook`,
unlike patching a `mov x0,#imm; ret` prologue.

For a real inline hook with your own replacement, register a C++ hooker in
`native/src/main/cpp/hookers/` and call `NativeHook.install("<id>")`:

```cpp
CHUANYI_NATIVE_HOOKER(kMine, "my_hook", "what it does", Install)
```

`openat_logger` ships as a working example: it Dobby-hooks libc `openat` and
logs matching paths, which is a fast way to see what files a target touches.

**Dynamically registered JNI.** A library that binds its natives with
`RegisterNatives` from `JNI_OnLoad` exports nothing else, so `findSymbol` has no
name to resolve — and when the method names are assembled at runtime rather than
stored, they are not in the binary either. The one moment where a name and its
pointer exist side by side is the `RegisterNatives` call:

```kotlin
NativeHook.watchJniRegistrations()                       // install before the .so loads
NativeHook.returnConstantOnJniRegister(cls, "nCheck", 1) // rule, applied on registration
NativeHook.jniRegistrations()                            // what was seen: name, sig, address
```

The rule is applied by substituting the entry's `fnPtr` in the
`JNINativeMethod` array on its way through, before ART binds anything — so the
target's library is never written to, which also keeps it invisible to a native
integrity check. A rule added after the method is already bound falls back to
patching the function, and says so in the log.

The interception is one pointer in the `JNIEnv` function table. That table is
shared by every thread and its layout is fixed by the JNI spec, so this depends
on neither an ART version nor a mangled symbol; if the page cannot be made
writable it falls back to inline-hooking what the slot points at.

## Build

Requirements, all present on this machine:

* **JDK 25+** — EzHookTool 1.1.3 is compiled to Java 25 bytecode, so the Kotlin
  compiler *and* javac must run on it. Pinned in
  `gradle/gradle-daemon-jvm.properties` (`toolchainVersion=26`), which outranks
  both `org.gradle.java.home` and the IDE's Gradle JDK. **Android Studio
  rewrites that file on sync** and puts its bundled JBR 21 back — if the build
  starts failing with `invalid source release: 25`, that is why.
* **compileSdk 37** — `libxposed:api:102.0.0` declares `minCompileSdk=37`.
  The installed platform is `android-37.1`, hence `compileSdkMinor = 1`.
* **NDK 30.0.15729638 + CMake 4.1.2** for the Dobby build.

```
./gradlew :app:assembleDebug
```

Notes on the toolchain, so the settings do not look arbitrary:

* `android.builtInKotlin=false` — AGP 9's built-in Kotlin is pinned to an older
  KGP, but miuix and EzHookTool ship 2.4.10 metadata. The Kotlin plugin is
  applied explicitly instead.
* CMake 4 dropped compatibility with `cmake_minimum_required` below 3.5. The
  vendored Dobby's declaration is bumped to 3.22 and
  `-DCMAKE_POLICY_VERSION_MINIMUM=3.5` is passed as a belt-and-braces guard in
  case the vendored tree is refreshed.
* R8 is off for release: the module is reflected into by the framework and its
  hookers are found through `ServiceLoader`.

## 版本号

不手工维护，两个值都从 **HEAD 的提交**算出来：

| | 取值 | 例 |
|---|---|---|
| `versionName` | `yyyyMMdd.HHmmss-<提交号前 8 位>` | `20260805.153045-a1b2c3d4` |
| `versionCode` | 同一时刻距 `2020-01-01T00:00:00Z` 的秒数 | `208085445` |

时间取 **committer date**（不是 author date —— rebase / cherry-pick 之后只有前者会
跟着变新，`versionCode` 的单调性靠它），按 `Asia/Shanghai` 渲染。时区写死不跟系统
走，否则 CI（UTC）和本机会给同一个提交算出两个版本号。

于是**同一个提交，什么时候、在哪台机器上构建，版本号都一样** —— 手里有个 APK 就能
`git checkout` 回它对应的源码。顺带 `BuildConfig` 不随时间变，增量编译和构建缓存也
不会因为「时间又走了一秒」整片失效。

工作区脏的时候反过来，用**构建时刻**打戳并加后缀：

```
20260805.161207-a1b2c3d4-dirty     有未提交的改动
20260805.161207-nogit              不是 git 仓库 / 还没有提交（例如源码压缩包）
```

脏代码不对应任何提交，照旧用提交时间的话，两个内容不同的包会显示成同一个版本，刷进
手机后分不出装的是哪一次。干净工作区也想按构建时刻打戳（同一个提交要出好几个包）：

```
./gradlew :app:assembleRelease -PstampNow
```

不构建也能问出这次会打上的版本号，发版脚本用它：

```
./gradlew -q :app:versionInfo
versionName=20260805.153045-a1b2c3d4
versionCode=208085445
```

产物文件名跟着一起带上，落在 `app/build/outputs/apk/<variant>/`：

```
ChuanyiHooker-20260805.153045-a1b2c3d4-release.apk
```

两个取舍写在这里，省得回头怀疑是随手定的：

* 分隔符用 `-` 而不是 semver 的 `+`。`+` 在 URL 里会被解成空格，挂到 Releases 上
  下载下来名字就被改掉了。
* `versionCode` 用秒数而不是 `yyMMddHH` 这类数字拼接。它必须单调递增，否则覆盖安装
  会被 `INSTALL_FAILED_VERSION_DOWNGRADE` 挡掉；而拼接方案精确到分（`yyMMddHHmm`）
  就已经超出 int 上限了，只能退到小时粒度。秒数到 2088 年才装不下，届时挪一次纪元。

关于页只显示 `versionName`：`versionCode` 是同一时刻的另一种写法，显示两遍没有信息量。

规则实现在 `app/build.gradle.kts` 的「版本号」一节。

## 签名

主体是 **`CN=Chuanyi Tech, O=Chuanyi Tech, C=CN`**，密钥库用标准 **JKS**
（RSA 4096 / SHA256withRSA / 10000 天）。`keytool` 会提示 JKS 的加密算法已过时、
建议迁到 PKCS12 —— 这里明确选 JKS，AGP 和 apksigner 对两种格式一视同仁。

凭据从仓库根目录的 `keystore.properties` 读，CI 上用同名环境变量顶替
（`CHUANYI_KEYSTORE` / `CHUANYI_KEYSTORE_PASSWORD` / `CHUANYI_KEY_ALIAS` /
`CHUANYI_KEY_PASSWORD`，优先级高于文件）。格式与生成命令见
`keystore.properties.example`；`*.jks`、`keystore/`、`keystore.properties`
都在 `.gitignore` 里。

**debug 与 release 用同一把钥匙。** 签名一致，两种构建就能互相覆盖安装，迭代时
不必先卸载 —— 而卸载会把模块设置一起带走。想恢复成「debug 用调试密钥」，改
`app/build.gradle.kts` 里 `buildTypes.debug` 那一行即可。

拿不到凭据时**不会**打断构建：不建签名配置，debug 退回 AGP 自带的调试密钥，
release 产出未签名包，并在配置期打一条 warning。让「没有密钥」表现为一个看得见
的产物差异，而不是让只想编译一下的人先被逼着生成密钥。

签名方案只开 v2 + v3，v1（JAR 签名）关掉 —— minSdk 28 的设备装机只看 v2/v3，
v1 纯属体积。核验：

```
apksigner verify --print-certs --min-sdk-version 24 app/build/outputs/apk/release/app-release.apk
```

`--min-sdk-version` 不能省：不带它时 apksigner 按 APK 自己的 minSdk 28 判定，
只会校验 v3 并把 v2 那一行报成 `false`，看起来像 v2 没生成，其实签名块里两个都在。

## Install

1. Build and install the APK.
2. Enable the module in LSPosed. `module.prop` sets `staticScope=true`, so the
   scope comes from the merged `scope.list` — the module's own package plus one
   entry per hooker. Nothing to tick per app.
3. Reboot or force-stop the targets. The Status screen turns green once the
   framework has loaded the module into its own process.

## Bundled hooker: Paisa

`dev.hemanths.paisa` — Flutter, RevenueCat billing, Google PairIP wrapping.

All premium decisions happen in Dart inside `libapp.so`, driven by the
customer-info `Map` the RevenueCat Android SDK sends over the method channel.
That map has exactly one producer, `CustomerInfoMapperKt.map(CustomerInfo)`, so
no Dart AOT patching is needed:

| Feature | What it hooks |
|---|---|
| `lifetime` | `CustomerInfoMapperKt.map` — injects an active, non-expiring `paisa_pro` entitlement. Covers `getCustomerInfo`, `restorePurchases`, `logIn`, purchase results and the `CustomerInfoUpdated` listener at once. |
| `offline_entitlement` | `CommonKt$getCustomerInfo$1.invoke` — turns a RevenueCat lookup failure into a synthetic entitlement, so the unlock survives no network and a cold first launch. |
| `pairip` | `LicenseClient.checkLicense` **and** `initializeLicenseCheck` — the two PairIP entry points do not share code. Off by default; only matters on a re-signed APK. |
| `log_customer_info` | Logs the entitlements RevenueCat reports. |
| `native_file_log` | Dobby-hooks libc `openat`, filtered to RevenueCat/ObjectBox paths. |

The injected payload mirrors `EntitlementInfoMapperKt` / `TransactionMapperKt`
key for key. `purchases_flutter` parses it with json_serializable, so a missing
key throws and the whole CustomerInfo fails to parse — which would be worse than
not patching at all.

## Bundled hooker: SkyPulse

`com.skypulse.weather` — Compose, R8-minified, offline activation.

Nothing defends this one: no packer, no native code, no signature self-check, no
server. Activation is a single Kotlin class (`l2.l` in 3.5.25) that validates
against a key shipped inside the APK:

```
machine code = SHA-256(ANDROID_ID + "sp_dev_salt_7f3a").hex[:8].uppercase()
accepted     = base32(HMAC-SHA256("skypulse_hmac_2026_v1", machine))[:8]
```

Both constants are XOR/reverse/or-assembled at runtime instead of stored as
strings, which defeats `strings` and nothing else. Signing and verifying use the
same secret, so the device can simply be asked what it would accept — that is
`reveal_code`, and the resulting activation is the app's own, written through its
own `EncryptedSharedPreferences`, surviving the module being removed.

| Feature | What it hooks |
|---|---|
| `premium` | The private `()Z` the constructor seeds `_isPremium` from. That one `StateFlow` is upstream of every gate — settings UI, all three widget providers, both notification workers, the daily-forecast card — so one constant-return covers all of them and writes nothing. Also floors the activation timestamp so Settings does not render the epoch. |
| `reveal_code` | The machine-code getter the Settings screen calls, logging the device code and the code it accepts. Off the hot path until the user goes looking. |
| `accept_any_code` | The `(String)` validator, swapping the typed code for the one that passes so the app stores a genuine activation. Off by default, and only reachable with `premium` off — otherwise the app short-circuits to "already activated" before it compares. |

Names are resolved by *shape*, not by the 3.5.25 names: within that class each
member is the unique method with its signature (`private ():boolean`,
`public ():long`, `public ():String`, `public (String):<enum>`, and on the
companion `public (String):String`), so an R8 reshuffle in a later build does not
break it. Kotlin's `public static synthetic` accessors have to be filtered first
— one of them is `():String` and would otherwise be indistinguishable from the
machine-code getter. Only the class name is pinned, and `license_class`
overrides it without a rebuild.

`Keygen` reimplements the scheme as a fallback for when the companion cannot be
reached; `LicenseRefs.expectedCode` calls the app's own implementation first so
a changed secret is followed automatically.

## Bundled hooker: Yamby

`com.hush.yamby` 2.0.5.5 — Emby/Jellyfin client, Compose + mpv + ffmpeg.

The heaviest-protected target here, and the one where none of that protection
matters. R8 renaming, StringFog XOR string encryption and Arabic-presentation-form
class names sit on top of **nmmp (dex2c)**, which lifts every app package
(`cd` / `ad` / `dd` / `pc` / `sc` / `lc` / `ld`) into `libnmmp.so` — the Pro
decision itself is not in the DEX at all, so neither smali patching nor reading
the decompiled source gets anywhere near it.

All of it faces inward. The entitlement has no self-evidence: no `SHA1withRSA`
over the purchase payload, no licence server, no receipt round-trip. The app
believes whatever Play Billing reports, and that arrives over the **legacy AIDL**
path, whose reply is a plain Java `Bundle`:

```
IInAppBillingService.getPurchases() -> Bundle{ RESPONSE_CODE + 3 parallel lists }
  -> new Purchase(dataList[i], signatureList[i])        billing library, plain Java
    -> the app's PurchasesResponseListener              nmmp-native
      -> Pro, cached in emby_setting/validBefore
```

One extra entry in those three lists is the whole unlock, so `lifetime` needs no
class name at all — the Bundle keys are Play Billing protocol. The app then
persists the verdict itself, which is why the unlock survives the module being
removed.

Products, read out of the live catalogue: `pro_lifetime_discount` (one-time,
"Lifetime Pro") and `yamby_subscription` (monthly / quarterly / yearly base
plans). `product_id` overrides the former without a rebuild.

| Feature | What it hooks |
|---|---|
| `force_entitlement` | The `(String, boolean)` accessors on `MMKV`, forcing `validBefore` — the app's own cached verdict, recovered from the delegated-property metadata R8 leaves behind (`getValidBefore()Z`, next to `lastTime` / `failedTime`). The billing query only runs when the user opens the subscription page, so without this the first launch shows Pro as off; forcing the cached read makes it true before anything composes. Read-side only, nothing is written. |
| `lifetime` | `Bundle.getStringArrayList` for the three `INAPP_PURCHASE_*` lists, appending one owned `pro_lifetime_discount` to each. Returns a copy — the library reads the same Bundle twice, and mutating in place desynchronises the three lists into an `IndexOutOfBoundsException`. |
| `billing_resilient` | `Bundle.get("RESPONSE_CODE")` and `Bundle.containsKey`, so a Play error or a reply missing the list keys still reaches the parse path. |
| `announce` | The listener's *constructor*, to capture the instance, then invokes its delivery method directly after the client connects. The method is `native` (nmmp compiled the body away) but this only *calls* it, which reflection does to a native method exactly as to any other. |
| `log_billing` | `Purchase.<init>`, whose two arguments are Play's raw JSON and signature — R8 inlined every getter, so the constructor is the only readable seam. |

`BillingResult` cannot be constructed reflectively: R8 elided its `<init>` and the
builder `new`s it while calling `Object.<init>` directly. `Billing.okResult` goes
through that builder instead, found by shape — one static no-arg factory on
`BillingResult`, one no-arg method on the result that hands a `BillingResult` back.

Only the listener class name is pinned (as `\uXXXX` escapes, so the source stays
ASCII), it is verified by shape before use, and `listener_class` overrides it.
Everything else is name-free.

## Bundled hooker: AstraFlow

`com.astraflow.tool` v1.31 — 星流, a dynamic-island / fluid-cloud enhancer that is
**itself an Xposed module** (libxposed 101). Its paid features do not render in its
own process: they run inside `com.android.systemui` and a set of ColorOS packages.

That split is the whole story, because the same entitlement is enforced by two
gates of very different strength.

**The app-process gate** (`EntitlementVault`) demands all of: a server-signed
device pass whose signature checks out inside `libastraflow_verify.so`, a device
fingerprint matching the hardware serial read through root, a verification no
older than 7 days, the right feature bit, and a field-by-field match against the
locally mirrored copy. The payload is signed with a key we do not have, so this
one can only be **bypassed**, never satisfied.

**The injected-process gate** cannot do any of that — it lives in SystemUI, where
the app's native library is not loaded. So it degrades to a plain-Java check:

```
expected = <sign>(passState, passClass, deviceIdHash, featureBits,
                  expiresAt, passGeneration, revocationGen, role, lastVerifyAt)
expected == storedHmac && lastVerifyAt > 0 && now - lastVerifyAt < 48h
  && passClass == "permanent" && passState == "permanent_active"
  && (featureBit & featureBits) != 0
```

HmacSHA256 with a key assembled from two static byte arrays in the DEX. No native
verification, no server round-trip — **that credential is forgeable, self
consistently**. The general lesson: when a protection has to cross a process
boundary, look at the far side first; it is the side that cannot reach the
native library.

Which is why this hooker never enters SystemUI. Everything happens in the app's
own process: `device_pass` calls **the app's own signing function** and writes a
permanent credential into the shared preferences the injected side reads. Trying
to hook SystemUI directly would mean chasing AstraFlow's `LspModuleClassLoader`
through `ClassLoader.loadClass` — our `HookScope.classLoader` there is SystemUI's,
which cannot see AstraFlow's classes at all.

Two details make the credential maintenance-free:

* `deviceIdHash` is only an **input to the HMAC** on that side, never compared
  against the live device — so a placeholder works when the hardware serial is
  unreadable, as long as the same value is used for signing.
* `lastVerifyAt` is written **in the future**. The check is `now - lastVerifyAt <
  48h`; a future timestamp makes that difference negative, so the credential never
  goes stale and never needs re-writing.

Paid features and their bits — `resident_weather_cloud` is free, the rest sum to
31, which is what the forged credential carries:

| Feature | id | bit |
|---|---|---|
| 录屏 | `screen_recording` | 1 |
| 常驻流体云 | `resident_fluid_cloud` | 2 |
| 歌词滚动 | `lyrics_scroll` | 4 |
| 动态背景 | `dynamic_background` | 8 |
| 手势侧边栏 | `gesture_sidebar` | 16 |

| Feature | What it hooks |
|---|---|
| `unlock` | The vault's per-feature verdict, forced true. Found without any renamed name: `AstraFlowApp.getEntitlementVault()` keeps its name, its **return type** is the vault class, and on that class the verdict is the only `boolean(<enum>)` method. |
| `device_pass` | The vault getter, to write the forged credential once the shared preferences exist. The HMAC comes from the app's own signer, so key, message layout and truncation follow the target across updates. |
| `lifetime` | `MeResponse` / `DevicePassInfo` constructors — the API models keep their field names, so `tier` / `expiresAt` / `state` / `passClass` are set directly on the freshly built instance. |
| `stardust` | `StarDustInfo`'s constructors. The balance is an immutable data class rebuilt on every refresh, so overwriting the construction result covers every consumer. |
| `native_verify` | `NativeVerify`'s two `boolean` natives, through the `RegisterNatives` watch. Off by default. |
| `log_entitlement` | The same verdict method, after — prints what was asked and what was answered, plus the native methods that were captured. |

All reflection goes through **KavaRef 1.1.0** (`kavaref-core` + `kavaref-extension`
+ `kavaref-android`; the last one was split out in 1.1.0 and its absence surfaces
as a `NoClassDefFoundError` in the target's clinit, not as a compile error).

### Finding the signer without a name

The signer is the load-bearing piece — the mirror HMAC has to be computed by the
*app's own* implementation, or key, message layout and truncation stop following
the target across updates. But its class is in the default package and R8 renames
it every build (`Xq` in 1.31), so pinning the name means reopening jadx after
every update.

`AstraFlowDex` scans for it instead, anchored on the string
**`astraflow-device-pass`**. That literal is part of the HMAC message, so client
and server have to agree on it — it is steadier than any class name. Paired with
the nine-parameter signature, which is unique in the package, the hit is
unambiguous:

```kotlin
dex.findMethod {
    matcher {
        modifiers = Modifier.STATIC
        returnType = "java.lang.String"
        paramTypes("java.lang.String", …, "long")
        usingStrings("astraflow-device-pass")
    }
}
```

Four routes, in order: the `signer_class` setting, the previous scan cached by
versionCode, the DexKit scan, then the pinned name. The cache lives in the
target's own data dir (`chuanyi_hooker_dex`) — a scan costs a few hundred
milliseconds on the launch path, and the module's remote preferences are
read-only from a hooked process. The pinned name is kept as the last route so a
device where `libdexkit.so` has no matching ABI still works.

### The native gate

`libastraflow_verify.so` is real: it verifies the server-signed device pass,
derives the device fingerprint, digests the APK signature and signs outgoing
requests. `unlock` steps around it; `native_verify` goes through it instead.

Getting at it needs the `RegisterNatives` watch, because the library exports only
`JNI_OnLoad` **and** assembles its method names at runtime — `strings` on it
yields `ss1_v1`, `mfin_v1`, `pubk_v1`, never `nVrfFinal`. So there is no name for
`DobbySymbolResolver` to look up, and the only place a name meets its pointer is
the registration call.

Only the two `boolean` returners are forced — `nVrfFinal` (the last of the
three-step verify, and what `NativeVerify.COM2` returns) and `nVerifyEnt`. The
others on that class return `byte[]` or `String`; a constant stub can only put a
register-width value in place, and handing Java a fabricated object reference
crashes it. They run for real, which is fine: `nVrfStep1` / `nVrfStep2` only fold
the payload and signature into an intermediate, and the verdict is `nVrfFinal`.

What this buys, precisely: the signature check is the one condition in the
app-process gate that cannot be forged, and it stops being a condition. The rest
of that gate is still there — a `device_pass_v1` JSON payload that parses and
matches the mirror field for field, and a `pass_device_id_hash` equal to the live
fingerprint, which needs root to read the hardware serial. So this does not
replace `unlock` on its own; it removes the part that no amount of Java can
handle, and it also stops an invalid server payload from tripping the vault's
self-wipe.

### Known gaps

* Whether the 星尘 badges on the fluid-cloud list are static labels or live state
  is **unverified** — it needs a SystemUI restart through the app's own
  「重启作用域」 and then looking at the capsule.
* `native_verify` is **built but not exercised on a device**. The registration
  capture logs every `NativeVerify` method it sees (turn on `log_entitlement`),
  which is the first thing to check.
* Satisfying the app-process gate end to end — forged `signed_payload` plus a
  Java hook on `NativeVerify.by_item()` to cover an unrooted device — is the
  remaining step if `unlock` ever stops being enough.

## Bundled hooker: Gboard

`com.google.android.inputmethod.latin` 17.8.4 —— 解开剪贴板的三处硬限制，
再让按键的上下滑可以自定义。

剪贴板那三处的位置差别很大，做法也就不同：

| 限制 | 原版 | 它在哪 | 怎么改 |
|---|---|---|---|
| 最近保留几条 | 5 | 装填列表的 `Callable` 里，内联常量 | 整个替换那段装填逻辑 |
| 有效期 | 1 小时 | 同上（查询的时间下限）+ 过期清理 | 同上，清理也一并接管 |
| 单条字数 | 20000 | Phenotype flag | 直接覆盖 flag 取值 |

前两条落在同一段代码里，所以共用一次接管；只开其中一项时，另一项按原版默认值传进去。

### 名字全部靠形状认

这一包被 R8 重命名得很彻底（`ClipboardAdapter` -> `fki`），但**每个类的 Flogger
TAG 里留着原始全限定名**：

```java
wbu.i("com/google/android/apps/inputmethod/libs/clipboard/ClipboardContentProviderUtils")
```

那是 Google 内部日志系统的定位信息，跨版本不变，比任何混淆后的名字都稳。DexKit 按它
找类，再按签名认成员。两个坑：

* `usingStrings` 是**包含**匹配，而 R8 会把一堆 lambda 合并进共享类，那些类身上
  也带着原主人的 TAG —— 光凭串会命中好几个且顺序不定，所以每次查找都要附一个
  「这个类身上得真有我要的成员」的验证。
* **DexKit 2.x 不再自己 `loadLibrary`**，漏掉的表现不是「找不到 so」而是调用时报
  `No implementation found for … nativeInitDexKit`，看起来像版本不匹配。在被注入的
  进程里还要多一层：`System.loadLibrary` 按调用类的 classloader 找库，而模块
  classloader 未必带着原生库路径，所以要能退回「问 classloader 要绝对路径再装」。

扫一次要几百毫秒，而这段代码跑在**输入法的启动路径**上，所以结果按 versionCode
缓存在目标自己的数据目录里。

### 过期项其实没被删

清理那一步在总条数攒到 120 时才跑，删的是普通项里超过一小时的 —— 也就是说日常使用
（不到 120 条）里过期项**并没有真被删**，只是查询时被时间下限挡住。所以延长有效期
本身不需要动它；但一旦条数过线，原版会按它那一小时的尺度把用户想留的东西删掉，
所以有效期改长了就得把清理也换成同一把尺子。

### 上下滑：拦查询，不改数据

Gboard 的触摸状态机判定滑动方向时**只看几何**，不检查这个键有没有定义上滑；真正决定
「上滑发生了什么」的是下一步按动作取 `ActionDef`。所以第一版做的是在 `SoftKeyDef`
构造后往它的动作表里补一条 —— 装得上、构造也确实拦到了，**但滑动依旧走滑行输入**：
被查询的并不是改过的那个实例，布局那一层会复用、重建 `SoftKeyDef`，改动没跟过去。

改成拦「按动作取 `ActionDef`」这一步之后就稳了，无论实例是哪个都绕不开。`SoftKeyDef`
上这样的方法有两个（一个严格查找、一个查不到时回退到按下），**两个都要挂**：

* 回退那个是实际派发用的，不挂就不会输出指定的内容；
* 严格那个是**滑行输入的判据** —— 它返回 null 时 Gboard 认为「这个键没有纵向滑动」，
  于是把纵向滑动当成滑行输入的起手。这正是上滑打不出字、反而冒出联想词的原因。

查询发生在触摸事件路径上，所以结果按 键+方向 缓存，滑动过程中不再分配。

三条规矩：用户在映射表里设过就覆盖；没设过但目标本来就有就不动（那是布局作者的
安排，字母键原本上滑出的是长按第一个候选，`z` 上滑出 `ź`）；两者都没有才拿长按
候选补。长按候选的第一条常是「打开候选弹窗」这类控制码（`data` 为 null），取候选
时要先滤掉，否则补上去的是一个什么都不输出的动作。

⚠️ 用 `adb shell input swipe` 测这个功能会**得到错误结论**：模拟手势的轨迹特性会被
判成滑行输入，真手指走的是另一条路。要么真手滑，要么看
`滑动查询 …… 改写=true` 那条日志。

| Feature | What it hooks |
|---|---|
| `clip_count` | 装填剪贴板列表的 `Callable`，整段替换 —— 查询、分段、组装全部自己来。列表结构（段头位置、顺序）与原版一致，下游 adapter 靠 `indexOf(段头)` 反算每段条数，错一位整个面板就乱。 |
| `clip_ttl` | 同上那段（查询的时间下限），外加过期清理那个 `Callable`。 |
| `clip_chars` | `text_clip_item_char_limit` 这个 flag。不用 hook：flag 对象自带一个把取值写进最高优先级档位的入口，直接调它，之后所有读取点拿到的都是新值。 |
| `slide_up` / `slide_down` | `SoftKeyDef` 上两个「按动作取 ActionDef」的方法。 |

数的是**复制次数**而不是条数：一次复制被实体识别拆成的电话、网址共享一个时间戳，
只算一组 —— 跟原版语义一致，所以默认值 5 就是原版行为。
