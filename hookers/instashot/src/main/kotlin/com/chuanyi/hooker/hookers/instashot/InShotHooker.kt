package com.chuanyi.hooker.hookers.instashot

import android.content.Context
import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookFeature
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.nativehook.NativeHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createReturnConstantHook
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * InShot 视频编辑器（`com.camerasideas.instashot`）2.219.1545 —— Google Play 结算。
 *
 * ## 结论先写
 *
 * 这个包**没有加壳、没有服务端复核、没有对购买凭证验签**。5 个 dex 全是明文，R8 只做了
 * 重命名；48 个 `.so` 里跟授权沾边的只有 `libcer.so`（APK 签名完整性）和
 * `libsafe_auth.so`（其实是 AES 文件读写，跟授权无关）。
 *
 * 权益判定是**纯本地的一个布尔**：Play 回包只负责把它写进 MMKV 的 `iab` 档，读的时候
 * 谁也不问。判定链与锚点选择见 [InShot] 的类注释。
 *
 * ## 为什么冻结 Google 之后依然成立
 *
 * 这不是绕过来的，是应用自己的设计：`UpdateBilling.applyPurchases()` 在结算失败
 * （连不上、回包为 null、响应码非 0）时**提前 return，不写盘**，还会把「当前已是 Pro」
 * 广播给所有监听者。冻结 Google 走的正是这条路。
 *
 * 真正会撤销权益的是**另一种情况**：Google 正常、查询成功、但名下没有购买 —— 这时它
 * 会把 `SubscribePro` 写成 false 并弹一次「Pro 不可用」。[persist] 与 [noPopup] 就是
 * 为这一种情况准备的，跟冻不冻结无关。
 *
 * ## 六项功能各自负责什么
 *
 * ```
 *                        ┌── lifetime         运行时按住三个读取点，不写盘
 * 本地权益布尔 ───────────┤
 *                        └── persist          写进应用自己的存档，卸载模块后仍有效
 *
 * Google Play 结算 ───────┬── no_popup         关掉「Pro 不可用」提示
 *                        └── bypass_bind      跳过账号绑定与联网校验
 *
 * libcer.so 签名校验 ─────── signature_check   重打包场景才需要，默认关
 * ```
 */
class InShotHooker : AppHooker {

    override val id = "instashot"
    override val displayName = "InShot"
    override val description = "视频编辑器，解锁永久 Pro，无 Google 服务也可用"
    override val targetPackages = setOf(InShot.PKG)

    @Volatile
    private var refs: InShotDex.Refs = InShotDex.Refs()

    /** 买断记录只写一次；结算回来后的重新写回不受它限制。 */
    private val recorded = AtomicBoolean(false)

    override val features: List<HookFeature> = listOf(
        HookFeature(
            id = "lifetime",
            title = "解锁永久 Pro",
            summary = "把应用的权益判定按成已购买。全部滤镜、特效、贴纸、字体、转场、" +
                "音乐与 AI 功能一并解锁，导出不再叠水印，广告位也不会注册。" +
                "只作用于运行时，不改动应用数据",
            install = { installLifetime() },
        ),
        HookFeature(
            id = "persist",
            title = "写入永久买断记录",
            summary = "把「永久版已购买」写进应用自己的存档，并在每次结算查询之后重新写回，" +
                "避免查不到购买时被改回未购买。写入后即使停用本模块，Pro 依然有效",
            install = { installPersist() },
        ),
        HookFeature(
            id = "no_popup",
            title = "屏蔽 Pro 失效提示",
            summary = "Google 服务正常、但账号名下查不到购买时，应用会弹一次" +
                "「Pro 不可用」并要求重新订阅。这一项把那个提示关掉",
            install = { installNoPopup() },
        ),
        HookFeature(
            id = "bypass_bind",
            title = "跳过账号绑定与联网校验",
            summary = "应用会拿 Google 账号去自家服务器复核订阅，并在设置页催促绑定。" +
                "关掉这条链路后启动不再等待网络，冻结 Google 服务时也不会卡顿",
            install = { installBypassBind() },
        ),
        HookFeature(
            id = "signature_check",
            title = "接管签名完整性校验",
            summary = "应用用 libcer.so 校验自身签名，不通过则人像抠图、视频分割、美颜、" +
                "去除物体、防抖、自动字幕等 AI 能力静默失效。" +
                "本模块不改动 APK，签名本就是完整的，所以默认关闭 —— " +
                "只有在使用重打包或改签名的版本时才需要打开",
            defaultEnabled = false,
            install = { installSignatureCheck() },
        ),
        HookFeature(
            id = "log_pro",
            title = "记录权益与结算过程",
            summary = "排查用：记录每一次权益判定的结果，以及 Google 结算回包的响应码与购买数量",
            defaultEnabled = true, // TODO 验证完改回 false
            install = { installLog() },
        ),
    )

