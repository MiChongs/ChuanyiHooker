package com.chuanyi.hooker.core

/**
 * 一套调好的配置，一下套用。
 *
 * 一个 hooker 有五个开关加六个取值时，「我该怎么配」本身就成了负担 —— 用户想要的
 * 是「剪贴板别再一小时就没了」，不是逐项理解每个数字的含义。预设把常见诉求直接
 * 表达成一个选项。
 *
 * 套用是**全量覆盖**：[features] 和 [options] 里没提到的项也会回到 hooker 声明的
 * 默认值。半套用（只改提到的项）会让「换一个预设」的结果取决于之前是什么状态，
 * 用户没法预期。
 *
 * [options] 的值只能是 `Int` 或 `String`，与 [HookOption] 的两种存储对应。
 */
class HookPreset(
    /** 稳定标识，用来记住"当前用的是哪一套"。 */
    val id: String,
    val title: String,
    val summary: String = "",
    /** 功能 id -> 开关。 */
    val features: Map<String, Boolean> = emptyMap(),
    /** 设置 key -> 取值（Int 或 String）。 */
    val options: Map<String, Any> = emptyMap(),
) {
    override fun toString(): String = "HookPreset($id)"
}
