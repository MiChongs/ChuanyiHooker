import java.io.File
import java.util.Properties

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

android {
    namespace = "com.chuanyi.hooker"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.chuanyi.hooker"
        minSdk = 28
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"
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
