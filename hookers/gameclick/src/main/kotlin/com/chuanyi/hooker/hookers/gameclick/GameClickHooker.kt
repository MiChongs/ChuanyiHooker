package com.chuanyi.hooker.hookers.gameclick

import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookFeature
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.nativehook.NativeHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createReturnConstantHook
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 连点器（`com.pbb.gameclick`）—— 解锁会员并显示为永久。
 *
 * ## 授权是怎么运作的
 *
 * 判定**整个在原生库里**：`NativeUtil` 有 30 多个 static native 方法，
 * `IsVip()` / `GetVipTime()` / `DeviceNum()` 都是其中之一，而 `libgameclick.so`
 * 只导出 `JNI_OnLoad`，方法全靠 `RegisterNatives` 动态注册，连方法名字符串都是
 * 运行时才解密的（静态扫 `.rodata` 一个都搜不到）。
 *
 * 但这一层的**输入**是明文的。登录时应用把 `Login4` 的响应体原样丢给
 * `NativeUtil.LoginResult(String)`，那是一份没有签名保护的 JSON：
 *
 * ```json
 * {"openshop":true,"openwx":true,"wxlogin":true,"devicenum":1,
 *  "viptime":"2026-8-11 1:33","vip":"1","uid":6174505,"vipchange":0,"par":"T7kAR…"}
 * ```
 *
 * 里头那个 `par` 看着像签名，实测不是：改完字段、`par` 原样送进去，原生库照收，
 * `GetVipTime()` 立刻变成新值。所以主路径是**改源头**（[LoginPayload]），而不是
 * 逐个去补 Java 侧那十几处 `IsVip()` 调用 —— 源头改掉之后，原生库内部状态本身
 * 就是「会员」，Java 侧、原生侧、以及原生库自己内部的判断全都自然一致。
 *
 * ## 「永久」不用伪造日期
 *
 * 两条实测结论决定了做法：
 *
 * 1. **`IsVip()` 只看 `vip` 字段，完全不看 `viptime`。**
 *    `vip="1"` + `viptime="2020-1-1 0:00"` → `IsVip()` 仍是 true；
 *    `vip="0"` + `viptime="2099-1-1 0:00"` → false。时间纯粹是展示。
 * 2. 界面本来就有「永久」这条分支（`LeftView`）：
 *    `GetVipTime().contains(" ")` 为假时直接显示 `R.string.forever` = **永久**。
 *
 * 所以 `viptime` 写成不含空格的「永久」两个字，应用自己就把会员显示成永久 ——
 * 比塞一个 2099 年的假日期干净，也不会在别的页面露出破绽。
 *
 * ## 三层，各管一段
 *
 * | 层 | 做什么 | 覆盖的场景 |
 * |---|---|---|
 * | [installLoginForge] | 改登录返回 | 正常联网登录（主路径，最彻底） |
 * | [installVipGate] | 常量化 Java 侧判定 | 断网、未登录、登录失败 —— 那些时候 `LoginResult` 根本不会被调用 |
 * | [installNativeGate] | 在 `RegisterNatives` 那一刻换函数指针 | 应用改版后 Java 判定形状变了；默认关 |
 *
 * ## 加固
 *
 * 目标是 360 加固包，`base.apk` 里只有壳的 5 个类。两处后果：
 * DexKit 必须走内存 dex（见 [GameClickDex]）；真实 dex 万一还没解密好，
 * 就退到壳的 `onCreate` 之后再装（见 [deferUntilReady]）。
 */
class GameClickHooker : AppHooker {

    override val id = "gameclick"
    override val displayName = "连点器"
    override val description = "解锁会员全部功能，并显示为永久"
    override val targetPackages = setOf(GameClick.PACKAGE)

