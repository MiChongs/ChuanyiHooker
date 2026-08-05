package com.chuanyi.hooker.hookers.flix

import com.chuanyi.hooker.core.HookerLog
import com.chuanyi.hooker.hookers.flix.DartPatch.Site

/**
 * Flix 2.2.1 (121) 的会员判定落点。
 *
 * ## Flix MAX 到底解锁什么
 *
 * 只有一件事：**免广告**。这不是推测，是应用自己在公告里写的 ——
 *
 * > 投屏、远程控制等功能已全面免费开放，为了维持基础运营成本，软件内将加入少量广告
 * > （Flix MAX 用户无广告），感谢支持
 *
 * 订阅页的类名也一致：`FlixMaxNoAdsSubscribeSheet`（NoAds）。所以「功能全部可用」在
 * 这个目标上等于「会员态成立 + 广告不出现」，没有第三件事。
 *
 * ## 判定链长什么样
 *
 * 包没混淆，下面这些名字都是从 `libapp.so` 里直接读出来的：
 *
 * ```
 * VipService._isFlixMax                    ← 实例字段，会员态的唯一真相
 *      ↑ 写它的只有一个地方
 * VipService._setIsFlixMax(bool)           ← 【落点 max_flag】★ 唯一的根
 *      ↑                    ↑
 *      │                    └── VipService.loadVipStatus()      联网：查服务端日期算天数
 *      └── VipService.syncFromPrefs()      离线：读本地剩余天数 > 0
 *
 * AdsManager._resolveProvider(type)        ← 【落点 no_ads】
 *      ├── type == 0 → TencentAdsProvider  真广告（优量汇）
 *      └── type >= 1 → NoopAdsProvider     应用自带的空实现
 *
 * VipService._computeRemainingDays(a, b)   ← 【落点 lifetime】
 *      日期差 / 86400000 → Smi 天数 → 写进 prefs，也决定界面上「剩 N 天后过期」
 *
 * _FlixMaxNoAdsSubscribeSheetState._checkPaymentStatus()   ← 【落点 pay_bypass】
 *      轮询查单 → HTTP 200 且 status == 1 → 「订阅成功」→ _onPaymentSuccess()
 * ```
 *
 * ## 为什么根是 `_setIsFlixMax` 而不是 `loadVipStatus`
 *
 * 联网和离线两条路各自算各自的，但**都以调用 `_setIsFlixMax` 收尾** —— 它是漏斗嘴。
 * 补在这里，两条路一起变，也不用管应用这次是联网起的还是离线起的。
 *
 * 补 `loadVipStatus` 则要同时处理它的异步形状（返回 `Future`，函数体里那些
 * `add x0,x22,#0x30` 是喂给 completer 的，不是返回值），而且盖不住 `syncFromPrefs`
 * 那条离线路径。
 *
 * ## `max_flag` 这一处为什么只改 4 个字节
 *
 * `_setIsFlixMax` 是 **setter**，不是判定函数 —— 把它整个短路掉（补成 `return null`）
 * 字段就永远不会被写，反而锁死在 false。原始形状：
 *
 * ```
 *  +20  ldur w0, [x1, #7]           ; w0 = 旧值（压缩指针）
 *  +24  add  x0, x0, x28, lsl #32   ; 解压成完整地址
 *  +28  cmp  w0, w2                 ; 旧值 == 新值？
 *  +32  b.ne +0x14                  ; 不等 → 去写
 *  +36  ...                         ; 相等 → 直接返回，不通知监听者
 *  +52  stur w2, [x1, #7]           ; 写入
 *  +56  bl   _notifyListeners
 * ```
 *
 * 把 `+24` 换成 `add x2, x22, #0x20`，新值 `w2` 就恒为 `true`：`+28` 变成「旧值是不是
 * 已经 true」，`+52` 写进去的也是 true。**「值没变就不通知」这个短路被完整保留下来**，
 * 不会每次调用都触发一轮多余的 UI 重建。
 *
 * 被覆盖掉的 `add x0, x0, x28, lsl #32` 是安全的：它只影响 `x0` 的高 32 位，而下一条
 * `cmp w0, w2` 比的是低 32 位 —— 这条指令对比较结果本来就没有贡献（`x0` 的完整形式在
 * 这个函数里再没被用过）。至于「低 32 位比较为什么等价于对象比较」，见 [DartPatch] 里
 * 关于压缩指针的那节。
 *
 * ## `no_ads` 为什么补分发而不是补展示
 *
 * `AdsManager.showSplashAd` 看着也像个落点，但把它废掉有风险：开屏广告的等待方
 * `showSplashAdAndWait` 是 async，靠一个 `Completer` 等广告结束的回调。展示函数被 nop
 * 掉，回调就永远不来，**启动页直接卡死**。
 *
 * 改补 `_resolveProvider`，让它恒选 `NoopAdsProvider` —— 那是应用自己写的降级实现（
 * 打一行 `noop` 日志，返回 false 表示没广告），回调链走的是原厂路径，一个字节都不用
 * 我们伪造。这也顺带覆盖了 `TencentAdsProvider` 的全部广告位，而不只是开屏。
 *
 * 分支判据是枚举 `AdProviderType` 的 index：`cmp x1, #1` / `b.gt` 走 Noop，index 0 走
 * 腾讯。把那条 `b.gt` 换成无条件 `b`，目标地址一个字没动，只是不再问条件。
 *
 * ## 没补什么，以及为什么
 *
 * * **本地那份订阅缓存**（`FlutterSharedPreferences` 里的 `flutter.vipRemainingDays`）
 *   —— 直接改它也能让 `syncFromPrefs` 判 true，但那是**输入**不是判定：应用一联网，
 *   `loadVipStatus` 就用服务端日期重算并覆盖回去。当第二重保险可以（见 [Prefs]），
 *   当主力不行。
 * * **`makeSign`（请求签名）** —— 伪造服务端回包要连它一起做，而解锁根本不需要走服务端
 *   那条路。补判定比伪造输入省事，也不依赖任何数据格式。
 */
