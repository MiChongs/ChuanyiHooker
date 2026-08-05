package com.chuanyi.hooker.hookers.poweramp

import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookFeature
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.hookers.poweramp.Entitlement.installFeaturePacks
import com.chuanyi.hooker.hookers.poweramp.Entitlement.installFullVersion
import com.chuanyi.hooker.hookers.poweramp.Entitlement.installLog
import com.chuanyi.hooker.hookers.poweramp.Entitlement.installOfflineTolerance
import com.chuanyi.hooker.hookers.poweramp.LicenseGate.installExpiryGuard
import com.chuanyi.hooker.hookers.poweramp.SettingsBackup.installBackupLog
import com.chuanyi.hooker.hookers.poweramp.SettingsBackup.installPlainBackup

/**
 * Poweramp（`com.maxmpz.audioplayer`）build-1025-bundle-play (vc 1025004)，arm64。
 *
 * ## 结论先写
 *
 * **没有订阅。**唯一的商品是 `fv0`，类型 `inapp`，界面上写着「一次性购买 | 终身许可证」，
 * 应用里没有续订、到期或权益回收的概念 —— 完整版本身就是终身的，不需要另外伪造标记。
 * 另外两条商品线（功能套餐 `fp*`、Uber Patron 徽章 `uberpatron*`）同样是买断。
 *
 * 收费一共四层，各自独立：
 *
 * | 层 | 判据 | 表现 | 判定在哪 |
 * |---|---|---|---|
 * | 完整版 | 授权结果 `>= 229` | 试用到期后应用停止工作 | Java |
 * | 功能套餐 | 共享块「已有套餐数 `>= 1`」 | 一批设置项在设置页里是灰的 | Java |
 * | 设置导入 | 需要「经过验证的完整版」 | 导入端拒绝解密任何文件 | **原生** |
 * | Uber 徽章 | 两个计数偏好之和 | 只换导航栏图标，不影响功能 | Java |
 *
 * 前三层本模块都做。徽章是纯捐助标识，改它只是把别人的赞助标记戴到自己身上，没有功能意义。
 *
 * ## 判定在原生，但**几乎只判定不执行**
 *
 * 授权逻辑整个在 `libpowerampcore.so` 里，通过 `com.maxmpz.audioplayer.Sync` 那八个
 * 动态注册的 JNI 方法暴露（方法名一律叫 `native_lock` / `native_release` 这种，
 * 看不出用途；`.so` 里搜不到任何 license/trial/purchase 字样）。
 *
 * 但它**只产出结论，不执行限制**：全包搜下来，`player/`、`processing/`、`output/`、
 * `scanner/` 四个包里没有任何一处读授权状态，`PlayerService` 对 `Sync` 的唯一调用是
 * 进程退出时的清理。真正读授权的是状态总线、设置页、购买项、欢迎页这些界面代码。
 *
 * 所以解锁不需要碰原生代码：改掉结论就够了，音频链路根本不看它。这也是本模块没有装
 * 任何 inline hook 的原因 —— 原生层只被用来做一件 Java 做不到的事，见下。
 *
 * **唯一的例外是设置导入。**那条路上的解密由原生执行，且它保留着自己那份授权状态：
 * Java 侧按成 272 之后，`ps`（授权状态文本）依然回「限时试用」，解密对任何输入都回
 * `error 0x1`。这一层只能绕，不能改，见 [SettingsBackup]。
 *
 * ## 为什么必须是 Xposed，不能重打包
 *
 * `BaseApplication` 启动时把自己的签名与内置的 96 字节证书片段逐字节比对，对不上就弹
 * 「签名已更改」并关掉所有 Activity。重打包这条路是堵死的。
 *
 * ## 冻结 Google 服务
 *
 * 走的不是「让校验通过」，而是**替换掉校验的结论**：结果 Bundle 在 `BaseApplication`
 * 消费它之前就被改写，所以原生侧查出来是试用中、试用到期、还是连不上 Play 都不影响
 * 结果。同时把 `error` 与待处理购买时间戳去掉，Google 不可用时设置页顶部那条长期错误
 * 提示也就不会出现。
 *
 * 应用本身没有启动期的许可闸（没有 PairIP，也没有 LVL 绑定失败即退出的逻辑），
 * 唯一会因为校验失败冒出来的界面是原生侧自己拉起的到期对话框，由 [LicenseGate] 挡掉。
 *
 * ## 原生层用在哪
 *
 * 只用一件事：**取共享状态块的真实地址**。功能套餐那一层的状态在一个 Java 与原生共享的
 * 32 字节 direct ByteBuffer 里，而 Java 侧拿到的是 `asReadOnlyBuffer()` 视图 ——
 * 只读标志在 Java 对象上，映射本身照样可写。`GetDirectBufferAddress` 取到地址之后
 * 用模块自己的数据写入通道改，绕过那层视图。这是 Java 侧做不到的一步，也是这个模块
 * 依赖 `:native` 的全部理由；没有它完整版照样解锁，只是功能套餐解不开。
 *
 * ## DexKit 用在哪
 *
 * 授权链路上一个能写死的名字都没有：存储类叫 `ׅ.i30`、偏好基类叫 `ׅ.n10`，
 * 包名是一个希伯来标点，类名是 R8 按顺序发的两三个字符，下次构建就重排。
 * 能按名字取的只有三个类，各有各的原因钉住（接口实现、`RegisterNatives`、manifest）。
 * 其余全靠锚点 + 形状，见 [PowerampDex]。
 */
