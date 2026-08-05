package com.chuanyi.hooker.hookers.wink

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.chuanyi.hooker.core.HookScope
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 按目标自己的日志串在 dex 里定位会员体系，全程不写死任何被 R8 重排过的名字。
 *
 * Wink 的会员判定是这么一条链：
 *
 * ```
 * VipInfoData         服务端下发的会员信息实体（er.i2）
 *        ↓
 * 会员类型(实体)Ⅰ      算成位掩码：4=VIP，12=SVIP(4|8)，1=过期，2=从来不是
 *        ↓
 * isVipUser()Z        (掩码 & 4) == 4
 * isSVIPUser()Z       (掩码 & 12) == 12
 * ```
 *
 * **那个「会员类型」是全 app 唯一的计算点** —— 设置页、我的页、桌面入口、编辑器的
 * 素材锁、导出档位、去广告标记，全部由它派生。所以主落点只有一个，另外两个方法
 * 只是补上它们各自的短路分支（见 [Refs.isVip]）。
 *
 * ## 锚点为什么选日志串
 *
 * 类名方法名全是 `rg0.b` / `er.i2` / `q()` / `n()` 这种，下一次构建就会换。但这三处
 * 的**日志文本**留着语义原文：
 *
 * | 锚点 | 出处 | 为什么稳 |
 * |---|---|---|
 * | `VipInfoData:[is_vip:` | 会员信息的 toString 辅助方法 | 拼的是字段的**协议名**，改它等于改日志格式 |
 * | `isVipUser,isInitialized(false)` | 判定方法自己的告警 | 串里写着方法的原始名字，是它的自我标识 |
 * | `isSVIPUser,isInitialized(false)` | 同上 | 同上 |
 *
 * 头一个锚点找到的是**同类里的另一个方法**（拼日志那个），会员类型计算和它同住一个
 * 类 —— 那个类只有三个方法，按形状（静态 + 一参 + 返回 int）挑就够，不会撞。
 *
 * ## 为什么必须缓存
 *
 * Wink 的 base.apk 有 **107 MB**、二十几个 dex，全量扫一遍是秒级的，而这段代码就跑在
 * 应用的启动路径上。所以扫描结果按 versionCode 缓存进**目标自己**的数据目录（模块的
 * 远端配置对被 hook 的进程只读），之后每次启动只是一次 SharedPreferences 读取。
 *
 * 缓存里存的是类名与方法名，回来时按名字反射 —— 对不上（原地覆盖安装但 versionCode
 * 没变）就当没缓存重新扫。
 */
internal object WinkDex {

    private const val CACHE_FILE = "chuanyi_hooker_wink"
    private const val KEY_VERSION = "version"

    /**
     * 会员信息的日志拼装方法。两个串一起用 —— 单独一个 `,use_vip:` 太普通，
     * 而 `usingStrings` 之间是与关系，两个都出现的方法全 app 只有这一个。
     */
    private val ANCHOR_VIP_INFO = listOf("VipInfoData:[is_vip:", ",use_vip:")

    /** `isVipUser()` 在会员模块没初始化时打的那句告警。 */
    private const val ANCHOR_IS_VIP = "isVipUser,isInitialized(false)"

    /** `isSVIPUser()` 同上。 */
    private const val ANCHOR_IS_SVIP = "isSVIPUser,isInitialized(false)"

    private val INT = Int::class.javaPrimitiveType!!
    private val BOOLEAN = Boolean::class.javaPrimitiveType!!

