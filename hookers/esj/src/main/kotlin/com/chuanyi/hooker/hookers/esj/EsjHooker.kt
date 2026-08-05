package com.chuanyi.hooker.hookers.esj

import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookFeature
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.nativehook.NativeHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 《疯狂水世界》（`com.gx.sw.qa.fkssj004.esj`）。
 *
 * ## 这个游戏是什么形态
 *
 * Cocos Creator 3.8.3 的**服务端权威 SLG**。整份协议 535 个 Request、294 个
 * Response，建造、升级、收产出、抽卡、战斗、公会、赛季全都是「客户端报意图 →
 * 服务端算 → 下发结果」。客户端里没有一行玩法逻辑：`src/chunks/bundle.js` 里
 * 79% 是 protobuf 定义，剩下的是网络层和加解密库；真正的界面逻辑在
 * `assets/main/index.jsc`（XXTEA 加密），而它算的也只是怎么把服务端下发的数字画出来。
 *
 * ## 一切改写成立的前提：协议是明文且无签名的
 *
 * ```
 * [0..3] 长度  [4] 版本=2  [5..8] 序号  [9] 名长  [10..] 消息名  [+1] 压缩标志  [..] protobuf
 * ```
 *
 * `ProtocolCodec.encode` 从头到尾没有算过任何校验值，body 也不参与校验。
 * 所以拦住 `WebSocket.prototype.send` 就能任意读写，改完不需要重新签名。
 *
 * ## 两种模式，能做的事完全不同
 *
 * ### 联机模式：连官方服务器
 *
 * 数值权威在对面。535 个请求里绝大多数只报「意图」（要建哪个、要升哪个），
 * 服务端算完再下发，所以改本地数字没有意义 —— 下一次操作就会被按真实值打回。
 *
 * 这个模式下真正能动的，是**服务端信任客户端上报结果**的那几处：跳舞总分、
 * 龙舟距离、点金次数之类（见 [Esj.REPORTED_FIELDS]）。改它们服务端发的是真奖励。
 *
 * 内购在这个模式下也动不了：订单号和签名由游戏服务端下发，发货走
 * 「渠道 → 易玩 → 游戏服务端」回调，客户端那个 `onPaymentCompleted()` 回调
 * 连数据都不带，拦它只能骗自己的 UI。
 *
 * ### 离线模式：本地服务端
 *
 * 把全局 `WebSocket` 换成本地回环（实测该属性 `writable` 且 `configurable`），
 * 游戏一个包都不发出去，对面变成模块自己。**于是权威回到本地** ——
 * 资源要多少有多少、内购道具直接发、任何玩法都不再受对面校验，
 * 因为根本没有对面了。
 *
 * 代价说清楚：这是一份与官方账号完全隔离的本地存档，联机玩法（公会、跨服排行、
 * 竞技场对手）没有真实对手，只有本地数据。
 *
 * 实现上没有去重写那 535 个请求的服务端逻辑 —— 而是**先录后放**：联网正常玩一次，
 * 把真实服务端下发的每条消息原样存下来（[Esj.RECORD_FILE]），离线时按
 * 「请求名 → 响应名」放回去。真实数据本身就是最准的样板，比推导 294 种响应的
 * 字段结构可靠得多。在这之上再改数值，就是离线版的「资源无限」。
 *
 * 本地服务端的业务逻辑不在模块里，而在设备上的 [Esj.EXTERNAL_SCRIPT]，
 * 由 [EsjRuntime] 的引导器读进引擎执行 —— 改逻辑只要推一个 js 文件，不用重装模块。
 */
class EsjHooker : AppHooker {

    override val id = "esj"
    override val displayName = "疯狂水世界"
    override val description = "可切离线单机运行；联机时改写客户端上报的小游戏成绩"
    override val targetPackages = setOf(Esj.PACKAGE)

