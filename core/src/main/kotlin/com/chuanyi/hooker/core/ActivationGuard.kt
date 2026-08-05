package com.chuanyi.hooker.core

/**
 * 模块的激活闸门。
 *
 * 判据只有一条：**这台机器上的 Telegram（或第三方 TG 客户端）里有没有那个群**。
 * 没有就一个 hook 都不装 —— 不是「少几个功能」，是整个模块在目标进程里不工作。
 *
 * ## 为什么要分成两半
 *
 * 「有没有那个群」这件事只有一个地方答得上来：TG 客户端**自己的进程**。它的
 * `files/cache4.db` 在应用私有目录里，别的 uid 打不开，模块自己的进程也不行。
 * 而要拦的目标（Poweramp、Gboard……）又都是别的进程。于是拆成两个动作：
 *
 * | | 在哪跑 | 干什么 |
 * |---|---|---|
 * | 探测 | TG 客户端进程（`:hookers:tgguard`） | 读 cache4.db，命中就签发一枚令牌 |
 * | 校验 | 每一个被注入的进程（这里） | 令牌是不是本模块签的、过没过期 |
 *
 * 令牌走模块的远端配置传递。中间还多一跳：hook 进去的进程**只能读**框架配置，
 * 写不了，所以探测结果是广播回模块自己的应用、由它落盘的。
 *
 * 两侧的签发和校验都在壳内代码里做（解密进匿名内存执行，见
 * `:native` 的 `cpp/activation.cpp`），Java 层从头到尾拿不到 MAC 密钥 ——
 * 所以伪造一枚令牌比自己进一次群麻烦得多，这正是这套东西想要的性价比。
 *
 * ## 失败一律当作未激活
 *
 * 原生层加载不起来、令牌读不到、配置还没同步 —— 全部判成未激活。这和这个仓库里
 * 其余「失败降级成无害值」的写法**相反**，是有意的：其余地方失败只是少一个能力，
 * 这里失败是「校验没做成」，当作通过等于让闸门变成可选项。
 *
 * ## 接线在 entry 里
 *
 * `:core` 看不见 `:native`（模块图里没有这条边），而校验必须走原生。所以这里只留
 * 一个注入点，由 `HookerEntry` 在每一代模块初始化时填 —— 那里是唯一同时看得见
 * `:core`、`:native` 和 `BuildConfig` 的地方，和 `NativeHook.setFallbackLibraryDir`
 * 是同一个套路。热重载会换 classloader，新一代必须重新接。
 */
object ActivationGuard {

    /** 校验一枚令牌。实现在 `:native`，由 [wire] 注入。 */
    fun interface Verifier {
        fun verify(token: String, moduleVersionCode: Int): Boolean
    }

    /**
     * 探测侧把令牌递给模块应用的那一跳。
     *
     * 存在的理由是一条框架约束：`XposedInterface.getRemotePreferences` 给被注入的
     * 进程的是**只读**视图，`edit()` 直接抛异常。所以 TG 进程算出来的令牌没法自己
     * 落盘，只能广播给模块应用，由它用 `XposedService` 那份可写句柄写进去。
     *
     * 接收方是导出的，任何应用都能往它发东西 —— 这没关系：令牌带 MAC，密钥只存在于
     * 壳内代码解密后的匿名内存里，伪造不出来，收到假的只会校验失败。
     *
     * 契约放在 `:core` 而不是两边各写一份字符串：发送方是 hooker 模块，接收方在
     * `:app`，两边都只依赖 `:core`。
     */
    object Broadcast {
        const val ACTION = "com.chuanyi.hooker.action.ACTIVATION"
        const val RECEIVER = "com.chuanyi.hooker.data.ActivationReceiver"

        /**
         * 签发时是新令牌；撤销时是**当前那一枚**。
         *
         * 撤销为什么也要带令牌：它是「我读得到框架配置」的证明。框架配置只有被注入的
         * 进程和模块应用自己够得着，所以随手写个应用是发不出一条能生效的撤销的 ——
         * 否则任意应用都能把别人的模块关掉。
         */
        const val EXTRA_TOKEN = "token"

        /** 发送方自己的包名。撤销时还要和令牌里签的那一个对得上。 */
        const val EXTRA_SOURCE = "source"

        /** true = 撤销当前令牌。见 `TgGuardHooker` 里「退群」那一段。 */
        const val EXTRA_REVOKE = "revoke"
    }

    @Volatile
    private var moduleVersion: Int = 0

    @Volatile
    private var verifier: Verifier? = null

    /**
     * 由 `HookerEntry` 在每一代模块初始化时调用。
     *
     * [moduleVersionCode] 会被签进令牌：换一版模块，旧令牌自然失效，用户下次打开
     * TG 时自动续签。这既避免了「拿旧版本的令牌喂新版本」，也让令牌不必再单独带
     * 一份版本信息。
     */
    fun wire(moduleVersionCode: Int, verifier: Verifier?) {
        moduleVersion = moduleVersionCode
        this.verifier = verifier
    }

    /** 签进令牌的模块 versionCode。探测侧（tgguard）要用同一个值。 */
    fun moduleVersionCode(): Int = moduleVersion

    /** 原生校验器接上了没有。接不上时 [isActivated] 恒为 false。 */
    val isWired: Boolean get() = verifier != null

    /**
     * 当前是不是激活状态。
     *
     * 每次 `install()` 都重新算一遍而不是缓存：令牌有有效期，进程可能活很久，
     * 缓存下来等于把过期检查废掉。这一次调用的代价是一次 mmap + 几微秒的 MAC 计算。
     */
    fun isActivated(settings: HookerSettings): Boolean {
        val checker = verifier ?: return false
        val token = settings.activationToken()
        if (token.isNullOrEmpty()) return false
        return runCatching { checker.verify(token, moduleVersion) }.getOrDefault(false)
    }
}
