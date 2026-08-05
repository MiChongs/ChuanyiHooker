import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

plugins {
    alias(libs.plugins.android.application)
    // Separate from kotlin-android (which AGP 9 provides itself) and still
    // required whenever buildFeatures.compose is on.
    alias(libs.plugins.kotlin.compose)
    // Navigation 3 back stacks are persisted through kotlinx-serialization, so
    // the route hierarchy has to be @Serializable.
    alias(libs.plugins.kotlin.serialization)
    // 关于页那份开源许可清单的来源。见下方 aboutLibraries {} 块。
    alias(libs.plugins.aboutlibraries.android)
}

// ---------------------------------------------------------------------------
// 发布签名
//
// 凭据来自仓库根目录的 keystore.properties（不进版本库），CI 上用同名环境变量顶替。
// 两者都拿不到时**不建**签名配置：debug 退回 AGP 自带的调试密钥，release 产出未签名
// 包。让「没有密钥」表现为一个看得见的产物差异，而不是配置期直接把构建打断 —— 只想
// 编译一下的人不该被逼着先生成密钥。
// ---------------------------------------------------------------------------
/** 签名配置名。debug 与 release 共用同一把发布密钥，见 buildTypes。 */
val signingConfigName = "chuanyi"

val signingProps = Properties().apply {
    rootProject.file("keystore.properties")
        .takeIf { it.isFile }
        ?.inputStream()
        ?.use(::load)
}

fun signingValue(key: String, env: String): String? =
    (System.getenv(env) ?: signingProps.getProperty(key))?.takeIf { it.isNotBlank() }

// storeFile 相对路径按仓库根目录解析，绝对路径原样使用 —— 想把私钥挪到仓库外面时
// 只改这一个值就够了。
val releaseKeystore: File? = signingValue("storeFile", "CHUANYI_KEYSTORE")
    ?.let { path -> File(path).takeIf(File::isAbsolute) ?: rootProject.file(path) }
    ?.takeIf(File::isFile)
val releaseStorePassword = signingValue("storePassword", "CHUANYI_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "CHUANYI_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "CHUANYI_KEY_PASSWORD")
val canSign = releaseKeystore != null && releaseStorePassword != null && releaseKeyAlias != null

if (!canSign) {
    logger.warn(
        "未找到签名凭据（keystore.properties 或 CHUANYI_KEYSTORE* 环境变量），" +
            "debug 用调试密钥，release 产物不签名。参见 keystore.properties.example。",
    )
}

// 每新增一个 hooker 都要在两处写同一个包名（注入用的 scope.list、包可见性用的
// <queries>），漏了后者是静默失败 —— 应用页会把装着的目标显示成「未安装」。
// 挂在 preBuild 上，这个错就出不了本机。校验本身见根 build.gradle.kts。
tasks.named("preBuild") { dependsOn(":checkHookerScope") }

// ---------------------------------------------------------------------------
// 版本号：提交时间 + 提交号
//
//     versionName   20260805.153045-a1b2c3d4         工作区干净
//                   20260805.161207-a1b2c3d4-dirty   有未提交的改动
//                   20260805.161207-nogit            不是 git 仓库 / 还没有提交
//     versionCode   该时刻距 2020-01-01T00:00:00Z 的秒数，如 208085445
//
// 时间取的是 **HEAD 的提交时间**，不是构建时刻：同一个提交今天编、下周编、换台
// 机器编，版本号都一样 —— 手里有个包就能 checkout 回它对应的源码。顺带 BuildConfig
// 不会每次构建都变，增量编译和构建缓存也就不会因为「时间又走了一秒」整片失效。
//
// 工作区脏的时候反过来，用构建时刻：那份代码不对应任何提交，再拿提交时间当版本号，
// 两个内容不同的包会显示成同一个版本 —— 刷进手机后分不出装的是哪一次。而脏构建
// 本来就在改代码、本来就要重编，版本号跟着变不额外花钱。
//
// 干净工作区也想按构建时刻打戳（同一个提交要出好几个包）：
//     .\gradlew.bat :app:assembleRelease -PstampNow
//
// versionCode 必须单调递增，否则覆盖安装会被 INSTALL_FAILED_VERSION_DOWNGRADE 挡掉。
// 秒数天然单调，而且和 versionName 里的时间是同一个东西的两种写法（所以关于页只显示
// versionName，不再重复显示一遍 versionCode）。Int 到 2088 年才装不下。
// ---------------------------------------------------------------------------

/** versionCode 的纪元：2020-01-01T00:00:00Z。改它会让版本号整体倒退，不要动。 */
val versionCodeEpochSeconds = 1_577_836_800L

