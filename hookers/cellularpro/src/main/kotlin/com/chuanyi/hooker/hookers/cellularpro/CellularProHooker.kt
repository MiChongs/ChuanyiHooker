package com.chuanyi.hooker.hookers.cellularpro

import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookFeature
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.hookers.cellularpro.Membership.installHidePurchase
import com.chuanyi.hooker.hookers.cellularpro.Membership.installLog
import com.chuanyi.hooker.hookers.cellularpro.Membership.installStealth
import com.chuanyi.hooker.hookers.cellularpro.Membership.installUnlock

/**
 * Cellular-Pro（`make.more.r2d2.cellular_pro`）1.9.10.1 (vc 109)，arm64。
 * 网络路测工具：信令解码、锁频锁网、打点、CSV/信令导出、基站工参管理。
 *
 * ## 结论先写
 *
 * **收费形态是订阅，不是买断。**商品共五档：高级会员 998/年、280/季、98/月，
 * 普通会员 150/年、20/月。资源里另有终身档位与文案（`general_member_final_year`、
 * `join_member_final`），但当前版本的购买页不再挂出来 —— 终身是应用自己支持的一种
 * 会员形态，只是不再售卖。会员与设备绑定，购买页底部那串 16 位十六进制就是设备标识。
 *
 * ## 判定确实在原生引擎里
 *
 * 应用被 **nmmp 虚拟化**保护：`NativeUtil.classes{,2,3,4,5}Init0(int)` 在各类的静态
 * 初始化里注册原生实现，`libQualcommAdapter.so`（5.1 MB，名字是伪装的）存放字节码与
 * 桩函数，真正的解释器是 `libkotlin.so` 导出的 `vmInterpret`（同样是伪装的名字）。
 * 全包 **10449 个方法**的方法体被搬走，会员管理器与会员记录**整类**在内 ——
 * dex 里只剩 `native` 声明，静态看不到、smali 改不动。
 *
 * 但保护只覆盖「判定」这一层：结论仍以 Java 布尔值返回给托管层的界面代码，
 * 所以接管返回值就够了，不必碰解释器本身。
 *
 * ## 为什么只能走原生层
 *
 * 会员记录的构造器是全链路上**唯一没有被虚拟化**的方法 —— 它要读调用栈确认调用方是
 * 管理器本身，这件事必须在 Java 层做：
 *
 * ```java
 * String caller = new Throwable().getStackTrace()[1].getClassName();
 * if (Class.forName(caller) != m3.class && ...) { post(new ef(7, 0)); throw ... }
 * ```
 *
 * 于是**任何 ART 级 hook 都会被它发现**：栈上多出一帧，调用方就不再是管理器，应用随即
 * 逐个关闭 Activity（表现为「用着用着自己退了」）。实测替换实现会触发，只挂前后回调
 * 同样触发。本模块因此完全不碰 ART，改在 `RegisterNatives` 绑定的那一刻换函数指针，
 * Java 调用栈一字不变，见 [Membership]。
 *
 * ## DexKit 用在哪
 *
 * 会员管理器叫 `A0B0.m3`、会员记录叫 `A0B0.lg0`，包名与类名都是构建期生成的。而方法体
 * 被搬走之后，「按方法引用的字符串找方法」也不成立了 —— 那些字面量已不在 dex 常量池里。
 * 唯一线索是上面那段反调用方校验：它没被搬走，日志串还在且全包唯一。以它命中记录类，
 * 再由「谁把记录当字段用」反查出管理器，两个类名都不用写死，见 [CellularProDex]。
 *
 * ## 实测放开了什么
 *
 * | 入口 | 接管前 | 接管后 |
 * |---|---|---|
 * | 关于页首项 | 注册会员 | 会员状态 |
 * | 基站工参管理 | 跳购买页 | 正常进入，五个制式的增删查与地图可用 |
 * | 锁网 / 锁频段 / 锁小区 / 锁频点 | 受限 | 控件全开 |
 * | LOG 回放、任务测试、信令与事件导出 | 受限 | 放开 |
 * | 主测量界面 | — | 不受影响，专业模式与实时测量正常 |
 *
 * 会员记录本身不接管：它带服务端签名，界面读取时会复验，伪造的记录同样会让应用退出。
 * 判定这一层改完之后也不需要它 —— 记录为空的路径已经全部走通，真付费用户的记录因此
 * 也不受影响。
 */
class CellularProHooker : AppHooker {

    override val id = "cellularpro"
    override val displayName = "Cellular-Pro"
    override val description = "接管会员判定，按高级会员放行"
    override val targetPackages = setOf(CellularPro.PKG)

    @Volatile
    private var refs: CellularProDex.Refs = CellularProDex.Refs()

    override val features: List<HookFeature> = listOf(
        HookFeature(
            id = "stealth",
            title = "绕过注入检测",
            summary = "目标自带环境探测与 PLT 钩子框架，发现注入后会在十几秒内自行退出。" +
                "本项隐藏模块的映射并挡住自杀动作。关闭后其余功能大概率无法维持",
            install = { installStealth() },
        ),
        HookFeature(
            id = "membership",
            title = "解锁会员权益",
            summary = "按高级会员放行，且不存在判定为已过期的路径。" +
                "工参管理、锁网锁频、LOG 回放、任务测试、信令与事件导出随之开放",
            install = { installUnlock(refs) },
        ),
        HookFeature(
            id = Membership.FEATURE_TOP_CHANNEL,
            title = "提升能力档位",
            summary = "工参管理挂在最高一档能力后面，本项把档位提上去。" +
                "该档位与解码通道绑定，在骁龙机型上开启会导致应用退出，故默认关闭",
            defaultEnabled = false,
            // 真正生效的地方在「解锁会员权益」里读这个开关，这里不装任何东西。
            install = { },
        ),
        HookFeature(
            id = "hide_purchase",
            title = "隐藏购买入口",
            summary = "会员页不再显示价格按钮。仅影响显示，与权益无关",
            defaultEnabled = false,
            install = { installHidePurchase(refs) },
        ),
        HookFeature(
            id = "log",
            title = "记录判定过程",
            summary = "排查用。列出会员链路上捕获到的原生方法及其绑定地址",
            defaultEnabled = false,
            install = { installLog(refs) },
        ),
    )

    /**
     * 只在主进程动手。
     *
     * 目标另有解码与上报进程，它们既不画界面也不查会员，在那里跑一遍 dex 扫描
     * 只是浪费启动时间。
     */
    override fun isCompatible(scope: HookScope): Boolean = scope.isMainProcess

    override fun onHook(scope: HookScope) {
        scope.log.i("Cellular-Pro ${scope.versionCode} 进程 ${scope.processName}")
        if (scope.versionCode != CellularPro.VERIFIED_VERSION_CODE) {
            scope.log.w(
                "本 hooker 在 ${CellularPro.VERIFIED_VERSION_NAME} " +
                    "(vc ${CellularPro.VERIFIED_VERSION_CODE}) 上验证过，当前是 vc ${scope.versionCode}，" +
                    "重命名结果可能已变；以下面的定位与挂号结果为准",
            )
        }

        refs = CellularProDex.resolve(scope)
        if (!refs.isReady) scope.log.e("会员链路未定位到，解锁类功能会失败${refs.describe()}")
        else scope.log.d("会员链路已定位${refs.describe()}")
    }
}
