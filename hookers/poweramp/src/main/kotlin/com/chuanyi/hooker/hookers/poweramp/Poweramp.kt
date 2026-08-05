package com.chuanyi.hooker.hookers.poweramp

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 目标里那些**不该由 Kotlin 侧猜**的名字、常量与形状，集中一份。
 *
 * 分三类，寿命差别很大：
 *
 * * **接口决定的名字** —— `onBusMsg` / `getIntState` 这些是 `MsgBusSubscriber`、
 *   `StateBus` 的实现方法，R8 不会重命名（重命名就实现不了接口）。`BaseApplication`、
 *   `Sync`、`ExpiredActivity` 三个类名同理：前两个被原生代码按名字反查，后一个写在
 *   manifest 里，都改不动。这是最可靠的一档。
 * * **协议常量** —— MsgBus 的消息号、授权结果码、Bundle 键名、共享缓冲区的槽位。
 *   它们是 native 与 Java 之间的约定，动一个就要两边一起改，所以稳。
 * * **形状** —— 混淆之后名字全变，签名不变。授权链路上的类（`ׅ.i30` / `ׅ.n10` /
 *   `ׅ.p5` 这种一两个字母的名字）只能靠形状认，见下面那组判据。
 */
internal object Poweramp {

    const val PKG = "com.maxmpz.audioplayer"

    // --- 名字（接口或 manifest 钉住的，R8 改不动） ---------------------------

    /** 授权结果的接收方。方法名来自 `MsgBusSubscriber` / `StateBus`，不会被重命名。 */
    const val BASE_APPLICATION = "com.maxmpz.app.base.BaseApplication"

    /**
     * 原生授权引擎的 Java 入口。
     *
     * 类名与方法名都是硬的：`libpowerampcore.so` 里明文写着
     * `com/maxmpz/audioplayer/Sync`，`RegisterNatives` 按这个名字绑定。
     */
    const val SYNC = "com.maxmpz.audioplayer.Sync"

    /** 试用到期对话框。Java 里没有任何地方启动它 —— 是原生侧自己拉起的。 */
    const val EXPIRED_ACTIVITY = "com.maxmpz.audioplayer.dialogs.ExpiredActivity"

    // --- 协议常量 -----------------------------------------------------------

    /** 授权检查完成，`obj` 是结果 Bundle。原生侧 post 到 `bus_app`。 */
    const val MSG_LICENSE_RESULT = 252510544

    /** `StateBus` 上的「是完整版吗」。`getBooleanState` 与 `getIntState` 都认它。 */
    const val STATE_FULL_VERSION = 252510551

    /** `StateBus` 上的「授权状态可用吗」——`res > 229 || res >= 3`。 */
    const val STATE_LICENSE_KNOWN = 252510552

    // 结果 Bundle 的键。native 写、Java 读，改一个两边都要动。
    const val KEY_RESULT = "res"
    const val KEY_STORE = "store"
    const val KEY_PURCHASED = "purchased"
    const val KEY_PENDING_END = "pending_end_ts"
    const val KEY_ERROR = "error"

    /**
     * 授权结果码。`>= 229` 即完整版，另外几个值有各自的含义：
     *
     * | 值 | 含义 | 表现 |
     * |---|---|---|
     * | `Integer.MIN_VALUE` | 还没查过 | 保持上次落盘的结果 |
     * | `0` | 查询失败 | 记为试用，弹到期提示 |
     * | `3` | 试用期即将结束 | 「应用即将停止工作」 |
     * | `4` | 试用期内 | 「试用期内所有功能可用」，且临时放行功能套餐 |
     * | `>= 229` | 完整版 | 「感谢您购买」 |
     * | `272` / `273` | 完整版且已完全验证 | 另外隐藏授权状态项、放行设置导入 |
     *
     * 取 [FULL_VERIFIED] 而不是随便一个 `>= 229`：272 那条分支会把待处理购买
     * （`pending_end_ts`）一并清零，得到的是一个没有任何待办的干净状态。
     */
    const val FULL_VERIFIED = 272

    /** 授权阈值。低于它就不是完整版，这个数字在 Java 侧出现了二十多处。 */
    const val FULL_VERSION_MIN = 229

    /** 商店编号：1/9 都是 Google Play（9 是新的 IAP 通道，1 是旧的）。 */
    const val STORE_GOOGLE_PLAY = 9

    // --- 共享缓冲区的槽位 ---------------------------------------------------
    //
    // 32 字节、8 个 int，native 写 Java 读。Java 侧拿到的是 asReadOnlyBuffer()
    // 视图，所以写它要绕过只读标志，见 Entitlement.Blob。

