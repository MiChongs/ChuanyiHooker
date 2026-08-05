package com.chuanyi.hooker.core

/**
 * 一个可以由用户填具体值的设置项。
 *
 * [HookFeature] 只能表达「开 / 关」，但有些功能真正要问的是「开到多少」——
 * 条数上限、有效期、字数上限都是这一类。把它们塞进功能开关的做法（每档一个开关）
 * 会让功能列表迅速失控，所以取值单独成一层。
 *
 * 取值存在与开关同一份远端配置里（[SettingsKeys.value]），hook 侧用
 * [HookScope.int] / [HookScope.string] 读，模块界面写。
 *
 * [featureId] 把取值挂到某个功能上：那个开关关掉时，这条设置在界面上一起变灰 ——
 * 一个不生效的数字摆在那里只会让人以为它还管用。
 *
 * 三种形态对应三种提问方式，界面按形态选控件：
 *
 * | 形态 | 什么时候用 | 界面上是什么 |
 * |---|---|---|
 * | [Choice] | 常用值就那么几个（有效期、灵敏度） | 下拉选择，可带「自定义」 |
 * | [Number] | 连续量，需要来回试（条数） | 滑块，点住标题可精确输入 |
 * | [Text] | 自由文本 | 弹窗里的输入框 |
 * | [AppList] | 一组应用 | 可搜索的应用列表，勾选 |
 * | [KeyMap] | 按键 -> 输出什么 | 一张能点的键盘 |
 */
sealed interface HookOption {

    /** 设置键，与 [HookFeature.id] 同一命名空间但互不冲突。永不改名。 */
    val key: String

    val title: String

    /** 一句说明。取值本身会显示在行尾，这里只讲它的含义。 */
    val summary: String

    /** 归属的功能；该功能关掉时这条设置跟着禁用。null = 始终可用。 */
    val featureId: String?

    /** 改完要重启目标应用才生效。 */
    val requiresRestart: Boolean

    /**
     * 一个整数，带上下界。界面上是滑块 —— 需要来回比较的量（多少条合适）用它，
     * 拖动时能立刻看到数值变化，比反复开关输入框快得多。
     */
    class Number(
        override val key: String,
        override val title: String,
        override val summary: String = "",
        override val featureId: String? = null,
        override val requiresRestart: Boolean = true,
        val default: Int,
        val min: Int,
        val max: Int,
        /** 滑块的步进。0 = 连续。挡位太密的话滑块拖不准。 */
        val step: Int = 0,
        val unit: String = "",
        /** 把取值渲染成人话，例如 90 -> "1 小时 30 分钟"。默认是「数字 + 单位」。 */
        val render: (Int) -> String = { "$it$unit" },
    ) : HookOption {
        fun coerce(value: Int): Int = value.coerceIn(min, max)

        /** 滑块的档数。miuix 的 `steps` 数的是**中间**的点，所以要减一。 */
        val sliderSteps: Int
            get() = if (step <= 0) 0 else ((max - min) / step - 1).coerceAtLeast(0)
    }

    /**
     * 几个预置档位里选一个。
     *
     * 有效期这种跨度大（一小时到永久）又只有几个常用值的量，滑块反而难用 ——
     * 拖到「7 天」要在 0 到 5256000 分钟之间找一个精确位置。列出来点一下就完了。
     *
     * [custom] 非空时列表末尾多一项「自定义…」，选中后弹出数字输入，
     * 于是既有快捷档位又不失去任意值的能力。
     */
    class Choice(
        override val key: String,
        override val title: String,
        override val summary: String = "",
        override val featureId: String? = null,
        override val requiresRestart: Boolean = true,
        val default: Int,
        val entries: List<Entry>,
        val custom: Number? = null,
    ) : HookOption {

        class Entry(val label: String, val value: Int)

        /** 当前值落在第几档；不在任何档上就是「自定义」。 */
        fun indexOf(value: Int): Int = entries.indexOfFirst { it.value == value }

        fun labelOf(value: Int): String =
            entries.firstOrNull { it.value == value }?.label
                ?: custom?.render?.invoke(value)
                ?: value.toString()
    }

