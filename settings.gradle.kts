pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // XXPermissions / DeviceCompat / libsu only publish here. Scoped to those
        // groups so JitPack never gets asked for — or shadows — anything else.
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.getActivity")
                includeGroup("com.github.topjohnwu.libsu")
            }
        }
    }
}

rootProject.name = "ChuanyiHooker"

include(":app")
include(":core")
include(":native")

// 注入进目标 APK 的运行时。不参与 :app 的构建 —— patch 脚本单独取它的 class 打 dex。
include(":patcher:runtime")

// ---------------------------------------------------------------------------
// Hookers. One Gradle module per target app.
// Adding a new one: create hookers/<name>/, include it here, add the
// implementation(project(...)) line in app/build.gradle.kts.
// Discovery at runtime is automatic (META-INF/services), scope is merged
// automatically (META-INF/xposed/scope.list).
// ---------------------------------------------------------------------------
include(":hookers:paisa")
include(":hookers:hills")
include(":hookers:skypulse")
include(":hookers:yamby")
include(":hookers:astraflow")
include(":hookers:capyplayer")
include(":hookers:secretshoot")
include(":hookers:flix")
include(":hookers:gameclick")
include(":hookers:zenneko")
include(":hookers:gifshop")
include(":hookers:instashot")
include(":hookers:esj")
include(":hookers:chuckle")
include(":hookers:womic")
include(":hookers:airmusic")
include(":hookers:gboard")
include(":hookers:bridgeaudio")