    override fun onHook(scope: HookScope) {
        scope.log.i("InShot ${scope.versionCode} in ${scope.processName}")
        refs = InShotDex.resolve(scope)
        if (refs.isSubscribePro == null && refs.userIsPro == null) {
            // 到这一步只有两种可能：dex 扫描没跑起来（上面会有 libdexkit.so 的警告），
            // 或者应用把落盘键改了。两种都得看日志，没有能自动兜的路。
            error("权益判定一个都没定位到 —— 见上方 dex 扫描日志")
        }
    }

    // -----------------------------------------------------------------------

    /**
     * 按住读取端，而不是伪造一笔购买。
     *
     * 三个读取点都接管，但它们**不是**三条并列的保险 —— 是一条链上的三节：
     *
     * ```
     * UnlockPreferences.isSubscribePro()  →  UserManager.isSubscribePro()  →  UserManager.isPro()
     * IAPBindHelper.isPro()               ────────────────────────────────↗
     * ```
     *
     * 单按住第一个就足够级联到 `isPro()`，进而级联到 `isUnlocked(sku)` —— 后者第一句
     * 就是 `if (!isPro() && …)`，短路返回 true，310 个调用点一起成立。
     *
     * 之所以三个都按，是因为**中间两个也有各自的直接调用方**：`IAPBindHelper.isPro()`
     * 被结算之外的代码直接问，`UserManager.isPro()` 被设置页、分享文案、启动统计直接问。
     * 只按第一个的话，那些点要多走一层才拿到正确答案；按住之后每一层自己就是对的。
     *
     * 用 after 钩子改返回值而不是常量替换：这样 [installLog] 和 [installPersist] 挂在
     * 同一个方法上的钩子照常执行，几个功能之间不会因为谁替换了方法体而互相踩。
     */
    private fun HookScope.installLifetime() {
        var installed = 0
        fun force(label: String, method: Method?) {
            if (method == null) {
                log.w("没定位到 $label，跳过")
                return
            }
            method.createAfterHook("instashot.lifetime.$label") { param ->
                if (param.result != true) param.result = true
            }
            installed++
            log.d("已按住 $label：${method.declaringClass.name}.${method.name}")
        }

        force("subscribePro", refs.isSubscribePro)
        force("userIsPro", refs.userIsPro)
        force("iapIsPro", refs.iapIsPro)

        if (installed == 0) error("三个权益读取点一个都没装上")
        log.i("权益判定已按住 $installed/3 处，全部 Pro 功能解锁")
    }

