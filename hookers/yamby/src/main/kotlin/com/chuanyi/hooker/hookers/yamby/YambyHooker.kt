package com.chuanyi.hooker.hookers.yamby

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookFeature
import com.chuanyi.hooker.core.HookScope
import io.github.lingqiqi5211.ezhooktool.core.findAllConstructors
import io.github.lingqiqi5211.ezhooktool.core.findMethodOrNull
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Yamby (`com.hush.yamby`) —— Emby/Jellyfin 客户端，Compose + mpv + ffmpeg。
 *
 * 这个包的防护是分层的：R8 重命名 + StringFog 异或字符串 + 阿拉伯变音符号类名，
 * 核心业务包（`cd` / `ad` / `dd` / `pc` / `sc` / `lc` / `ld`）再经 nmmp(dex2c)
 * 整体原生化 —— 判断 Pro 的代码全在 `libnmmp.so` 里，静态看不到、smali 也改不动。
 *
 * 但这些防护全部朝内。授权本身**没有任何自证**：APK 里没有 `SHA1withRSA`
 * 校验购买签名，没有授权服务器，也没有回执复验，应用完全信任 Google Play
 * `queryPurchasesAsync` 报回来的东西。而这条链路走的是 Play Billing 的
 * **旧版 AIDL**，回包是一个纯 Java 层的 Bundle：
 *
 * ```
 * IInAppBillingService.getPurchases() -> Bundle{ RESPONSE_CODE, 三个平行数组 }
 *   -> new Purchase(dataList[i], signatureList[i])      ← 库里做的，纯 Java
 *     -> 应用自己的 PurchasesResponseListener            ← nmmp 原生方法
 *       -> 置 Pro，并把权益写进 MMKV
 * ```
 *
 * 所以整套解锁只需要往那个 Bundle 里多塞一条记录（[lifetime]），
 * 原生层、签名、smali 一概不碰。应用随后会**自己把权益持久化**，
 * 即使之后停用本模块，Pro 依然在。
 *
 * 顺带一提：`MainActivity` 里有一段自校验，读自身 APK 的 `META-INF` 签名证书。
 * Xposed 不改包，所以它天然通过 —— 这也是这里不选择改包重签的原因。
 */
class YambyHooker : AppHooker {

    override val id = "yamby"
    override val displayName = "Yamby"
    override val description = "影视客户端，解锁终身 Pro"
    override val targetPackages = setOf("com.hush.yamby")

    /** 已捕获的购买回调实例，由 [announce] 使用。 */
    private val listeners = Collections.synchronizedList(ArrayList<Any>())
    private val announced = AtomicBoolean(false)

    override val features: List<HookFeature> = listOf(
        HookFeature(
            id = "force_entitlement",
            title = "启动即生效",
            summary = "默认要先进一次订阅页 Pro 才亮。开启后从进入应用起就是开通状态，也不需要联网",
            install = { installForceEntitlement() },
        ),
        HookFeature(
            id = "lifetime",
            title = "解锁终身 Pro",
            summary = "让应用按自己的正常流程认定终身版已购买，Pro 功能全部开放。" +
                "解锁结果会被应用记住，之后即使关掉本模块也仍然有效",
            install = { installLifetime() },
        ),
        HookFeature(
            id = "billing_resilient",
            title = "无视购买校验失败",
            summary = "没有应用商店服务、离线、或首次冷启动时，解锁一样成立",
            install = { installBillingResilience() },
        ),
        HookFeature(
            id = "announce",
            title = "主动补发购买结果",
            summary = "有些界面只在收到购买通知时才刷新，这一项负责把那条通知补上",
            install = { installAnnounce() },
        ),
        HookFeature(
            id = "log_billing",
            title = "记录购买信息",
            summary = "排查用：记录应用实际读到的购买记录，用来确认解锁是否生效",
            defaultEnabled = false,
            install = { installBillingLog() },
        ),
    )

