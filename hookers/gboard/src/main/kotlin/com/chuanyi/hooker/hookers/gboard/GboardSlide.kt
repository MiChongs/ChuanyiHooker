package com.chuanyi.hooker.hookers.gboard

import com.chuanyi.hooker.core.HookScope
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.lang.reflect.Array as JArray

/**
 * 按键的上滑 / 下滑动作。
 *
 * ## 为什么改的是按键定义，而不是手势派发
 *
 * Gboard 的触摸状态机判定滑动方向时**只看几何**：位移超过阈值就返回 `SLIDE_UP`，
 * 不检查这个键有没有定义上滑。真正决定"上滑发生了什么"的是下一步 ——
 *
 * ```java
 * ActionDef j(piz action) { return softKeyView.g(action); }   // -> SoftKeyDef.i(action)
 * // SoftKeyDef.i(): 找不到该动作时回退到 PRESS
 * ```
 *
 * 所以上滑字母键现在等于再打一遍那个字母。要改的是这一步的输入 ——
 * 给 `SoftKeyDef.actionDefs` 补一条 `SLIDE_UP` 的记录，剩下的完全走原版路径：
 * 派发、上屏、音效、无障碍播报都不用碰。这和 Gboard 自己在布局 XML 里给某些键
 * 定义上滑是同一件事，只是发生在运行时。
 *
 * 另一个好处是**滑行输入不会被误伤**。手势输入的判定是
 * `if (|dy| >= |dx| && softKeyDef.h(上/下) != null) 不启动手势` —— 补了上滑之后，
 * 纵向为主的滑动确实不再触发滑行输入（这正是想要的：上滑是要打数字），而横向滑行
 * 完全不受影响。
 *
 * ## 输出什么
 *
 * Gboard 自己就给字母键定义了上滑 —— 指向长按的第一个候选，`z` 上滑出 `ź`。所以
 * 这里不是"从无到有"，而是三条规矩：
 *
 * 1. 用户在映射表里写了这个键 → **覆盖**原版；
 * 2. 没写、但这个键本来就有这个方向 → 原样不动，那是布局作者的安排；
 * 3. 没写也没有 → 拿长按候选补上（上滑取第一个，下滑取第二个）。
 *
 * 长按候选里第一条常常是「打开候选弹窗」这样的控制码（`data` 为 null），取候选时
 * 要先把它们滤掉，否则补上去的是一个什么都不输出的动作。
 */
internal object GboardSlide {

    private const val ACTION_PRESS = "PRESS"
    private const val ACTION_LONG_PRESS = "LONG_PRESS"
    private const val ACTION_SLIDE_UP = "SLIDE_UP"
    private const val ACTION_SLIDE_DOWN = "SLIDE_DOWN"

    /** 灵敏度枚举里最好认的那个常量 —— 只有它能把这个枚举和别的区分开。 */
    private const val SENSITIVITY_MARK = "NO_SLIDE"

    /** 灵敏度档位，与目标枚举的序号对齐。 */
    private const val SENS_NORMAL = 2

    /** 头几次滑动查询打详细日志，用来判断「装上了却没生效」卡在哪一步。 */
    private const val PROBE_CALLS = 8