    override val features: List<HookFeature> = listOf(
        HookFeature(
            id = FEATURE_BOOST,
            title = "放大小游戏成绩",
            summary = "游戏里有几处小游戏是客户端算完成绩再报给服务器的，服务器直接照单全收。" +
                "这一项把上报的分数、距离、次数按倍数放大，奖励是服务器真发的，不是本地显示。" +
                "覆盖跳舞、龙舟、文物鉴定、点金、周抽奖",
            install = { installBoost() },
        ),
        HookFeature(
            id = FEATURE_KEEP_ALIVE,
            title = "断线不再弹框卡死",
            summary = "网络一抖，游戏会弹「无法与服务器通信」然后整个卡住，只能杀进程重开。" +
                "这一项把那次放弃拦下来，改成继续安静重连",
            install = { installKeepAlive() },
        ),
        HookFeature(
            id = FEATURE_LOG,
            title = "协议观察台",
            summary = "把游戏和服务器之间每一条消息的名字、序号、大小写进日志，" +
                "客户端说了算的那几条会额外标出来。想自己找新的可改点时打开它",
            defaultEnabled = false,
            install = { installProtocolLog() },
        ),
        HookFeature(
            id = FEATURE_OFFLINE,
            title = "离线模式",
            summary = "接管游戏的网络层，让它一个包都不发出去，改由本地服务端应答。" +
                "本地服务端的业务逻辑写在设备上的 esj/server.js 里，改它不用重装模块。" +
                "开这项之前先用下面的录制功能联网跑一次，让本地服务端有真实数据可用",
            defaultEnabled = false,
            install = { installOffline() },
        ),
        HookFeature(
            id = FEATURE_RECORD,
            title = "录制服务器应答",
            summary = "联网玩一次，把服务器下发的每一条消息原样存到 esj/record.txt。" +
                "这是离线模式的素材：有了它，本地服务端不用去猜 294 种响应各自长什么样",
            defaultEnabled = false,
            install = { installRecord() },
        ),
        HookFeature(
            id = FEATURE_DRILL,
            title = "验证改写链路",
            summary = "开机时挑一条普通请求，往里补一个服务器按协议必须跳过的空字段，" +
                "然后看还能不能正常收到回包。用来确认改写这条路在当前版本上还通着，" +
                "不会动到任何游戏数据。验完可以关掉",
            defaultEnabled = false,
            install = { installDrill() },
        ),
        HookFeature(
            id = FEATURE_FREEZE,
            title = "锁定热更版本",
            summary = "官方推新版热更时，把版本号改成本地已有的，让游戏认为自己已是最新。" +
                "避免更新后模块还没跟上。想更新时关掉这项重进即可",
            defaultEnabled = false,
            install = { installFreezeHotUpdate() },
        ),
        HookFeature(
            id = FEATURE_PAY_TRACE,
            title = "记录充值流程",
            summary = "把下单参数、订单号、签名和支付回调全写进日志。" +
                "这项不会让充值免费（发货由服务器凭渠道回调完成，客户端拦不住），" +
                "它的用处是看清签名到底覆盖了哪些字段",
            defaultEnabled = false,
            install = { installPayTrace() },
        ),
        HookFeature(
            id = FEATURE_RISK,
            title = "隐藏模块痕迹",
            summary = "游戏带了顶象风控和爱加密。这一项把模块自己的库从进程模块链表里摘掉，" +
                "并挡住检测到异常后的自杀式退出。目前实测不开也能正常玩，" +
                "遇到闪退或封号提示再打开",
            defaultEnabled = false,
            install = { installRiskQuiet() },
        ),
    )

    override fun isCompatible(scope: HookScope): Boolean {
        if (!scope.isMainProcess) {
            scope.log.d("跳过非主进程 ${scope.processName}")
            return false
        }
        return true
    }

    override fun onHook(scope: HookScope) {
        bridge = EsjBridge(scope)
        scope.log.i("疯狂水世界 ${scope.versionCode} @ ${scope.processName}")
        if (!bridge.isUsable) {
            scope.log.e("注入通道不可用：${bridge.unavailableReason}")
        }
    }