    override fun isCompatible(scope: HookScope): Boolean {
        val purchase = scope.classOrNull(Billing.PURCHASE)
        if (purchase == null) {
            scope.log.w("找不到 ${Billing.PURCHASE}，Play Billing 库可能被换掉了")
            return false
        }
        return runCatching {
            purchase.getConstructor(String::class.java, String::class.java)
        }.onFailure {
            scope.log.w("Purchase 没有 (String, String) 构造器，版本不兼容")
        }.isSuccess
    }

    override fun onHook(scope: HookScope) {
        scope.log.i("Yamby ${scope.versionCode} in ${scope.processName}")
    }

    // -----------------------------------------------------------------------

    /**
     * 让解锁在**冷启动第一帧**就成立。
     *
     * [lifetime] 走的是应用自己的计费流程 —— 干净、可持久化，但要等应用发起
     * `queryPurchasesAsync`，而这件事在 Yamby 里是**打开 Pro 页面时**才做的。
     * 所以首次安装后必须先进一次订阅页，Pro 才亮。
     *
     * 这一项绕过那个时序：应用把权益缓存在 `emby_setting` 的
     * [Billing.ENTITLEMENT_KEY]（`Boolean`，默认 false），每次启动都读。
     * 把这个读取按成 true，Pro 在任何界面组合之前就已经成立。
     *
     * 只改读取结果，不写盘 —— 停用本项后应用立刻回到它自己的判断。
     *
     * MMKV 的布尔存取有三个同形方法（`getBoolean` / `decodeBool` / `encodeBool`
     * 在 R8 之后都是 `(String, boolean) -> boolean`），无法只按签名分辨读写。
     * 三个一起改成 true 正好：读取得到已购，写入得到「写成功」，都无害。
     */
    private fun HookScope.installForceEntitlement() {
        val key = string(KEY_ENTITLEMENT)?.takeIf { it.isNotBlank() } ?: Billing.ENTITLEMENT_KEY
        val mmkv = classOrNull(Billing.MMKV) ?: error("${Billing.MMKV} not found")
        val bool = Boolean::class.javaPrimitiveType!!

        fun plainBooleanMethods(vararg params: Class<*>) = mmkv.declaredMethods.filter { method ->
            !Modifier.isNative(method.modifiers) &&
                method.returnType == bool &&
                method.parameterTypes.contentEquals(params)
        }

        // (String, boolean): 取值 / 存值
        val accessors = plainBooleanMethods(String::class.java, bool)
        // (String): 存在性判断
        val probes = plainBooleanMethods(String::class.java)
        if (accessors.isEmpty()) error("MMKV 上没有 (String, boolean) 形状的布尔存取方法")

        (accessors + probes).forEach { method ->
            method.createAfterHook("yamby.entitlement.${method.name}") { param ->
                if (param.arg(0) != key) return@createAfterHook
                if (param.result == true) return@createAfterHook
                param.result = true
            }
        }

        log.i("本地权益 $key 已强制为已购（${accessors.size} 个存取 + ${probes.size} 个判存）")
    }

    /**
     * 唯一真正的解锁点。
     *
     * Play Billing 从回包 Bundle 里取三个**平行**数组：商品 ID、购买 JSON、
     * 签名，然后按下标逐条拼成 `Purchase`。三个数组各补一条，就等于凭空多了
     * 一笔已完成的订单。
     *
     * 返回的是副本而不是就地改原列表 —— 库对同一个 Bundle 会读两次
     * （一次判空、一次解析），改原列表会导致重复追加、三个数组长度错位，
     * 直接把应用打成 `IndexOutOfBoundsException`。
     *
     * 判重读的是 Bundle 里的原始数组（`get` 而非 `getStringArrayList`，
     * 避免递归进本 hook），所以三个 key 得到的判定完全一致，长度不会跑偏。
     */
    private fun HookScope.installLifetime() {
        val product = productId()
        val json = Billing.purchaseJson(packageName, product)

        bundleMethod("getStringArrayList", String::class.java)
            .createAfterHook("yamby.lifetime.purchases") { param ->
                val key = param.arg(0) as? String ?: return@createAfterHook
                if (key !in Billing.PURCHASE_LISTS) return@createAfterHook

                val bundle = param.thisObject as? Bundle ?: return@createAfterHook
                if (!isPurchasesReply(bundle)) return@createAfterHook
                // 判重刻意走 get 而不是 getStringArrayList：后者就是本 hook 自己，
                // 且返回的是副本，读原始数组才能对三个 key 得到一致的判定。
                @Suppress("DEPRECATION")
                val stored = bundle.get(Billing.KEY_DATA_LIST) as? Collection<*>
                if (Billing.ownsProduct(stored, product)) return@createAfterHook

                @Suppress("UNCHECKED_CAST")
                val current = param.result as? ArrayList<String> ?: ArrayList()
                val patched = ArrayList<String>(current)
                patched += when (key) {
                    Billing.KEY_ITEM_LIST -> product
                    Billing.KEY_DATA_LIST -> json
                    else -> ""              // 签名数组：应用不校验，空串即可
                }
                param.result = patched
            }

        log.i("已在 Play 已购列表中注入 $product")
    }

