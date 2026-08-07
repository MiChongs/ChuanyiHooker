package com.chuanyi.hooker.hookers.osmin

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.chuanyi.hooker.core.HookScope
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createReturnConstantHook
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 权益本身。
 *
 * ## 为什么只动应用进程，不注入 SystemUI
 *
 * 目标的付费功能**不在应用进程里跑** —— 它自己是个 Xposed 模块，功能渲染在
 * SystemUI 和一批 ColorOS 包里。第一反应是把我们也注进那十个进程去压判定，
 * 这条路**走不通且没必要**：目标的类在它自己的 `LspModuleClassLoader` 里，
 * 我们在 SystemUI 拿到的是 SystemUI 的 classloader，`classOrNull` 根本看不见。
 *
 * 而那一侧的判定是
 *
 * ```
 * auth_active && <该功能自己的开关>
 * ```
 *
 * 没有设备指纹、没有有效期、没有签名 —— 只读一个布尔。这个布尔存在**框架托管的
 * 远端配置**里，写它的权限在应用进程手上。所以正解是在应用进程里把它写成 true：
 * 被注入的十个进程照常读到已授权，我们的注入范围收回到只有目标应用本身。
 *
 * ## 三处着力点，各自独立
 *
 * | 做什么 | 挡住的是 |
 * |---|---|
 * | [installUnlock] 里的等级判定 | 界面把付费项显示成「需授权」、同步时算出未授权 |
 * | [installUnlock] 里的写入拦截 | 未登录 / 退出登录 / 同步失败时把权益写回 false |
 * | [pushEntitlement] | 用户从没走到过任何同步路径，远端那份一直是初始值 |
 *
 * 三处任一失败其余照常工作，所以都不用 `error()` 中止。
 */
internal object Entitlement {

    /** 远端写入成功过一次就不再重试；界面每次回到前台仍会复查取值。 */
    private val remoteWritten = AtomicBoolean(false)

    /** 拿不到框架连接时的重试节奏，毫秒。连接是异步建立的，头一两次多半是空的。 */
    private val RETRY_DELAYS = longArrayOf(600L, 1_500L, 4_000L)

    // -----------------------------------------------------------------------

    /**
     * 解锁。
     *
     * 等级判定常量化 + 拦住写回 + 主动补写，三件事一起做 —— 它们针对的是同一个
     * 布尔的三条路径，拆成三个开关只会让用户面对三个必须同时打开的选项。
     */
    fun HookScope.installUnlock() {
        forceTierGate()
        guardEntitlementWrite()
        installEntitlementPush()
    }

    /**
     * 「这个等级算不算已授权」恒为 true。
     *
     * 常量化这一个方法，等于同时解决两件事：界面上每个付费项都从「需授权」变回可用，
     * 而同步流程算出来要写进权益开关的那个值也一起变成 true。
     *
     * 它的入参是账号等级，**与登录状态无关** —— 未登录时等级是「未授权」，
     * 判定照样返回 true。免登录使用就是这么来的，不需要伪造令牌。
     */
    private fun HookScope.forceTierGate() {
        val gate = OsMinDex.tierGate(this) ?: run {
            log.w("找不到授权等级判定，界面可能仍显示为需授权")
            return
        }
        gate.createReturnConstantHook("osmin.unlock.tier", true)
        log.i("授权等级判定已常量化")
    }

    /**
     * 拦住把权益写回 false 的那几条路。
     *
     * 目标在四种情况下会主动清掉权益：令牌为空、退出登录、同步抛异常、从备份恢复。
     * 前两种在未登录时**一定会走到** —— 也正因如此，这个拦截同时就是免登录使用的
     * 保障：那条路照常执行，只是写进去的值被换成了 true。
     *
     * 按 key 过滤是必须的：这个方法是目标写所有开关的公共通道，一律改会把
     * 每一个功能开关都打开。
     */
    private fun HookScope.guardEntitlementWrite() {
        val writer = OsMinDex.entitlementWriter(this) ?: run {
            log.w("找不到权益写入方法，退出登录后权益可能被清掉")
            return
        }
        writer.createBeforeHook("osmin.unlock.write") { param ->
            if (param.arg(KEY_ARG) != OsMin.KEY_AUTH_ACTIVE) return@createBeforeHook
            if (param.arg(VALUE_ARG) == true) return@createBeforeHook
            param.args[VALUE_ARG] = true
            log.d("应用想清掉权益开关，已改回已授权")
        }
        log.i("权益写入已接管")
    }

    /**
     * 补写一次权益。
     *
     * 上面两处都只在目标**主动跑到那段代码**时才生效。刚装上模块、用户还没进过
     * 「我的」页时，远端那份配置里可能压根没有这个键 —— 被注入的进程读到默认值
     * false，功能一个都不出来。所以界面一起来就补写一次。
     *
     * 挂在 `onStart` 而不是 `onCreate`：远端配置要等与框架的连接建立，
     * `onCreate` 时那个服务对象常常还是空的。即便如此也可能没就绪，所以
     * [pushEntitlement] 自带重试。
     */
    private fun HookScope.installEntitlementPush() {
        val onStart = classOrNull(OsMin.MAIN_ACTIVITY)
            ?.declaredMethods
            ?.firstOrNull { it.name == "onStart" && it.parameterCount == 0 }
        if (onStart == null) {
            log.w("找不到 ${OsMin.MAIN_ACTIVITY}.onStart，权益补写跳过")
            return
        }
        onStart.createAfterHook("osmin.unlock.push") { pushEntitlement(this, attempt = 0) }
        log.d("权益补写已就绪")
    }