    /**
     * 所有走 JS 的功能拼成**一次**注入。
     *
     * 分开注入会有两个麻烦：`WebSocket.prototype.send` 被包两层，第二层拿到的是
     * 第一层的包装；而且两段脚本各自维护一份配置，开关状态会打架。所以这里等所有
     * feature 都 install 完，再按最终的开关状态生成一份脚本送进去。
     */
    override fun onHooked(scope: HookScope, installed: List<HookFeature>) {
        if (!wantsJs) return
        if (!bridge.isUsable) {
            scope.log.e("需要注入的功能开着，但通道不可用，这些功能不会生效")
            return
        }

        bridge.listen(Esj.EVENT_LOG) { msg -> msg?.let { scope.log.i(it) } }
        bridge.listen(Esj.EVENT_READY) { msg ->
            bridge.markReady()
            scope.log.i("引擎侧回报：${msg ?: "就绪"}")
        }
        if (recordWriter != null) {
            bridge.listen(Esj.EVENT_RECORD) { line -> appendRecord(scope, line) }
        }

        val offline = scope.isEnabled(FEATURE_OFFLINE, false)
        val js = EsjRuntime.build(
            logProtocol = scope.isEnabled(FEATURE_LOG, false),
            boost = scope.isEnabled(FEATURE_BOOST, true),
            keepAlive = scope.isEnabled(FEATURE_KEEP_ALIVE, true),
            drill = scope.isEnabled(FEATURE_DRILL, false),
            record = recordWriter != null,
            offline = offline,
            multiplier = scope.int(KEY_MULTIPLIER, DEFAULT_MULTIPLIER).coerceIn(1, 10_000),
            maxValue = scope.int(KEY_MAX_VALUE, DEFAULT_MAX_VALUE).coerceAtLeast(1),
            generation = GENERATION,
        )

        // 挂在 CocosActivity.onCreate 之后：那时排的任务会在引擎第一帧执行，
        // 而第一帧必然在 JS 引擎和 jsb 绑定就绪之后、游戏连上服务器之前。
        val activity = scope.classOrNull(Esj.APP_ACTIVITY, "com.cocos.lib.CocosActivity")
        val onCreate = activity?.let {
            runCatching { it.getDeclaredMethod("onCreate", android.os.Bundle::class.java) }.getOrNull()
        }
        if (onCreate == null) {
            scope.log.w("找不到 Activity 的 onCreate，改为立即投递注入任务")
            startInject(scope, js)
            return
        }
        val fired = AtomicBoolean(false)
        onCreate.createAfterHook("esj.inject") {
            if (fired.compareAndSet(false, true)) startInject(scope, js)
        }
        scope.log.i("注入已挂号，等引擎起来")
    }

    private fun startInject(scope: HookScope, js: String) {
        bridge.injectUntilReady(js, attempts = 20, intervalMs = 500) {
            scope.log.w("注入脚本投递了 20 次仍未收到回报，引擎可能没起来")
        }
    }

    // =======================================================================
    // 走 JS 的三项：这里只标记「要注入」，真正的活在 onHooked 里一次做完
    // =======================================================================

    private fun HookScope.installBoost() {
        wantsJs = true
        val n = Esj.REPORTED_FIELDS.size
        log.i("上报改写已排入注入（$n 个消息，倍数 ${int(KEY_MULTIPLIER, DEFAULT_MULTIPLIER)}）")
    }

    private fun HookScope.installKeepAlive() {
        wantsJs = true
        log.i("断线容错已排入注入")
    }

    private fun HookScope.installProtocolLog() {
        wantsJs = true
        log.i("协议观察台已排入注入")
    }

    private fun HookScope.installDrill() {
        wantsJs = true
        log.i("链路演练已排入注入")
    }

    private fun HookScope.installOffline() {
        wantsJs = true
        log.i("离线模式已排入注入")
    }

    /**
     * 录制落盘放在 Java 侧。
     *
     * JS 那边没有可靠的追加写（`writeStringToFile` 是整文件覆盖），而且游戏被杀时
     * 攒在内存里的缓冲会整个丢掉。Java 这边每条都 flush，随时拔电也只丢最后一条。
     */
    private fun HookScope.installRecord() {
        wantsJs = true
        // 两者互斥，而且必须在开文件之前就拦住：录制是以覆盖方式打开 record.txt 的，
        // 一旦在离线模式下开了录制，原始素材当场被清空，录进去的还是本地回放的包
        // —— 等于自己喂自己，素材再也回不来。
        if (isEnabled(FEATURE_OFFLINE, false)) {
            log.w("离线模式开着，这次不录制：否则会用回放的包覆盖掉原始素材")
            return
        }
        runCatching {
            val f = File(Esj.RECORD_FILE)
            f.parentFile?.mkdirs()
            // 每次启动重开一份：录的是「一次完整会话」，混着上次的反而没法回放。
            recordWriter = BufferedWriter(FileWriter(f, false))
            log.i("录制已就绪，写到 ${Esj.RECORD_FILE}")
        }.onFailure {
            log.e("录制文件打不开：${it.message}")
        }
    }

    // =======================================================================
    // 热更
    // =======================================================================