    override val features: List<HookFeature> = listOf(
        HookFeature(
            id = "login_forge",
            title = "接管登录返回",
            summary = "登录时把服务端下发的会员信息改成「永久会员」再交给应用。" +
                "这是最彻底的一项：应用内部的会员状态本身就变成已开通，" +
                "连点区域、设备位、到期提示全都跟着正常",
            install = { installLoginForge() },
        ),
        HookFeature(
            id = "vip_gate",
            title = "会员判定兜底",
            summary = "断网或没登录微信时，服务端那份信息根本不会下发，" +
                "上一项就没有可改的东西。这一项直接让应用自己的会员判定恒为「是」，" +
                "并把到期时间显示成永久",
            install = { installVipGate() },
        ),
        HookFeature(
            id = "rect_limit",
            title = "放宽连点区域数量",
            summary = "应用原本最多 6 个连点区域，改成 %d 个。数量可在下面的设置里改"
                .format(DEFAULT_RECT_LIMIT),
            install = { installRectLimit() },
        ),
        HookFeature(
            id = "device_check",
            title = "跳过设备位限制",
            summary = "换手机或多设备登录时，服务端会下发「设备位已满」把悬浮窗拦掉。" +
                "这一项让这道检查一律放行",
            install = { installDeviceCheck() },
        ),
        HookFeature(
            id = "skip_recheck",
            title = "关闭定时联网复核",
            summary = "应用每 24 小时会重新联网核对一次会员，核对不过就关掉悬浮窗。" +
                "上面两项已经能让复核通过，所以默认不动它；" +
                "网络环境不稳时可以打开这项彻底跳过",
            defaultEnabled = false,
            install = { installSkipRecheck() },
        ),
        HookFeature(
            id = "native_gate",
            title = "接管原生会员判定",
            summary = "在原生库注册方法的那一刻把会员判定换成常量，不改动库本身一个字节。" +
                "前面几项已经够用，这一项留给应用改版后判定形状变化的情况。" +
                "打开后需要重启应用才生效",
            defaultEnabled = false,
            install = { installNativeGate() },
        ),
        HookFeature(
            id = "no_update",
            title = "屏蔽强制更新",
            summary = "应用可以从服务端下发「必须更新」把旧版本卡死。" +
                "打开这项后不再强制，避免更新到本模块还没适配的版本",
            defaultEnabled = false,
            install = { installNoUpdate() },
        ),
        HookFeature(
            id = "log_auth",
            title = "记录会员判定",
            summary = "排查用：把每次登录返回和会员判定结果写进日志",
            defaultEnabled = false,
            install = { installAuthLog() },
        ),
    )

    override fun isCompatible(scope: HookScope): Boolean {
        // 只在主进程干活。无障碍服务跑在同一进程，所以不用额外放行别的进程。
        if (!scope.isMainProcess) {
            scope.log.d("跳过非主进程 ${scope.processName}")
            return false
        }
        return true
    }

    override fun onHook(scope: HookScope) {
        val packed = scope.classOrNull(GameClick.STUB_APP) != null
        scope.log.i(
            "连点器 ${scope.versionCode} @ ${scope.processName}" +
                if (packed) "（360 加固）" else ""
        )
    }

    // =======================================================================
    // 1. 主路径：改登录返回
    // =======================================================================

    /**
     * 把 `NativeUtil.LoginResult(String)` 收到的 JSON 换成永久会员版本。
     *
     * 用 before 改入参而不是 after 改返回值：这个方法返回 void，它的作用就是把
     * 那份 JSON**写进原生库的内部状态**。只有在写进去之前动手，后面所有从那份
     * 状态读的地方（Java 的、原生自己的）才会读到改过的值。
     */
    private fun HookScope.installLoginForge() = deferUntilReady("login_forge") {
        val method = GameClickDex.loginResult(this) ?: error("找不到登录返回的落地方法")
        val slots = int(KEY_DEVICE_SLOTS, 0)
        val verbose = settings.isVerbose()

        method.createBeforeHook("gameclick.login_forge") { param ->
            val raw = param.args.getOrNull(0) as? String ?: return@createBeforeHook
            val forged = LoginPayload.forge(raw, slots) ?: return@createBeforeHook
            if (forged == raw) return@createBeforeHook
            param.args[0] = forged
            if (verbose) log.d("登录返回已改写：${LoginPayload.digest(raw)} -> ${LoginPayload.digest(forged)}")
        }
        log.i("登录返回接管就绪（${method.declaringClass.name}.${method.name}）")
    }