    /**
     * 装上滑 / 下滑。
     *
     * [upMap] / [downMap] 是 `q=1, w=2` 这样的映射表，键按该键的按下内容匹配；
     * 留空则用长按候选。两个方向至少要开一个，否则不用调这里。
     */
    fun install(
        scope: HookScope,
        up: Boolean,
        down: Boolean,
        upMap: String,
        downMap: String,
    ) {
        val refs = GboardRefs.of(scope)
        val press = refs.action(ACTION_PRESS) ?: error("认不出按下动作")
        val longPress = refs.action(ACTION_LONG_PRESS) ?: error("认不出长按动作")
        val slideUp = refs.action(ACTION_SLIDE_UP) ?: error("认不出上滑动作")
        val slideDown = refs.action(ACTION_SLIDE_DOWN) ?: error("认不出下滑动作")

        val upRules = parseMap(upMap)
        val downRules = parseMap(downMap)

        // 「这个键的这个方向该给什么」的结果按 键+方向 缓存 —— 查询发生在触摸事件
        // 路径上，每次都合成一个 ActionDef 会在滑动过程中持续分配。
        val cache = ConcurrentHashMap<String, Any>()
        val hits = AtomicInteger()

        // 拦的是**查询**，不是数据。
        //
        // 先前的做法是在 SoftKeyDef 构造后往它的动作表里补一条，实测装得上、也确实
        // 拦到了构造，但滑动依旧走滑行输入 —— 被查询的并不是我们改过的那个实例
        // （布局那一层会复用、重建 SoftKeyDef，改动没跟过去）。改成拦「按动作取
        // ActionDef」这一步，无论实例是哪个都绕不开。
        //
        // 这个类上收一个动作枚举、返回 ActionDef 的方法有两个：一个严格查找、一个
        // 查不到时回退到按下。**两个都挂同样的逻辑**，不必区分：
        //
        //  * 回退那个是实际派发用的，不挂就不会输出我们指定的内容；
        //  * 严格那个是滑行输入的判据 —— 它返回 null 时 Gboard 认为「这个键没有纵向
        //    滑动」，于是把纵向滑动当成滑行输入的起手。这正是之前上滑打不出字、
        //    反而出现联想词的原因，所以它也必须跟着返回非 null。
        val lookups = refs.softKeyDef.declaredMethods.filter {
            it.parameterCount == 1 &&
                it.parameterTypes[0] == refs.actionEnum &&
                it.returnType == refs.actionDef
        }
        if (lookups.isEmpty()) error("找不到按动作取 ActionDef 的方法")

        lookups.forEach { method ->
            method.createAfterHook("gboard.slide.${method.name}") { param ->
                val wanted = param.args.getOrNull(0) ?: return@createAfterHook
                if (wanted != slideUp && wanted != slideDown) return@createAfterHook
                val self = param.thisObjectOrNull ?: return@createAfterHook
                val rules = if (wanted == slideUp) upRules else downRules
                val enabled = if (wanted == slideUp) up else down
                if (!enabled) return@createAfterHook

                runCatching {
                    val label = labelOf(refs, self, press)
                    val original = param.result
                    val replacement = resolveAction(
                        refs = refs,
                        cache = cache,
                        key = self,
                        label = label,
                        wanted = wanted,
                        press = press,
                        longPress = longPress,
                        rules = rules,
                        fallbackIndex = if (wanted == slideUp) 0 else 1,
                    )
                    if (replacement != null) param.result = replacement

                    // 排查用，且**只盯跟这次配置有关的键**：键盘初始化时会把每个键都
                    // 查一遍，不加这道筛选的话日志全被没配过的键占满，真正想看的那
                    // 一次反而挤不进来 —— 「装上了却没生效」正是卡在这儿看不清。
                    // 滑动本身是高频事件，所以还要限次数。
                    if ((rules.containsKey(label) || replacement != null) &&
                        hits.incrementAndGet() <= PROBE_CALLS
                    ) {
                        scope.log.d(
                            "滑动查询 ${method.name}(${(wanted as? Enum<*>)?.name}) 键=$label " +
                                "规则=${rules[label]} 原=${describeResult(refs, original)} " +
                                "改写=${replacement != null}",
                        )
                    }
                }.onFailure { scope.log.w("取滑动动作失败：${it.message}") }
            }
        }

        val what = buildList {
            if (up) add("上滑" + describe(upRules))
            if (down) add("下滑" + describe(downRules))
        }.joinToString("、")
        scope.log.i("按键滑动已装上（接管 ${lookups.size} 个查询入口）：$what")
    }

    /**
     * 这个键的这个方向最终给什么，null = 保持目标原来的答案。
     *
     * 三条规矩，和类文档一致：用户设过就覆盖；没设过但目标本来就有就不动；
     * 两者都没有才拿长按候选补。
     *
     * 「目标本来就有」的判据不是 `current != null` —— 回退版的查询在查不到时会把
     * **按下**那条还回来，看着非 null，其实这个方向根本没有定义。所以要比对返回的
     * 那条动作自己的类型。
     */
    private fun resolveAction(
        refs: GboardRefs,
        cache: ConcurrentHashMap<String, Any>,
        key: Any,
        label: String,
        wanted: Any,
        press: Any,
        longPress: Any,
        rules: Map<String, String>,
        fallbackIndex: Int,
    ): Any? {
        val name = (wanted as? Enum<*>)?.name ?: return null
        val mapped = rules[label]
        cache[label + ' ' + name]?.let { return it }

        val actions = refs.actionsField.get(key) as? Array<*> ?: return null
        if (actions.isEmpty()) return null
        val present = actions.filterNotNull()
        // 目标自己就定义了这个方向：用户没指定的话不动它，那是布局作者的安排。
        if (mapped == null && present.any { refs.actionOf(it) == wanted }) return null

        val pressDef = present.firstOrNull { refs.actionOf(it) == press } ?: return null
        val pressKey = refs.keyDatasOf(pressDef).firstOrNull() ?: return null

        val data = if (mapped != null) {
            refs.keyDataCtor.newInstance(0, refs.intentOf(pressKey), mapped)
        } else {
            // 长按候选的第一条常是「打开候选弹窗」这类控制码（data 为 null），
            // 真正会上屏的在后面。
            present.firstOrNull { refs.actionOf(it) == longPress }
                ?.let { refs.keyDatasOf(it) }
                ?.filterNotNull()
                ?.filter { refs.dataOf(it) != null }
                ?.getOrNull(fallbackIndex)
                ?: return null
        }

        val built = buildAction(refs, wanted, data)
        cache[label + ' ' + name] = built
        return built
    }