    /**
     * 让游戏认为自己已是最新。
     *
     * Cocos 的 `AssetsManager` 先下 `version.manifest` 比版本号，高了才去下
     * `project.manifest` 和差异资源。所以不去阻断下载 —— 那样游戏会走「更新失败」
     * 分支，不同版本的处理不一样，有的会卡在检查更新界面。改成**把下回来的版本号
     * 换成本地那份**，比较结果自然是「一致」，更新流程正常走完然后什么也不做。
     *
     * 落点是 `CocosDownloader.onFinish(id, errCode, errStr, byte[] data)`：
     * 下载结果都从这里回到原生层，`data` 就是文件内容。`createTask` 那一侧只负责
     * 记住哪个任务号对应 manifest，因为 `onFinish` 里只有任务号没有 URL。
     */
    private fun HookScope.installFreezeHotUpdate() {
        val downloader = classOrNull(Esj.COCOS_DOWNLOADER) ?: error("找不到 ${Esj.COCOS_DOWNLOADER}")

        val createTask = downloader.declaredMethods.firstOrNull {
            it.name == "createTask" && it.parameterTypes.size >= 4
        } ?: error("找不到 createTask")
        val onFinish = downloader.declaredMethods.firstOrNull {
            it.name == "onFinish" && it.parameterTypes.size == 4
        } ?: error("找不到 onFinish")

        // createTask(CocosDownloader, int id, String url, String path, String[] headers)
        val idIndex = createTask.parameterTypes.indexOfFirst { it == Int::class.javaPrimitiveType }
        val urlIndex = createTask.parameterTypes.indexOfFirst { it == String::class.java }
        if (idIndex < 0 || urlIndex < 0) error("createTask 的参数形状不认识")

        createTask.createBeforeHook("esj.freeze.task") { param ->
            val url = param.args.getOrNull(urlIndex) as? String ?: return@createBeforeHook
            if (Esj.HOT_UPDATE_MANIFESTS.none { url.contains(it) }) return@createBeforeHook
            val taskId = param.args.getOrNull(idIndex) as? Int ?: return@createBeforeHook
            manifestTasks[taskId] = url
            log.d("热更清单请求 #$taskId $url")
        }

        onFinish.createBeforeHook("esj.freeze.finish") { param ->
            val taskId = param.args.getOrNull(0) as? Int ?: return@createBeforeHook
            val url = manifestTasks.remove(taskId) ?: return@createBeforeHook
            val data = param.args.getOrNull(3) as? ByteArray ?: return@createBeforeHook
            if (!url.contains("version.manifest")) {
                log.i("放行 ${url.substringAfterLast('/')}（${data.size} 字节）")
                return@createBeforeHook
            }
            val local = localVersion(this) ?: run {
                log.w("读不到本地热更版本号，这次不改远端清单")
                return@createBeforeHook
            }
            val patched = replaceVersion(data, local) ?: return@createBeforeHook
            param.args[3] = patched
            log.i("热更版本已锁在 $local")
        }

        log.i("热更锁定就绪")
    }

    /** 本地已装的热更版本，来自 `files/gx_game_hotupdate/project.manifest`。 */
    private fun localVersion(scope: HookScope): String? {
        cachedLocalVersion?.let { return it }
        val ctx = scope.appContextOrNull() ?: return null
        val f = File(ctx.filesDir, "$HOT_UPDATE_DIR/project.manifest")
        if (!f.isFile) return null
        // 清单有 1.5 MB，绝大部分是资源列表；版本号在最前面，读个头就够。
        val head = runCatching {
            f.inputStream().use { s ->
                val buf = ByteArray(4096)
                val n = s.read(buf)
                if (n <= 0) null else String(buf, 0, n, Charsets.UTF_8)
            }
        }.getOrNull() ?: return null
        return VERSION_RE.find(head)?.groupValues?.getOrNull(1)?.also { cachedLocalVersion = it }
    }

    private fun replaceVersion(data: ByteArray, version: String): ByteArray? {
        val text = runCatching { String(data, Charsets.UTF_8) }.getOrNull() ?: return null
        val m = VERSION_RE.find(text) ?: return null
        if (m.groupValues.getOrNull(1) == version) return null
        return text.replaceRange(m.range, "\"version\": \"$version\"").toByteArray(Charsets.UTF_8)
    }

    // =======================================================================
    // 充值观察
    // =======================================================================

