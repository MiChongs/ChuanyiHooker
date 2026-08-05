package com.chuanyi.hooker.hookers.poweramp

import android.os.Bundle
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.core.HookerLog
import com.chuanyi.hooker.nativehook.NativeHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 权益。
 *
 * ## 这个应用的授权长什么样
 *
 * 判定不在 Java 里。`libpowerampcore.so` 通过 `Sync` 那八个动态注册的 JNI 方法
 * 完成校验，Java 只是**触发方与消费方**。结论从原生侧回来时走两条出口，
 * 两条都得按住，缺一条界面就是半解锁状态：
 *
 * ```
 * 原生授权引擎
 *   ├─ Bundle{res, store, purchased, error, pending_end_ts}
 *   │    └─ MsgBus 252510544 → BaseApplication.onBusMsg → 写进那个 int 偏好项
 *   │         └─ 二十多处直接读它：状态总线、购买项、设置导入、欢迎页、授权状态项
 *   └─ 32 字节共享 ByteBuffer（同一块内存，原生写 Java 读）
 *        └─ 槽位 1 授权结果 / 2 已有套餐数 / 3 归属方式 / 4 套餐总数
 *             └─ 功能套餐的可用性、以及若干设置项开不开
 * ```
 *
 * 阈值是 `229`；取 [Poweramp.FULL_VERIFIED] 是因为 272 那条分支还会把待处理购买
 * 清零，拿到的是一个没有待办的干净状态。
 *
 * ## 为什么写一次不够
 *
 * 授权检查在**每次主界面启动时**重跑一遍（`BaseApplication` 延时 250ms 投任务，
 * 走后台线程调 `Sync`），回来就把上面两处一起覆盖掉。实测把两处直接改成 272 之后，
 * 只要回一次主界面就被打回 4。所以这里不做一次性写入，而是**挂在结果回来的那条路上**：
 * 结果 Bundle 在被消费之前改掉，共享块在原生侧写完之后立刻补回去。
 *
 * ## 时序：必须在 onBusMsg 之前补共享块
 *
 * 处理 252510544 的过程里会同步转发一条 252510548，功能套餐的状态类正是在那时候
 * 重新读共享块。所以补写只放在 after 是晚的 —— 第一次进设置页会看到旧状态，得再进
 * 一次才对。前后各补一次就没有这个窗口了。
 *
 * ## 只有买断，没有订阅
 *
 * 商品 `fv0` 是 `inapp`，界面上写着「一次性购买 | 终身许可证」，应用里没有任何
 * 续订、到期或回收的概念。所以「终身」不需要另外伪造一个标记 —— 完整版本身就是终身的。
 * 另外两条商品线（功能套餐 `fp*`、Uber Patron 徽章 `uberpatron*`）也都是买断。
 */
internal object Entitlement {

    /** 三个功能共用同一套钩子，装一次。 */
    private val installed = AtomicBoolean(false)

    @Volatile private var fullVersion = false

    @Volatile private var featurePacks = false

    @Volatile private var offline = false

    @Volatile private var verbose = false

    /**
     * 把授权结果按成「完整版·已验证」。
     *
     * 结果 Bundle 在 [Poweramp.BASE_APPLICATION] 消费它之前就被改掉，所以原生侧
     * 查出来是什么并不重要 —— 试用中、试用到期、连不上 Google、签名对不上，
     * 到 Java 这一侧统一变成 272。这也是「冻结 Google 服务仍可用」的根据：
     * 走的不是「让校验通过」，而是「替换掉校验的结论」。
     */
    fun HookScope.installFullVersion(refs: PowerampDex.Refs) {
        if (!refs.canUnlock) error("没定位到授权结果，无法解锁完整版")
        fullVersion = true
        installBridge(refs)
        log.i("完整版已解锁（授权结果按 ${Poweramp.FULL_VERIFIED} 计）")
    }

