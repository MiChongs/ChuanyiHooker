package com.chuanyi.hooker.hookers.wink

import com.chuanyi.hooker.core.HookScope
import java.lang.reflect.Field
import java.util.Calendar

/**
 * 按**协议字段名**读写会员信息实体的字段。
 *
 * 实体类（`er.i2`）连字段名带类名一起被 R8 换过，但它是 Gson 反序列化出来的 ——
 * 每个字段身上都挂着 `@SerializedName("valid_time")` 这样的注解，而 Gson 要在运行时
 * 读它，所以注解**必须**保留到 dex 里。实测这个版本 28 个字段 28 个都带着。
 *
 * 于是「哪个字段是到期时间」有了一个不依赖任何混淆结果的答案：遍历字段，问它的
 * `@SerializedName` 叫什么。这比按类型和顺序猜稳得多 —— 这个实体里光 `long` 就有六个。
 *
 * 注解类型用反射拿（`Class.forName("com.google.gson.annotations.SerializedName")`），
 * 模块自己不引 Gson：宿主里有哪个版本、在哪个 classloader 下，都由目标说了算。
 *
 * ## 哪些字段该动
 *
 * 界面上那句「会员有效期至 …」读的是 **`invalid_time`**（失效时间），不是名字更像的
 * `valid_time` —— 后者是**生效时间**。这一点是从 getter 的字节码反推的，不是猜的：
 * 拿 `invalid_time` 那个 getter 正是渲染函数调用的那一个。
 *
 * 所以 `valid_time` 一个字都不改：它留在 0（等于「1970 年就已生效」），是安全的方向；
 * 往未来写反而可能让某处判成「还没开始」。
 */
internal class VipInfo private constructor(private val fields: Map<String, Field>) {

    /** 有没有拿到该拿的字段。少了到期时间这一项，这个类就没有存在意义。 */
    val usable: Boolean get() = fields.containsKey(INVALID_TIME)

    /**
     * 把一份会员信息就地改成「已开通、到 [expiryMillis] 为止」。
     *
     * 只在原值缺失时才写（到期时间为 0 = 服务端压根没下发），真会员的数据一个字不动 ——
     * 付费用户的到期时间是真的，被模块改成 2099 年只会掩盖真实状态。
     *
     * 改的是**目标自己那个对象**，所以两件事同时成立：界面读到的是新值，而
     * 「会员类型」那套原生计算（它看的就是 `use_vip`）也会自己算出「已开通」——
     * 即使某条路绕过了模块的 hook，答案依然一致。
     *
     * @return true 表示这次真的改了东西
     */
    fun grant(instance: Any, expiryMillis: Long): Boolean {
        val invalid = fields[INVALID_TIME] ?: return false
        val current = runCatching { invalid.getLong(instance) }.getOrElse { return false }
        if (current != 0L) return false

        runCatching {
            invalid.setLong(instance, expiryMillis)
            // 试用期那条分支读的是另一个字段，两边给同一个值，免得界面在两种状态下
            // 显示出两个不同的日期。
            fields[TRIAL_INVALID_TIME]?.takeIf { it.getLong(instance) == 0L }
                ?.setLong(instance, expiryMillis)
            fields[IS_VIP]?.setBoolean(instance, true)
            fields[USE_VIP]?.setBoolean(instance, true)
        }.onFailure { return false }
        return true
    }

    companion object {

        private const val GSON_SERIALIZED_NAME = "com.google.gson.annotations.SerializedName"

        /** 失效时间，毫秒。界面上「会员有效期至 …」显示的就是它。 */
        const val INVALID_TIME = "invalid_time"

        /** 试用期失效时间，毫秒。目标在试用分支里改读这一个。 */
        const val TRIAL_INVALID_TIME = "trial_period_invalid_time"

        const val IS_VIP = "is_vip"
        const val USE_VIP = "use_vip"

        private val WANTED = setOf(INVALID_TIME, TRIAL_INVALID_TIME, IS_VIP, USE_VIP)

        /**
         * 建立「协议字段名 -> 字段」的对照表。失败返回 null，调用方跳过这一项功能。
         */
        fun of(scope: HookScope, clazz: Class<*>): VipInfo? {
            @Suppress("UNCHECKED_CAST")
            val annotation = runCatching {
                Class.forName(GSON_SERIALIZED_NAME, false, clazz.classLoader) as Class<out Annotation>
            }.getOrElse {
                scope.log.w("目标里没有 Gson 的 @SerializedName，改不了会员有效期")
                return null
            }
            val valueOf = runCatching { annotation.getDeclaredMethod("value") }.getOrNull() ?: return null

            val found = HashMap<String, Field>(WANTED.size)
            clazz.declaredFields.forEach { field ->
                val name = runCatching {
                    field.getAnnotation(annotation)?.let { valueOf.invoke(it) as? String }
                }.getOrNull() ?: return@forEach
                if (name in WANTED) found[name] = field.apply { isAccessible = true }
            }

            val info = VipInfo(found)
            if (!info.usable) {
                scope.log.w("会员信息实体 ${clazz.name} 里找不到 $INVALID_TIME 字段，改不了有效期")
                return null
            }
            scope.log.d("会员信息字段已对齐：${found.keys.joinToString()}")
            return info
        }

        /**
         * 到期时间的绝对毫秒值。
         *
         * [years] <= 0 表示「永久」，落到 [FOREVER_YEAR] 年的最后一秒 —— 目标那句文案是
         * 「会员有效期至 {日期}」，它没有「永久」这种写法，给一个足够远的日期是这套界面
         * 里唯一能表达永久的方式。
         *
         * 用 [Calendar] 而不是写死一个时间戳：格式化走的是**设备本地时区**，写死的 UTC
         * 瞬间在不同时区会渲染成不同的日子（甚至跨年）。按本地时区构造就不会。
         */
        fun expiryMillis(years: Int): Long = Calendar.getInstance().apply {
            if (years <= 0) {
                set(FOREVER_YEAR, Calendar.DECEMBER, 31, 23, 59, 59)
            } else {
                add(Calendar.YEAR, years)
            }
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        /**
         * 「永久」渲染成哪一年。
         *
         * 2099 而不是更大的数：这个值最终进的是 `java.util.Date`，年份太大时某些机型的
         * 日期格式化会给出很奇怪的结果，而 2099 在任何人的使用周期里都等价于永久。
         */
        const val FOREVER_YEAR = 2099
    }
}