    // =======================================================================
    // 2. 兜底：常量化 Java 侧判定
    // =======================================================================

    /**
     * 让 `NativeUtil` 上那几个门恒为真，并把到期时间换成「永久」。
     *
     * 这一层覆盖的是**上一层够不着的时候**：没网、没登录微信、服务端返回空 ——
     * 那些情况下 `LoginResult` 压根不会被调用，原生库里还是上一次的状态
     * （首次安装时就是「非会员」）。
     *
     * `GetVipTime` 单独处理：它返回的是 `jstring`，不能用常量桩那条路
     * （见 [GameClick.NATIVE_BOOL_GATES]），只能在 Java 侧换返回值。
     */
    private fun HookScope.installVipGate() = deferUntilReady("vip_gate") {
        var installed = 0
        GameClick.NATIVE_BOOL_GATES.forEach { name ->
            val method = GameClickDex.boolGate(this, name)
            if (method == null) {
                log.w("找不到判定方法 $name，跳过")
                return@forEach
            }
            method.createReturnConstantHook("gameclick.gate.$name", true)
            installed++
        }
        if (installed == 0) error("一个会员判定方法都没找到")

        GameClickDex.vipTime(this)?.let { method ->
            // 不含空格 -> 账号页走 R.string.forever 那条分支，显示「永久」。
            method.createReturnConstantHook("gameclick.gate.vip_time", GameClick.VIP_TIME_FOREVER)
        } ?: log.w("找不到到期时间方法，会员时间仍显示服务端下发的值")

        val slots = int(KEY_DEVICE_SLOTS, 0)
        if (slots > 0) {
            // 装箱成 Any? 再传：裸 Int 会同时匹配上 `(key, value)` 和
            // `(value, priority)` 两个重载，编译期直接歧义。
            val boxed: Any? = slots
            GameClickDex.deviceNum(this)?.createReturnConstantHook("gameclick.gate.device_num", boxed)
        }

        log.i("会员判定已常量化（$installed 处），到期时间显示为「${GameClick.VIP_TIME_FOREVER}」")
    }

    // =======================================================================
    // 3. 功能面的限制
    // =======================================================================

    /**
     * 连点区域数量上限。
     *
     * 上限是 `DataConst.maxRectCount`（默认 6），判定就一处，在
     * `FloatClickView.AddClickRect()` 里：
     * ```
     * if (DataConst.ClickDatas().size() >= DataConst.maxRectCount) { 提示上限; return; }
     * ```
     * 所以在这个方法进去之前把字段改掉即可。
     *
     * **不在 install 的时候直接改**：`DataConst` 的静态初始化块会读
     * SharedPreferences，而那需要应用的 Context —— 在 hook 安装的时机去碰它
     * 会提前触发 clinit 并崩在里面。等到用户真的点「添加区域」，这个类必然
     * 早就初始化好了。
     */
    private fun HookScope.installRectLimit() = deferUntilReady("rect_limit") {
        val limit = int(KEY_RECT_LIMIT, DEFAULT_RECT_LIMIT).coerceIn(1, 999)
        val method = GameClickDex.addClickRect(this) ?: error("找不到添加连点区域的方法")
        val dataConst = GameClickDex.dataConst(this) ?: error("找不到全局状态类")
        val field = runCatching {
            dataConst.getDeclaredField("maxRectCount").apply { isAccessible = true }
        }.getOrNull() ?: error("找不到区域上限字段")

        val applied = AtomicBoolean(false)
        method.createBeforeHook("gameclick.rect_limit") {
            runCatching {
                if (field.getInt(null) != limit) field.setInt(null, limit)
                if (applied.compareAndSet(false, true)) log.i("连点区域上限放宽到 $limit")
            }.onFailure { log.w("写区域上限失败：${it.message}") }
        }
        log.i("连点区域上限已挂号（$limit）")
    }

