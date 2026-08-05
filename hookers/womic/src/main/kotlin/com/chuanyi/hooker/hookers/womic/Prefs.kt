package com.chuanyi.hooker.hookers.womic

import android.content.SharedPreferences
import com.chuanyi.hooker.core.HookScope
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 权益的**读取端**，落在 SharedPreferences 上。
 *
 * ## 为什么这一侧最稳
 *
 * 应用把订阅结论缓存在 `settings/purchaseState`，启动时读一次进设置单例：
 *
 * ```java
 * this.purchaseState = prefs.getInt("purchaseState", 0);   // ← 就是这一句
 * this.productId     = prefs.getString("productId", null);
 * ```
 *
 * 单例的类名（`O2.a`）、字段名（`f`）、包名全是 R8 产物，下个版本必然重排；而
 * `"purchaseState"` 这个**键名**改不动 —— 一改就等于把所有老用户的缓存作废。
 * 所以这一侧一行混淆名都不用碰，一个类都不用找。
 *
 * ## 它管得到什么，管不到什么
 *
 * 管得到：**应用启动那一刻**的权益判定。设置单例、主 ViewModel 的权益 LiveData 初值、
 * 设置页的状态文案，全部由这一次读取决定。
 *
 * 管不到：**运行中**的改写。应用每次回到前台都会向 Play 查一次购买，查完不管结果如何
 * 都会走一遍写入点 —— 那一侧归 [WoMicDex] 找出来的方法管，见 [WoMicHooker]。
 *
 * 两侧合起来才是完整的：这一侧保证「开着就对」，另一侧保证「用着不会被改回去」。
 */
internal object Prefs {

    /**
     * 让读出来的订阅状态恒为「已购买」。
     *
     * 只改读出来的值，磁盘上那份 `settings.xml` 一个字节都不动 —— 关掉模块立刻恢复原样。
     * 想要关掉模块之后仍然有效的那种，用 [persist]。
     *
     * @param productId 商品 ID 读到空时补上的值，纯粹为了界面上有东西显示
     */
    fun installGuard(scope: HookScope, productId: String) = with(scope) {
        val impl = classOrNull(WoMic.PREFS_IMPL) ?: run {
            log.w("找不到 ${WoMic.PREFS_IMPL}，读取端兜底跳过")
            return@with
        }

        val getInt = impl.declaredMethods.firstOrNull { it.name == "getInt" && it.parameterCount == 2 }
        if (getInt == null) {
            log.w("${WoMic.PREFS_IMPL} 上没有 getInt(String,int)，读取端兜底装不上")
        } else {
            getInt.createAfterHook("womic.prefs.getInt") { param ->
                if (param.args.getOrNull(0) != WoMic.KEY_PURCHASE_STATE) return@createAfterHook
                val old = param.result as? Int ?: return@createAfterHook
                if (old == WoMic.STATE_PURCHASED) return@createAfterHook
                param.result = WoMic.STATE_PURCHASED
                log.d("读 ${WoMic.KEY_PURCHASE_STATE}: $old → ${WoMic.STATE_PURCHASED}")
            }
        }

        // 商品 ID 只影响界面显示，读到空补一个；已经有真实值就别覆盖 —— 用户真买过
        // 月付的话，界面上显示他自己那份更合理。
        impl.declaredMethods
            .firstOrNull { it.name == "getString" && it.parameterCount == 2 }
            ?.createAfterHook("womic.prefs.getString") { param ->
                if (param.args.getOrNull(0) != WoMic.KEY_PRODUCT_ID) return@createAfterHook
                val old = param.result as? String
                if (!old.isNullOrBlank()) return@createAfterHook
                param.result = productId
                log.d("读 ${WoMic.KEY_PRODUCT_ID}: 空 → $productId")
            }

        // 这个应用自己不走 getAll()，但 androidx.preference 在设置页会走一次全表。
        // 那张表是内部 map 的**拷贝**（`new HashMap<>(mMap)`），改它污染不到磁盘。
        impl.declaredMethods
            .firstOrNull { it.name == "getAll" && it.parameterCount == 0 }
            ?.createAfterHook("womic.prefs.getAll") { param ->
                @Suppress("UNCHECKED_CAST")
                val map = param.result as? MutableMap<String, Any?> ?: return@createAfterHook
                // 键不在表里说明这不是 settings 那份，别乱插。
                if (!map.containsKey(WoMic.KEY_PURCHASE_STATE)) return@createAfterHook
                if (map[WoMic.KEY_PURCHASE_STATE] == WoMic.STATE_PURCHASED) return@createAfterHook
                map[WoMic.KEY_PURCHASE_STATE] = WoMic.STATE_PURCHASED
            }

        log.i("读取端兜底已挂上（${WoMic.KEY_PURCHASE_STATE} 恒为 ${WoMic.STATE_PURCHASED}）")
    }

