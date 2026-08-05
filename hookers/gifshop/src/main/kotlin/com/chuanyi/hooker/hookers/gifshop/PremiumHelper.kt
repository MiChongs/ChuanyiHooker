package com.chuanyi.hooker.hookers.gifshop

import com.chuanyi.hooker.core.HookScope
import java.lang.reflect.Method

/**
 * Zipoapps **PremiumHelper 5.2.1** 的协议常量与取用点。
 *
 * 这个 SDK 被上百个应用共用，GIFShop 只是其中之一 —— 所以这里的东西是**协议**，
 * 不是这一个包的实现细节：键名要跨版本保住用户已购状态，不能改；Play Billing 那几个
 * 类名是 Google 的公开 API，R8 不会动。真正会随版本漂的只有被 R8 重命名的
 * `com.zipoapps.premiumhelper.d.z()`，那个交给 [PremiumHelperDex] 去找。
 *
 * ## 权益是怎么成立的
 *
 * 整个高级版判定收敛到**一个布尔值**：
 *
 * ```
 * Play 查询回包 → Billing.getActivePurchases()
 *   → Preferences.setHasActivePurchase(有没有已购)       写 premium_helper_data
 *     → Preferences.hasActivePurchase()                  读，且每次都读，不缓存
 *       ├── PremiumHelper.hasActivePurchase()  → 应用的 ie.a0.b()
 *       │     ├── 主界面菜单：购买入口 ↔ 语言设置
 *       │     └── 导出分辨率上限 1000 → 1280
 *       ├── 广告：横幅/插屏/开屏全部直接 return
 *       ├── GDPR 同意书：跳过（这一条要联网，冻结 Google 时尤其关键）
 *       └── 各种订阅弹窗 / 重启促销：跳过
 * ```
 *
 * 应用侧没有任何独立的会员开关 —— 全包只有两处读 `ie.a0.b()`，其余都在 SDK 里。
 * 所以把这一个布尔按住，高级版就整体成立，且**不依赖 Google 服务**：
 * 判定读的是本地 SharedPreferences，不是 Play。
 *
 * ## 没有的东西
 *
 * 值得单独记一笔，因为这决定了不用做什么：
 *
 * * **没有服务端复验** —— 全包搜不到对购买回执的二次校验，应用完全信任本地那个布尔。
 * * **没有签名自校验** —— 不改包所以本来也不相干，但这也意味着不必走重打包路线。
 * * **没有原生授权** —— 四个 `.so`（`libtuyen` / `libgifcodec` / `libgifsicle` /
 *   `libpl_droidsonroids_gif`）全是 GIF 编解码，`libtuyen.so` 导出的是
 *   `nativeEncoderInit` / `addFrame` 这类东西，和授权无关。
 */
internal object PremiumHelper {

    /**
     * 权益键。
     *
     * 这是 PremiumHelper 存放「有没有有效购买」的 SharedPreferences 键，落在
     * [PREFS_FILE] 里。它同时是 [PremiumHelperDex] 扫描判定函数用的特征串 ——
     * 改了它所有老用户的已购状态都会丢，所以 SDK 不会改。
     */
    const val KEY_ENTITLEMENT = "has_active_purchase"

    /** 购买详情（Gson 序列化的 `ActivePurchaseInfo`）。只被分析上报和客服邮箱读，可空。 */
    const val KEY_PURCHASE_INFO = "active_purchase_info"

    /** `Preferences` 构造器里写死的文件名，见 `com.zipoapps.premiumhelper.d.<init>`。 */
    const val PREFS_FILE = "premium_helper_data"

    /** 判定函数所在的包。DexKit 只扫这里，不扫整包 38 MB 的 dex。 */
    const val PACKAGE = "com.zipoapps.premiumhelper"

    /** PremiumHelper 主类。包名被保留，只用来判本 hooker 该不该起作用。 */
    const val PREMIUM_HELPER = "com.zipoapps.premiumhelper.e"

    // --- Play Billing（Google 公开 API，R8 不重命名） -------------------------

    const val BILLING_CLIENT = "com.android.billingclient.api.BillingClient"
    const val BILLING_CLIENT_BUILDER = "com.android.billingclient.api.BillingClient\$Builder"
    const val BILLING_RESULT = "com.android.billingclient.api.BillingResult"

    // --- 框架侧存储实现 ------------------------------------------------------

    /**
     * `Context.getSharedPreferences` 返回的实现类。
     *
     * 拦这一层的意义在于**完全不需要目标的任何名字**：键名是 PremiumHelper 协议，
     * 类名是 Android 框架，两头都不随目标版本变。代价是进程内每次布尔读取都会过一次
     * 字符串比较 —— 长度不同的字符串 `equals` 直接短路，可以忽略。
     */
    const val SHARED_PREFS_IMPL = "android.app.SharedPreferencesImpl"
    const val SHARED_PREFS_EDITOR_IMPL = "android.app.SharedPreferencesImpl\$EditorImpl"

    // -----------------------------------------------------------------------

    private val BOOLEAN = Boolean::class.javaPrimitiveType!!

    /** `SharedPreferencesImpl.getBoolean(String, boolean)`。 */
    fun getBooleanMethod(scope: HookScope): Method? =
        scope.classOrNull(SHARED_PREFS_IMPL)
            ?.let { runCatching { it.getDeclaredMethod("getBoolean", String::class.java, BOOLEAN) }.getOrNull() }

    /** `SharedPreferencesImpl.EditorImpl.putBoolean(String, boolean)`。 */
    fun putBooleanMethod(scope: HookScope): Method? =
        scope.classOrNull(SHARED_PREFS_EDITOR_IMPL)
            ?.let { runCatching { it.getDeclaredMethod("putBoolean", String::class.java, BOOLEAN) }.getOrNull() }

    /**
     * 造一个 `BillingResult{responseCode = 0}`。
     *
     * `BillingResult` 的构造器是私有的，但它的 `Builder` 是公开静态嵌套类，
     * PremiumHelper 自己也是这么造的（`BillingResult.newBuilder().setResponseCode(0).build()`），
     * 所以这条路一定在包里。
     */
    fun okResult(scope: HookScope): Any? = runCatching {
        val clazz = scope.classOrNull(BILLING_RESULT) ?: return null
        val builder = clazz.getMethod("newBuilder").invoke(null) ?: return null
        builder.javaClass.getMethod("setResponseCode", Int::class.javaPrimitiveType).invoke(builder, 0)
        builder.javaClass.getMethod("build").invoke(builder)
    }.getOrNull()
}
