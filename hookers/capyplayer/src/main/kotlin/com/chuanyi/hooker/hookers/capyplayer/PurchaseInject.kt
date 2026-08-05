package com.chuanyi.hooker.hookers.capyplayer

import com.chuanyi.hooker.core.HookScope
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook

import java.lang.reflect.Modifier


/**
 * 往 Play Billing 的查询回调里补一条终身买断，让应用**自己**把订阅落盘。
 *
 * ## 为什么走这一层
 *
 * [Entitlement] 那五处代码补丁解决了「功能能不能用」，但订阅页那张已订阅卡片解决不
 * 了 —— 它要的是一个真实的 `Subscription` 对象，而对象只能由应用自己的
 * `Subscription.fromJson` 造出来。想绕过它就得手写那份 JSON，字段名、枚举取值、嵌套
 * 的 plan 一个都不能错，猜错了 json_serializable 直接抛异常，比不改还糟。
 *
 * 所以不猜。让应用走它自己那条正路：
 *
 * ```
 * BillingClient.queryPurchasesAsync(params, listener)
 *   listener(BillingResult, List<Purchase>)        ← 【在这里补一条】
 *     → 计费库翻成 pigeon 的 PlatformPurchase
 *       → 编码投给 Dart
 *         → IAPService.purchaseStream
 *           → SubscriptionNotifier._handlePurchaseUpdate
 *             → _processPurchase → _saveSubscription   ← 应用用自己的格式写盘
 * ```
 *
 * 我们只造 `com.android.billingclient.api.Purchase`，那是整条链上唯一没被混淆、
 * 且构造器公开的类。剩下十几个 pigeon 数据类全由计费库自己填。
 *
 * ## 为什么按形状拦，以及为什么不能用 Proxy
 *
 * `queryPurchasesAsync` 在 R8 之后叫 `e`，回调类型是 `G6.d` —— 那是 R8 把一堆互不相
 * 干的接口横向合并出来的**抽象类**，不是接口。`java.lang.reflect.Proxy` 只代理接口，
 * 这条路直接堵死；而且方法名（`q`）和类名一样每次构建都会变。
 *
 * 稳定的是**形状**：计费库里凡是「查询完把结果交回来」的回调，签名一律是
 * `(BillingResult, List<Purchase>)`。
 *
 * 所以改成两段式：先挂住 `BillingClient` 上所有双参方法，从实参里拿到**真实的回调实
 * 例**，再按它的实际类去挂那个 `(?, List)` 方法。回调是 lambda，类名只有运行时才存
 * 在，这也是唯一能拿到它的时机。每个类只挂一次。
 *
 * 覆盖面同时包含 `queryPurchasesAsync`、历史查询和购买更新推送 —— 三者形状相同，补
 * 同一条记录都是对的。挂不上也只是少注入一次，不改变应用原有行为。
 */
internal object PurchaseInject {