    /**
     * 让解锁在 Play 不配合时也成立。
     *
     * 两处：响应码非 0 时库会整包丢弃已购列表；三个数组任意一个 `containsKey`
     * 为假时库连解析都不会开始。把这两处按成功处理，[lifetime] 注入的那条
     * 记录就能穿过错误路径。
     *
     * 作用域收得很紧：只认 `RESPONSE_CODE` 这一个 key，以及带着它的 Bundle。
     * 这几个 key 属于 Play Billing 协议，进程里不会有别人用。
     */
    private fun HookScope.installBillingResilience() {
        bundleMethod("get", String::class.java)
            .createAfterHook("yamby.billing.response_code") { param ->
                if (param.arg(0) != Billing.KEY_RESPONSE_CODE) return@createAfterHook
                val code = param.result as? Int ?: return@createAfterHook
                if (code == 0) return@createAfterHook
                log.d("Play 响应码 $code -> 0")
                param.result = 0
            }

        bundleMethod("containsKey", String::class.java)
            .createAfterHook("yamby.billing.has_lists") { param ->
                val key = param.arg(0) as? String ?: return@createAfterHook
                if (key !in Billing.PURCHASE_LISTS) return@createAfterHook
                if (param.result == true) return@createAfterHook
                val bundle = param.thisObject as? Bundle ?: return@createAfterHook
                if (!isPurchasesReply(bundle)) return@createAfterHook
                param.result = true
            }

        log.i("计费失败路径已中和")
    }

    /**
     * 兜底：Play 查询压根没发生时（没有 Play 服务、服务绑定失败），
     * 上面两项都没有可以搭车的 Bundle。
     *
     * 这里改为主动投递：hook 回调对象的构造器把实例抓在手里，等计费客户端
     * 连接完成后自己调一次。回调方法是 nmmp 原生方法 —— 但这里只是**调用**
     * 它，不需要 hook，反射调用原生方法与调用普通方法没有区别。
     *
     * 延迟是为了排在真正的查询结果之后：真结果先到，我们再覆盖，
     * 而不是反过来被它冲掉。
     */
    private fun HookScope.installAnnounce() {
        val listenerClass = resolveListenerClass()
            ?: error("找不到购买回调类；可用 listener_class 设置手动指定")
        val delivery = Billing.deliveryMethod(listenerClass)
            ?: error("${listenerClass.name} 上没有 (BillingResult, List) 形状的方法")
        val delay = int(KEY_ANNOUNCE_DELAY, DEFAULT_ANNOUNCE_DELAY_MS).toLong()

        listenerClass.findAllConstructors().forEach { constructor ->
            constructor.createAfterHook("yamby.announce.capture") { param ->
                val instance = param.thisObjectOrNull ?: return@createAfterHook
                listeners += instance
                schedule(delivery, delay)
            }
        }

        log.i("已挂上 ${listenerClass.name} 的构造器，${delay}ms 后补发")
    }

