package com.chuanyi.hooker.hookers.gboard

import android.content.Context
import android.net.Uri
import com.chuanyi.hooker.core.HookScope
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createReplaceHook

/**
 * 剪贴板的三处限制。
 *
 * Gboard 的剪贴板面板由一个后台 `Callable` 装填：它查一次库，把结果分成「最近 /
 * 已固定 / 截图等」三段，中间夹上三个段头对象，交给 adapter 铺开。两处限制就写死在
 * 这个 `Callable` 里 ——
 *
 * ```java
 * // 时间下限：一小时
 * Math.max(Instant.now().toEpochMilli() - 3600000, prefs.getLong(…))
 * // 最近段：最多 5 组
 * if (instant == null || !item.timestamp.equals(instant)) { if (groups >= 5) break; … }
 * ```
 *
 * 两个都是内联常量，Java 层改不动。所以这里**整个替换那个 `Callable`**：查询、
 * 分段、组装全部自己来，用户设的数值直接进去。列表结构（段头位置、顺序）与原版
 * 完全一致 —— 下游 adapter 靠 `indexOf(段头)` 反算每段条数，错一位整个面板就乱。
 *
 * 第三处限制（单条字数）在别处，是个 Phenotype flag，见 [installCharLimit]。
 *
 * ## 关于「条」
 *
 * 原版数的是**不同时间戳的组数**，不是条数：一次复制如果被实体识别拆成了电话、
 * 网址等好几条，它们共享一个时间戳，只算一组。这里跟着原版数组，所以默认值 5
 * 就是原版行为，用户填 30 得到的是「最近 30 次复制」。
 */
internal object GboardClipboard {

    /** 普通「最近」项：既不是固定项，也不是截图那一类。 */
    private const val SEL_RECENT =
        "(item_type & 1) = 0 AND (item_type & 2) = 0 AND timestamp >= ?"

    /** 用户固定的项，不受有效期影响。 */
    private const val SEL_PINNED = "(item_type & 1) != 0"

    /** 第三段：截图等由系统塞进来的。 */
    private const val SEL_OTHER = "(item_type & 1) = 0 AND (item_type & 2) != 0"

    /** 该被清掉的：普通项里过了有效期的。固定项和截图段不动。 */
    private const val SEL_EXPIRED =
        "(item_type & 1) = 0 AND (item_type & 2) = 0 AND timestamp < ?"

    private const val ORDER_NEWEST = "timestamp DESC"

    /** ContentProvider 的 URI matcher：2 = 整张 clips 表。 */
    private const val MATCH_ALL = 2

    /** 固定项在原版里就有的上限，这里照搬 —— 面板再长也得有个头。 */
    private const val MAX_PINNED = 200

    /**
     * 接管列表装填：最近段的条数与有效期都换成用户设的。
     *
     * 两个功能共用一个 hook 是有意的 —— 它们改的是同一次查询的两个参数，拆成两个
     * hook 就得让第二个知道第一个替换过什么。这里由调用方把「没开的那一项用原版
     * 默认值」算好再传进来。
     */
    fun installList(scope: HookScope, maxRecent: Int, ttlMillis: Long) {
        val refs = GboardRefs.of(scope)
        val loader = GboardDex.callableByString(scope, GboardDex.ANCHOR_LOADER)
            ?: error("找不到剪贴板装填逻辑")

        // 先把这几个解析跑一遍：装填发生在后台线程，那里抛异常只会得到一个空面板，
        // 不如在装 hook 的时候就炸出来，日志和界面上都能看到原因。
        scope.log.d("剪贴板入口：${refs.queryItems.declaringClass.name}，段头 ${refs.sepRecent.javaClass.name}")
        check(refs.buildUri.parameterCount == 3) { "剪贴板 URI 方法形状不对" }

        loader.createReplaceHook("gboard.clipboard.list") {
            val context = scope.appContextOrNull()
            if (context == null) {
                scope.log.w("装填剪贴板时拿不到 Context，退回空列表")
                ArrayList<Any>()
            } else {
                runCatching { build(scope, refs, context, maxRecent, ttlMillis) }
                    .onFailure { scope.log.e("重建剪贴板列表失败", it) }
                    .getOrDefault(ArrayList())
            }
        }
        scope.log.i("剪贴板列表已接管：最近 $maxRecent 条，有效期 ${ttlMillis / 60_000} 分钟")
    }