    /**
     * 两份配置各写一次。
     *
     * 本地那份在目标自己的数据目录里，随时可写；远端那份要经框架，连接没建立时
     * 拿不到，按 [RETRY_DELAYS] 退避重试。已经是 true 就不写 —— 远端写入要过一次
     * binder，没必要每次回到前台都来一遍。
     */
    // 返回类型显式写出来：函数体里递归调用自己，推断会成环。
    private fun pushEntitlement(scope: HookScope, attempt: Int): Unit = with(scope) {
        runCatching {
            appContextOrNull()
                ?.getSharedPreferences(OsMin.PREFS_SETTINGS, Context.MODE_PRIVATE)
                ?.takeIf { !it.getBoolean(OsMin.KEY_AUTH_ACTIVE, false) }
                ?.edit()
                ?.putBoolean(OsMin.KEY_AUTH_ACTIVE, true)
                ?.apply()
        }

        if (remoteWritten.get()) return@with

        val remote = OsMin.remotePrefs(this)
        if (remote == null) {
            if (attempt >= RETRY_DELAYS.size) {
                log.w("拿不到跨进程配置，被注入的进程可能仍看不到授权")
                return@with
            }
            Handler(Looper.getMainLooper())
                .postDelayed({ pushEntitlement(this, attempt + 1) }, RETRY_DELAYS[attempt])
            return@with
        }

        if (remote.getBoolean(OsMin.KEY_AUTH_ACTIVE, false)) {
            remoteWritten.set(true)
            log.d("跨进程权益已就位")
            return@with
        }
        runCatching {
            remote.edit().putBoolean(OsMin.KEY_AUTH_ACTIVE, true).apply()
        }.onSuccess {
            remoteWritten.set(true)
            log.i("已写入跨进程权益，被注入的进程可直接使用付费功能")
        }.onFailure {
            log.w("跨进程权益写入失败：${it.message}")
        }
    }

    // -----------------------------------------------------------------------

    /**
     * 账号页的等级显示。
     *
     * 与解锁是两件事：解锁靠判定常量化，那一步不改任何存储，于是账号页仍照实显示
     * 「未授权」。这一项只改**读出来的**等级，磁盘上那份一个字节都没动 ——
     * 关掉这个开关立刻恢复原样。
     *
     * 挂在系统的 `SharedPreferencesImpl` 上而不是目标的读取点：读取点被 R8 合并进了
     * 界面代码里，而键名是跨版本稳定的。已经是已授权等级时不改，避免把「月授权」
     * 这种真实状态覆盖掉。
     */
    fun HookScope.installLifetimeTier() {
        val impl = OsMin.prefsImpl(this) ?: run {
            log.w("找不到 SharedPreferences 实现，等级显示不受影响")
            return
        }
        val tier = OsMin.TIER_LIFETIME

        impl.declaredMethods
            .firstOrNull { it.name == "getString" && it.parameterCount == 2 }
            ?.createAfterHook("osmin.lifetime.get") { param ->
                if (param.arg(0) != OsMin.KEY_TIER) return@createAfterHook
                val current = param.result as? String
                if (current in OsMin.TIERS_AUTHORIZED) return@createAfterHook
                param.result = tier
            } ?: log.w("SharedPreferencesImpl 上没有 getString，等级显示不受影响")

        // 备份导出走的是整表读取，逐个 getString 不会发生。改它是为了显示一致，
        // 拿到的是内部表的拷贝（`new HashMap<>(mMap)`），污染不到持久化数据。
        impl.declaredMethods
            .firstOrNull { it.name == "getAll" && it.parameterCount == 0 }
            ?.createAfterHook("osmin.lifetime.getAll") { param ->
                @Suppress("UNCHECKED_CAST")
                val map = param.result as? MutableMap<String, Any?> ?: return@createAfterHook
                if (!map.containsKey(OsMin.KEY_TIER)) return@createAfterHook
                if (map[OsMin.KEY_TIER] in OsMin.TIERS_AUTHORIZED) return@createAfterHook
                map[OsMin.KEY_TIER] = tier
            }

        log.i("账号等级显示为$tier")
    }

    // -----------------------------------------------------------------------

    /**
     * 排查用。
     *
     * 目标更新之后如果功能没解锁，第一件事是看这里：等级判定有没有被调用、
     * 权益开关被谁写成了什么、跨进程那份现在是什么值。令牌只报长度。
     */
    fun HookScope.installAuthTrace() {
        OsMinDex.tierGate(this)?.createAfterHook("osmin.trace.tier") { param ->
            log.i("等级判定 ${param.arg(0)} -> ${param.result}")
        }

        OsMinDex.entitlementWriter(this)?.createAfterHook("osmin.trace.write") { param ->
            log.i("写开关 ${param.arg(KEY_ARG)} = ${param.arg(VALUE_ARG)}")
        }

        val impl = OsMin.prefsImpl(this) ?: return
        impl.declaredMethods
            .firstOrNull { it.name == "getString" && it.parameterCount == 2 }
            ?.createAfterHook("osmin.trace.account") { param ->
                when (param.arg(0)) {
                    OsMin.KEY_TIER -> log.i("读等级 = ${param.result}")
                    OsMin.KEY_TOKEN -> log.i("读令牌 = <${(param.result as? String)?.length ?: 0} 字符>")
                }
            }

        log.i("授权追踪已挂上")
    }

    /** 权益写入方法的参数位：`(Context, String key, boolean value)`。 */
    private const val KEY_ARG = 1
    private const val VALUE_ARG = 2
}
