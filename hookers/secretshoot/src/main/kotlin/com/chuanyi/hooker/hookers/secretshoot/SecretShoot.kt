package com.chuanyi.hooker.hookers.secretshoot

import com.chuanyi.hooker.core.HookScope
import java.lang.reflect.Method

/**
 * 目标内部结构的解析层。
 *
 * 秘拍（`com.weixikeji.secretshoot.googleV2`）的会员判定全部收束在**一个类**上，
 * 4.3.8 里 R8 把它命名为 `ij.f`。它是个进程单例，界面、服务、悬浮窗都只通过它提问：
 *
 * ```
 *   b()  取本地缓存的 Google 付费凭据（GooglePayBean）   ← 全部结论的唯一数据来源
 *   u()  = b().isVipValid()          正式会员是否有效
 *   r()  = u() && f().contains("permanent")   是否永久会员
 *   s()  试用期内            t()  看激励视频换来的时长内
 *   o()  = u() || s() || t()         有权益（功能闸门用这个）
 *   p()  = 三者皆无（弹推广用这个）   m() = !u() && !s()（**广告用这个**）
 *   q()  已登录（login_token 非空）
 *   f()/g()/h()/d()/n()/j()  产品号 / 购买令牌 / 剩余时间 / 通知类型 / 是否自动续订 / 会员名称
 * ```
 *
 * 关键在于：**`u`/`r`/`o`/`p`/`m`/`f`/`g`/`h`/`d`/`n`/`j` 全部由 [b] 的返回值推导出来。**
 * 所以整套解锁只要接管 `b()` 这一个方法 —— 换一张合成的永久凭据进去，十一个判定同时
 * 变成想要的样子，一个功能闸门都不用单独去按，广告也因为 `m()` 变 false 而不再加载。
 *
 * 凭据本身**没有自证**：全包搜不到对购买负载的验签，没有回执复验，服务端返回什么客户端
 * 就信什么。它唯一的来源是 `GooglePayService` 把 Play 的购买上报给自家服务器换回的
 * `GooglePayBean`，而那是个纯 Java 的 POJO。
 *
 * ## 类名一个都不写死（除了没被混淆的）
 *
 * `com.weixikeji.secretshoot.bean.*` 和 `com.weixikeji.secretshoot.preferences` 这两个包
 * R8 没动包名，`GooglePayBean` 的类名也留着 —— 它是本文件所有形状查询的锚点。
 * 权益中枢的类名（`ij.f`）是 R8 生成的，按特征串扫，见 [SecretShootDex]。
 */
internal object SecretShoot {

    /** 目标包名。 */
    const val PACKAGE = "com.weixikeji.secretshoot.googleV2"

    /**
     * Google 付费凭据。R8 保留了 `com.weixikeji.secretshoot.bean` 整个包的名字
     * （Gson 按字段名反序列化，改名就解析不出来），所以这个名字可以直接写死，
     * 也正因为可以写死，它才适合当所有形状查询的锚点。
     */
    const val GOOGLE_PAY_BEAN = "com.weixikeji.secretshoot.bean.GooglePayBean"

    /**
     * 会员周期的判定串。
     *
     * 权益中枢用 `productId.contains("permanent")` 之类的方式把商品号翻译成
     * 「月度 / 季度 / 年度 / 永久会员」。这四个片段是**商品号里必须出现的约定**，
     * 客户端和 Play 商品配置得对上，比任何被 R8 重命名的类名都稳。
     */
    val PRODUCT_KINDS = arrayOf("month", "quarter", "year", "permanent")

    /**
     * 离线宽限窗口那个判定里的两个配置键。
     *
     * 应用把「上次成功同步」记在这两个键上：同步成功写 `count=5` + 当前时间戳，
     * 之后 5 天内（或 5 次启动内）即使拿不到服务器，缓存的凭据依然算数；
     * 服务器明确回「无会员」则把两者清零，缓存立刻失效。
     */
    const val GRACE_TIMESTAMP_KEY = "fetch_user_info_failed_timestamp"
    const val GRACE_COUNT_KEY = "fetch_user_info_failed_count"

    /**
     * 合成凭据用的商品号。
     *
     * 只有一个硬要求：**包含 `permanent`** —— 权益中枢就是按这个子串判「是不是永久版」的，
     * 判到了才不会再去显示到期时间。可用 `product_id` 设置项覆盖。
     */
    const val DEFAULT_PRODUCT = "secret_shoot_permanent_vip"

    // --- AppLovin。第三方库，consumer 规则保住了公开 API，名字是明文 ---------
    const val MAX_AD_VIEW = "com.applovin.mediation.ads.MaxAdView"
    const val MAX_INTERSTITIAL = "com.applovin.mediation.ads.MaxInterstitialAd"

    // -----------------------------------------------------------------------
    // 权益中枢上的成员，一律按形状取。

    /**
     * `b()` —— 取缓存凭据。整个解锁的落点。
     *
     * 判据：本类里唯一一个「无参、返回 GooglePayBean」的方法。同一个类上不会有第二个。
     */
    fun credentialGetter(vault: Class<*>, bean: Class<*>): Method? =
        vault.declaredMethods.singleOrNull { m ->
            m.parameterCount == 0 && m.returnType == bean
        }

    /**
     * `w(GooglePayBean)` —— 把服务器结论写回本地。
     *
     * 参数为 null 时它做的是**清空**（把上面那两个宽限键归零），这正是「Play 报告
     * 未购买」时权益被抹掉的那一下。判据：唯一一个「收一个 GooglePayBean、返回 void」。
     */
    fun credentialWriter(vault: Class<*>, bean: Class<*>): Method? =
        vault.declaredMethods.singleOrNull { m ->
            m.parameterCount == 1 &&
                m.parameterTypes[0] == bean &&
                m.returnType == Void.TYPE
        }

