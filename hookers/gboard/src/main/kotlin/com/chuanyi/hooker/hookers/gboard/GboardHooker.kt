package com.chuanyi.hooker.hookers.gboard

import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookFeature
import com.chuanyi.hooker.core.HookOption
import com.chuanyi.hooker.core.HookPreset
import com.chuanyi.hooker.core.HookScope
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gboard（`com.google.android.inputmethod.latin`）—— 解开剪贴板的三处硬限制，
 * 再给按键补上上下滑。
 *
 * 剪贴板那三处的位置差别很大，所以做法也不同：
 *
 * | 限制 | 原版 | 它在哪 | 怎么改 |
 * |---|---|---|---|
 * | 最近保留几条 | 5 | 装填列表的 Callable 里，内联常量 | 整个替换那段装填逻辑 |
 * | 有效期 | 1 小时 | 同上（查询的时间下限）+ 过期清理 | 同上，清理也一并接管 |
 * | 单条字数 | 20000 | Phenotype flag | 直接覆盖 flag 取值 |
 *
 * 前两条落在同一段代码里，所以共用一次接管 —— 只开其中一项时，另一项按原版默认值
 * 传进去。
 *
 * 按键滑动走的是另一条路：不碰手势派发，只给按键定义补一条动作记录，
 * 剩下的完全是原版流程。细节见 [GboardSlide]。
 */
class GboardHooker : AppHooker {

    override val id = "gboard"
    override val displayName = "Gboard"
    override val description = "解开剪贴板的条数、有效期、字数限制，按键支持上下滑"
    override val targetPackages = setOf("com.google.android.inputmethod.latin")

    /** 装填逻辑只有一处，两个功能都要它 —— 谁先跑谁装。 */
    private val listInstalled = AtomicBoolean(false)

    /** 上滑下滑同理，共用一次按键定义的接管。 */
    private val slideInstalled = AtomicBoolean(false)

    override val features: List<HookFeature> = listOf(
        HookFeature(
            id = FEATURE_COUNT,
            title = "自定义剪贴板条数",
            summary = "原版「最近」一栏只留 5 条，超出的直接看不到。开启后按下面设的条数保留",
            install = { installList() },
        ),
        HookFeature(
            id = FEATURE_TTL,
            title = "自定义剪贴板有效期",
            summary = "原版复制满 1 小时就从面板消失，攒够 120 条时还会真删掉。" +
                "开启后按下面设的时长算，固定的项不受影响",
            install = { installTtl() },
        ),
        HookFeature(
            id = FEATURE_CHARS,
            title = "自定义剪贴板字数上限",
            summary = "原版单条超过 20000 字会被截断后再存。开启后按下面设的上限",
            install = { installCharLimit() },
        ),
        HookFeature(
            id = FEATURE_SLIDE_UP,
            title = "自定义按键上滑",
            summary = "原版字母键上滑出的是长按的第一个候选（z 上滑出 ź）。" +
                "开启后可以在下面自己指定哪个键上滑输出什么，没指定的键保持原样；" +
                "原本没有上滑的键补上长按候选",
            install = { installSlide() },
        ),
        HookFeature(
            id = FEATURE_SLIDE_DOWN,
            title = "自定义按键下滑",
            summary = "原版没有下滑。开启后下滑输出长按的第二个候选，也可以在下面自己指定",
            defaultEnabled = false,
            install = { installSlide() },
        ),
    )