    /**
     * 设备位检查。
     *
     * `DataConst.CheckDeviceNum()` 读的是服务端下发的 `loginwxerror`：非空就弹框
     * 并返回 false，悬浮窗开不起来。[installLoginForge] 已经把那个字段清空了，
     * 这一项是给「上一层没生效」的情况兜底，代价只有一个常量返回。
     */
    private fun HookScope.installDeviceCheck() = deferUntilReady("device_check") {
        val method = GameClickDex.checkDeviceNum(this) ?: error("找不到设备位校验方法")
        method.createReturnConstantHook("gameclick.device_check", true)
        log.i("设备位校验已放行（${method.declaringClass.name}.${method.name}）")
    }

    /**
     * 24 小时定时复核。
     *
     * `DataConst.CheckTime()` 会重新走一次登录，回来后如果 `IsVip()` 为假就调
     * `CloseFloatClickView()` 把悬浮窗关掉。前两项都会让这个复核顺利通过，
     * 所以默认不动它 —— 保留复核，应用的其它状态（公告、更新信息）也能跟着刷新。
     */
    private fun HookScope.installSkipRecheck() = deferUntilReady("skip_recheck") {
        val method = GameClickDex.checkTime(this) ?: error("找不到定时复核方法")
        // 方法返回 void，「常量」就是 null。
        method.createReturnConstantHook("gameclick.skip_recheck", null)
        log.i("定时联网复核已关闭")
    }

    /** 强制更新开关，两个都来自原生库。 */
    private fun HookScope.installNoUpdate() = deferUntilReady("no_update") {
        var n = 0
        listOf("IsForceUpdate", "ForceShowUpdate").forEach { name ->
            GameClickDex.boolGate(this, name)?.let {
                it.createReturnConstantHook("gameclick.no_update.$name", false)
                n++
            } ?: log.w("找不到 $name")
        }
        if (n == 0) error("找不到更新判定方法")
        log.i("强制更新已屏蔽（$n 处）")
    }

    // =======================================================================
    // 4. 原生层
    // =======================================================================

    /**
     * 在 `RegisterNatives` 那一刻把判定函数的指针换成常量桩。
     *
     * `libgameclick.so` 只导出 `JNI_OnLoad`，方法名连字符串都藏在运行时 ——
     * 静态扫这个 so，`IsVip` 这几个名字一个都搜不到。名字和函数指针**同时存在**
     * 的地方只有 `RegisterNatives` 传进去的那张 `JNINativeMethod` 表，
     * 所以拦的是那张表，改的是表里的指针。
     *
     * 换指针发生在 ART 绑定之前，**原生库自己一个字节都没被改** —— 它要是去读
     * `/proc/self/maps` 或者校验自身代码段，什么也发现不了。
     *
     * 必须赶在应用加载那个库之前挂上（库是在 `NativeUtil` 类初始化时
     * `System.loadLibrary("gameclick")` 加载的），所以这一项改完要重启应用。
     *
     * 只挂返回 boolean 的那几个：`GetVipTime` 返回 `jstring`，给 Java 递一个
     * 伪造的对象引用会当场崩。
     */
    private fun HookScope.installNativeGate() {
        if (!NativeHook.isAvailable) error("原生层不可用：${NativeHook.lastError}")
        NativeHook.setVerbose(settings.isVerbose())
        if (!NativeHook.watchJniRegistrations()) error("RegisterNatives 监视装不上")

        val forced = GameClick.NATIVE_BOOL_GATES.filter {
            NativeHook.returnConstantOnJniRegister(GameClick.NATIVE_UTIL, it, GameClick.JNI_TRUE)
        }
        if (forced.isEmpty()) error("原生判定接管失败")
        log.i("原生判定已挂号：${forced.joinToString()}，等 ${GameClick.NATIVE_LIB} 加载")
    }

