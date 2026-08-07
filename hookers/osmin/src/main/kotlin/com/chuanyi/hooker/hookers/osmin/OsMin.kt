package com.chuanyi.hooker.hookers.osmin

import android.content.SharedPreferences
import com.chuanyi.hooker.core.HookScope
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 目标里那些名字**没被 R8 动过**的坐标，以及从它们出发的形状定位。
 *
 * 这个目标的混淆边界很整齐：写进 manifest 与 `java_init.list` 的类保留了原名
 * （`OsApp` / `MainActivity` / `OsXposedModule`），其余全部被重命名进默认包并合并
 * 成几个巨型类。所以能写死的只有这三个名字，别的一律靠形状或特征串找。
 */
internal object OsMin {

    const val PKG = "org.chsi.min"

    /** `Application` 子类。静态字段里握着与框架通信的那个服务对象。 */
    const val APP = "org.chsi.min.OsApp"

    /** 唯一的界面入口。权益补写挂在它的生命周期上。 */
    const val MAIN_ACTIVITY = "org.chsi.min.MainActivity"

    // -----------------------------------------------------------------------
    // 存储
    // -----------------------------------------------------------------------

    /**
     * 功能开关那份配置，**同时存在两处**：目标自己数据目录里的一份，以及框架托管的
     * 一份远端副本。被注入的进程只读得到后者 —— 这是整个解锁方案的着力点。
     */
    const val PREFS_SETTINGS = "module_settings"

    /** 账号那份配置，只在应用进程里用。令牌与等级都在这儿。 */
    const val PREFS_CONFIG = "module_config"

    /**
     * 跨进程的权益开关，**目标全部付费功能唯一的总闸**。
     *
     * 被注入的进程里每一处判定都是 `auth_active && <该功能的开关>`，除此之外不看
     * 设备指纹、不看有效期、不做签名校验 —— 所以把这个布尔钉成 true，
     * 那十个 ColorOS 进程里的付费功能就全部放行，我们一行代码都不用注入进去。
     */
    const val KEY_AUTH_ACTIVE = "auth_active"

    /** 账号等级。界面显示它，同时它也是 [OsMinDex.tierGate] 的输入。 */
    const val KEY_TIER = "mine_auth_tier_v2"

    /** 登录令牌。为空即未登录，本 hooker 只读不改（见 [Entitlement]）。 */
    const val KEY_TOKEN = "mine_auth_token_v2"

    // -----------------------------------------------------------------------
    // 等级
    // -----------------------------------------------------------------------

    /** 判定函数认的三个等级，任意一个成立即已授权。 */
    const val TIER_LIFETIME = "永久绑定"
    const val TIER_MONTHLY = "月授权"
    const val TIER_TRIAL = "试用"

    /** 未授权时界面显示的那个值，也是等级读取的默认值。 */
    const val TIER_NONE = "未授权"

    val TIERS_AUTHORIZED = listOf(TIER_MONTHLY, TIER_LIFETIME, TIER_TRIAL)

    private const val PREFS_IMPL = "android.app.SharedPreferencesImpl"

    fun prefsImpl(scope: HookScope): Class<*>? = scope.classOrNull(PREFS_IMPL)

    /**
     * 那份**远端**配置。
     *
     * 目标拿它的路径是 `OsApp.<某个静态字段>.<某个无参方法>()`，两个名字都被混淆过，
     * 但形状是唯一的：`OsApp` 上只有一个静态字段的类型带着「无参、返回
     * `SharedPreferences`」的方法。按这个形状找，比记住 `OsApp.f` 和 `c12.d()`
     * 稳得多 —— 那两个名字下次构建就会变。
     *
     * 返回 null 有一种正常情况：与框架的连接还没建立。调用方要能接受并稍后重试，
     * 不要当成错误。
     */
    fun remotePrefs(scope: HookScope): SharedPreferences? = runCatching {
        val app = scope.classOrNull(APP) ?: return null
        val service = app.declaredFields
            .asSequence()
            .filter { Modifier.isStatic(it.modifiers) }
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(null)
                }.getOrNull()
            }
            .firstOrNull { prefsGetter(it.javaClass) != null }
            ?: return null
        val getter = prefsGetter(service.javaClass) ?: return null
        getter.invoke(service) as? SharedPreferences
    }.getOrNull()

    /** 无参、返回 `SharedPreferences` 的方法 —— 服务对象上只有一个。 */
    private fun prefsGetter(type: Class<*>): Method? = runCatching {
        type.declaredMethods.firstOrNull {
            it.parameterCount == 0 && it.returnType == SharedPreferences::class.java
        }?.apply { isAccessible = true }
    }.getOrNull()
}