    /**
     * 调整滑动触发阈值。
     *
     * 阈值挂在每个按键的 `Compose`（一组排版与手感参数）上，是个五档枚举。
     * **只改 NORMAL 那一档**：另外几档是布局作者的明确表态 —— `NO_SLIDE` 是"这个键
     * 不许滑"（空格、回车），`ABSOLUTE` 是"按像素算"，把它们一并改掉会改变原本
     * 正常的手势。
     */
    fun installSensitivity(scope: HookScope, level: Int) {
        if (level == SENS_NORMAL) return
        val refs = GboardRefs.of(scope)
        val compose = refs.softKeyDef.declaredClasses.firstOrNull { it.simpleName == "Compose" }
            ?: error("找不到按键的 Compose 参数组")

        // 两个枚举字段（灵敏度、弹窗时机），认带 NO_SLIDE 常量的那个。
        val field = compose.declaredFields.firstOrNull {
            !Modifier.isStatic(it.modifiers) && it.type.isEnum &&
                it.type.enumConstants?.any { c -> (c as Enum<*>).name == SENSITIVITY_MARK } == true
        }?.apply { isAccessible = true } ?: error("找不到滑动灵敏度字段")

        val constants = field.type.enumConstants ?: error("灵敏度枚举取不到常量")
        val normal = constants.getOrNull(SENS_NORMAL) ?: error("灵敏度档位对不上")
        val target = constants.getOrNull(level) ?: error("灵敏度档位 $level 不存在")

        val composeField = refs.softKeyDef.declaredFields.firstOrNull {
            !Modifier.isStatic(it.modifiers) && it.type == compose
        }?.apply { isAccessible = true } ?: error("找不到按键的 Compose 字段")

        refs.softKeyDefCtor.createAfterHook("gboard.slide.sensitivity") { param ->
            val self = param.thisObjectOrNull ?: return@createAfterHook
            runCatching {
                val group = composeField.get(self) ?: return@runCatching
                if (field.get(group) === normal) field.set(group, target)
            }
        }
        scope.log.i("滑动触发阈值改为第 $level 档（${(target as Enum<*>).name}）")
    }

    // -----------------------------------------------------------------------

    /** 排查用：这个键按下会出什么。 */
    private fun labelOf(refs: GboardRefs, key: Any, press: Any): String = runCatching {
        val actions = refs.actionsField.get(key) as? Array<*> ?: return "?"
        val pressDef = actions.filterNotNull().firstOrNull { refs.actionOf(it) == press } ?: return "无按下"
        refs.keyDatasOf(pressDef).firstOrNull()?.let { refs.dataOf(it) }?.toString() ?: "无内容"
    }.getOrDefault("?")

    /** 排查用：查询原本还回来的是哪一条动作。 */
    private fun describeResult(refs: GboardRefs, result: Any?): String = runCatching {
        if (result == null) return "null"
        (refs.actionOf(result) as? Enum<*>)?.name ?: "?"
    }.getOrDefault("?")

    /**
     * 合成一条动作记录。
     *
     * 后面三个数组参数长度必须和 keyDatas 一致，否则目标只会打一条日志然后
     * 带着一个内部不自洽的对象继续跑。弹窗标签留空 = 滑动时不弹候选框，
     * 这正是滑动该有的样子。
     */
    private fun buildAction(refs: GboardRefs, action: Any, keyData: Any): Any {
        val datas = JArray.newInstance(refs.keyData, 1)
        JArray.set(datas, 0, keyData)
        return refs.actionDefCtor.newInstance(
            action,
            datas,
            false, // actionOnDown：抬手才算数
            false, // repeatable
            0, // popupLayoutId
            false, // alwaysShowPopup
            true, // playMediaEffect：和普通按键一样给反馈
            false, // playMediaEffectOnRelease
            0, // iconBackgroundLevel
            0, // mergeInsertionIndex
            null, // contentDescription
            arrayOfNulls<String>(1),
            IntArray(1),
            false, // alwaysDisabledInNavigationMode
        )
    }

    /** `q=1, w=2` / `q:1 w:2` 都认。键统一小写，值原样（大小写可能有意义）。 */
    private fun parseMap(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split(',', '，', ';', '；', '\n', ' ')
            .mapNotNull { entry ->
                val at = entry.indexOfFirst { it == '=' || it == ':' || it == '：' }
                if (at <= 0 || at == entry.lastIndex) return@mapNotNull null
                val key = entry.substring(0, at).trim().lowercase()
                val value = entry.substring(at + 1).trim()
                if (key.isEmpty() || value.isEmpty()) null else key to value
            }
            .toMap()
    }

    private fun describe(rules: Map<String, String>): String =
        if (rules.isEmpty()) "用长按候选" else "自定义 ${rules.size} 个键"
}
