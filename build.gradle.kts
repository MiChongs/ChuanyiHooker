// AGP 9 has Kotlin support built in and pins it to an older KGP. miuix and
// EzHookTool ship 2.4.10 metadata, which that compiler cannot read
// ("The actual metadata version is 2.4.0, but the compiler version ... can read
// versions up to 2.3.0"). Forcing kotlin-stdlib does nothing — the compiler is
// what has to move — so raise it on the buildscript classpath, which is the
// supported way to override built-in Kotlin.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Hardcoded: version catalog accessors are not available inside
        // buildscript {}. Keep in sync with `kotlin` in gradle/libs.versions.toml.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // 只在 :app 应用（关于页的开源许可清单），这里声明是为了把版本收口到
    // 版本目录里，跟其余插件保持一致。
    alias(libs.plugins.aboutlibraries.android) apply false
}

// ---------------------------------------------------------------------------
// hooker 声明一致性校验
//
// 每个 hooker 要把同一个包名写在**两处**，两处管的是完全不同的事：
//
//   META-INF/xposed/scope.list   注入哪个进程（LSPosed 读）
//   AndroidManifest <queries>    模块自己能不能**看见**这个包（系统读）
//
// 漏掉后者不会有任何报错：hook 照常生效，只是模块界面里 `getPackageInfo` 被包可见性
// 过滤掉，抛出的 NameNotFoundException 和「真没装」一模一样 —— 应用页把一个装着的
// 目标显示成「未安装」，而「允许读取应用列表」那张提示卡在权限已授予时根本不出现，
// 于是这个状态没有任何界面途径可以纠正。查起来会一路查到权限上去，而根因和权限无关。
//
// 这类错误只可能在新增 hooker 时犯，且一犯就是静默的，所以放到构建期挡住。
// ---------------------------------------------------------------------------
val hookersDir = layout.projectDirectory.dir("hookers")

val checkHookerScope = tasks.register("checkHookerScope") {
    group = "verification"
    description = "校验每个 hooker 的 scope.list 都在自己 manifest 的 <queries> 里声明了包可见性"

    val root = hookersDir.asFile
    inputs.files(
        fileTree(root) {
            include("*/src/main/AndroidManifest.xml")
            include("*/src/main/resources/META-INF/xposed/scope.list")
        },
    ).withPropertyName("hookerDeclarations")

    val stamp = layout.buildDirectory.file("reports/hooker-scope.txt")
    outputs.file(stamp)

    doLast {
        // `<package android:name="…"/>`。这些 manifest 只有一个 <queries> 块、
        // 十来行，用正则比拖一个 XML 解析器进构建脚本划算。
        val packageEntry = Regex("""<package[^>]*android:name\s*=\s*"([^"]+)"""")
        val problems = mutableListOf<String>()
        val report = StringBuilder()

        root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { module ->
            val scopeFile = module.resolve("src/main/resources/META-INF/xposed/scope.list")
            if (!scopeFile.isFile) return@forEach

            val raw = scopeFile.readText()
            // 合并是**拼接**：末尾少一个换行，两个模块的包名会被粘成一行，
            // 于是两个作用域一起失效。README 里写过，这里挡住。
            if (raw.isNotEmpty() && !raw.endsWith("\n")) {
                problems += "hookers/${module.name}：scope.list 结尾缺换行，合并时会和下一个模块的包名粘成一行"
            }
            val scope = raw.lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()
            if (scope.isEmpty()) return@forEach

            val manifest = module.resolve("src/main/AndroidManifest.xml")
            if (!manifest.isFile) {
                problems += "hookers/${module.name}：缺 src/main/AndroidManifest.xml，" +
                    "${scope.joinToString()} 在 Android 11+ 上对模块不可见，应用页会显示成「未安装」"
                return@forEach
            }

            val visible = packageEntry.findAll(manifest.readText()).map { it.groupValues[1] }.toSet()
            val invisible = scope - visible
            if (invisible.isNotEmpty()) {
                problems += "hookers/${module.name}：${invisible.joinToString()} 在 scope.list 里，" +
                    "但没写进 manifest 的 <queries>，应用页会显示成「未安装」"
            }
            report.appendLine("${module.name}: scope=${scope.joinToString()} queries=${visible.joinToString()}")
        }

        val stampFile = stamp.get().asFile
        stampFile.parentFile.mkdirs()
        stampFile.writeText(report.toString())

        if (problems.isNotEmpty()) {
            throw GradleException(
                problems.joinToString(
                    separator = "\n  - ",
                    prefix = "hooker 声明不一致（scope.list 与 <queries> 必须覆盖同一批包）：\n  - ",
                ),
            )
        }
    }
}