    /** 变更计数。Java 用它判断「原生侧动过了没有」，决定要不要落盘。 */
    const val SLOT_REVISION = 0

    /** 授权结果，与结果 Bundle 的 `res` 同一套取值。 */
    const val SLOT_LICENSE = 1

    /** 已拥有的功能套餐数。`>= 1` 是若干设置项的开关，界面上显示成「套餐 #N 已包含」。 */
    const val SLOT_PACKS_OWNED = 2

    /**
     * 权益归属方式，决定界面怎么解释这些套餐的来源。
     *
     * `1` 解锁器应用、`2`/`4` 折扣价（还要买）、`3` 最近购买过套餐、
     * `5` 随完整版赠送。取 [OWNERSHIP_INCLUDED_IN_FULL] —— 它是唯一一个既显示
     * 「已包含」又不再挂购买按钮的值。
     */
    const val SLOT_OWNERSHIP = 3

    /** 可用的功能套餐总数。`已拥有 >= 总数` 时界面显示「套餐已购买」。 */
    const val SLOT_PACKS_AVAILABLE = 4

    const val OWNERSHIP_INCLUDED_IN_FULL = 5

    /** 共享缓冲区一共 32 字节。写之前按它裁一次，别越界。 */
    const val BLOB_BYTES = 32

    // --- DexKit 锚点 --------------------------------------------------------

    /**
     * 授权存储类的锚点：它 `<clinit>` 里那条 Kotlin 委托属性引用。
     *
     * ```java
     * new MutablePropertyReference0Impl(K, i30.class, "sbuf", "getSbuf()Ljava/nio/ByteBuffer;")
     * ```
     *
     * 全 dex 唯一，且 R8 不会动字符串字面量。缺点是它绑在一个属性名上 ——
     * 开发者改名就失效，所以另有 [ANCHOR_PENDING_END] 兜底。
     */
    const val ANCHOR_SBUF = "getSbuf()Ljava/nio/ByteBuffer;"

    /**
     * 导出加密与导入解密的锚点：交给原生引擎的两个命令选择子。
     *
     * 各自在整个 dex 里只出现一次，且是 native↔Java 的命令约定 —— 比方法名稳。
     * 两个方法的形状也一样（`static (byte[]) -> byte[]`），命中后按形状复核。
     */
    const val ANCHOR_ENCRYPT = "eS"
    const val ANCHOR_DECRYPT = "dS"

    /**
     * 兜底锚点：处理「待处理购买」的那个方法引用的 Bundle 键。
     *
     * 比 [ANCHOR_SBUF] 更稳 —— 它是原生侧与 Java 侧的字段约定，改名要两边一起改。
     * 命中的是方法（不是类），再从它读到的静态字段反推出授权存储类。
     */
    const val ANCHOR_PENDING_END = KEY_PENDING_END

    // --- 形状 ---------------------------------------------------------------

    /**
     * 一个 int 型偏好项的「当前值」字段。
     *
     * 那个基类只有两个 int 字段：一个 `final` 的默认值，一个可变的当前值。
     * 按 `final` 分得开，不需要知道任何名字。
     */
    fun Field.isPrefValue(): Boolean =
        type == Int::class.javaPrimitiveType && !Modifier.isFinal(modifiers)

    /** 同一个基类里那个 `final` 的默认值字段。 */
    fun Field.isPrefDefault(): Boolean =
        type == Int::class.javaPrimitiveType && Modifier.isFinal(modifiers)

    /**
     * 认出授权结果那一项。
     *
     * 授权存储里有五个 int 项，四个的默认值是 0（商店编号、待处理时间戳、两个徽章
     * 计数），**只有授权结果的默认值是 `Integer.MIN_VALUE`** —— 因为「还没查过」
     * 必须和「查过，结果是 0」区分开。这个区别是语义要求，不是巧合，所以拿它当判据。
     */
    const val LICENSE_PREF_DEFAULT = Int.MIN_VALUE

    /**
     * `static (byte[]) -> byte[]`：导出加密与导入解密。
     *
     * 两个方法形状相同，靠各自的命令选择子分开（[ANCHOR_ENCRYPT] / [ANCHOR_DECRYPT]）。
     */
    fun Method.isByteArrayTransform(): Boolean =
        Modifier.isStatic(modifiers) &&
            returnType == ByteArray::class.java &&
            parameterTypes.size == 1 &&
            parameterTypes[0] == ByteArray::class.java
}