    /**
     * 一组应用包名。
     *
     * 存的还是一行文本（逗号分隔），但**不该拿这行去问用户**：包名既记不住又容易
     * 打错 —— `com.termux` 和 `com.termux.window` 只差一截，错一个字符就是静默失效。
     * 界面上给的是本机装了哪些应用，勾选即可。
     *
     * 手填的能力保留着：目标还没装、或者要写 `com.termux*` 这样的前缀规则时用得上，
     * 所以解析出来的条目**不保证是已安装的包**，使用方要自己处理匹配。
     *
     * [suggested] 是这个功能最可能要选的那几个，界面上单独置顶一段 —— 否则用户得在
     * 两三百个应用里自己找。
     * [emptyMeansAll] 说明空集合怎么解释：有的功能空 = 全部生效，有的空 = 谁都不生效。
     */
    class AppList(
        override val key: String,
        override val title: String,
        override val summary: String = "",
        override val featureId: String? = null,
        override val requiresRestart: Boolean = true,
        val default: String = "",
        val suggested: List<String> = emptyList(),
        val emptyMeansAll: Boolean = false,
    ) : HookOption {

        /** 分隔符宽松些，手填时不必较真。去重但保序。 */
        fun parse(raw: String): List<String> = raw
            .split(',', '，', ';', '；', '\n', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        fun format(packages: Collection<String>): String = packages.distinct().joinToString(", ")

        /** 行尾显示什么。铺开所有包名会把行撑爆，多于一个就只报数量。 */
        fun describe(raw: String): String {
            val picked = parse(raw)
            return when {
                picked.isEmpty() -> if (emptyMeansAll) "全部应用" else "未选择"
                picked.size == 1 -> picked.first()
                else -> "${picked.size} 项"
            }
        }

        /** 末尾 `*` 是前缀规则，不是某个具体的包。 */
        fun isPattern(entry: String): Boolean = entry.endsWith('*')
    }

    /** 一段文本。空串表示「没设置」，由 [render] 决定怎么显示。 */
    class Text(
        override val key: String,
        override val title: String,
        override val summary: String = "",
        override val featureId: String? = null,
        override val requiresRestart: Boolean = true,
        val default: String = "",
        val hint: String = "",
        val singleLine: Boolean = true,
        val render: (String) -> String = { it.ifBlank { "未设置" } },
    ) : HookOption

    /**
     * 一张「按键 -> 输出什么」的表。
     *
     * 存的还是一行文本（`q=1, w=2`），但**问法完全不同**：让用户在一个输入框里
     * 手写二十几个键的映射，既要记格式又难改其中一个 —— 该键在哪、现在是什么，
     * 全得自己在字符串里找。所以界面上给的是一张键盘：每个键上写着它现在滑出什么，
     * 点哪个改哪个。
     *
     * [rows] 是键盘长什么样，由使用方给 —— 不同目标的布局不一样，框架不该假设。
     * [presets] 是一键铺满的方案（整行数字、整行符号、清空）。
     */
    class KeyMap(
        override val key: String,
        override val title: String,
        override val summary: String = "",
        override val featureId: String? = null,
        override val requiresRestart: Boolean = true,
        val default: String = "",
        /** 键盘每一行的键，例如 `listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")`。 */
        val rows: List<String> = DEFAULT_ROWS,
        val presets: List<Preset> = emptyList(),
    ) : HookOption {

        class Preset(val label: String, val value: String)

        /** `q=1, w=2` -> `{q: 1, w: 2}`。分隔符宽松些，用户手写时不必较真。 */
        fun parse(raw: String): Map<String, String> = raw
            .split(',', '，', ';', '；', '\n', ' ')
            .mapNotNull { entry ->
                val at = entry.indexOfFirst { it == '=' || it == ':' || it == '：' }
                if (at <= 0 || at == entry.lastIndex) return@mapNotNull null
                val k = entry.substring(0, at).trim().lowercase()
                val v = entry.substring(at + 1).trim()
                if (k.isEmpty() || v.isEmpty()) null else k to v
            }
            .toMap()

        /** 按键盘顺序写回去，这样存下来的串和界面上看到的顺序一致。 */
        fun format(map: Map<String, String>): String {
            val ordered = rows.flatMap { row -> row.map { it.toString() } }
            val known = ordered.mapNotNull { k -> map[k]?.let { "$k=$it" } }
            val extra = map.keys.filterNot { it in ordered }.map { "$it=${map[it]}" }
            return (known + extra).joinToString(", ")
        }

        companion object {
            val DEFAULT_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        }
    }
}
