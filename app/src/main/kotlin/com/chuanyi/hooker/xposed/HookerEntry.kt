package com.chuanyi.hooker.xposed

import android.os.Build
import androidx.annotation.RequiresApi
import com.chuanyi.hooker.BuildConfig
import com.chuanyi.hooker.core.ActivationGuard
import com.chuanyi.hooker.core.HookerRuntime
import com.chuanyi.hooker.nativehook.NativeHook
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * The module's single libxposed entry point, listed in
 * `META-INF/xposed/java_init.list`.
 *
 * It owns no target-specific knowledge: it wires EzHookTool up, asks
 * [HookerRuntime] whether the current process is interesting, and hands the
 * decision back to EzHookTool. Everything else lives in the hooker modules.
 *
 * ## 热重载
 *
 * `module.prop` 里 `autoHotReload=true`，所以覆盖安装模块之后 framework 会挨个进入
 * 已注入的目标进程，把上一代的 hook 原子替换成新一代的，不用强停目标。
 *
 * 这条路上有一个**很容易漏、且漏了是静默的**前提：热重载会给模块一个
 * **全新的 classloader**，于是 `:core` / `:native` 里那些 object 在新一代里全是空的
 * 初始状态。而 framework 只重放 [onHotReloaded]，**不重放** [onModuleLoaded]、
 * [onPackageLoaded]、[onPackageReady] —— 也就是说，原本负责接线（`attach`）和认领目标
 * （`claim`）的三个回调，一个都不会再来。
 *
 * 漏掉重建的后果不是「少装几个 hook」，而是：`install()` 因为拿不到
 * `XposedInterface` 和认领结果直接返回，EzHookTool 看到新一代一个 hook 都没声明，
 * 就按约定把上一代的 handle 全部撤掉 —— **模块在那个进程里彻底失效**，而且只能靠
 * 强停目标恢复，比不支持热重载还糟。
 *
 * 所以这里把「一代模块要做的接线」收在 [prepareGeneration] 里，初次加载和热重载
 * 都走它；认领结果则通过 saved state 跨代传递，见
 * [HookerRuntime.crossGenerationState] 与 [HookerRuntime.restoreClaim]。
 */
