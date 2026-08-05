package com.chuanyi.hooker.hookers.instashot

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * InShot 的常量、锚点串与「按形状挑成员」的工具。
 *
 * ## 授权链路（2.219.1545 实测）
 *
 * 整个 Pro 收敛到 `UserManager`（R8 后叫 `store.billing.T`）上的一个方法。原始文件名
 * jadx 还留着，所以这几个类的身份是确定的：
 *
 * ```
 * UserManager.isPro()                       ← 总判定，310 个调用点最终都问它
 *   ├── IAPBindHelper.isPro(ctx)            ← Jg/IapBind 存的布尔
 *   ├── UserManager.isSubscribePro()
 *   │     ├── UnlockPreferences.isSubscribePro(ctx)   ← iab 里的 "SubscribePro"
 *   │     ├── IAPBindHelper.isPro(ctx)
 *   │     └── UserManager.isPurchased("…fup")
 *   └── UserManager.isProOfHw()
 *         └── iab 里的 "SubscribeProOfHw"
 *
 * UserManager.isUnlocked(sku)               ← 商店条目/功能项都走它
 *   = isPro() || UnlockPreferences.isUnlocked(ctx, sku) || iab[sku]
 * ```
 *
 * **`isUnlocked` 不用单独接管**：它第一步就是 `if (!isPro() && …)`，`isPro()` 为真时
 * 直接短路返回 true。所以按住上面那几个读取点，310 个调用点一起成立 —— 这是读代码
 * 得到的结论，不是猜的。
 *
 * 广告与水印是同一条链的反面：`shouldShowAds() = !isUnlocked("…remove.ads")`，
 * 于是 `MobileAdsInitializer` 不再注册广告位、导出时不再叠水印、结果页横幅消失，
 * 都不需要各自单独 hook。
 *
 * ## 为什么这样就能扛住 Google 服务被冻结
 *
 * `UpdateBilling.applyPurchases()` 是唯一会**改写** `SubscribePro` 的地方，而它开头就是：
 *
 * ```java
 * if (result == null || list == null || result.responseCode != 0) {
 *     notifyListeners(UnlockPreferences.isSubscribePro(ctx));   // 广播当前值
 *     return;                                                   // ← 不写盘
 * }
 * ```
 *
 * 冻结 Google 之后结算连不上，future 被异常完成，走的正是这条提前 return —— 已写入的
 * 权益不会被清掉，还会顺带把「已是 Pro」广播给所有监听者。
 *
 * 反过来，Google **正常**且查不到购买时（responseCode == 0、列表为空）它会
 * `putBoolean("SubscribePro", false)` 并弹一次「Pro 不可用」。那条路由
 * [InShotHooker] 的 `no_billing_reset` 挡住。
 *
 * ## 锚点选择
 *
 * 类名全被 R8 重命名（`T` / `E` / `P` / `z`），但**落盘键名不能变** —— 改一次全体
 * 老用户的购买状态就丢了。所以定位一律以键名为锚，配签名消歧。每个锚点在
 * 2.219.1545 的全包出现次数都数过，见各常量注释。
 */
internal object InShot {

    const val PKG = "com.camerasideas.instashot"

    /** 买断版商品号。月/年订阅是另外几个，见 `SkuDefinition`。 */
    const val SKU_PERMANENT = "com.camerasideas.instashot.pro.permanent"

    /**
     * 总权益键，落在 MMKV 的 `iab` 档里。
     *
     * 锚点：全包 2 处 —— `UnlockPreferences.isSubscribePro()` 读，
     * `UpdateBilling.applyPurchases()` 写。签名完全不同，一次扫描两个都能拿到。
     *
     * ⚠️ 必须用 `StringMatchType.Equals`：`"SubscribeProOfHw"` 以它为前缀，
     * Contains 匹配会把华为分支那个方法一起捞进来。
     */
    const val KEY_SUBSCRIBE_PRO = "SubscribePro"

    /**
     * 逐商品解锁键的前缀（真实键是 `Unlocked_<sku>`）。
     *
     * 锚点：全包 2 处，都在 `UnlockPreferences` 里 —— `(Context,String)Z` 读、
     * `(Context,String,boolean)V` 写。
     */
    const val KEY_UNLOCKED_PREFIX = "Unlocked_"

    /**
     * 华为渠道的 Pro 标记。
     *
     * 锚点：全包**仅 1 处**，在 `UserManager.isProOfHw()` 里 —— 拿它认 `UserManager`
     * 这个类本身，比认类名稳。
     */
    const val ANCHOR_USER_MANAGER = "SubscribeProOfHw"

    /**
     * 内部调试开关，`UserManager.isPro()` 最后一行读它。
     *
     * 锚点：全包 3 处 —— 设置页的一个 lambda、一个 `(int)Z` 的调试项分发、
     * 以及 `isPro()` 本身。限定「`UserManager` 上的无参 `()Z`」后唯一。
     */
    const val ANCHOR_IS_PRO = "DebugPro"

