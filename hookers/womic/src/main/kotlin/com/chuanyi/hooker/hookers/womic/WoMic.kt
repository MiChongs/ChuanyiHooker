package com.chuanyi.hooker.hookers.womic

/**
 * WO Mic（`com.wo.voice2`）权益链路里那些**不随混淆变化**的事实。
 *
 * 目标是 R8 处理过的：类名压成 `O2.a` / `Z2.f` / `R2.c` 这种，下个版本必然重排。
 * 所以这里一个类名都不当作协议 —— 写在这个文件里的只有两类东西：
 *
 * 1. **持久化键名**（[KEY_PURCHASE_STATE] / [KEY_PRODUCT_ID] / [PREFS_NAME]）。
 *    改了它们等于把老用户的设置全丢掉，厂商不会改，比任何类名都稳。
 * 2. **Play 商品 ID**（[PRODUCT_YEARLY] / [PRODUCT_MONTHLY]）。这是和 Google 后台
 *    对齐的东西，改了得重新配商品，同样不会随手动。
 *
 * 混淆过的落点全部走 [WoMicDex] 按形状扫，不在这里写死。
 */
internal object WoMic {

    // -----------------------------------------------------------------------
    // 持久化协议
    // -----------------------------------------------------------------------

    /** 设置单例用的 SharedPreferences 名，`getSharedPreferences("settings", 0)`。 */
    const val PREFS_NAME = "settings"

    /**
     * 订阅状态。取值直接来自 Play Billing 的 `Purchase.getPurchaseState()`：
     *
     * | 值 | 含义 | 应用行为 |
     * |---|---|---|
     * | 0 | 没有购买 | 显示广告、音量条锁死 |
     * | 1 | 已购买 | 全部付费功能可用 |
     * | 2 | 待支付 | 显示「处理中」，功能仍锁 |
     *
     * 应用启动时从这里读一次进内存，之后每次回到前台都会拿 Play 的查询结果覆盖它。
     */
    const val KEY_PURCHASE_STATE = "purchaseState"

    /** 上次生效的订阅商品 ID。只用于界面展示，不参与判定。 */
    const val KEY_PRODUCT_ID = "productId"

    /** [KEY_PURCHASE_STATE] 的「已购买」。 */
    const val STATE_PURCHASED = 1

    // -----------------------------------------------------------------------
    // Play 商品
    // -----------------------------------------------------------------------

    /** 包里只有这两个 subs 商品，没有一次性买断项。 */
    const val PRODUCT_YEARLY = "premium_yearly"
    const val PRODUCT_MONTHLY = "premium_monthly"

    // -----------------------------------------------------------------------
    // 系统类
    // -----------------------------------------------------------------------

    const val PREFS_IMPL = "android.app.SharedPreferencesImpl"
    const val EDITOR_IMPL = "android.app.SharedPreferencesImpl\$EditorImpl"

    // -----------------------------------------------------------------------
    // 资源名
    //
    // 资源**名**不会被 R8 改（改的是 R 类的字段引用，`resources.arsc` 里的名字还在），
    // 所以 `getIdentifier` 拿到的 id 跨版本可用，比写死 0x7f0e0123 稳。
    // -----------------------------------------------------------------------

    /** 订阅状态页与设置页显示「已订阅」的那条文案。 */
    const val STR_STATE_ACTIVE = "subscription_state_active"

    /** 同上，「未订阅」。 */
    const val STR_STATE_NONE = "subscription_state_none"

    /** 同上，「处理中」。 */
    const val STR_STATE_PENDING = "subscription_state_pending"
}