class HookerEntry : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        EzXposed.initOnModuleLoaded(this, param)
        prepareGeneration(param)
        EzXposed.onTargetReady { HookerRuntime.install() }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: PackageLoadedParam) {
        // Later packages in the same process are libraries of the first one;
        // handling them would put hooks outside the hot-reload batch.
        if (!param.isFirstPackage) return

        when (HookerRuntime.onPackageLoaded(param)) {
            HookerRuntime.Decision.IGNORE -> detachQuietly()
            HookerRuntime.Decision.PREPARE -> EzXposed.initOnPackageLoaded(param)
            // A hooker asked for the early stage: install before the target's
            // AppComponentFactory runs. The later initOnPackageReady is then
            // safely ignored by EzHookTool.
            HookerRuntime.Decision.INSTALL -> EzXposed.initOnPackageLoadedAsTargetReady(param)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return

        when (HookerRuntime.onPackageReady(param)) {
            // Reachable on API 28, where onPackageLoaded is never dispatched.
            HookerRuntime.Decision.IGNORE -> detachQuietly()
            HookerRuntime.Decision.PREPARE,
            HookerRuntime.Decision.INSTALL -> EzXposed.initOnPackageReady(param)
        }
    }

    /**
     * 上一代收到的提问：这次热重载能不能做。
     *
     * 顺带把本进程的认领结果塞进 saved state —— 那是新一代唯一能拿到「这个进程当初
     * 认领了什么」的途径，因为它不会再收到 `onPackageLoaded` / `onPackageReady`。
     * 数组里只放 bootclasspath 类型，这是 framework 的硬约束，理由见
     * [HookerRuntime.crossGenerationState]。
     *
     * EzHookTool 自己也会拒：framework 不支持（API < 102）、本代的 hook 批次没有正常
     * 提交，都会返回 false。那些情况下拒绝是对的 —— 强行重载会留下一个半代状态。
     */
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        val state = HookerRuntime.crossGenerationState()
        val accepted = EzXposed.handleHotReloading(param, state ?: emptyArray())
        if (!accepted) {
            HookerRuntime.log().d(
                "拒绝本次热重载（本代没有可恢复的状态或 hook 批次未就绪），" +
                    "这个进程要等下次冷启动才会用上新版本",
            )
        }
        return accepted
    }

    /**
     * 新一代的入口。
     *
     * 三步，缺一不可：
     *
     * 1. [prepareGeneration] —— 把 `XposedInterface`、设置、日志、原生层兜底路径全部
     *    接到**这一代**上。新一代的 [HookerRuntime] 是全新对象，不接就是 null。
     * 2. `onExtra` 里 [HookerRuntime.restoreClaim] —— 从 saved state 重建认领结果。
     *    EzHookTool 保证它跑在 target-ready 分发之前。
     * 3. target-ready 回调里 `install()` —— 重新注册全部 hook，由 EzHookTool 按 hook ID
     *    与上一代原子替换，替换完成后才撤掉不再声明的旧 handle，中间没有空窗。
     *
     * **任何异常都在这里吃掉。**往外抛的话 LSPosed 会把它当成「模块类加载失败」，
     * 直接把整个模块从这个进程里丢掉；而热重载失败的正确降级是「这次不换，继续用
     * 上一代」—— 上一代的 hook 还在，目标照常工作，等下次冷启动再更新。
     */
    override fun onHotReloaded(param: HotReloadedParam) {
        prepareGeneration(param)
        val log = HookerRuntime.log()

        runCatching {
            EzXposed.handleHotReloadedWithTargetReady(
                this,
                param,
                { HookerRuntime.install() },
                { extra -> HookerRuntime.restoreClaim(extra) },
            )
        }.onSuccess { result ->
            log.i(
                "热重载完成：装上 ${result.installedHookCount} 个 hook，" +
                    "原子替换 ${result.atomicallyReplacedHookCount} 个，" +
                    "撤掉 ${result.removedOldHookCount} 个旧 hook",
            )
        }.onFailure { error ->
            // 最常见的一种：framework 给的模块 APK 路径指向刚被覆盖掉的旧包，
            // EzHookTool 初始化模块资源时就会在这里抛出来。上一代的 hook 未被触碰，
            // 目标继续按旧版本工作。
            log.w("热重载未完成，继续沿用上一代（强停目标即可用上新版本）：${error.message}", error)
        }
    }

    /**
     * 一代模块要做的全部接线。初次加载和热重载都走这里 —— 两条路做的事必须一致，
     * 分开写就是这个模块原本热重载失效的根因。
     *
     * [HookerRuntime.attach] 建立的是**这一代**的 `XposedInterface`、远端设置和日志。
     * 新一代必须重新建立：设置对象绑在具体的 `XposedInterface` 上，沿用上一代的会读到
     * 一个已经废弃的 binder。
     */
    private fun prepareGeneration(param: ModuleLoadedParam) {
        HookerRuntime.attach(this, param)

        // 顺序要紧：这一句必须排在任何会触碰 NativeHook.isAvailable 的调用之前
        // （下一行的 setVerbose 就是），否则加载已经定型，兜底路径来不及注册。
        // 这里是唯一同时看得见 :core 和 :native 的地方，所以接线放在 entry 里。
        //
        // 热重载时这一句同样要重做：新一代的 NativeHook 也是全新对象，而且模块 APK
        // 换了路径，上一代记下的 nativeLibraryDir 已经不存在了。
        NativeHook.setFallbackLibraryDir(
            runCatching { moduleApplicationInfo.nativeLibraryDir }.getOrNull(),
        )
        NativeHook.setVerbose(HookerRuntime.currentSettings().isVerbose())

        // 激活闸门的接线。同样只能放在 entry 里：:core 定义闸门，:native 实现校验，
        // 而模块图里没有 core -> native 这条边 —— 这里是唯一同时看得见两边的地方，
        // 顺带也是唯一看得见 BuildConfig 的地方。
        //
        // versionCode 会被签进令牌：换一版模块，旧令牌自动失效，用户下次打开 TG
        // 时静默续签。热重载换了 classloader，新一代的 ActivationGuard 是全新对象，
        // 所以这一句和上面几句一样，两条路径都必须重做。
        ActivationGuard.wire(BuildConfig.VERSION_CODE) { token, moduleVersionCode ->
            NativeHook.activationVerify(token, moduleVersionCode)
        }
    }

    /**
     * Stops the framework dispatching further callbacks to this entry so the
     * module class loader can be collected in processes we do not touch.
     */
    private fun detachQuietly() {
        runCatching { EzXposed.detachCurrentEntry() }
    }
}