    override val options: List<HookOption> = listOf(
        HookOption.Number(
            key = KEY_MAX_ITEMS,
            title = "最近保留条数",
            summary = "数的是复制次数：一次复制里被识别出的电话、网址算同一条",
            featureId = FEATURE_COUNT,
            default = DEFAULT_MAX_ITEMS,
            min = 1,
            max = 200,
            unit = " 条",
        ),
        HookOption.Choice(
            key = KEY_TTL_MINUTES,
            title = "内容有效期",
            summary = "超过这个时长的普通剪贴项不再显示，并会被清理。固定的项不受影响",
            featureId = FEATURE_TTL,
            default = DEFAULT_TTL_MINUTES,
            entries = listOf(
                HookOption.Choice.Entry("1 小时（原版）", 60),
                HookOption.Choice.Entry("6 小时", 360),
                HookOption.Choice.Entry("1 天", 1_440),
                HookOption.Choice.Entry("3 天", 4_320),
                HookOption.Choice.Entry("7 天", 10_080),
                HookOption.Choice.Entry("30 天", 43_200),
                HookOption.Choice.Entry("永久保留", 0),
            ),
            custom = HookOption.Number(
                key = KEY_TTL_MINUTES,
                title = "自定义有效期",
                default = DEFAULT_TTL_MINUTES,
                min = 0,
                max = MAX_TTL_MINUTES,
                unit = " 分钟",
                render = ::renderDuration,
            ),
        ),
        HookOption.Choice(
            key = KEY_CHAR_LIMIT,
            title = "单条字数上限",
            summary = "超过这个长度的文本存进剪贴板时会被截断",
            featureId = FEATURE_CHARS,
            default = DEFAULT_CHAR_LIMIT,
            entries = listOf(
                HookOption.Choice.Entry("1 万字", 10_000),
                HookOption.Choice.Entry("2 万字（原版）", 20_000),
                HookOption.Choice.Entry("5 万字", 50_000),
                HookOption.Choice.Entry("20 万字", 200_000),
                HookOption.Choice.Entry("200 万字", 2_000_000),
            ),
            custom = HookOption.Number(
                key = KEY_CHAR_LIMIT,
                title = "自定义字数上限",
                default = DEFAULT_CHAR_LIMIT,
                min = 100,
                max = 2_000_000,
                unit = " 字",
            ),
        ),
        HookOption.KeyMap(
            key = KEY_UP_MAP,
            title = "上滑输出",
            summary = "设过的键以这里为准；没设的保持原样（原版是长按的第一个候选）",
            featureId = FEATURE_SLIDE_UP,
            presets = listOf(
                HookOption.KeyMap.Preset("数字行", DIGIT_ROW_MAP),
                HookOption.KeyMap.Preset("符号行", SYMBOL_ROW_MAP),
                HookOption.KeyMap.Preset("大写字母", UPPERCASE_MAP),
            ),
        ),
        HookOption.KeyMap(
            key = KEY_DOWN_MAP,
            title = "下滑输出",
            summary = "设过的键以这里为准；没设的用长按的第二个候选",
            featureId = FEATURE_SLIDE_DOWN,
            presets = listOf(
                HookOption.KeyMap.Preset("符号行", SYMBOL_ROW_MAP),
                HookOption.KeyMap.Preset("数字行", DIGIT_ROW_MAP),
                HookOption.KeyMap.Preset("大写字母", UPPERCASE_MAP),
            ),
        ),
        HookOption.Choice(
            key = KEY_SLIDE_LEVEL,
            title = "滑动灵敏度",
            summary = "只影响原本就允许滑动的键；空格、回车这类明确禁用滑动的不动",
            featureId = FEATURE_SLIDE_UP,
            default = SLIDE_LEVEL_DEFAULT,
            entries = listOf(
                HookOption.Choice.Entry("灵敏（滑一点就触发）", 1),
                HookOption.Choice.Entry("默认（原版）", 2),
                HookOption.Choice.Entry("迟钝（要滑更远）", 3),
            ),
        ),
    )