    /**
     * 把功能套餐记为「随完整版赠送」。
     *
     * 套餐是与完整版并列的另一条商品线，界面之外还管着一批设置项 ——
     * 未拥有时它们在设置页里是灰的（那段判定是「已有套餐数 >= 1 或 授权结果 == 4」，
     * 后者正是试用期，所以试用期内看起来什么都能用，试用一结束就锁上）。
     *
     * 已有套餐数写成原生侧报回来的**总数**而不是写死 1：将来出了第二个套餐，
     * 总数会跟着变大，这里不用改。归属方式取「随完整版赠送」——
     * 它是唯一一个既显示「已包含」又不再挂购买按钮的值。
     *
     * 需要原生层可用：共享块 Java 侧拿到的是只读视图，写它得先取到那块内存的地址。
     */
    fun HookScope.installFeaturePacks(refs: PowerampDex.Refs) {
        if (!refs.canWriteBlob) error("共享状态块不可写，无法解锁功能套餐")
        featurePacks = true
        installBridge(refs)
        log.i("功能套餐已记为随完整版赠送")
    }

    /**
     * 抹掉授权检查的失败痕迹。
     *
     * Google 不可用时原生侧回的是一条带 `error` 的结果，应用会把那段文案落进存档并在
     * 设置页顶部长期显示（「许可校验失败」「无法连接服务器」这一类），同时把待处理购买
     * 的时间戳记上，于是又多一条「购买处理中」的提示。两个字段在结果被消费之前就去掉，
     * 应用自己下一次写存档时会把旧文案一并清空。
     *
     * 与「解锁完整版」分开是有意的：那一项关掉时，用户仍然可能想要一个不因为断网而报错的
     * 界面。这一项自己就成立，不依赖前者。
     */
    fun HookScope.installOfflineTolerance(refs: PowerampDex.Refs) {
        offline = true
        installBridge(refs)
        log.i("授权检查的错误结果将被忽略")
    }

    /** 排查用。记的是**改写之前**的原始结果，所以开着它能看到原生侧真实的判定。 */
    fun HookScope.installLog(refs: PowerampDex.Refs) {
        verbose = true
        installBridge(refs)
        log.i("授权判定日志已开启${refs.describe()}")
    }

    // -----------------------------------------------------------------------
    // 共用的那套钩子
    // -----------------------------------------------------------------------

    private fun HookScope.installBridge(refs: PowerampDex.Refs) {
        if (!installed.compareAndSet(false, true)) {
            apply(refs, log)
            return
        }

        val application = classOrNull(Poweramp.BASE_APPLICATION)
            ?: error("找不到 ${Poweramp.BASE_APPLICATION}")
        val onBusMsg = application.declaredMethods.firstOrNull { it.name == "onBusMsg" }
            ?: error("${Poweramp.BASE_APPLICATION} 上没有 onBusMsg")

        onBusMsg.createBeforeHook("poweramp.license.result") { param ->
            if (param.args.getOrNull(1) != Poweramp.MSG_LICENSE_RESULT) return@createBeforeHook
            (param.args.getOrNull(4) as? Bundle)?.let { rewrite(it, log) }
            // 前置补写：处理这条消息的过程里会同步通知功能套餐去重读共享块。
            apply(refs, log)
        }
        onBusMsg.createAfterHook("poweramp.license.settle") { param ->
            if (param.args.getOrNull(1) != Poweramp.MSG_LICENSE_RESULT) return@createAfterHook
            apply(refs, log)
        }

        // 原生侧只可能在这四个 Java 包装方法之内动共享块 —— 它们是 Sync 上仅有的
        // 非 native 方法，进原生的每条路都从其中之一起步。挂 after 而不是 hook 那些
        // native 方法本身：包装方法是普通 Java 方法，改它们没有任何不确定性。
        val bridge = classOrNull(Poweramp.SYNC)?.declaredMethods
            ?.filter { Modifier.isStatic(it.modifiers) && !Modifier.isNative(it.modifiers) }
            .orEmpty()
        if (bridge.isEmpty()) {
            log.w("${Poweramp.SYNC} 上没有可挂的包装方法，共享块只在授权结果回来时补写")
        }
        bridge.forEach { method ->
            method.createAfterHook("poweramp.sync.${method.name}") { apply(refs, log) }
        }

        apply(refs, log)
        log.d("授权链路已接管：onBusMsg + ${bridge.size} 个 Sync 包装方法")
    }