internal object Entitlement {

    /**
     * 锚点一律**全库唯一**、**不与被改写的字节重叠**。
     *
     * 里面出现的 `b.ne` / `b.ls` / `cbz` 都是**函数内部**的短跳，偏移由函数自身的形状
     * 决定，重新编译只要函数体没改就原样保留 —— 这类可以进锚点。跨函数的长 `bl`
     * （偏移随整个快照的布局漂移）能不用就不用，[PAY_BYPASS] 是唯一的例外，那里的取舍
     * 写在它自己的注释里。
     */

    /** ★ 会员态的根。补上它，界面、广告开关、功能判定一起认账。 */
    val MAX_FLAG = Site(
        id = "max_flag",
        dartName = "VipService._setIsFlixMax(bool)",
        // +28..+60：cmp/b.ne/返回序列/stur/bl，跨过 +24 那条要改的指令
        anchor = "1F00026BA1000054E00316AAEF031DAAFD79C1A8C0035FD6227000B807000094",
        anchorOffset = 28,
        patchOffset = 24,
        note = "写入值恒为 true（保留「值没变就不通知」的短路）",
        payload = { DartPatch.loadTrue(rd = 2) }, // add x2, x22, #0x20
    )

    /** 广告分发。恒选应用自带的空实现，走原厂降级路径。 */
    val NO_ADS = Site(
        id = "no_ads",
        dartName = "AdsManager._resolveProvider(AdProviderType)",
        // 入口起 40 字节，正好停在要改的那条 b.gt 之前
        anchor = "FD79BFA9FD030FAAEF2100D1501F40F9FF0110EBC9040054407040B800801C8B017040F83F0400F1",
        anchorOffset = 0,
        patchOffset = 40,
        note = "provider 恒为 NoopAdsProvider（开屏广告不再加载）",
        payload = { DartPatch.branch(0x5C) }, // b.gt +0x5c → b +0x5c
    )

    /**
     * 剩余天数。同步函数，直接补入口返回一个大 Smi。
     *
     * 这个值有两个去处：界面上的「剩 N 天后过期」，以及被 `loadVipStatus` 写回
     * `flutter.vipRemainingDays`。后者让本地那份缓存也变成永久，于是下次离线启动
     * `syncFromPrefs` 自己就判 true —— [MAX_FLAG] 之外的第二道保险，不需要额外做什么。
     */
    fun lifetime(days: Long) = Site(
        id = "lifetime",
        dartName = "VipService._computeRemainingDays(String, String)",
        // +40 起：cmp w2,w22 / b.eq / ldur / cbz / ldur / ubfx，离入口那 12 字节很远
        anchor = "5F00166B20020054407040B8E001003440F05FF8007C4CD3",
        anchorOffset = 40,
        patchOffset = 0,
        note = "剩余天数恒为 $days 天，并写回本地缓存",
        payload = { DartPatch.returnInt(days) },
    )

    /**
     * 支付直通。让查单结果恒判为「已支付」。
     *
     * 微信和支付宝是两条**下单**路径（`_generateWxAppPaymentLink` /
     * `_generatePaymentLink`），但**查单只有一条**：两者付完都回到
     * `_checkPaymentStatus` 轮询同一个接口。所以补这一处，两个渠道一起覆盖，不用分别去
     * 碰 fluwx 的 `PayResp` 回调和支付宝的 scheme 返回 —— 那两条路各有各的形状，还依赖
     * 对应 App 装没装。
     *
     * 原始形状：`cmp x0, #1` / `b.ne +0xb8`（不等就跳过成功分支）。把 `b.ne` 换成
     * `nop`，无论服务端回什么状态都往下走：弹「订阅成功」→ `_onPaymentSuccess()` →
     * 刷新会员态。
     *
     * **前置条件仍然成立**：更前面还有一道 `cmp x1, #0xc8`（HTTP 200）没有动。查单请求
     * 本身要真的通，响应也要真的能解析成 JSON —— 这是故意留的，把那道也废掉的话，网络
     * 失败时会拿一个空响应去解析，当场抛异常。所以这一项是「跳过付款」，不是「跳过下单」。
     *
     * 锚点里含一条跨函数的长 `bl`。不含它的候选试过两个，分别命中 70 和 256 处 —— 那段
     * 是 Dart 通用的 dispatch-table 调用序列，做不了标识符。这里接受它跨版本更易失效：
     * 三道校验（唯一性 / 函数序言 / 写后回读）会让失效表现为「跳过并报警」，而这一项本身
     * 默认关闭，解锁不依赖它。
     */
    val PAY_BYPASS = Site(
        id = "pay_bypass",
        dartName = "_FlixMaxNoAdsSubscribeSheetState._checkPaymentStatus()",
        anchor = "1E00118BBE7A7EF8C0033FD6E10300AA647B40F9E655F0971F0400F1",
        anchorOffset = 336,
        patchOffset = 364,
        note = "查单状态恒判为已支付（HTTP 层的前置判断保持不变）",
        payload = { DartPatch.NOP },
    )

    /** 定位并打上 [site]，返回是否成功。 */
    fun install(site: Site, log: HookerLog): Boolean = DartPatch.install(site, log)
}