    /**
     * 四套方案，覆盖从「一点都别改」到「什么都别丢」。
     *
     * 数字行上滑打数字是最常被问起的诉求，所以除了「保持原版」以外都带上了上滑。
     * 下滑只在最激进那一套里开：它取的是长按的第二个候选，不是每个键都有，
     * 默认打开会让一部分键的下滑毫无反应，看起来像坏了。
     */
    override val presets: List<HookPreset> = listOf(
        HookPreset(
            id = "stock",
            title = "保持原版",
            summary = "全部关掉，Gboard 是什么样就什么样",
            features = mapOf(
                FEATURE_COUNT to false,
                FEATURE_TTL to false,
                FEATURE_CHARS to false,
                FEATURE_SLIDE_UP to false,
                FEATURE_SLIDE_DOWN to false,
            ),
        ),
        HookPreset(
            id = "light",
            title = "够用就好",
            summary = "剪贴板留 20 条、存 1 天，字母键上滑打数字",
            features = mapOf(
                FEATURE_COUNT to true,
                FEATURE_TTL to true,
                FEATURE_CHARS to false,
                FEATURE_SLIDE_UP to true,
                FEATURE_SLIDE_DOWN to false,
            ),
            options = mapOf(
                KEY_MAX_ITEMS to 20,
                KEY_TTL_MINUTES to 1_440,
                KEY_UP_MAP to DIGIT_ROW_MAP,
            ),
        ),
        HookPreset(
            id = "recommended",
            title = "推荐",
            summary = "留 50 条、存 7 天、单条 20 万字，字母键上滑打数字",
            features = mapOf(
                FEATURE_COUNT to true,
                FEATURE_TTL to true,
                FEATURE_CHARS to true,
                FEATURE_SLIDE_UP to true,
                FEATURE_SLIDE_DOWN to false,
            ),
            options = mapOf(
                KEY_MAX_ITEMS to 50,
                KEY_TTL_MINUTES to 10_080,
                KEY_CHAR_LIMIT to 200_000,
                KEY_UP_MAP to DIGIT_ROW_MAP,
            ),
        ),
        HookPreset(
            id = "hoard",
            title = "绝不丢失",
            summary = "留 200 条、永久保留、单条 200 万字，上滑数字 + 下滑符号",
            features = mapOf(
                FEATURE_COUNT to true,
                FEATURE_TTL to true,
                FEATURE_CHARS to true,
                FEATURE_SLIDE_UP to true,
                FEATURE_SLIDE_DOWN to true,
            ),
            options = mapOf(
                KEY_MAX_ITEMS to 200,
                KEY_TTL_MINUTES to 0,
                KEY_CHAR_LIMIT to 2_000_000,
                KEY_UP_MAP to DIGIT_ROW_MAP,
                KEY_DOWN_MAP to SYMBOL_ROW_MAP,
                KEY_SLIDE_LEVEL to 1,
            ),
        ),
    )

    /**
     * 只在主进程动手。
     *
     * Gboard 还会拉起若干服务进程（实验框架、训练任务），它们既不画键盘也不碰
     * 剪贴板面板，在那里跑一次 dex 扫描纯属浪费。
     */
    override fun isCompatible(scope: HookScope): Boolean {
        if (!scope.isMainProcess) return false
        if (scope.classOrNull(PROBE_CLASS) == null) {
            scope.log.w("找不到 $PROBE_CLASS，Gboard 可能换了实现")
            return false
        }
        return true
    }

    override fun onHook(scope: HookScope) {
        scope.log.i("Gboard ${scope.versionCode} in ${scope.processName}")
    }

    // -----------------------------------------------------------------------

    private fun HookScope.installList() {
        if (!listInstalled.compareAndSet(false, true)) return
        // 没开的那一项按原版默认值传进去 —— 接管是整段的，不能只替换其中一半。
        val maxItems = if (isEnabled(FEATURE_COUNT, true)) {
            int(KEY_MAX_ITEMS, DEFAULT_MAX_ITEMS).coerceIn(1, 200)
        } else {
            ORIGINAL_MAX_ITEMS
        }
        val ttl = if (isEnabled(FEATURE_TTL, true)) ttlMillis() else ORIGINAL_TTL_MILLIS
        GboardClipboard.installList(this, maxItems, ttl)
    }

    /** 有效期还多一步：过期清理那一侧也得换成同一把尺子。 */
    private fun HookScope.installTtl() {
        installList()
        GboardClipboard.installCleaner(this, ttlMillis())
    }