    /**
     * 账号侧的 Pro 标记，存在 `IapBind` 档里。
     *
     * 锚点：全包多处（`isProcessing` / `isProgressive` 之类同前缀的一大堆），
     * **必须 Equals 匹配**，再限定 `static (Context)Z` 才唯一。
     */
    const val ANCHOR_IAP_IS_PRO = "isPro"

    /**
     * 「是否启用账号绑定」的远端配置键。
     *
     * 锚点：全包 2 处 —— 一处是默认配置表的 put，一处是
     * `IAPBindHelper.isBindSupported(Context)Z`。按签名分得开。
     */
    const val ANCHOR_BIND_SUPPORTED = "support_bind_1484"

    /**
     * 「继续弹 Pro 不可用提示」的日志串。
     *
     * 锚点：全包**仅 1 处**，就在
     * `UpdateBilling.shouldShowProUnavailable(Context)Z` 里。
     */
    const val ANCHOR_PRO_UNAVAILABLE = "Continue pop-up prompt"

    /**
     * 签名完整性校验。类名没被混淆（在 `com.cer` 下，被 keep 住了），
     * 方法名有；`(Context)I` 在这个类上唯一。
     *
     * 它卡的是 12 个 AI 原生能力：人像抠图、视频分割、美颜、瘦下巴、人脸检测、
     * 自动字幕、自动调色、去除物体、防抖、静音检测、自动踩点、目标追踪 ——
     * 校验不过这些 `init()` 直接返回 false，功能静默失效。
     */
    const val CER_CHECKER = "com.cer.CerChecker"

    /** 类名保留了包路径，扫描时可以收窄到这里。 */
    const val BILLING_PACKAGE = "com.camerasideas.instashot.store.billing"

    // --- 设置项键（应急覆盖用，不重新出包就能纠正） -------------------------

    const val KEY_USER_MANAGER_CLASS = "user_manager_class"

    // -----------------------------------------------------------------------

    private val BOOLEAN = Boolean::class.javaPrimitiveType!!
    private val INT = Int::class.javaPrimitiveType!!

    fun Method.isStatic(): Boolean = Modifier.isStatic(modifiers)

    /** `static (Context) -> boolean` */
    fun Method.isContextPredicate(): Boolean =
        isStatic() && returnType == BOOLEAN && parameterCount == 1 &&
            parameterTypes[0].name == "android.content.Context"

    /** `static (Context, String, boolean) -> void` —— `UnlockPreferences.setUnlocked` */
    fun Method.isUnlockWriter(): Boolean =
        isStatic() && returnType == Void.TYPE && parameterCount == 3 &&
            parameterTypes[0].name == "android.content.Context" &&
            parameterTypes[1] == String::class.java && parameterTypes[2] == BOOLEAN

    /** 实例方法 `() -> boolean` */
    fun Method.isNoArgPredicate(): Boolean =
        !isStatic() && returnType == BOOLEAN && parameterCount == 0

    /** 实例方法 `(Context) -> boolean` */
    fun Method.isInstanceContextPredicate(): Boolean =
        !isStatic() && returnType == BOOLEAN && parameterCount == 1 &&
            parameterTypes[0].name == "android.content.Context"

    /**
     * `UpdateBilling.applyPurchases(Context, BillingResult, List, Runnable)`。
     *
     * 第二个参数的类型名被 R8 改过（`com.android.billingclient.api.k`），不能写死；
     * 「四个参数、第一个是 Context、第三个是 List、返回 void」在全包已经唯一。
     */
    fun Method.isPurchaseApply(): Boolean =
        !isStatic() && returnType == Void.TYPE && parameterCount == 4 &&
            parameterTypes[0].name == "android.content.Context" &&
            List::class.java.isAssignableFrom(parameterTypes[2])

    /** `UserManager` 上唯一的 `(String, boolean) -> void`，直写 `iab` 档。 */
    fun writerIn(owner: Class<*>): Method? =
        owner.declaredMethods.singleOrNull {
            !it.isSynthetic && !it.isBridge && it.returnType == Void.TYPE &&
                it.parameterCount == 2 &&
                it.parameterTypes[0] == String::class.java && it.parameterTypes[1] == BOOLEAN
        }

    /** `UserManager` 上唯一的 `static (Context) -> UserManager` 单例工厂。 */
    fun factoryIn(owner: Class<*>): Method? =
        owner.declaredMethods.singleOrNull {
            !it.isSynthetic && Modifier.isStatic(it.modifiers) && it.returnType == owner &&
                it.parameterCount == 1 && it.parameterTypes[0].name == "android.content.Context"
        }

    /** `CerChecker` 上唯一的实例方法 `(Context) -> int`。 */
    fun cerCheckIn(owner: Class<*>): Method? =
        owner.declaredMethods.singleOrNull {
            !it.isSynthetic && !Modifier.isStatic(it.modifiers) && it.returnType == INT &&
                it.parameterCount == 1 && it.parameterTypes[0].name == "android.content.Context"
        }
}