    /**
     * 把「已购买」真正写进磁盘上的 `settings.xml`。
     *
     * ## 这一项解决的是「关掉模块之后」
     *
     * [installGuard] 是运行期改写，模块一停就没了。写进磁盘之后，应用启动时读到的就是
     * 真实的 1，**不需要模块在场**。
     *
     * ## 它什么时候会被应用改回去
     *
     * 应用每次回到前台都会 `queryPurchasesAsync`，回调里：
     *
     * ```java
     * if (billingResult.code != 0) return;               // ← 连不上 Play：原样不动
     * if (purchases.isEmpty()) sink(0, "");              // ← 连得上但没买过：清零
     * ```
     *
     * 所以：
     *
     * * **Google 服务被冻结 / 停用 / 设备没装 GMS** → 走第一条，`return` 之后什么都不改，
     *   写进去的 1 就一直留着。这正是「冻结 Google 服务后仍然可用」的机制来源。
     * * **Google 服务正常但账号名下确实没有这个订阅** → 走第二条，会被清零。这时候要靠
     *   [installGuard] 和写入点拦截撑着 —— 也就是模块得开着。
     *
     * 写入借的是 [SharedPreferences] 实例自己的 `edit()`，不需要 Context，因此可以在
     * 应用刚开始读设置的那一刻就完成，早于任何权益判定。
     */
    fun installPersist(scope: HookScope, productId: String) = with(scope) {
        val impl = classOrNull(WoMic.PREFS_IMPL) ?: run {
            log.w("找不到 ${WoMic.PREFS_IMPL}，永久授权缓存写不了")
            return@with
        }
        val getInt = impl.declaredMethods.firstOrNull { it.name == "getInt" && it.parameterCount == 2 }
            ?: run {
                log.w("${WoMic.PREFS_IMPL} 上没有 getInt(String,int)，永久授权缓存写不了")
                return@with
            }

        val written = AtomicBoolean(false)
        getInt.createAfterHook("womic.persist") { param ->
            if (param.args.getOrNull(0) != WoMic.KEY_PURCHASE_STATE) return@createAfterHook
            // 借这次读取拿到的正是 settings 那个实例 —— 应用里只有它存这个键。
            val prefs = param.thisObjectOrNull as? SharedPreferences ?: return@createAfterHook
            if (!written.compareAndSet(false, true)) return@createAfterHook

            // 这里**不能**再调 prefs.getInt/getString 去看「磁盘上现在是多少」：
            // [installGuard] 挂的就是这两个方法，读回来的永远是改过的值，判断必然
            // 得出「已经是已购买」然后什么都不写。写入本身是幂等的，索性不判断，
            // 每个进程写一次就够。
            //
            // 商品 ID 例外：它读回来要么是磁盘上的真值（guard 只在空值时才替换），
            // 要么是我们自己的默认值 —— 两种情况写回去都对，真买过月付的用户不会被
            // 改成年付。
            val product = runCatching { prefs.getString(WoMic.KEY_PRODUCT_ID, null) }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: productId

            runCatching {
                prefs.edit()
                    .putInt(WoMic.KEY_PURCHASE_STATE, WoMic.STATE_PURCHASED)
                    .putString(WoMic.KEY_PRODUCT_ID, product)
                    .apply()
            }.onSuccess {
                log.i("已写入永久授权缓存（${WoMic.KEY_PURCHASE_STATE}=${WoMic.STATE_PURCHASED}，商品 $product）")
            }.onFailure {
                written.set(false)
                log.e("永久授权缓存写入失败", it)
            }
        }
    }

    /**
     * 把设置读写打出来。排查用：订阅状态到底是从缓存来的还是被 Play 改的，跑一次就看得见。
     */
    fun installTrace(scope: HookScope) = with(scope) {
        val impl = classOrNull(WoMic.PREFS_IMPL) ?: run {
            log.w("找不到 ${WoMic.PREFS_IMPL}，存储追踪跳过")
            return@with
        }

        impl.declaredMethods
            .filter { it.name in READ_METHODS && it.parameterCount == 2 }
            .forEach { method ->
                method.createAfterHook("womic.trace.${method.name}") { param ->
                    val key = param.args.getOrNull(0) as? String ?: return@createAfterHook
                    if (key !in TRACKED_KEYS) return@createAfterHook
                    log.i("读 $key = ${param.result}")
                }
            }

        classOrNull(WoMic.EDITOR_IMPL)?.declaredMethods
            ?.filter { it.name in WRITE_METHODS && it.parameterCount == 2 }
            ?.forEach { method ->
                method.createAfterHook("womic.trace.${method.name}") { param ->
                    val key = param.args.getOrNull(0) as? String ?: return@createAfterHook
                    if (key !in TRACKED_KEYS) return@createAfterHook
                    log.i("写 $key = ${param.args.getOrNull(1)}")
                }
            }

        log.i("存储追踪已挂上")
    }

    private val TRACKED_KEYS = setOf(WoMic.KEY_PURCHASE_STATE, WoMic.KEY_PRODUCT_ID)
    private val READ_METHODS = setOf("getInt", "getString", "getBoolean")
    private val WRITE_METHODS = setOf("putInt", "putString", "putBoolean")
}