    private fun HookScope.ttlMillis(): Long {
        val minutes = int(KEY_TTL_MINUTES, DEFAULT_TTL_MINUTES).coerceIn(0, MAX_TTL_MINUTES)
        return minutes * 60_000L
    }

    private fun HookScope.installCharLimit() {
        val limit = int(KEY_CHAR_LIMIT, DEFAULT_CHAR_LIMIT).coerceIn(100, 2_000_000)
        GboardClipboard.installCharLimit(this, limit)
    }

    private fun HookScope.installSlide() {
        if (!slideInstalled.compareAndSet(false, true)) return
        val up = isEnabled(FEATURE_SLIDE_UP, true)
        val down = isEnabled(FEATURE_SLIDE_DOWN, false)
        GboardSlide.install(
            scope = this,
            up = up,
            down = down,
            upMap = string(KEY_UP_MAP).orEmpty(),
            downMap = string(KEY_DOWN_MAP).orEmpty(),
        )
        if (up) {
            val level = int(KEY_SLIDE_LEVEL, SLIDE_LEVEL_DEFAULT).coerceIn(1, 3)
            GboardSlide.installSensitivity(this, level)
        }
    }

    private companion object {
        const val FEATURE_COUNT = "clip_count"
        const val FEATURE_TTL = "clip_ttl"
        const val FEATURE_CHARS = "clip_chars"
        const val FEATURE_SLIDE_UP = "slide_up"
        const val FEATURE_SLIDE_DOWN = "slide_down"

        const val KEY_MAX_ITEMS = "clip_max_items"
        const val KEY_TTL_MINUTES = "clip_ttl_minutes"
        const val KEY_CHAR_LIMIT = "clip_char_limit"
        const val KEY_UP_MAP = "slide_up_map"
        const val KEY_DOWN_MAP = "slide_down_map"
        const val KEY_SLIDE_LEVEL = "slide_level"

        /** 原版行为，功能没开时按它传。 */
        const val ORIGINAL_MAX_ITEMS = 5
        const val ORIGINAL_TTL_MILLIS = 3_600_000L

        const val DEFAULT_MAX_ITEMS = 30
        const val DEFAULT_TTL_MINUTES = 7 * 24 * 60
        const val DEFAULT_CHAR_LIMIT = 20_000

        /** 约 10 年，够表达「基本等于永久又不至于溢出」。填 0 才是真的永久。 */
        const val MAX_TTL_MINUTES = 5_256_000

        const val SLIDE_LEVEL_DEFAULT = 2

        /** 字母行上滑打数字 —— 这是这个功能最常被要的用法，预设里直接给全。 */
        const val DIGIT_ROW_MAP = "q=1, w=2, e=3, r=4, t=5, y=6, u=7, i=8, o=9, p=0"

        /** 下滑打常用符号，位置对着上面那排数字的 shift 位。 */
        const val SYMBOL_ROW_MAP = "q=!, w=@, e=#, r=$, t=%, y=^, u=&, i=*, o=(, p=)"

        /** 滑一下出大写，比按 shift 再按字母少一步。 */
        val UPPERCASE_MAP = "qwertyuiopasdfghjklzxcvbnm"
            .map { "$it=${it.uppercase()}" }
            .joinToString(", ")

        /** 键盘元数据那一层没被混淆，拿它当兼容性探针。 */
        const val PROBE_CLASS = "com.google.android.libraries.inputmethod.metadata.SoftKeyDef"

        fun renderDuration(minutes: Int): String = when {
            minutes <= 0 -> "永久"
            minutes < 60 -> "$minutes 分钟"
            minutes % (24 * 60) == 0 -> "${minutes / (24 * 60)} 天"
            minutes % 60 == 0 -> "${minutes / 60} 小时"
            else -> "${minutes / 60} 小时 ${minutes % 60} 分"
        }

    }
}