    /** 本类上所有「无参返回 boolean」的判定方法 —— 排查项用来一次性打印全部结论。 */
    fun verdicts(vault: Class<*>): List<Method> =
        vault.declaredMethods.filter { m ->
            m.parameterCount == 0 && m.returnType == Boolean::class.javaPrimitiveType
        }

    // -----------------------------------------------------------------------

    /**
     * 造一张永久会员凭据。
     *
     * 字段按应用**实际会读**的那几个填，其余给合理值只是为了日志好看：
     *
     * | 字段 | 取值 | 为什么 |
     * |---|---|---|
     * | `productId` | 含 `permanent` | 「是不是永久版」就看这个子串 |
     * | `autoRenewing` | false | 见下 |
     * | `systemTimeMillis` | 0 | 见下 |
     * | `expiryTimeMillis` | 约一百年后 | 有效期判定的右端 |
     * | `paymentState` | 1 | Play 的「已收款」 |
     * | `notificationType` | 4 | Play 的 SUBSCRIPTION_PURCHASED。**不能是 11** —— 那是「暂停计划已变更」，界面会去显示暂停提示 |
     *
     * 有效期判定原样是：
     *
     * ```java
     * long t = getSystemTimeMillis();                              // 服务器时间
     * if (!isAutoRenewing()) t = max(t, System.currentTimeMillis()); // 防改本地时钟
     * return t < getExpiryTimeMillis();
     * ```
     *
     * `autoRenewing = false` 让它走 `max(服务器时间, 本机时间)` 那一支，于是
     * `systemTimeMillis` 填 0 也不影响结论 —— 这样凭据可以**只造一次并长期缓存**，
     * 不必为了让"服务器时间"跟上而每次调用重建。永不过期靠的是右端那个一百年。
     *
     * 顺带：`autoRenewing = false` 也让界面不去渲染「续订 / 切换套餐」那一支。
     */
    fun synthesize(scope: HookScope, product: String): Any? = runCatching {
        val bean = scope.classOrNull(GOOGLE_PAY_BEAN) ?: return null
        val instance = bean.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

        // setter 的形参是**装箱**类型（bean 里字段就是 Boolean / Long / Integer），
        // 所以取 javaObjectType；用 javaPrimitiveType 会 NoSuchMethodException。
        val boxedBoolean = Boolean::class.javaObjectType
        val boxedLong = Long::class.javaObjectType
        val boxedInt = Int::class.javaObjectType

        val now = System.currentTimeMillis()
        set(bean, instance, "setProductId", String::class.java, product)
        set(bean, instance, "setPurchaseToken", String::class.java, "chuanyi.$product")
        set(bean, instance, "setOrderId", String::class.java, ORDER_ID)
        set(bean, instance, "setAutoRenewing", boxedBoolean, false)
        set(bean, instance, "setSystemTimeMillis", boxedLong, 0L)
        set(bean, instance, "setCreationTimeMillis", boxedLong, PURCHASE_TIME_MS)
        set(bean, instance, "setStartTimeMillis", boxedLong, PURCHASE_TIME_MS)
        set(bean, instance, "setExpiryTimeMillis", boxedLong, now + NEVER_EXPIRES_MS)
        set(bean, instance, "setPaymentState", boxedInt, 1)
        set(bean, instance, "setNotificationType", boxedInt, NOTIFICATION_PURCHASED)
        set(bean, instance, "setPriceAmountMicros", String::class.java, "0")
        set(bean, instance, "setPriceCurrencyCode", String::class.java, "USD")
        set(bean, instance, "setCountryCode", String::class.java, "")
        set(bean, instance, "setUuid", String::class.java, "")

        instance
    }.onFailure { scope.log.e("合成会员凭据失败", it) }.getOrNull()

    /** 凭据的可读摘要，排查项用。 */
    fun describe(credential: Any?): String {
        if (credential == null) return "null"
        val cls = credential.javaClass
        fun call(name: String): Any? =
            runCatching { cls.getMethod(name).invoke(credential) }.getOrNull()
        return "productId=${call("getProductId")}, 到期=${call("getExpiryTimeMillis")}, " +
            "服务器时间=${call("getSystemTimeMillis")}, 自动续订=${call("isAutoRenewing")}, " +
            "有效=${call("isVipValid")}"
    }

    // -----------------------------------------------------------------------

    /**
     * 调 setter。
     *
     * 装箱类型的 setter（`Boolean` / `Long` / `Integer`）和基本类型的签名不同，
     * 所以形参类型要照着 bean 的声明给，不能用 `javaPrimitiveType`。
     */
    private fun set(
        bean: Class<*>,
        instance: Any,
        name: String,
        type: Class<*>,
        value: Any,
    ) {
        runCatching { bean.getDeclaredMethod(name, type).invoke(instance, value) }
    }

    /** 约一百年，毫秒。 */
    private const val NEVER_EXPIRES_MS = 3_155_760_000_000L

    /** 固定的购买时间，免得冷启动看起来像「几秒前刚买」。2023-11-14。 */
    private const val PURCHASE_TIME_MS = 1_700_000_000_000L

    /** Play 的 `SUBSCRIPTION_PURCHASED`。 */
    private const val NOTIFICATION_PURCHASED = 4

    private const val ORDER_ID = "GPA.0000-0000-0000-00000"
}
