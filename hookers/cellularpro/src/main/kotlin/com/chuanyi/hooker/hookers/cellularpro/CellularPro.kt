package com.chuanyi.hooker.hookers.cellularpro

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 目标里那些**不该由 Kotlin 侧猜**的名字、常量与形状，集中一份。
 *
 * 这个目标能写下来的东西非常少：会员链路上的类与方法全部经过 R8 重命名
 * （`A0B0.m3` / `A0B0.lg0`，包名是构建期生成的），而方法体又被 nmmp 虚拟化搬进了
 * `.so`，连「按方法引用的字符串找方法」这条常规路子都断了 —— 那些字面量已经不在
 * dex 常量池里。
 *
 * 于是分成三类，可靠程度依次递减：
 *
 * * **锚点** —— [ANCHOR_RECORD_GUARD]。会员记录的构造器**没有**被虚拟化（它要读调用栈
 *   判断调用方，必须留在 Java 层），那句日志串因此留在 dex 里且全包唯一。最稳的一处。
 * * **形状** —— 字段类型、返回类型、`native` 修饰。R8 不会改这些。
 * * **重命名结果** —— [TIER_PREDICATES] / [CHANNEL_PREDICATES] / [PURCHASE_HIDDEN]。只对本版本成立，
 *   换版本要重新测。用到它们的地方一律「找不到就报出来」，不会静默失效。
 */
internal object CellularPro {

    const val PKG = "make.more.r2d2.cellular_pro"

    /** 本 hooker 验证过的版本。其余版本仍会尝试，只是先提醒一句。 */
    const val VERIFIED_VERSION_CODE = 109L
    const val VERIFIED_VERSION_NAME = "1.9.10.1"

    // --- 锚点 ---------------------------------------------------------------

    /**
     * 会员记录类的锚点。它的构造器会检查调用方：
     *
     * ```java
     * String caller = new Throwable().getStackTrace()[1].getClassName();
     * if (Class.forName(caller) != m3.class && ...) {
     *     ns.s("other loader :" + caller, true);   // ← 这句
     *     EventBus.getDefault().post(new ef(7, 0));
     *     SystemClock.sleep(1000);
     *     throw new RuntimeException("Invalid param");
     * }
     * ```
     *
     * **这段也是本模块坚持只走原生层的原因**：任何 ART 级 hook 都会在这条栈上多出一帧，
     * 应用随即判定被注入并逐个关闭 Activity。实测替换实现会触发，只挂前后回调同样触发。
     */
    const val ANCHOR_RECORD_GUARD = "other loader :"

    /** 同一段里的第二个串，用来复核命中的确实是那个构造器。 */
    const val ANCHOR_RECORD_GUARD_THROW = "Invalid param"

    // --- 重命名结果（仅对 [VERIFIED_VERSION_CODE] 成立） -----------------------

    /**
     * 会员是否成立 —— 这三个只被界面读，改掉它们不影响任何数据链路。
     *
     * 分别对应三个调用点（我的页 / 功能入口 / 导出前置检查）。必须一起改：
     * 只动其中之一会出现「这里说是会员、那里说不是」的矛盾状态。
     *
     * ⚠️ 同族的 `Y0`（锁频锁网那一路）**刻意不收**。它和上面三个的差别在于调用方拿到
     * 「成立」之后会接着去读会员记录，而未登录时那个记录是空的 —— 按真之后调用方
     * 直接解引用空记录，进程崩在一个空指针上（`fault addr = 0x0`，PC 是个不合法的
     * 低位地址，即跳进了垃圾）。判定这一层能改的前提是**调用方不会因此多读一个对象**。
     */
    val TIER_PREDICATES = listOf("T0", "U0", "V0")

    /**
     * 「当前拿到哪一档能力」的那组**互斥**判定。
     *
     * ⚠️ **不要把它们按成常量。**它们同时被射频与解码链路读，按常量硬顶之后解码器会走进
     * 不匹配的分支，进程十几秒内崩在 `libd001.so`（厂商 diag 解码库）里，
     * faultaddr 是一个被当成指针解引用的字符串。
     *
     * 正确做法是改那个被比较的状态字段本身，让这组判定各自算出自洽的结果，见 [Channel]。
     * 这里列出它们只为两件事：差分探测时用来判断档位改对没有，以及在管理器的形状复核里
     * 充当版本核对。
     */
    val CHANNEL_PREDICATES = listOf("M0", "N0", "O0", "Q0", "R0", "S0")

    /**
     * 这组互斥判定里对应**最高档能力**的那一个。
     *
     * 工参管理那条入口链的成立条件是「其余档位全假、且这一个为真」，所以换档位时必须
     * 精确命中它 —— 换成别的档位同样能让「恰好一个为真」成立，但入口仍然打不开，
     * 而且会把射频通道换到一条这台设备用不上的路上。
     */
    const val TOP_CHANNEL_PREDICATE = "S0"

    /**
     * 隐藏内购入口的判定。按成 `true` 之后会员页上那六个价格按钮不再渲染。
     *
     * 纯外观项：它不参与任何权益判断，关掉也不影响解锁。
     */
    const val PURCHASE_HIDDEN = "I0"

    // --- 形状 ---------------------------------------------------------------

    /** JNI 里的 `JNI_TRUE` / `JNI_FALSE`。原生侧的常量桩按整型返回。 */
    const val JNI_TRUE = 1L
    const val JNI_FALSE = 0L

    /**
     * 会员管理器：唯一持有会员记录字段的类。
     *
     * 记录类由 [ANCHOR_RECORD_GUARD] 定位，管理器再由「谁把它当字段用」反查 ——
     * 两个类的名字都不用写死。
     */
    fun Field.isRecordField(recordClass: Class<*>): Boolean =
        !Modifier.isStatic(modifiers) && type == recordClass

    /** 管理器自己的静态单例字段，类型是它自己。复核用。 */
    fun Field.isSelfSingleton(owner: Class<*>): Boolean =
        Modifier.isStatic(modifiers) && type == owner

    /**
     * 判定的形状：实例方法、无参、返回 `boolean`、且是 `native`。
     *
     * `native` 这一条是这个目标特有的判据 —— 会员链路上的方法体全被搬进了 `.so`，
     * 反过来说，**没被搬走的方法一定不在这条链上**。
     */
    fun Method.isNativePredicate(): Boolean =
        Modifier.isNative(modifiers) &&
            !Modifier.isStatic(modifiers) &&
            parameterCount == 0 &&
            returnType == Boolean::class.javaPrimitiveType
}
