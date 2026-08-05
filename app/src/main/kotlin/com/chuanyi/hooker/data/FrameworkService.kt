package com.chuanyi.hooker.data

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 一个正在被本模块注入的进程。
 *
 * [handle] 是框架发的不透明句柄，[FrameworkService.hotReload] 要拿它回传；
 * 其余字段按 libxposed 的说法都只是诊断值，不要拿来做判断依据。
 */
@Immutable
data class RunningTarget(
    internal val handle: HookedTarget,
    val pid: Int,
    val uid: Int,
    val processName: String,
    val state: HookedTarget.State,
    val loadedVersionCode: Long,
) {
    /** 进程名形如 `com.foo.bar` 或 `com.foo.bar:remote`，冒号前是包名。 */
    val packageName: String get() = processName.substringBefore(':')

    /** 子进程（`:xxx`）。主进程和子进程都会被注入，界面上要能分辨。 */
    val isSubProcess: Boolean get() = ':' in processName

    /** 进程里跑的是旧一代模块，热重载正是为这种情况准备的。 */
    val isStale: Boolean get() = state == HookedTarget.State.STALE
}

/** 一次作用域操作的结果。失败时 [message] 是框架给的原因。 */
@Immutable
data class ScopeOutcome(val approved: List<String>, val message: String?) {
    val isSuccess: Boolean get() = message == null
}

/**
 * libxposed 102 服务的封装。
 *
 * 这个类**不自己注册监听**：[io.github.libxposed.service.XposedServiceHelper] 全局
 * 只有一个静态 listener 槽位，且 binder 到达后缓存就被清空 —— 注册第二次的人什么都
 * 收不到。所以整个进程里只能有一处 `registerListener`，那处是 [ModuleSettings]，
 * 由它把 service 喂给这里（[onBind] / [onDied]）。
 *
 * 所有对外状态都是 snapshot state；binder 回调可能落在任意线程上，直接赋值是安全的
 * （快照系统本身跨线程）。
 *
 * 能力分档，调用前必须先判断，否则 102 才有的方法会直接抛
 * [UnsupportedOperationException]：
 *
 * | 能力 | 判据 |
 * |---|---|
 * | 作用域增删 | API 101 起就有 |
 * | 运行中进程 / 热重载 | [supportsRunningTargets]（API ≥ 102） |
 * | 远程配置与文件 | [hasRemoteStorage]（`PROP_CAP_REMOTE`） |
 */
@Stable
class FrameworkService {

    /** binder 调用一律不上主线程。 */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var service: XposedService? = null

    var isBound by mutableStateOf(false)
        private set

    /** 形如 "LSPosed 2.1.1-it (7818)"。未绑定时为空。 */
    var label by mutableStateOf("")
        private set

    var apiVersion by mutableStateOf(0)
        private set

    /** `PROP_*` 位图，见下面几个派生属性。 */
    var properties by mutableStateOf(0L)
        private set

    /** 框架当前实际应用本模块的包，**不等于** scope.list 声明的那份。 */
    var scope by mutableStateOf<List<String>>(emptyList())
        private set

    var runningTargets by mutableStateOf<List<RunningTarget>>(emptyList())
        private set

    /** 最近一次操作的失败原因，成功后清空。 */
    var lastError by mutableStateOf<String?>(null)
        private set

    /** 正在等框架回话的包。UI 拿它显示进行中，并避免重复点。 */
    var pendingScope by mutableStateOf<Set<String>>(emptySet())
        private set

    var isRefreshing by mutableStateOf(false)
        private set

    // --- 能力位 -------------------------------------------------------------

    /** 框架能注入 system_server 等系统进程。 */
    val canHookSystem: Boolean get() = properties and XposedService.PROP_CAP_SYSTEM != 0L

    /** 框架提供远程配置与远程文件。没有它，模块设置传不到被注入进程。 */
    val hasRemoteStorage: Boolean get() = properties and XposedService.PROP_CAP_REMOTE != 0L

    /** 框架禁止通过反射或动态加载的代码访问 Xposed API。 */
    val enforcesApiProtection: Boolean
        get() = properties and XposedService.PROP_RT_API_PROTECTION != 0L

    /** 能查运行中进程、能热重载。API 102 起。 */
    val supportsRunningTargets: Boolean get() = apiVersion >= XposedService.API_102

    // --- 生命周期（只由 ModuleSettings 调用）--------------------------------

    internal fun onBind(bound: XposedService) {
        service = bound
        isBound = true
        label = runCatching {
            "${bound.frameworkName} ${bound.frameworkVersion} (${bound.frameworkVersionCode})"
        }.getOrDefault("")
        apiVersion = runCatching { bound.apiVersion }.getOrDefault(0)
        properties = runCatching { bound.frameworkProperties }.getOrDefault(0L)
        refresh()
    }

    internal fun onDied() {
        service = null
        isBound = false
        label = ""
        apiVersion = 0
        properties = 0L
        scope = emptyList()
        runningTargets = emptyList()
        pendingScope = emptySet()
    }

    // --- 查询 ---------------------------------------------------------------