    /**
     * 定位结果。每一项都可能为 null —— 少一项只少一条路，不该让整个 hooker 罢工。
     */
    class Refs(
        /**
         * `(VipInfoData)I` 会员类型位掩码。**主落点**，接管它等于接管全部会员判断。
         */
        val vipType: Method?,
        /**
         * `()Z` isVipUser。它自己会先查「会员模块初始化了没」，没初始化就直接返回
         * false —— 那一小段窗口里 [vipType] 根本不会被调到，所以这一个也要挂。
         */
        val isVip: Method?,
        /** `()Z` isSVIPUser，同 [isVip]。 */
        val isSvip: Method?,
        /** 会员信息实体的类，用来按 `@SerializedName` 定位字段。见 [VipInfo]。 */
        val vipInfoClass: Class<*>?,
    ) {
        /** 主落点都没有就等于什么也做不了。 */
        val isEmpty: Boolean get() = vipType == null

        override fun toString(): String =
            "会员类型=${vipType.describe()} isVip=${isVip.describe()} " +
                "isSVIP=${isSvip.describe()} 信息实体=${vipInfoClass?.name ?: "-"}"

        private fun Method?.describe(): String =
            if (this == null) "-" else "${declaringClass.name}.$name"
    }

    // -----------------------------------------------------------------------

    fun resolve(scope: HookScope): Refs {
        val vipType = vipTypeMethod(scope)
        return Refs(
            vipType = vipType,
            isVip = booleanFlagMethod(scope, ANCHOR_IS_VIP, "is_vip"),
            isSvip = booleanFlagMethod(scope, ANCHOR_IS_SVIP, "is_svip"),
            // 会员类型方法的唯一入参就是会员信息实体本身 —— 不用再扫一次。
            vipInfoClass = vipType?.parameterTypes?.firstOrNull(),
        )
    }

    // -----------------------------------------------------------------------

    /**
     * 会员类型计算：先按日志串找到同类的拼装方法，再在那个类里按形状挑。
     *
     * 那个类一共三个方法（拼日志 → String、算类型 → int、是不是会员 → boolean），
     * 「静态 + 一个入参 + 返回 int」只有一个。
     */
    private fun vipTypeMethod(scope: HookScope): Method? {
        cachedName(scope, "class:vip_type")
            ?.let { scope.classOrNull(it) }
            ?.let(::pickVipType)
            ?.let { return it }

        val owner = scan(scope, "扫会员类型计算") { dex ->
            dex.findMethod { matcher { usingStrings(ANCHOR_VIP_INFO) } }
                .asSequence()
                .mapNotNull { runCatching { it.getMethodInstance(scope.classLoader) }.getOrNull() }
                .map { it.declaringClass }
                .firstOrNull { pickVipType(it) != null }
        } ?: return null

        return pickVipType(owner)?.also { remember(scope, "class:vip_type", owner.name) }
    }

    private fun pickVipType(owner: Class<*>): Method? = owner
        .declaredMethods
        .filter {
            !it.isSynthetic && !it.isBridge &&
                Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 1 &&
                it.returnType == INT &&
                // 入参必须是引用类型：会员信息实体，不是别的重载
                !it.parameterTypes[0].isPrimitive
        }
        .singleOrNull()
        ?.apply { isAccessible = true }

    /**
     * 按告警串找一个静态无参 boolean —— 也就是 isVipUser / isSVIPUser。
     *
     * `paramCount` 与 `returnType` 一起卡住，就不会命中同样引用了这个串的日志工具类。
     */
    private fun booleanFlagMethod(scope: HookScope, anchor: String, cacheKey: String): Method? {
        cachedName(scope, "method:$cacheKey")
            ?.split('#')
            ?.takeIf { it.size == 2 }
            ?.let { (owner, name) ->
                scope.classOrNull(owner)
                    ?.declaredMethods
                    ?.firstOrNull { it.name == name && it.parameterCount == 0 && it.returnType == BOOLEAN }
                    ?.let { return it.apply { isAccessible = true } }
            }

        return scan(scope, "扫 $cacheKey") { dex ->
            dex.findMethod {
                matcher {
                    paramCount = 0
                    returnType = "boolean"
                    usingStrings(anchor)
                }
            }.firstOrNull()?.getMethodInstance(scope.classLoader)
        }?.apply {
            isAccessible = true
            remember(scope, "method:$cacheKey", "${declaringClass.name}#$name")
        }
    }