    // =======================================================================
    // 5. 诊断
    // =======================================================================

    private fun HookScope.installAuthLog() = deferUntilReady("log_auth") {
        GameClickDex.loginResult(this)?.createBeforeHook("gameclick.log.login") { param ->
            log.i("登录返回 ${LoginPayload.digest(param.args.getOrNull(0) as? String)}")
        } ?: log.w("找不到登录返回方法，跳过登录日志")

        val dumped = AtomicBoolean(false)
        GameClickDex.boolGate(this, "IsVip")?.createAfterHook("gameclick.log.isvip") { param ->
            log.i("会员判定 -> ${param.result}")
            // 判定跑过一次，原生库就一定加载过了 —— 这时候捞注册表才有东西。
            if (dumped.compareAndSet(false, true)) dumpNativeRegistrations()
        } ?: log.w("找不到会员判定方法，跳过判定日志")
    }

    private fun HookScope.dumpNativeRegistrations() {
        val entries = NativeHook.jniRegistrations().filter { it.className == GameClick.NATIVE_UTIL }
        if (entries.isEmpty()) {
            log.d("没有捕获到原生方法注册（未开启「接管原生会员判定」？）")
            return
        }
        entries.forEach { log.i("原生方法 ${it.methodName}${it.signature} @ 0x${it.address.toString(16)}") }
    }

    // =======================================================================

    /**
     * 360 加固下的安装时机。
     *
     * 壳（`com.stub.StubApp`）在 `attachBaseContext` 里解密真实 dex 并换掉
     * classloader。LSPosed 的 `onPackageReady` 是在 `LoadedApk.makeApplication`
     * 之后触发的，那时 `attachBaseContext` 已经跑完 —— 实测这时候
     * `com.pbb.gameclick.NativeUtil` 直接就能拿到，所以正常路径没有任何延迟。
     *
     * 留这条兜底是因为加固版本会变：万一某个版本把解密推迟到更后面，
     * 就退到壳的 `onCreate` 之后再装，那时无论如何都就绪了。
     *
     * 代价是这条路上注册的 hook 脱离了 EzHookTool 的热重载事务（模块更新后
     * 需要重启目标而不是热替换）—— 只在兜底时发生，可以接受。
     */
    private fun HookScope.deferUntilReady(tag: String, block: HookScope.() -> Unit) {
        if (GameClickDex.nativeUtil(this) != null) {
            block()
            return
        }
        val stub = classOrNull(GameClick.STUB_APP)
            ?: error("拿不到 ${GameClick.NATIVE_UTIL}，壳类也不在，应用可能已改版")
        val onCreate = runCatching { stub.getDeclaredMethod("onCreate") }.getOrNull()
            ?: error("壳类没有 onCreate，无法延迟安装")

        onCreate.createAfterHook("gameclick.defer.$tag") {
            runCatching { block() }.onFailure { log.e("延迟安装 $tag 失败", it) }
        }
        log.w("$tag：真实 dex 还没解密好，改到壳 onCreate 之后再装")
    }

    private companion object {
        const val KEY_RECT_LIMIT = "rect_limit"
        const val KEY_DEVICE_SLOTS = "device_slots"

        /**
         * 放宽后的连点区域上限。
         *
         * 没有取一个夸张的数：每个区域都是一个真实的悬浮 View，加上无障碍服务
         * 要按区域派发点击，几十个以上会明显拖慢并让界面挤成一团。
         */
        const val DEFAULT_RECT_LIMIT = 32
    }
}
