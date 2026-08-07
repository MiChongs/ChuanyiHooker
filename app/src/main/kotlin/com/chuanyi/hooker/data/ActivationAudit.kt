package com.chuanyi.hooker.data

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.chuanyi.hooker.core.ActivationToken
import com.chuanyi.hooker.core.HookerRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 模块应用这一侧的激活稽核。
 *
 * TG 进程那边（`:hookers:tgguard`）能回答的只有一个问题：「这个客户端的库里还有没有
 * 那个群」。剩下两种失效它**看不见**，因为它压根就不会再被执行：
 *
 * | 情况 | 谁能发现 | 怎么发现 |
 * |---|---|---|
 * | 退群 | TG 进程 | 下次启动 TG 时探测到权威的「没有」，定向撤销 |
 * | **签发方被移出框架作用域** | 只有这里 | 模块不再被注入那个客户端，探测永远不会再跑 |
 * | **签发方被卸载** | 只有这里 | 同上 |
 *
 * 后两种如果只靠令牌过期兜底，最长要等 [com.chuanyi.hooker.nativehook.NativeHook.ACTIVATION_TTL_DAYS]
 * 天。用户一打开模块界面就跑一遍这里，那两种情况**当场**失效。
 *
 * ## 两条防误伤的闸
 *
 * 稽核会真的删令牌，所以「拿不到信息」绝不能被当成「不满足」：
 *
 *  * **看不到任何 TG 客户端时不撤销。** 部分 ROM 把应用列表锁在一个厂商权限后面，
 *    没授权时 `getApplicationInfo` 抛的异常和真没装一模一样。一个都看不见更可能是
 *    看不见，而不是全卸载了。
 *  * **作用域还没拉到时不撤销。** [FrameworkService.scope] 是异步刷新的，binder 到
 *    之前是空表。拿空表当「不在作用域里」，会在每次冷启动的头几百毫秒里把令牌删掉。
 */
object ActivationAudit {

    /** 一个装在本机、且本模块认得出来的 TG 客户端。 */
    @Immutable
    data class Client(
        val packageName: String,
        val label: String,
        /** 框架当前有没有把本模块应用到它上面。作用域未知时恒为 true，见类文档。 */
        val inScope: Boolean,
    )

    enum class Verdict {
        /** 令牌有效，模块可用。 */
        ACTIVE,

        /** 一个受支持的 TG 客户端都没装（或者看不见）。 */
        NO_CLIENT,

        /** 装了客户端，但一个都没被框架应用本模块 —— 探测跑不起来。 */
        SCOPE_MISSING,

        /** 作用域也有了，就差启动一次客户端（或者这台机器确实没进群）。 */
        WAITING,
    }

    @Immutable
    data class Status(
        val verdict: Verdict,
        val clients: List<Client>,
    ) {
        /** 装了但没进作用域的那些，可以直接喂给 [FrameworkService.requestScope]。 */
        val missingScope: List<String> get() = clients.filterNot { it.inScope }.map { it.packageName }

        companion object {
            /** 还没稽核过。界面拿它当初始值，不据此下任何结论。 */
            val Unknown = Status(Verdict.WAITING, emptyList())
        }
    }