    /**
     * 装上注入。
     *
     * @return 挂上钩子的方法数；0 表示没找着落点
     */
    fun install(scope: HookScope): Int {
        val field = Billing.billingClientField(scope) ?: return 0
        val clientClass = field.type
        val packageName = scope.packageName
        val log = scope.log

        val purchaseClass = scope.classOrNull("com.android.billingclient.api.Purchase") ?: run {
            log.w("找不到 com.android.billingclient.api.Purchase，注入跳过")
            return 0
        }
        val purchaseCtor = runCatching {
            purchaseClass.getDeclaredConstructor(String::class.java, String::class.java)
        }.getOrNull() ?: run {
            log.w("${purchaseClass.name} 没有 (String, String) 构造器，注入跳过")
            return 0
        }

        // 每次拦截都新造一条：Purchase 的 equals 只比 originalJson + signature，
        // 复用同一个实例在「查询」和「购买更新」两条路上会被去重掉一条。
        fun synthesize(): Any? = runCatching {
            purchaseCtor.newInstance(Billing.syntheticPurchaseJson(packageName), SIGNATURE)
        }.onFailure { log.w("造 Purchase 失败：${it.message}") }.getOrNull()

        // 第一段：任何双参方法都可能捎着回调。不筛接口——R8 把那些接口合并成抽象类了。
        val carriers = clientClass.declaredMethods.filter { m ->
            !m.isSynthetic && !m.isBridge &&
                m.parameterCount == 2 &&
                !Modifier.isStatic(m.modifiers) &&
                !m.parameterTypes[1].isPrimitive
        }
        if (carriers.isEmpty()) {
            log.w("${clientClass.name} 上没有可用的双参方法，注入跳过")
            return 0
        }

        val hooked = java.util.Collections.synchronizedSet(HashSet<String>())
        carriers.forEach { carrier ->
            carrier.createBeforeHook("capyplayer.inject.carrier.${carrier.name}") { param ->
                val listener = param.args.getOrNull(1) ?: return@createBeforeHook
                hookListenerClass(listener.javaClass, hooked, ::synthesize, log)
            }
        }
        log.i("购买注入已就位，监视 ${clientClass.simpleName} 的 ${carriers.size} 个入口：${carriers.joinToString { it.name }}")
        return carriers.size
    }

    /**
     * 第二段：按回调实例的真实类挂钩，每个类只挂一次。
     *
     * 判据是「两个参数、第二个是 List」。产品详情查询的回调也是这个形状，但它的列表里
     * 装的不是 `Purchase` —— 塞进去会让下游按产品详情读它而拿到一堆 null。所以再加一
     * 道：**只在列表为空、或列表里已经装着 `Purchase` 时才补**，用元素类型把两者分开。
     */
    private fun hookListenerClass(
        cls: Class<*>,
        hooked: MutableSet<String>,
        synthesize: () -> Any?,
        log: com.chuanyi.hooker.core.HookerLog,
    ) {
        if (!hooked.add(cls.name)) return
        val sinks = cls.declaredMethods.filter { m ->
            !m.isSynthetic && !m.isBridge &&
                m.returnType == Void.TYPE &&
                m.parameterCount == 2 &&
                List::class.java.isAssignableFrom(m.parameterTypes[1])
        }
        if (sinks.isEmpty()) return

        sinks.forEach { sink ->
            runCatching {
                sink.createBeforeHook("capyplayer.inject.sink.${cls.name}.${sink.name}") { param ->
                    val list = param.args.getOrNull(1) as? List<*> ?: return@createBeforeHook
                    if (!acceptsPurchases(list)) return@createBeforeHook
                    val extra = synthesize() ?: return@createBeforeHook
                    // 新建一份而不是就地改：计费库对同一个结果列表可能读两次，
                    // 就地追加会在第二次读时变成两条。
                    param.args[1] = ArrayList<Any?>(list).apply { add(extra) }
                    log.i("已往 ${cls.simpleName}.${sink.name} 的结果里补入终身买断（原 ${list.size} 条）")
                }
            }.onFailure { log.w("挂 ${cls.name}.${sink.name} 失败：${it.message}") }
        }
        log.i("回调实现 ${cls.name} 已挂上 ${sinks.size} 个出口")
    }

    /** 空列表，或者已经装着 Purchase 的列表 —— 两种都是「购买结果」而非产品详情。 */
    private fun acceptsPurchases(list: List<*>): Boolean {
        val first = list.firstOrNull() ?: return true
        return first.javaClass.name == "com.android.billingclient.api.Purchase"
    }

    /**
     * 签名字段。
     *
     * 本地一行验签代码都没有（全包 grep 不到 `SHA1withRSA`），这个值只是被原样带去
     * 服务端。给一段定长 base64 占位即可 —— 真要过服务端那关得有 Play 的私钥，
     * 那是另一条路的事。
     */
    private const val SIGNATURE =
        "Q2h1YW55aUhvb2tlclN5bnRoZXRpY1NpZ25hdHVyZVBsYWNlaG9sZGVyMDAwMDAwMDAwMDAw"
}