    /**
     * 只投递一次。回调对象会构造多个（查询一个、购买更新一个），
     * 第一个建好就排任务，到点时把当时抓到的全部实例一起喂。
     */
    private fun HookScope.schedule(delivery: Method, delay: Long) {
        if (!announced.compareAndSet(false, true)) return
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { deliver(delivery) }
                .onFailure { log.e("补发购买回调失败", it) }
        }, delay)
    }

    private fun HookScope.deliver(delivery: Method) {
        val product = productId()
        val purchase = Billing.newPurchase(this, Billing.purchaseJson(packageName, product))
            ?: run { log.w("构造 Purchase 失败，跳过补发"); return }
        val result = Billing.okResult(delivery.parameterTypes[0])
            ?: run { log.w("构造 BillingResult 失败，跳过补发"); return }

        delivery.isAccessible = true
        val snapshot = synchronized(listeners) { listeners.toList() }
        var delivered = 0
        snapshot.forEach { listener ->
            runCatching { delivery.invoke(listener, result, listOf(purchase)) }
                .onSuccess { delivered++ }
                .onFailure { log.d("回调实例 ${listener.javaClass.name} 投递失败: ${it.message}") }
        }
        log.i("已向 $delivered/${snapshot.size} 个回调补发 $product")
    }

    /**
     * 只读诊断。
     *
     * `Purchase` 的 getter 被 R8 全部内联掉了，类里只剩两个字段，所以直接读
     * 构造参数最省事 —— 那就是 Play 原样返回的 JSON 与签名。
     */
    private fun HookScope.installBillingLog() {
        val purchase = classOrNull(Billing.PURCHASE) ?: error("${Billing.PURCHASE} not found")
        purchase.getConstructor(String::class.java, String::class.java)
            .createAfterHook("yamby.log.purchase") { param ->
                log.i("Purchase: ${param.arg(0)}")
            }

        bundleMethod("get", String::class.java)
            .createAfterHook("yamby.log.response_code") { param ->
                if (param.arg(0) == Billing.KEY_RESPONSE_CODE) {
                    log.i("Play RESPONSE_CODE = ${param.result}")
                }
            }

        log.i("计费日志已开启")
    }

    // -----------------------------------------------------------------------

    /**
     * 认不认这个 Bundle 是 Play 的已购回包。
     *
     * `RESPONSE_CODE` 是 Play Billing 协议自带的 key，进程里的其它 Bundle
     * 不会有它 —— 用它当门闩，比按 key 名单匹配更不容易误伤。
     */
    private fun isPurchasesReply(bundle: Bundle): Boolean =
        runCatching { bundle.containsKey(Billing.KEY_RESPONSE_CODE) }.getOrDefault(false)

    /** `containsKey` / `get` 声明在 `BaseBundle` 上，所以要往父类找。 */
    private fun bundleMethod(methodName: String, vararg paramTypes: Class<*>): Method =
        Bundle::class.java.findMethodOrNull {
            name(methodName)
            params(*paramTypes)
            findAndSuper()
        } ?: error("android.os.Bundle 上找不到 $methodName(${paramTypes.joinToString { it.simpleName }})")

    private fun HookScope.productId(): String =
        string(KEY_PRODUCT_ID)?.takeIf { it.isNotBlank() } ?: Billing.PRODUCT_LIFETIME

    /**
     * 回调类按名字取、按形状验：名字是 R8 生成的，可能随版本变；
     * 「唯一一个二参且第二参是 List 的方法」这个形状不会变。
     */
    private fun HookScope.resolveListenerClass(): Class<*>? {
        val pinned = string(KEY_LISTENER_CLASS)?.takeIf { it.isNotBlank() } ?: Billing.LISTENER
        val clazz = classOrNull(pinned) ?: run {
            log.w("$pinned 不存在（应用可能已更新）")
            return null
        }
        if (Billing.deliveryMethod(clazz) == null) {
            log.w("$pinned 形状不符，不是购买回调")
            return null
        }
        return clazz
    }

    private companion object {
        /** 商品 ID 可被商店改名，留一个覆盖入口。 */
        const val KEY_PRODUCT_ID = "product_id"
        const val KEY_ENTITLEMENT = "entitlement_key"
        const val KEY_LISTENER_CLASS = "listener_class"
        const val KEY_ANNOUNCE_DELAY = "announce_delay_ms"

        /** 够真实查询跑完，又不至于让用户先看见一眼未解锁的界面。 */
        const val DEFAULT_ANNOUNCE_DELAY_MS = 3_000
    }
}
