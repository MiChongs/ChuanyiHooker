package com.chuanyi.hooker.hookers.gameclick

/**
 * 连点器（`com.pbb.gameclick`）的常量与协议事实。
 *
 * 全部来自 4.0.1 的实测，改动前先回头看 [GameClickHooker] 顶部那段说明。
 */
internal object GameClick {

    const val PACKAGE = "com.pbb.gameclick"

    // --- 目标类 -------------------------------------------------------------

    /** 授权判定的唯一入口，30+ 个 static native 方法都在这。 */
    const val NATIVE_UTIL = "com.pbb.gameclick.NativeUtil"

    /** 全局状态与登录流程。连点区域列表、设备位校验、24 小时复核都在这。 */
    const val DATA_CONST = "com.pbb.gameclick.DataConst"

    /** 悬浮窗，连点区域的增删就在这里判会员。 */
    const val FLOAT_CLICK_VIEW = "com.pbb.gameclick.FloatClickView"

    /** 360 加固的壳 Application。真实 dex 由它在 attachBaseContext 里解密。 */
    const val STUB_APP = "com.stub.StubApp"

    /** 授权判定所在的原生库。只导出 JNI_OnLoad，方法全是动态注册。 */
    const val NATIVE_LIB = "libgameclick.so"

    // --- 服务端登录返回的字段 -------------------------------------------------

    /**
     * 会员标志。**`IsVip()` 只看这一个字段**：实测把 `viptime` 写成 2020 年、
     * `vip` 留 "1"，`IsVip()` 依然是 true；反过来 `viptime` 写 2099 而 `vip` 是 "0"，
     * 就是 false。所以永久会员的关键在这里，不在时间上。
     */
    const val F_VIP = "vip"

    /**
     * 会员到期时间，纯展示用，不参与判定。
     *
     * 界面对它有一条现成的分支（`LeftView`）：
     * ```
     * if (GetVipTime().contains(" ")) 显示 "<时间>到期" else 显示 R.string.forever
     * ```
     * `R.string.forever` 就是「永久」——**只要给一个不含空格的字符串，应用自己就把
     * 会员显示成永久**，不需要伪造一个远期日期。见 [VIP_TIME_FOREVER]。
     */
    const val F_VIP_TIME = "viptime"

    /** 已占用的设备位。`VipView` 拿它和套餐上限做分母显示。 */
    const val F_DEVICE_NUM = "devicenum"

    /** 商店入口是否开放。关掉的话账号页整块会员信息都不显示。 */
    const val F_OPEN_SHOP = "openshop"

    /** 微信入口是否开放。 */
    const val F_OPEN_WX = "openwx"

    /** 是否已微信登录。为 false 时账号页只会提示去登录。 */
    const val F_WX_LOGIN = "wxlogin"

    /**
     * 登录侧的错误文案。**空字符串以外的任何值都会拦下悬浮窗**：
     * `DataConst.CheckDeviceNum()` 只要看到它非空就弹框返回 false，
     * 文案里含「购买」或「设备位」时更是直接不让开。清空它等于放行。
     */
    const val F_LOGIN_ERROR = "loginwxerror"

    /** 会员状态是否发生变化，应用据此弹「vip时间延长至」的提示。 */
    const val F_VIP_CHANGE = "vipchange"

    // --- 取值 ---------------------------------------------------------------

    /** `vip` 字段的「是会员」取值。服务端给的是字符串而不是布尔。 */
    const val VIP_YES = "1"

    /**
     * 写进 `viptime` 的永久标记。
     *
     * 不含空格是硬要求 —— 界面正是靠「有没有空格」来区分「到期时间」和「永久」的。
     * 文案本身用「永久」，这样即使某个页面把它直接拼进句子里也读得通。
     */
    const val VIP_TIME_FOREVER = "永久"

    // --- 原生方法名 ----------------------------------------------------------

    /**
     * 可以安全常量化的原生判定 —— 返回值都落在寄存器里（jboolean）。
     *
     * `GetVipTime` 不在这里：它返回 `jstring`，塞一个假指针给 Java 会当场崩。
     * 那一个只能在 Java 侧换返回值。
     */
    val NATIVE_BOOL_GATES = listOf("IsVip", "LoginOk", "WXLogin", "OpenShop", "OpenWx")

    /** JNI 的 `JNI_TRUE`。常量桩把它放进返回寄存器，Java 侧读到的就是 true。 */
    const val JNI_TRUE = 1L
}