    /**
     * 重新拉一遍作用域和运行中进程。
     *
     * 每次回到前台都该调一次：申请作用域走的是框架的系统弹窗，用户是在**别的界面**
     * 上点的同意，回来时本进程手里的这份还是旧的。
     */
    fun refresh() {
        val bound = service ?: return
        io.launch {
            isRefreshing = true
            try {
                scope = runCatching { bound.scope }
                    .onFailure { lastError = it.readableMessage() }
                    .getOrDefault(scope)

                runningTargets = if (!supportsRunningTargets) {
                    emptyList()
                } else {
                    runCatching { bound.runningTargets.map(::toRunningTarget) }
                        .onFailure { lastError = it.readableMessage() }
                        .getOrDefault(runningTargets)
                }
            } finally {
                isRefreshing = false
            }
        }
    }

    fun isInScope(packageName: String): Boolean = packageName in scope

    /** [packages] 里还不在作用域内的那些。 */
    fun missingScope(packages: Collection<String>): List<String> =
        packages.filterNot { it in scope }

    /** 命中 [packages] 里任一包的运行中进程。 */
    fun targetsOf(packages: Collection<String>): List<RunningTarget> =
        runningTargets.filter { it.packageName in packages }

    // --- 作用域 -------------------------------------------------------------

    /**
     * 向框架申请把 [packages] 加进作用域。框架会弹系统确认框，用户点完才回调。
     *
     * 注意 `module.prop` 里的 `staticScope`：它声明「用户不应把模块用在 scope.list
     * 之外的应用上」。申请 scope.list 之内的包是自洽的；之外的包会不会被框架直接拒，
     * 取决于框架实现，失败原因会原样带到 [onResult] 和 [lastError]。
     */
    fun requestScope(packages: List<String>, onResult: (ScopeOutcome) -> Unit = {}) {
        val bound = service
        if (packages.isEmpty()) return
        if (bound == null) {
            fail("Xposed 服务未连接", packages, onResult)
            return
        }
        pendingScope = pendingScope + packages
        val listener = object : XposedService.OnScopeEventListener {
            override fun onScopeRequestApproved(approved: MutableList<String>) {
                pendingScope = pendingScope - packages.toSet()
                lastError = null
                // 以框架为准重新拉一遍：批量申请可能只批了一部分。
                refresh()
                onResult(ScopeOutcome(approved.toList(), null))
            }

            override fun onScopeRequestFailed(message: String) {
                pendingScope = pendingScope - packages.toSet()
                lastError = message
                refresh()
                onResult(ScopeOutcome(emptyList(), message))
            }
        }
        runCatching { bound.requestScope(packages, listener) }
            .onFailure {
                pendingScope = pendingScope - packages.toSet()
                fail(it.readableMessage(), packages, onResult)
            }
    }

    /** 把 [packages] 移出作用域。框架不弹窗，同步生效。 */
    fun removeScope(packages: List<String>, onResult: (ScopeOutcome) -> Unit = {}) {
        val bound = service
        if (packages.isEmpty()) return
        if (bound == null) {
            fail("Xposed 服务未连接", packages, onResult)
            return
        }
        io.launch {
            runCatching { bound.removeScope(packages) }
                .onSuccess {
                    lastError = null
                    refresh()
                    onResult(ScopeOutcome(emptyList(), null))
                }
                .onFailure { fail(it.readableMessage(), packages, onResult) }
        }
    }

    // --- 热重载 -------------------------------------------------------------

    /**
     * 让 [target] 进程换上新一代模块，不重启进程。
     *
     * 这是给「模块 APK 更新了」用的，**不是**给配置变更用的 —— 官方文档明确写了
     * 配置走远程 SharedPreferences。所以界面上只对 [RunningTarget.isStale] 的进程
     * 推荐它；配置改完要生效仍然得重启目标（见 RootShell）。
     */
    fun hotReload(target: RunningTarget, onResult: (HotReloadResult) -> Unit) {
        val bound = service
        if (bound == null || !supportsRunningTargets) {
            lastError = if (bound == null) "Xposed 服务未连接" else "框架不支持热重载（需要 API 102）"
            return
        }
        runCatching {
            bound.hotReloadModule(target.handle, null) { _, result ->
                if (result.status() != HotReloadResult.Status.SUCCEEDED) {
                    lastError = result.message() ?: result.status().name
                } else {
                    lastError = null
                }
                refresh()
                onResult(result)
            }
        }.onFailure { lastError = it.readableMessage() }
    }

    // --- 内部 ---------------------------------------------------------------

    private fun fail(message: String, packages: List<String>, onResult: (ScopeOutcome) -> Unit) {
        lastError = message
        pendingScope = pendingScope - packages.toSet()
        onResult(ScopeOutcome(emptyList(), message))
    }

    private fun toRunningTarget(target: HookedTarget) = RunningTarget(
        handle = target,
        pid = target.pid,
        uid = target.uid,
        processName = target.processName,
        state = target.state,
        loadedVersionCode = target.loadedVersionCode,
    )

    /** ServiceException 的 message 常常是空的，退回类名总比显示 "null" 强。 */
    private fun Throwable.readableMessage(): String = message ?: javaClass.simpleName
}