    // -----------------------------------------------------------------------

    /**
     * DexKit 的原生库。
     *
     * 2.x 起 `DexKitBridge` **不再自己 `loadLibrary`**，要求使用方显式加载。漏掉这一步
     * 的表现不是「找不到 so」，而是调用时报 `No implementation found for …
     * nativeInitDexKit`，看起来像版本不匹配。
     *
     * 在被注入的进程里还有第二层麻烦：`System.loadLibrary` 按**调用类的 classloader**
     * 找库，而这里的调用类由框架的模块 classloader 加载，它未必带着模块 APK 的原生库
     * 路径。所以直接加载失败时，再问一次 classloader 那个库的绝对路径
     * （`findLibrary` 是 protected，只能反射），拿到就按路径装。
     */
    private val nativeReady: Boolean by lazy {
        runCatching {
            System.loadLibrary("dexkit")
            return@lazy true
        }
        runCatching {
            val loader = WinkDex::class.java.classLoader ?: return@runCatching false
            val find = ClassLoader::class.java
                .getDeclaredMethod("findLibrary", String::class.java)
                .apply { isAccessible = true }
            val path = find.invoke(loader, "dexkit") as? String ?: return@runCatching false
            System.load(path)
            true
        }.getOrDefault(false)
    }

    /**
     * 开一次 DexKit 做一件事。
     *
     * 打不开就返回 null（本机 ABI 没有 `libdexkit.so` 时会抛 `UnsatisfiedLinkError`）——
     * 调用方各自决定这一项是跳过还是报错，不牵连别的落点。
     */
    private fun <T> scan(scope: HookScope, what: String, block: (DexKitBridge) -> T?): T? {
        val apkPath = scope.apkPath
        if (apkPath.isNullOrEmpty()) {
            scope.log.w("拿不到 APK 路径，跳过 $what")
            return null
        }
        if (!nativeReady) {
            scope.log.w("libdexkit.so 装不上（本机 ABI 可能没打进来），跳过 $what")
            return null
        }
        val startedAt = SystemClock.elapsedRealtime()
        val bridge = runCatching { DexKitBridge.create(apkPath) }
            .onFailure { scope.log.w("DexKit 打不开 $apkPath：${it.message}") }
            .getOrNull() ?: return null

        return bridge.use { dex ->
            val result = runCatching { block(dex) }
                .onFailure { scope.log.w("$what 失败：${it.message}") }
                .getOrNull()
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (result == null) scope.log.w("$what 没有结果（${elapsed}ms）")
            else scope.log.d("$what 用时 ${elapsed}ms")
            result
        }
    }

    private fun cache(scope: HookScope): SharedPreferences? =
        scope.appContextOrNull()?.let {
            runCatching { it.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE) }.getOrNull()
        }

    private fun cachedName(scope: HookScope, key: String): String? {
        val prefs = cache(scope) ?: return null
        if (prefs.getLong(KEY_VERSION, Long.MIN_VALUE) != scope.versionCode) return null
        return prefs.getString(key, null)?.takeIf { it.isNotBlank() }
    }

    /**
     * 版本变了就先清空再写：旧版本扫出来的名字放在那儿，[cachedName] 虽然会因为版本对
     * 不上而不用它，但一旦某次写入把版本号抬上来，那些陈旧条目就会突然「生效」。
     */
    private fun remember(scope: HookScope, key: String, name: String) {
        val prefs = cache(scope) ?: return
        runCatching {
            val editor = prefs.edit()
            if (prefs.getLong(KEY_VERSION, Long.MIN_VALUE) != scope.versionCode) {
                editor.clear().putLong(KEY_VERSION, scope.versionCode)
            }
            editor.putString(key, name).apply()
        }
    }
}