    /**
     * 让解锁进到应用自己的存档，用应用自己的写入函数。
     *
     * 写两处，对应判定链上两个独立的分支：
     *
     * * `SubscribePro = true` —— 总权益键。它是 `isPro()` 成立的主路径，
     *   也是应用自己在真实购买后写的那一个。
     * * `Unlocked_com.camerasideas.instashot.pro.permanent = true` —— **买断**商品的
     *   解锁记录。写这一个而不是月/年订阅的，等于告诉应用「永久版已买」：
     *   `isPermanent()` 因此成立，而 `isMonthly()` / `isYearly()` 保持为假，
     *   不会出现「订阅到期」这种概念。
     *
     * 写入时机选在权益判定第一次被问到的时候：那一刻 MMKV 一定已经初始化好
     * （判定本身就要读它），不用猜文件路径，也不用等 `Context`。
     *
     * 结算查询回来之后再写一次。应用查完 Play 会用查询结果覆盖 `SubscribePro`，
     * 查不到购买就是 false —— 覆盖之后补写回来，那次覆盖就变成一次确认。
     * 冻结 Google 时这次覆盖根本不会发生（失败路径提前 return），补写也就不会触发，
     * 两种情况都对。
     */
    private fun HookScope.installPersist() {
        val writer = refs.putFlag
        val setUnlocked = refs.setUnlocked
        if (writer == null && setUnlocked == null) {
            error("两个写入函数都没定位到，无法写入买断记录")
        }

        // 第一次判定时写。isSubscribePro 带着 Context，是最省事的触发点；
        // 它没定位到时退到 isPro()，那边可以直接拿 thisObject 当 UserManager。
        refs.isSubscribePro?.createAfterHook("instashot.persist.seed") { param ->
            if (!recorded.compareAndSet(false, true)) return@createAfterHook
            record(param.args.getOrNull(0) as? Context, null)
        } ?: refs.userIsPro?.createAfterHook("instashot.persist.seed") { param ->
            if (!recorded.compareAndSet(false, true)) return@createAfterHook
            record(appContextOrNull(), param.thisObjectOrNull)
        }

        // 结算查询之后补写。arg0 就是 Context。
        val apply = refs.applyPurchases
        if (apply == null) {
            log.w("没定位到结算写入函数，跳过「查询后补写」（首次写入不受影响）")
        } else {
            apply.createAfterHook("instashot.persist.rewrite") { param ->
                record(param.args.getOrNull(0) as? Context, null)
            }
            log.d("已在 ${apply.declaringClass.name}.${apply.name} 之后补写")
        }
        log.i("买断记录写入已就位")
    }

    /**
     * 写一次买断记录。
     *
     * [userManager] 给了就直接用，没给就走应用自己的单例工厂 —— 两条路拿到的是同一个
     * 实例，工厂本身就是 `getInstance(Context)`。
     */
    private fun HookScope.record(context: Context?, userManager: Any?) {
        val instance = userManager
            ?: context?.let { ctx ->
                runCatching { refs.userManagerOf?.invoke(null, ctx) }
                    .onFailure { log.w("取 UserManager 实例失败：${it.message}") }
                    .getOrNull()
            }

        val writer = refs.putFlag
        if (writer != null && instance != null) {
            runCatching {
                writer.invoke(instance, InShot.KEY_SUBSCRIBE_PRO, true)
                writer.invoke(instance, InShot.SKU_PERMANENT, true)
            }.onFailure { log.w("写入总权益键失败：${it.message}") }
        }

        if (context != null) {
            runCatching { refs.setUnlocked?.invoke(null, context, InShot.SKU_PERMANENT, true) }
                .onFailure { log.w("写入买断解锁记录失败：${it.message}") }
        }

        if (instance == null && context == null) {
            log.w("既没有 Context 也没有 UserManager 实例，这次写入跳过")
            recorded.set(false)
        } else {
            log.d("买断记录已写入应用存档")
        }
    }

    /**
     * 关掉「Pro 不可用」提示。
     *
     * 触发条件是「升级前是 Pro、升级后查不到购买」。被 [installPersist] 写成 Pro 之后，
     * 一旦 Google 恢复正常且账号名下确实没有购买，这个条件就成立了 —— 所以这一项和
     * `persist` 是配套的，不是可有可无的装饰。
     *
     * 判定函数第一句读的是一个已置位的粘滞标记（`ShouldShowProUnavailableAfterUpdate`），
     * 置位之后每次启动都会再弹一次；常量化返回值把这条粘滞路径一起关掉。
     */
    private fun HookScope.installNoPopup() {
        val method = refs.proUnavailable ?: error("没定位到 Pro 失效提示的判定函数")
        method.createReturnConstantHook("instashot.no_popup", false)
        log.i("Pro 失效提示已关闭：${method.declaringClass.name}.${method.name}")
    }