    /**
     * 跑一遍稽核，必要时撤销令牌。有 binder 和 PackageManager 调用，别在主线程上调。
     */
    fun run(context: Context, settings: ModuleSettings): Status {
        val framework = settings.framework
        // 作用域「已知」的判据要严：绑上了、不在刷新中、而且拿到的确实是一份非空表。
        // 三条缺一，就退回「未知」并把每个客户端都当作在作用域内 —— 宁可少撤一次。
        val scope = framework.scope
        val scopeKnown = framework.isBound && !framework.isRefreshing && scope.isNotEmpty()

        val clients = installedClients(context.packageManager).map { (packageName, label) ->
            Client(packageName, label, inScope = !scopeKnown || packageName in scope)
        }

        var token = settings.activationToken
        var active = settings.isActivated

        if (active && !token.isNullOrEmpty() && clients.isNotEmpty()) {
            val issuer = clients.firstOrNull { ActivationToken.issuedBy(token, it.packageName) }
            when {
                issuer == null -> {
                    // 签发方已经不在这台机器上了。它再也不会来续签，更不会来撤销。
                    settings.revokeActivation()
                    token = null
                    active = false
                }

                !issuer.inScope -> {
                    // 签发方还在，但框架已经不往它里面注入了 —— 探测这条链路断了，
                    // 手上这枚令牌等于无人负责。断了就当场失效，别让它苟到过期。
                    settings.revokeActivation()
                    token = null
                    active = false
                }
            }
        }

        val verdict = when {
            active -> Verdict.ACTIVE
            clients.isEmpty() -> Verdict.NO_CLIENT
            scopeKnown && clients.none { it.inScope } -> Verdict.SCOPE_MISSING
            else -> Verdict.WAITING
        }
        return Status(verdict, clients)
    }

    /**
     * 装在本机的受支持客户端。
     *
     * 包名取自那个产出激活凭据的 hooker 自己（`bypassesActivation` 为 true 的那个），
     * 不在这里另抄一份 —— 抄一份就意味着以后加一个 TG 分支要改两处，而漏掉这处是
     * 静默的：探测明明支持，界面却说不支持。
     */
    private fun installedClients(pm: PackageManager): List<Pair<String, String>> {
        val packages = HookerRegistry.all()
            .firstOrNull { it.bypassesActivation }
            ?.targetPackages
            .orEmpty()
        return packages.mapNotNull { packageName ->
            val info = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
                ?: return@mapNotNull null
            packageName to pm.getApplicationLabel(info).toString()
        }
    }
}

/**
 * 把稽核挂到组合上：每次回到前台重拉一遍作用域，作用域或设置一变就重跑一遍稽核。
 *
 * ## 必须挂在「不管激活与否都会渲染」的地方
 *
 * 这个函数原本只被 `ActivationLockScreen` 调用，而那一屏**只在未激活时才渲染** ——
 * 于是负责发现「签发方已被移出作用域」的稽核，只在已经锁上之后才跑，激活状态下
 * 一次都不跑。表现就是「我把作用域取消了，模块照样能用」。同一个原因还让
 * [FrameworkService.refresh] 在激活状态下从不被调用，手里那份作用域永远停在进程
 * 启动那一刻。
 *
 * 所以它现在由 `MainActivity` 在分支**之前**调用，两种状态下都跑。
 *
 * ## 为什么要在 RESUMED 时重拉
 *
 * 用户是**离开这个界面**去改状态的：去 LSPosed 改作用域、去 TG 切账号或退群。
 * 回来时如果不重拉，稽核看的还是旧数据，等于没跑。
 *
 * 稽核有副作用（可能删令牌），所以不能写在 `remember` 里 —— 那会在每次重组、每次
 * 重建时随机地跑。放进 [LaunchedEffect] 才有确定的触发时机。
 *
 * 返回值只用来决定「提示里说什么」。**模块到底能不能用不看它**，看
 * [ModuleSettings.isActivated] —— 那个是同步的，不会在首帧给出一个还没稽核过的结论。
 */
@Composable
fun rememberActivationStatus(settings: ModuleSettings): ActivationAudit.Status {
    val context = LocalContext.current
    val framework = settings.framework
    val lifecycleOwner = LocalLifecycleOwner.current
    var status by remember { mutableStateOf(ActivationAudit.Status.Unknown) }

    LaunchedEffect(lifecycleOwner, framework) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            framework.refresh()
        }
    }

    LaunchedEffect(
        settings.revision,
        framework.isBound,
        framework.isRefreshing,
        framework.scope,
    ) {
        status = withContext(Dispatchers.IO) { ActivationAudit.run(context, settings) }
    }
    return status
}
