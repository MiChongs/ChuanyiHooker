package com.chuanyi.hooker.hookers.cellularpro

import com.chuanyi.hooker.core.HookerLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 把「当前拿到哪一档能力」这个状态改成高档那一档 —— 用赋值，不用打补丁。
 *
 * ## 为什么不能按常量硬顶
 *
 * 会员管理器上有一组互斥判定，形如「当前档位 == 某个常量」。功能入口按这组判定分支，
 * 工参管理那一支要求高档位成立。直觉做法是把这组判定按成想要的真假 —— 实测**不行**：
 * 这组判定同时也被射频与解码链路读，按成常量之后解码器走进不匹配的分支，进程在十几秒内
 * 崩在 `libd001.so`（厂商 diag 解码库）里，faultaddr 是一个被当成指针解引用的字符串。
 *
 * 正确做法是改那个**被比较的状态字段**本身：它是管理器上一个普通的 `String` 实例字段，
 * 高档位对应的常量也在同一个对象上。把状态字段赋成高档常量之后，那组判定各自算出来的
 * 结果自然自洽，射频与解码链路看到的也是一个真实存在的档位，不再走进死路。
 *
 * 这一步不需要任何 hook：普通反射赋值，Java 调用栈不变，代码段不变。
 *
 * ## 怎么在不知道字段名的前提下找对
 *
 * 类名与字段名都是构建期生成的，写死没有意义。这里改用**差分探测**：管理器上的
 * `String` 实例字段两两组合试一遍，每试一组就地评估那组判定 ——
 * 只有「状态字段 := 高档常量」这一组能让判定组呈现出唯一一种形态：
 * **恰好一个为真、其余全假**，且为真的那个不是原本就为真的那个。
 * 试错的每一步都会立刻还原，失败不留痕迹。
 */
internal object Channel {

    /**
     * 探测结果。
     *
     * @param field 被改写的状态字段
     * @param previous 改写前的值，用来回滚
     * @param granted 改写后为真的那个判定的方法名，只用来出日志
     */
    class Grant(val field: Field, val previous: Any?, val granted: String)

    /**
     * 把管理器的档位状态换成高档那一档。
     *
     * @param manager 会员管理器类
     * @param singleton 它的单例
     * @param predicates 那组互斥判定，见 [CellularPro.CHANNEL_PREDICATES]
     */
    fun grantTopTier(
        log: HookerLog,
        manager: Class<*>,
        singleton: Any,
        predicates: List<Method>,
    ): Grant? {
        if (predicates.size < 2) {
            log.w("互斥判定不足两个，无法判断档位是否改对")
            return null
        }
        val strings = manager.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .onEach { it.isAccessible = true }
        if (strings.size < 2) {
            log.w("管理器上没有足够的 String 字段，档位状态定位不到")
            return null
        }

        fun evaluate(): List<String> = predicates.mapNotNull { m ->
            runCatching { m.invoke(singleton) as? Boolean }.getOrNull()?.takeIf { it }?.let { m.name }
        }

        val baseline = evaluate()
        if (baseline.size > 1) {
            log.w("这组判定不是互斥的（当前为真：${baseline.joinToString()}），不改档位")
            return null
        }
        // 全假说明应用还没给自己定档 —— 这时候动手会挑中一个它本来不会用的档位。
        // 交给调用方下一轮再来。
        if (baseline.isEmpty()) return null

        for (state in strings) {
            val before = runCatching { state.get(singleton) }.getOrNull()
            for (source in strings) {
                if (source === state) continue
                val value = runCatching { source.get(singleton) }.getOrNull() ?: continue
                if (value == before) continue
                if (runCatching { state.set(singleton, value) }.isFailure) continue

                val now = evaluate()
                // 想要的形态：恰好一个为真，且正好是最高档那一个。换成别的档位同样满足
                // 「恰好一个为真」，但入口打不开，还会把射频通道换到用不上的路上。
                if (now.size == 1 && now.first() == CellularPro.TOP_CHANNEL_PREDICATE) {
                    log.i(
                        "档位状态 ${manager.simpleName}.${state.name} := ${source.name}，" +
                            "判定 ${now.first()} 成立（原为 ${baseline.firstOrNull() ?: "全假"}）",
                    )
                    return Grant(state, before, now.first())
                }
                runCatching { state.set(singleton, before) }
            }
            runCatching { state.set(singleton, before) }
        }
        log.w("试遍了也没找到能让高档位成立的赋值，档位保持原样")
        return null
    }
}