    /**
     * 断开账号绑定这条链。
     *
     * 这一个开关同时管住三件事，因为它们问的是同一个函数
     * （`IAPBindHelper.isBindSupported()`）：
     *
     * 1. 结算更新完成后那次「拿 Google 账号去自家服务器复核订阅」的请求 ——
     *    判定为假时直接 return，请求根本不会发出
     * 2. 设置页 Pro 卡片上的「绑定」按钮 —— 权益被按成已购买之后，
     *    未登录账号会让它显示成催促绑定，关掉这条链就不会出现
     * 3. 登录/绑定弹窗的展示节流逻辑
     *
     * 对已经永久解锁的用户，这条链没有任何用处，而它是启动路径上唯一还会等网络的部分。
     */
    private fun HookScope.installBypassBind() {
        val method = refs.iapBindSupported ?: error("没定位到账号绑定开关")
        method.createReturnConstantHook("instashot.bypass_bind", false)
        log.i("账号绑定与联网复核已跳过：${method.declaringClass.name}.${method.name}")
    }

    /**
     * 接管 `libcer.so` 的签名完整性校验。
     *
     * 校验不过时受影响的是 12 个 AI 原生能力（人像抠图 / 视频分割 / 美颜 / 瘦下巴 /
     * 人脸检测 / 自动字幕 / 自动调色 / 去除物体 / 防抖 / 静音检测 / 自动踩点 /
     * 目标追踪）—— 它们的模型加载器先要这一关通过才会 `init`，不通过就静默返回 false，
     * 界面上表现为功能点了没反应。
     *
     * 两条路一起走：
     *
     * * **Java 层** —— `CerChecker` 的 `(Context)int` 包装方法常量化成 0（通过）。
     *   类名在 `com.cer` 下没被混淆，方法按形状挑，一步到位。
     * * **原生层** —— 真正的 `cerCheck` 是 `private static native`，由 `JNI_OnLoad`
     *   用 `RegisterNatives` 动态绑定，导出表里只有
     *   `_Z16NAbvVQDZzQvWCPNp…` 这种混淆过的 C++ 符号，`dlsym` 按名字找不到它。
     *   名字和指针唯一同时存在的时刻就是注册那一刻 —— 用 :native 的注册监视在
     *   ART 绑定之前替换掉 `fnPtr`，目标的 `.so` 一个字节都不用改，
     *   原生侧的自校验也看不出来。
     *
     * 默认关闭：Xposed 不改动 APK，签名本来就是完整的，这一关本来就会过。
     * 它留给用重打包 / 改签名版本的场景。
     */
    private fun HookScope.installSignatureCheck() {
        // 注册监视必须赶在 libcer.so 被加载之前装 —— 这里是 PACKAGE_READY，
        // 早于应用任何代码执行，来得及。
        if (NativeHook.watchJniRegistrations()) {
            if (NativeHook.returnConstantOnJniRegister(InShot.CER_CHECKER, "cerCheck", 0)) {
                log.i("已在 JNI 注册时接管 cerCheck")
            } else {
                log.w("cerCheck 的注册规则没装上，只走 Java 层")
            }
        } else {
            log.w("原生注册监视装不上（本机 ABI 可能没有对应的 .so），只走 Java 层")
        }

        val method = refs.cerCheck
        if (method == null) {
            log.w("没定位到 ${InShot.CER_CHECKER} 的校验方法，只剩原生层那条路")
            return
        }
        // 具名传参：返回值是 int，位置传参会和 `(value, priority: Int)` 那个重载撞上。
        method.createReturnConstantHook(key = "instashot.cer", value = 0)
        log.i("签名校验已接管：${method.declaringClass.name}.${method.name}")
    }

    /**
     * 排查用日志。
     *
     * 记的是**最终**判定值 —— [installLifetime] 的钩子挂在同一个方法上，两个 after
     * 钩子的执行顺序不保证，所以这里读到的可能已经是被按过的值。要看原始值就把
     * `lifetime` 关掉再看。
     */
    private fun HookScope.installLog() {
        refs.userIsPro?.createAfterHook("instashot.log.verdict") { param ->
            log.i("权益判定 isPro() = ${param.result}")
        }
        refs.isSubscribePro?.createAfterHook("instashot.log.subscribe") { param ->
            log.i("权益判定 isSubscribePro() = ${param.result}")
        }
        refs.applyPurchases?.createBeforeHook("instashot.log.billing") { param ->
            val result = param.args.getOrNull(1)
            val purchases = param.args.getOrNull(2) as? List<*>
            log.i("结算回包：result=${result ?: "null"}，购买数=${purchases?.size ?: "null"}")
            purchases?.forEach { log.i("  $it") }
        }
        log.i("权益与结算日志已开启")
    }
}