    /**
     * 接管过期清理。
     *
     * 原版这一步在总条数攒到 120 时才跑，删掉普通项里超过一小时的 —— 也就是说
     * 日常使用（不到 120 条）里过期项**并没有真被删**，只是查询时被时间下限挡住。
     * 所以延长有效期本身不需要动它；但一旦条数过线，原版会按它那一小时的尺度把
     * 用户想留的东西删掉，所以有效期改长了就得把这一步也换成同一把尺子。
     *
     * 「永久保留」时这里什么都不删。
     */
    fun installCleaner(scope: HookScope, ttlMillis: Long) {
        val refs = GboardRefs.of(scope)
        val cleaner = GboardDex.callableByString(scope, GboardDex.ANCHOR_CLEANER)
            ?: error("找不到剪贴板清理逻辑")

        cleaner.createReplaceHook("gboard.clipboard.cleaner") {
            if (ttlMillis > 0) {
                val context = scope.appContextOrNull()
                if (context != null) {
                    val cutoff = System.currentTimeMillis() - ttlMillis
                    runCatching {
                        val deleted = context.contentResolver.delete(
                            clipsUri(refs, context),
                            SEL_EXPIRED,
                            arrayOf(cutoff.toString()),
                        )
                        if (deleted > 0) scope.log.d("清掉 $deleted 条过期剪贴项")
                    }.onFailure { scope.log.w("清理过期剪贴项失败：${it.message}") }
                }
            }
            // 原版这里返回的也是 null（Callable<Void>），后续回调只看有没有抛异常。
            null
        }
        val how = if (ttlMillis > 0) "超过 ${ttlMillis / 60_000} 分钟才清" else "不再自动清理"
        scope.log.i("过期清理已接管：$how")
    }

    /**
     * 单条字数上限。
     *
     * 这一处不用 hook：它是个 Phenotype flag（`text_clip_item_char_limit`，默认
     * 20000），而 flag 对象自带一个把取值写进最高优先级档位的入口 —— 直接调它，
     * 之后所有读取点（复制时的截断、显示时的长度计算）拿到的都是新值。
     */
    fun installCharLimit(scope: HookScope, limit: Int) {
        val refs = GboardRefs.of(scope)
        // flag 存的是 long，给 int 会在取值处 ClassCastException。
        val ok = refs.overrideFlag(GboardRefs.FLAG_CHAR_LIMIT, limit.toLong())
        if (!ok) error("改不了 ${GboardRefs.FLAG_CHAR_LIMIT}")
        scope.log.i("剪贴板单条字数上限改为 $limit")
    }

    // -----------------------------------------------------------------------

    /**
     * 重建面板列表。
     *
     * 三段各查一次，比原版查一次再按位分组多两次查询，但省掉了读 `item_type` ——
     * 位运算写在 SQL 里，一个混淆过的字段访问都不需要。剪贴板的量级（百条）下
     * 这点差别看不出来。
     */
    private fun build(
        scope: HookScope,
        refs: GboardRefs,
        context: Context,
        maxRecent: Int,
        ttlMillis: Long,
    ): ArrayList<Any> {
        val since = if (ttlMillis <= 0) 0L else (System.currentTimeMillis() - ttlMillis).coerceAtLeast(0L)

        val recent = query(refs, context, SEL_RECENT, arrayOf(since.toString()))
        val pinned = query(refs, context, SEL_PINNED, emptyArray())
        val other = query(refs, context, SEL_OTHER, emptyArray())

        val out = ArrayList<Any>(recent.size + pinned.size + other.size + 3)
        out.add(refs.sepRecent)
        out.addAll(takeGroups(refs, recent, maxRecent))
        out.add(refs.sepPinned)
        out.addAll(pinned.take(MAX_PINNED))
        out.add(refs.sepOther)
        out.addAll(other)

        scope.log.d("剪贴板：最近 ${out.size - pinned.size - other.size - 3}，固定 ${pinned.size}，其他 ${other.size}")
        return out
    }

    /**
     * 取前 [limit] **组**（一组 = 一个时间戳 = 一次复制）。
     *
     * 列表已按时间倒序，所以相邻的同一时间戳一定挨着，扫一遍就够。
     */
    private fun takeGroups(refs: GboardRefs, items: List<Any>, limit: Int): List<Any> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<Any>(minOf(items.size, limit))
        var groups = 0
        var current: Any? = null
        for (item in items) {
            val stamp = refs.timestampOf(item)
            if (current == null || current != stamp) {
                if (groups >= limit) break
                current = stamp
                groups++
            }
            out.add(item)
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun query(
        refs: GboardRefs,
        context: Context,
        selection: String,
        args: Array<String>,
    ): List<Any> = runCatching {
        refs.queryItems.invoke(null, context, selection, args, ORDER_NEWEST) as? List<Any>
    }.getOrNull() ?: emptyList()

    private fun clipsUri(refs: GboardRefs, context: Context): Uri =
        refs.buildUri.invoke(null, context, MATCH_ALL, -1L) as Uri
}