    /**
     * 只记录，不改行为。
     *
     * `SDKSupport.pay(roleJson, orderJson)` 收到的 `orderJson` 里带着服务端下发的
     * `orderNo` 和 `sign`。想知道「改商品 ID 能不能过」，得先看清签名覆盖了什么 ——
     * 这一项就是为了拿到那份原文。真正的发货在服务端那侧，客户端这里改什么都不影响。
     */
    private fun HookScope.installPayTrace() {
        val support = classOrNull(Esj.SDK_SUPPORT)
        if (support == null) {
            log.w("找不到 ${Esj.SDK_SUPPORT}，充值记录跳过")
            return
        }
        support.declaredMethods.firstOrNull { it.name == "pay" }?.createBeforeHook("esj.pay") { param ->
            log.i("发起充值 角色=${param.args.getOrNull(0)}")
            log.i("发起充值 订单=${param.args.getOrNull(1)}")
        } ?: log.w("找不到 pay 方法")

        val wrapper = classOrNull(Esj.JSB_BRIDGE_WRAPPER)
        wrapper?.declaredMethods?.filter { it.name == "dispatchEventToScript" }?.forEach { m ->
            m.createBeforeHook("esj.pay.event.${m.parameterTypes.size}") { param ->
                val evt = param.args.getOrNull(0) as? String ?: return@createBeforeHook
                if (!evt.startsWith("SDK_onPayment")) return@createBeforeHook
                log.i("支付回调 $evt")
            }
        }
        log.i("充值流程记录就绪")
    }

    // =======================================================================
    // 原生层：默认不启用
    // =======================================================================

    /**
     * 顶象 `libDXRisk` 和爱加密 `libijiami_*` 都在进程里，但实测装着 LSPosed
     * （配 Shamiko 隐藏）正常进游戏、正常联网，没有触发拦截。所以这一项默认关着 ——
     * 少加载一个原生库就少一处被扫到的可能。真遇到闪退再打开。
     */
    private fun HookScope.installRiskQuiet() {
        if (!NativeHook.isAvailable) error("原生层不可用：${NativeHook.lastError}")
        NativeHook.setVerbose(settings.isVerbose())

        val available = runCatching { NativeHook.available().map { it.id } }.getOrDefault(emptyList())
        val wanted = listOf("linkmap_hide", "suicide_guard")
        val installed = wanted.filter { want ->
            if (want !in available) {
                log.w("原生层没有 $want，跳过")
                false
            } else {
                NativeHook.install(want).also { ok -> if (!ok) log.w("$want 装载失败") }
            }
        }
        if (installed.isEmpty()) error("一项原生防护都没装上")
        log.i("模块痕迹隐藏已启用：${installed.joinToString("、")}")
    }

    // =======================================================================

    private fun appendRecord(scope: HookScope, line: String?) {
        val w = recordWriter ?: return
        if (line.isNullOrEmpty()) return
        synchronized(w) {
            runCatching {
                w.write(line)
                w.write("\n")
                // 每条都刷：游戏随时可能被系统杀掉，攒着就没了。量不大，一次登录百来条。
                w.flush()
                recordCount++
                if (recordCount % 50 == 0) scope.log.i("已录制 $recordCount 条")
            }.onFailure {
                scope.log.w("录制写入失败：${it.message}")
                recordWriter = null
            }
        }
    }

    private lateinit var bridge: EsjBridge

    private var recordWriter: BufferedWriter? = null

    private var recordCount = 0

    /** 三个走 JS 的开关只要有一个开着就注入。 */
    private var wantsJs = false

    private val manifestTasks = ConcurrentHashMap<Int, String>()

    private var cachedLocalVersion: String? = null

    private companion object {
        const val FEATURE_BOOST = "report_boost"
        const val FEATURE_KEEP_ALIVE = "keep_alive"
        const val FEATURE_LOG = "protocol_log"
        const val FEATURE_DRILL = "link_drill"
        const val FEATURE_OFFLINE = "offline_mode"
        const val FEATURE_RECORD = "record_traffic"
        const val FEATURE_FREEZE = "freeze_hotupdate"
        const val FEATURE_PAY_TRACE = "pay_trace"
        const val FEATURE_RISK = "risk_quiet"

        const val KEY_MULTIPLIER = "boost_multiplier"
        const val KEY_MAX_VALUE = "boost_max"

        /**
         * 默认倍数取 5 而不是一个天文数字。
         *
         * 这几个上报值服务端多半有上限或合理区间校验，报一个明显不可能的成绩
         * 大概率被直接打回，还会在服务端留下一条异常记录。5 倍既能明显加快，
         * 又落在「玩得好」的范围内。想更激进改 `boost_multiplier` 即可。
         */
        const val DEFAULT_MULTIPLIER = 5

        /** 放大后的封顶，防止倍数乘出一个溢出 int32 的值把包写坏。 */
        const val DEFAULT_MAX_VALUE = 1_000_000

        const val HOT_UPDATE_DIR = "gx_game_hotupdate"

        val VERSION_RE = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")

        /**
         * 注入代号。改了 [EsjRuntime] 的行为就把它加一 —— 脚本靠它判断
         * 「进程里那份是不是我这一代」，不然热重载后旧钩子会一直留着。
         */
        const val GENERATION = 7L
    }
}