/**
 * 版本号里的日期按这个时区渲染。写死而不是跟系统走 —— CI 上是 UTC，跟系统走的话同一个
 * 提交在两台机器上会得出两个版本号，「同一提交 = 同一版本」当场不成立。
 */
val versionZone: ZoneId = ZoneId.of("Asia/Shanghai")

/**
 * 构建时刻（Unix 秒）。走 ValueSource 而不是直接 `Instant.now()`：配置缓存会把配置期
 * 算出的普通值原样存下来，之后每次命中缓存拿到的都是缓存那一刻的时间，脏构建就又分不
 * 出彼此了。ValueSource 每次构建开始都会重新求值 —— 这正是 Gradle 判断缓存还算不算数
 * 的机制。
 */
abstract class BuildEpochSeconds : ValueSource<Long, ValueSourceParameters.None> {
    override fun obtain(): Long = System.currentTimeMillis() / 1000L
}

/**
 * 跑一条 git 命令取标准输出，失败返回 null。没装 git、不是仓库、命令报错一律走 null
 * 分支 —— 从压缩包解出来的源码也应该能直接编，不该被版本号卡住。
 */
fun gitOrNull(vararg args: String): String? {
    val exec = providers.exec {
        workingDir = rootProject.projectDir
        commandLine("git", *args)
        isIgnoreExitValue = true
    }
    return runCatching {
        val text = exec.standardOutput.asText.get()
        text.trim().takeIf { exec.result.get().exitValue == 0 && it.isNotEmpty() }
    }.getOrNull()
}

// %ct 是 committer date 的 Unix 秒，不是 %at（author date）—— rebase / cherry-pick 之后
// 只有前者会跟着变新，versionCode 的单调性靠它。%H 取完整 hash 自己截 8 位：`%h`
// 的长度由 core.abbrev 决定，会随仓库变大而变长，那样版本号的长度就不稳定了。
val headCommit = gitOrNull("log", "-1", "--format=%ct %H")?.split(" ")
val headEpochSeconds = headCommit?.getOrNull(0)?.toLongOrNull()
val headShortHash = headCommit?.getOrNull(1)?.take(8)

// --porcelain 有输出即为脏。work/ 那二十几万个文件被 .gitignore 整目录挡住，git 不会
// 往里递归，所以这条命令在这个仓库里也是毫秒级。
val isWorkTreeDirty = headShortHash != null && gitOrNull("status", "--porcelain") != null

val versionStampSeconds: Long = headEpochSeconds
    ?.takeUnless { isWorkTreeDirty || providers.gradleProperty("stampNow").isPresent }
    ?: providers.of(BuildEpochSeconds::class) {}.get()

val moduleVersionCode = (versionStampSeconds - versionCodeEpochSeconds)
    .also {
        if (it !in 1..Int.MAX_VALUE.toLong()) {
            throw GradleException(
                "versionCode 越界（$it）：打戳时刻 " +
                    "${Instant.ofEpochSecond(versionStampSeconds)} 不在 2020-01-01 和 2088 年之间。" +
                    "系统时钟不对，或者该换 versionCodeEpochSeconds 这个纪元了。",
            )
        }
    }
    .toInt()

val moduleVersionName = buildString {
    append(
        DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss")
            .withZone(versionZone)
            .format(Instant.ofEpochSecond(versionStampSeconds)),
    )
    append('-').append(headShortHash ?: "nogit")
    if (isWorkTreeDirty) append("-dirty")
}

// 给发版脚本 / CI 用：不构建也能拿到这次会打上的版本号。
//     .\gradlew.bat -q :app:versionInfo
tasks.register("versionInfo") {
    group = "help"
    description = "打印本次构建会使用的 versionName / versionCode"
    // 捕成局部量而不是在 doLast 里读 project，配置缓存才认。
    val name = moduleVersionName
    val code = moduleVersionCode
    doLast {
        println("versionName=$name")
        println("versionCode=$code")
    }
}