    /** 改写授权结果 Bundle。改的是**输入**，原生侧查出来是什么都不影响结论。 */
    private fun rewrite(bundle: Bundle, log: HookerLog) {
        if (verbose) {
            log.i(
                "原始授权结果：res=${bundle.getInt(Poweramp.KEY_RESULT, Int.MIN_VALUE)}" +
                    "，store=${bundle.getInt(Poweramp.KEY_STORE, 0)}" +
                    "，purchased=${bundle.getInt(Poweramp.KEY_PURCHASED, 0)}" +
                    "，pending=${bundle.getInt(Poweramp.KEY_PENDING_END, 0)}" +
                    "，error=${bundle.getString(Poweramp.KEY_ERROR) ?: "无"}",
            )
        }
        if (fullVersion) {
            bundle.putInt(Poweramp.KEY_RESULT, Poweramp.FULL_VERIFIED)
            bundle.putInt(Poweramp.KEY_STORE, Poweramp.STORE_GOOGLE_PLAY)
            bundle.putInt(Poweramp.KEY_PURCHASED, 1)
        }
        if (fullVersion || offline) {
            bundle.putInt(Poweramp.KEY_PENDING_END, 0)
            bundle.remove(Poweramp.KEY_ERROR)
        }
    }

    /**
     * 把两处状态按到想要的值上。
     *
     * 每次都先比对再写：这个方法挂在 `Sync` 的每个包装方法之后，其中有些（播放服务的
     * 收尾）在播放线程上跑，所以常态必须是「几次 int 比较，什么都不做」。
     */
    @Synchronized
    private fun apply(refs: PowerampDex.Refs, log: HookerLog) {
        if (fullVersion) {
            val field = refs.licenseValue
            val owner = refs.licensePref
            if (field != null && owner != null) {
                runCatching {
                    if (field.getInt(owner) != Poweramp.FULL_VERIFIED) {
                        field.setInt(owner, Poweramp.FULL_VERIFIED)
                    }
                }.onFailure { log.w("写授权结果失败：${it.message}") }
            }
        }

        val blob = refs.blob ?: return
        val address = refs.blobAddress
        if (address == 0L) return

        val wanted = buildMap {
            if (fullVersion) put(Poweramp.SLOT_LICENSE, Poweramp.FULL_VERIFIED)
            if (featurePacks) {
                val total = blob.slot(Poweramp.SLOT_PACKS_AVAILABLE).coerceAtLeast(1)
                put(Poweramp.SLOT_PACKS_OWNED, total)
                put(Poweramp.SLOT_OWNERSHIP, Poweramp.OWNERSHIP_INCLUDED_IN_FULL)
            }
        }
        wanted.forEach { (slot, value) ->
            if (blob.slot(slot) == value) return@forEach
            val offset = address + slot * Int.SIZE_BYTES
            if (!NativeHook.writeMemory(offset, encode(value, blob))) {
                log.w("共享状态块槽位 $slot 写不进去")
            } else if (verbose) {
                log.d("共享状态块槽位 $slot -> $value")
            }
        }
    }

    /** 绝对定位读，不动 position —— 这个视图还在应用自己手里。 */
    private fun ByteBuffer.slot(index: Int): Int =
        runCatching { getInt(index * Int.SIZE_BYTES) }.getOrDefault(0)

    /** 按共享块自己的字节序编码；那块内存是原生侧建的，跟着它走。 */
    private fun encode(value: Int, blob: ByteBuffer): ByteArray =
        ByteBuffer.allocate(Int.SIZE_BYTES).order(blob.order()).putInt(value).array()
}
