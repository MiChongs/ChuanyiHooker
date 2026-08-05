package com.chuanyi.hooker.hookers.capyplayer

import com.chuanyi.hooker.core.HookerLog
import com.chuanyi.hooker.hookers.capyplayer.DartPatch.Result
import com.chuanyi.hooker.hookers.capyplayer.DartPatch.Site

/**
 * CapyPlayer 1.1.3 (11312) 的权益判定落点。
 *
 * ## 判定链长什么样
 *
 * 包没混淆，下面这些名字都是从 `libapp.so` 里直接读出来的，不是推测：
 *
 * ```
 * SubscriptionNotifier.build()  →  SubscriptionState
 *   ├── _loadSubscription() → _checkIfActive()      ← 【落点 active】
 *   ├── _verifyWithServer()      POST /subscriptions/verify/google
 *   └── SubscriptionSyncService.mergeSubscriptions(local, server)
 *                     ↓ ref.watch
 *          isSubscribed(Ref) : bool                 ← 【落点 subscribed】★ 唯一的根
 *                     ↓
 *          proFeatureEntitlement(Ref, feature)      ← 【落点 entitlement】
 *                     ↓                                最后一条路径直接返回 isSubscribed
 *   PaywallGuard|ensureEntitled()                   ← 【落点 paywall】
 *   NativePlayerSubtitleSearchBridge
 *     ._ensureSubtitleSearchEntitled()              ← 【落点 subtitle】
 *
 *   ProBadge.build()  ────────────────────────────→ 直接读 isSubscribed，不经过上面任何一层
 * ```
 *
 * ## 为什么根是 `isSubscribed` 而不是 `ensureEntitled`
 *
 * `ensureEntitled` 是**最下游**的一个消费者，管的只是「点下去拦不拦」。界面上那些
 * 「解锁 PRO 获取完整体验」横幅、功能条目上的 `PRO` 角标、启动时的「升级至Pro」弹
 * 窗，走的都不是它 —— 只补它，功能能点开，但满屏还写着未订阅。
 *
 * 顺着屏幕上的文案往回找才看得清：画角标的 `ProBadge.build` 里
 *
 * ```
 * watch(enforcedProFeatures) → A     ; 这个功能算不算 Pro
 * watch(isSubscribed)        → B     ; 用户订没订
 * A == false → 不画;  B == true → 不画;  否则画 PRO 角标
 * ```
 *
 * 它**直接读 `isSubscribed`**，一层中间层都没有。而 `proFeatureEntitlement` 的三条
 * 出口里，前两条返回常量 `true`，最后一条 `return watch(isSubscribed)` —— 也是它。
 *
 * 全库只有 5 处引用 `isSubscribedProvider`，`ProBadge` 和 `proFeatureEntitlement`
 * 就占了两处。**补这一个函数，界面与功能一起变。**
 *
 * ## 那为什么还留着下游那几处
 *
 * 冗余，成本是三条指令。Riverpod 的 provider 有缓存，family 实例（
 * `proFeatureEntitlement` 是带参数的）在补丁落地前可能已经算过一轮并把
 * `false` 留在容器里；下游各点各自钉死就不依赖「补丁是否赶在第一次求值之前」。
 * 五处彼此独立，[installAll] 也是逐个装、互不影响。
 *
 * ## 为什么字幕搜索要单列
 *
 * `_ensureSubtitleSearchEntitled` 没走 `PaywallGuard`，它自己读 provider，不满足时
 * **抛异常**（`"Pro entitlement required for subtitle search"`）而不是跳转。返回类
 * 型是同步 `void`，所以补成返回 `null` 就是它的正常出口。
 *
 * ## 没补什么，以及为什么
 *
 * `IAPService.initialize` 看着也像个好落点（无 Play 时它返回 false），但它是
 * `async`，返回的是 `Future<bool>`。函数体里那些 `add x0,x22,#0x30` 是喂给
 * completer 的，不是返回值；改成直接返回 bool 会让调用方拿它当 Future 用，当场就
 * 崩。无 Play 的问题在 [Billing] 那边从 Java 侧解决。
 */
internal object Entitlement {

    /**
     * 锚点取自函数体，均为 12 字节、全库唯一、不含任何 PC 相对操作数
     * （`B`/`BL`/`ADR`/`ADRP` 的偏移会随每次编译整体位移，拿它们做特征等于自废）。
     * 也都不与入口那 8 个被改写的字节重叠 —— 重叠的话补丁一落，锚点就没了。
     */
    val SITES = listOf(
        // ★ 根。Riverpod 生成的顶层计算函数，紧跟在 init:isSubscribedProvider 后面。
        //    原逻辑：watch(subscriptionNotifier) → 取订阅对象 → 取里面那个 bool；
        //    任意一层为 null 就返回 false。补成恒 true，界面和功能一起认账。
        Site(
            id = "subscribed",
            dartName = "isSubscribed(Ref) [isSubscribedProvider]",
            anchor = "89040054403F40F900B44EF9",
            anchorOffset = 0x14,
            result = Result.TRUE,
        ),
        // proFeatureEntitlement(Ref, feature)：三条出口，最后一条就是 return
        // watch(isSubscribed)。根补上之后它自然为真，这里钉死只是不依赖求值时序。
        Site(
            id = "entitlement",
            dartName = "proFeatureEntitlement(Ref, ProFeature)",
            anchor = "FF0110EB29080054403F40F9",
            anchorOffset = 0x10,
            result = Result.TRUE,
        ),
        Site(
            id = "paywall",
            dartName = "PaywallGuard|ensureEntitled",
            anchor = "E10302AAA2031FF8823041B8",
            anchorOffset = 0x10,
            result = Result.TRUE,
        ),
        Site(
            id = "subtitle",
            dartName = "NativePlayerSubtitleSearchBridge._ensureSubtitleSearchEntitled",
            anchor = "A0831FF8403F40F900984EF9",
            anchorOffset = 0x20,
            result = Result.NULL,
        ),
        Site(
            id = "active",
            dartName = "SubscriptionNotifier._checkIfActive",
            anchor = "EF4100D1E00302AAE20303AA",
            anchorOffset = 0x8,
            result = Result.TRUE,
        ),
    )

    /**
     * 依次定位并补掉全部落点，返回成功的个数。
     *
     * 单个落点失败不影响其余 —— 三处彼此独立，补上一处就多放行一部分功能。全军覆没
     * 才说明目标版本变了。
     */
    fun installAll(log: HookerLog): Int =
        SITES.count { site -> DartPatch.apply(DartPatch.locate(site, log), log) }
}