android {
    namespace = "com.chuanyi.hooker"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.chuanyi.hooker"
        minSdk = 28
        targetSdk = 37
        // 见上面「版本号」那节：两个值都由 HEAD 的提交时间算出来，不手工维护。
        versionCode = moduleVersionCode
        versionName = moduleVersionName
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (canSign) {
            create(signingConfigName) {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                // JKS 允许 key 密码与 store 密码不同；相同是常态，省掉一行配置。
                keyPassword = releaseKeyPassword ?: releaseStorePassword

                // minSdk 28，装机只看 v2/v3 签名块，v1（JAR 签名）纯属体积。
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        // debug 也用同一把钥匙。签名一致，debug 与 release 就能互相覆盖安装，
        // 迭代时不必先卸载 —— 而卸载会连模块设置一起清掉。没有密钥时自动退回
        // 调试密钥。
        debug {
            signingConfig = signingConfigs.findByName(signingConfigName)
                ?: signingConfigs.getByName("debug")
        }
        release {
            // 这个包的绝大部分体积是 Compose + miuix + coil，用到的其实是很小一部分。
            // 不开 R8 的话 dex 有 26 MB，开了以后剩个零头。
            //
            // 「Xposed 模块不能开 R8」的说法针对的是**没有对应 keep 规则**的情况：
            // 入口类写在 java_init.list 里、hooker 走 ServiceLoader、原生层按名字
            // RegisterNatives、自检探针按方法名反射 —— 这四处 R8 都看不见。
            // proguard-rules.pro 逐条钉住了它们，并在文件末尾附了实机验证清单。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName(signingConfigName)
        }
    }

    // 界面全是硬编码中文，miuix / androidx 带进来的其余语种资源用不上。
    androidResources {
        localeFilters += listOf("zh", "en")
    }

    // Play 用的依赖清单签名块。模块是自分发的，留着只是体积。
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            // Each hooker module ships its own META-INF/xposed/scope.list;
            // merging is what makes "add a module, get its scope" work.
            merges += "META-INF/xposed/*"
            excludes += setOf(
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "kotlin/**",
                "DebugProbesKt.bin",
                // androidx 各 artifact 各带一份同样的 Apache-2.0 全文
                "META-INF/androidx/**",
                // kotlinx 的构建校验元数据，运行时没人读
                "META-INF/org/jetbrains/**",
                "META-INF/version-control-info.textproto",
                "kotlin-tooling-metadata.json",
            )
            // 注意：META-INF/services/** 绝不能进这个列表，hooker 就是靠它发现的。
        }
        jniLibs {
            // 必须是 false。LSPosed 给模块构造的 LspModuleClassLoader，其
            // nativeLibraryDirectories 指向的是模块 APK 的**包内**路径：
            //
            //     /data/app/.../com.chuanyi.hooker-.../base.apk!/lib/arm64-v8a
            //
            // 而不是安装时解压出来的 /data/app/.../lib/arm64/。linker 要能直接从
            // APK 里 mmap，.so 就必须是未压缩且页对齐地存放（extractNativeLibs=false）。
            //
            // 设成 true 会让 .so 被 DEFLATE 压缩，包内路径下就找不到它，目标进程里
            // System.loadLibrary 抛 "couldn't find libchuanyihook.so"，而模块自己的
            // 进程反而正常——因为宿主 classloader 用的是解压后的目录。
            useLegacyPackaging = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
}

// ---------------------------------------------------------------------------
// 产物文件名
//
// 默认叫 app-release.apk，攒几次构建就彼此没有区别了 —— 装到手机上想回头确认「这个包
// 是哪次编的」只能靠文件时间。带上版本号之后，文件名本身就够定位到源码，也不必再靠
// 目录去区分 debug / release。
//
//     ChuanyiHooker-20260805.153045-a1b2c3d4-release.apk
//
// 版本号里不用 `+`（semver 的构建元数据分隔符）就是为了这一步：`+` 在 URL 里会被解成
// 空格，挂到 Releases 上下载会拿到一个名字被改掉的文件。
// ---------------------------------------------------------------------------
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("ChuanyiHooker-$moduleVersionName-${variant.name}.apk")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

// ---------------------------------------------------------------------------
// Compose 编译器
//
// 稳定性配置把几个「实际上不可变、但编译器推断不出来」的外部类型标成 stable。
// 一个 composable 只要有一个不稳定参数，它和它的整棵子树就永远不可跳过 ——
// 状态一变就全量重组。
//
// 哪些类型该进那个文件不靠猜，靠编译器报告：
//
//     .\gradlew.bat :app:assembleRelease -PcomposeReports
//     产物在 app/build/compose_reports/：
//       *-composables.txt   每个 composable 是否 skippable / restartable
//       *-classes.txt       每个类的稳定性判定和判成不稳定的具体字段
// ---------------------------------------------------------------------------
composeCompiler {
    stabilityConfigurationFiles.add(
        layout.projectDirectory.file("compose_stability.conf"),
    )

    if (providers.gradleProperty("composeReports").isPresent) {
        val reports = layout.buildDirectory.dir("compose_reports")
        reportsDestination.set(reports)
        metricsDestination.set(reports)
    }
}

// ---------------------------------------------------------------------------
// 开源许可清单
//
// 「关于 → 开源许可」那一页的数据源。插件按 variant 遍历运行时依赖图，读每个
// artifact 的 pom，产出
//
//     build/generated/aboutLibraries/<variant>/res/raw/aboutlibraries.json
//
// 并把那个 res 目录挂进同名 variant 的源集 —— 所以 R.raw.aboutlibraries 直接可用，
// 不用手工把 json 拷进 src/main/res，也不会因为改了依赖忘了重新导出而过期。
//
// 手动维护这份名单是不可能维护对的：光一个 compose-bom 展开就是几十个 artifact。
//
// 导出到别处（做合规审计）用现成任务，不必改配置：
//     .\gradlew.bat :app:exportLibraryDefinitionsRelease
//     .\gradlew.bat :app:exportLibrariesRelease          # CSV 到标准输出
// ---------------------------------------------------------------------------
aboutLibraries {
    // 联网（默认值，这里写出来是因为它决定了这一页有没有意义）：pom 里通常只有
    // 许可名和一个 URL，正文要去 spdx.org 取。关掉的话 licenseContent 全是空，
    // 页面就只剩「Apache-2.0」这么一行字，起不到附带许可全文的作用。
    // 断网构建时插件只是取不到正文，不会让构建失败。
    offlineMode = false

    collect {
        // compose-bom 这类 platform 依赖只是版本约束，不会有代码进 APK，
        // 列进「本应用使用了这些开源软件」是错的。
        includePlatform = false
    }

    export {
        // 这份 json 要进 APK，缩进纯属体积。
        prettyPrint = false
        // 赞助入口跟许可合规无关，占的还是每个 artifact 的位置。
        excludeFields.add("funding")
    }

    license {
        // 把 pom 里五花八门的写法（"The Apache Software License, Version 2.0"
        // 之类）归一到 SPDX id，列表里才能按许可归类而不是按字符串。
        mapLicensesToSpdx = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":native"))

    // --- hookers ---------------------------------------------------------
    // One line per target app. Discovery and scope are automatic.
    implementation(project(":hookers:paisa"))
    implementation(project(":hookers:hills"))
    implementation(project(":hookers:skypulse"))
    implementation(project(":hookers:yamby"))
    implementation(project(":hookers:astraflow"))
    implementation(project(":hookers:capyplayer"))
    implementation(project(":hookers:flix"))
    implementation(project(":hookers:secretshoot"))
    implementation(project(":hookers:gameclick"))
    implementation(project(":hookers:zenneko"))
    implementation(project(":hookers:gifshop"))
    implementation(project(":hookers:instashot"))
    implementation(project(":hookers:esj"))
    implementation(project(":hookers:chuckle"))
    implementation(project(":hookers:womic"))
    implementation(project(":hookers:airmusic"))
    implementation(project(":hookers:gboard"))
    // ---------------------------------------------------------------------

    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // LocalLifecycleOwner + repeatOnLifecycle 的 Compose 版，用来在返回前台时
    // 复查权限（用户可能是去系统设置里授的）
    implementation(libs.androidx.lifecycle.runtime.compose)

    // 运行时权限。这里只需要一个：读取已安装应用列表
    implementation(libs.xxpermissions)
    implementation(libs.devicecompat)

    // root shell。用来 force-stop 目标进程 —— 没有任何非 root 途径能停掉别的应用，
    // 而「改完开关要重启目标」是这个模块最高频的操作。
    implementation(libs.libsu.core)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)

    implementation(libs.miuix.squircle)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.shader)

    // miuix 的 NavDisplay / Scene（androidx Navigation3 的 KMP 移植）
    implementation(libs.miuix.navigation3.ui)
    // 运行时：NavKey / NavBackStack / entryProvider / NavEntry
    implementation(libs.navigation3.runtime)
    // 返回栈条目级的 ViewModelStore。目前这个模块一个 ViewModel 都没有，引它是为了
    // 把 nav3 的条目作用域补全 —— 以后任何一屏想要「跟着这一条返回栈活、出栈就死」
    // 的状态，直接 viewModel() 就有，不用再回来改导航宿主。
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.kotlinx.serialization.core)

    implementation(libs.coil.compose.core)

    // 关于页的开源许可清单。只要数据层，界面用 miuix 自己画（官方 compose-m3
    // 那套是 Material 长相，混进来会很突兀）。
    implementation(libs.aboutlibraries.core)
}
