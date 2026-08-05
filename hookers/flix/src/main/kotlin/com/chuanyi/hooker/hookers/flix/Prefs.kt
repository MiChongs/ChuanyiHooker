package com.chuanyi.hooker.hookers.flix

import com.chuanyi.hooker.core.HookScope
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook

/**
 * 本地会员缓存这一侧。
 *
 * ## 这是第二条通道，不是主力
 *
 * `VipService` 有两条路给 `_setIsFlixMax` 喂值：
 *
 * * `loadVipStatus()` —— 联网，问服务端要日期，自己算天数
 * * `syncFromPrefs()` —— 离线，读本地 `flutter.vipRemainingDays`，**大于 0 就算会员**
 *
 * 主力补丁 [Entitlement.MAX_FLAG] 落在两条路的交汇处，本来就够。这里再补一手离线路径，
 * 图的是**不依赖代码补丁**：目标发新版把 Dart 那边的特征码打乱之后，代码补丁会安全地
 * 跳过（三道校验拦着），而这一侧只依赖一个 key 名 —— key 比机器码稳得多，大概率还活着。
 *
 * ## 为什么是 `getAll` 而不是 `getLong`
 *
 * Flutter 的 `shared_preferences` 在 Android 上走
 * `LegacySharedPreferencesPlugin.getAllPrefs()`，里面是 `preferences.getAll()` 一次性
 * 拿走整张表再过滤 —— 逐个 `getLong` 的调用根本不会发生。只挂 `getLong` 会一次都不触发。
 *
 * `SharedPreferencesImpl.getAll()` 返回的是内部表的**拷贝**（`new HashMap<>(mMap)`），
 * 改它不会污染应用真正的持久化数据：磁盘上那份 `FlutterSharedPreferences.xml` 一个字节
 * 都没动，关掉模块立刻恢复原样。
 *
 * `getLong` 仍然挂着，成本是一次 key 前缀比较 —— 万一哪个版本改走了逐个读的路径，这边
 * 不至于整个失效。
 */
internal object Prefs {

    /** Flutter 给所有 key 统一加的前缀。 */
    private const val FLUTTER_PREFIX = "flutter."

    /** 剩余天数。`syncFromPrefs` 判 `> 0` 的就是它。 */
    const val KEY_REMAINING_DAYS = "flutter.vipRemainingDays"

    /** 登录令牌。`loadVipStatus` 拿不到它会直接判未登录，这里只读不改。 */
    private const val KEY_TOKEN = "flutter.token"

    private const val PREFS_IMPL = "android.app.SharedPreferencesImpl"
    private const val EDITOR_IMPL = "android.app.SharedPreferencesImpl\$EditorImpl"

    /**
     * 让本地缓存里的剩余天数恒为 [days]。
     *
     * 只改读出来的值，不写磁盘 —— 应用下次联网还是会用服务端的真实数据覆盖它自己那份，
     * 而我们每次读都会再抬一次，两边互不干扰。
     */
    fun installGuard(scope: HookScope, days: Long) = with(scope) {
        val impl = classOrNull(PREFS_IMPL) ?: run {
            log.w("找不到 $PREFS_IMPL，本地会员兜底跳过")
            return@with
        }

        impl.declaredMethods
            .firstOrNull { it.name == "getAll" && it.parameterCount == 0 }
            ?.createAfterHook("flix.prefs.getAll") { param ->
                @Suppress("UNCHECKED_CAST")
                val map = param.result as? MutableMap<String, Any?> ?: return@createAfterHook
                // 键不在表里说明这个 SharedPreferences 不是 Flutter 那份，别乱插。
                if (!map.containsKey(KEY_REMAINING_DAYS)) return@createAfterHook
                val old = map[KEY_REMAINING_DAYS]
                if (old is Long && old >= days) return@createAfterHook
                map[KEY_REMAINING_DAYS] = days
                log.d("本地剩余天数 $old → $days")
            } ?: log.w("$PREFS_IMPL 上没有 getAll()，本地会员兜底只剩 getLong 一条")

        impl.declaredMethods
            .firstOrNull { it.name == "getLong" && it.parameterCount == 2 }
            ?.createAfterHook("flix.prefs.getLong") { param ->
                if (param.args.getOrNull(0) != KEY_REMAINING_DAYS) return@createAfterHook
                val old = param.result as? Long ?: return@createAfterHook
                if (old >= days) return@createAfterHook
                param.result = days
            }

        log.i("本地会员兜底已挂上（剩余天数不低于 $days）")
    }

    /**
     * 把 Flutter 那边的持久化读写打出来。
     *
     * 排查用：会员态到底是从缓存来的还是从服务端来的、token 有没有、天数是多少 —— 跑一次
     * 就看得见，不用去猜快照里的数据格式。令牌只打长度不打内容。
     */
    fun installTrace(scope: HookScope) = with(scope) {
        val impl = classOrNull(PREFS_IMPL) ?: run {
            log.w("找不到 $PREFS_IMPL，存储追踪跳过")
            return@with
        }

        impl.declaredMethods
            .filter { it.name in READ_METHODS }
            .forEach { method ->
                method.createAfterHook("flix.trace.${method.name}") { param ->
                    val key = param.args.getOrNull(0) as? String ?: return@createAfterHook
                    if (!key.startsWith(FLUTTER_PREFIX)) return@createAfterHook
                    log.i("读 $key = ${param.result.redact(key)}")
                }
            }

        impl.declaredMethods
            .firstOrNull { it.name == "getAll" && it.parameterCount == 0 }
            ?.createAfterHook("flix.trace.getAll") { param ->
                val map = param.result as? Map<*, *> ?: return@createAfterHook
                val flutterKeys = map.keys.filterIsInstance<String>().filter { it.startsWith(FLUTTER_PREFIX) }
                if (flutterKeys.isEmpty()) return@createAfterHook
                log.i("读全表：" + flutterKeys.joinToString { "$it=${map[it].redact(it)}" })
            }

        classOrNull(EDITOR_IMPL)?.declaredMethods
            ?.filter { it.name in WRITE_METHODS }
            ?.forEach { method ->
                method.createAfterHook("flix.trace.${method.name}") { param ->
                    val key = param.args.getOrNull(0) as? String ?: return@createAfterHook
                    if (!key.startsWith(FLUTTER_PREFIX)) return@createAfterHook
                    log.i("写 $key = ${param.args.getOrNull(1).redact(key)}")
                }
            }

        log.i("存储追踪已挂上")
    }

    /** 令牌只报长度 —— 日志会落盘，没必要把它原样留在里面。 */
    private fun Any?.redact(key: String): String = when {
        this == null -> "null"
        key == KEY_TOKEN -> "<${toString().length} 字符>"
        else -> toString().take(200)
    }

    private val READ_METHODS = setOf("getString", "getLong", "getInt", "getBoolean", "getStringSet")
    private val WRITE_METHODS = setOf("putString", "putLong", "putInt", "putBoolean")
}