class PowerampHooker : AppHooker {

    override val id = "poweramp"
    override val displayName = "Poweramp"
    override val description = "解锁完整版与功能套餐，无 Google 服务也可用"
    override val targetPackages = setOf(Poweramp.PKG)

    @Volatile
    private var refs: PowerampDex.Refs = PowerampDex.Refs()

    override val features: List<HookFeature> = listOf(
        HookFeature(
            id = "full_version",
            title = "解锁完整版",
            summary = "把授权结果按成已购买且已验证。试用限制、到期提示与购买入口一并解除。" +
                "该商品为一次性买断，解锁即永久",
            install = { installFullVersion(refs) },
        ),
        HookFeature(
            id = "feature_packs",
            title = "解锁功能套餐",
            summary = "功能套餐是与完整版并列的另一项收费，管着设置页里一批显示为灰色的选项。" +
                "本项把它记为随完整版赠送",
            install = { installFeaturePacks(refs) },
        ),
        HookFeature(
            id = "offline",
            title = "无 Google 服务时仍可用",
            summary = "忽略授权检查的失败结果，不再记录错误提示，并关闭到期对话框。" +
                "冻结 Google 服务或断网时不受影响",
            install = {
                installOfflineTolerance(refs)
                installExpiryGuard()
            },
        ),
        HookFeature(
            id = "plain_backup",
            title = "导出导入不加密",
            summary = "设置备份原本经原生加密，且导入端只对已购买的机器放行，未购买时任何文件都导不进来。" +
                "本项让导出写明文，导入同时接受明文与原有的加密文件",
            install = { installPlainBackup(refs) },
        ),
        HookFeature(
            id = "log",
            title = "记录授权判定",
            summary = "排查用。记录每一次授权检查改写前的原始结果、共享状态的写入与备份的进出",
            defaultEnabled = false,
            install = {
                installLog(refs)
                installBackupLog(refs)
            },
        ),
    )

    override fun onHook(scope: HookScope) {
        scope.log.i("Poweramp ${scope.versionCode} in ${scope.processName}")
        refs = PowerampDex.resolve(scope)

        // 授权结果是硬要求，共享状态块不是：拿不到后者只影响功能套餐那一项，
        // 完整版仍然成立。所以这里只报，不抛 —— 抛出会让整个 hooker 停摆。
        if (!refs.canUnlock) {
            scope.log.e("没定位到授权结果，解锁类功能会失败${refs.describe()}")
        } else if (!refs.canWriteBlob) {
            scope.log.w("共享状态块不可写，功能套餐解不开${refs.describe()}")
        } else if (!refs.canRewriteBackup) {
            scope.log.w("没定位到备份加解密，导出导入不受影响${refs.describe()}")
        } else {
            scope.log.d("授权链路已定位${refs.describe()}")
        }
    }
}
