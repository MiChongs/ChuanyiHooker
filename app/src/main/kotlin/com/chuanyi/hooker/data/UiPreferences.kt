package com.chuanyi.hooker.data

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/** 深浅模式。 */
enum class ThemeMode { System, Light, Dark }

/**
 * 配色从哪来。
 *
 *  * [Default]   miuix 内置的那套蓝
 *  * [Wallpaper] 系统取色（Monet）。Android 12 起才有，见 [UiPreferences.canFollowWallpaper]
 *  * [Custom]    自己挑一个主题色当种子，由 materialkolor 生成整套配色
 */
enum class ColorSource { Default, Wallpaper, Custom }

/**
 * 模块自己界面的偏好：主题、毛玻璃、启动页签。
 *
 * ## 为什么不放进 [ModuleSettings]
 *
 * 那份走的是框架的远程存储，被 hook 的**目标进程**能读到 —— 那是给 hook 行为用的
 * （总开关、各功能开关、详细日志）。「这台手机上模块界面长什么样」跟目标进程没有
 * 半点关系，塞进去只会让每个被注入的应用都跟着存一份没人读的键。所以另开一份本地
 * SharedPreferences。
 *
 * ## 属性为什么要走委托
 *
 * SharedPreferences 里的值不是 snapshot state，Compose 读它不会被 invalidate。下面
 * 三个委托把「读一次落盘值」和「写回并触发重组」包在一起：状态在内存里是
 * `mutableStateOf`，赋值时顺手 `apply()` 落盘。于是页面里直接 `ui.themeMode = X`
 * 就够了，既立刻生效也已经存下。
 *
 * 直接存 miuix 的 [ThemePaletteStyle] / [ThemeColorSpec] 而不是再定义一套平行枚举：
 * 这两个值原样喂给 `ThemeController`，中间加一层映射只会多一处要同步的地方。
 */
@Stable
class UiPreferences private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 深浅模式。 */
    var themeMode: ThemeMode by enumPref(KEY_THEME_MODE, ThemeMode.System, ThemeMode.entries)

    /** 深色下把背景压成纯黑（OLED 屏这样才真的不点亮像素）。 */
    var pureBlack: Boolean by boolPref(KEY_PURE_BLACK, false)

    /** 配色来源。 */
    var colorSource: ColorSource by enumPref(KEY_COLOR_SOURCE, ColorSource.Default, ColorSource.entries)

    /** [ColorSource.Custom] 时的种子色（ARGB）。默认取 miuix 浅色主色。 */
    var seedColor: Int by intPref(KEY_SEED_COLOR, DEFAULT_SEED)

    /** 由种子色生成整套配色时的调色板风格。 */
    var paletteStyle: ThemePaletteStyle by enumPref(
        KEY_PALETTE_STYLE,
        ThemePaletteStyle.TonalSpot,
        ThemePaletteStyle.entries,
    )

    /**
     * Material 色彩规范。
     *
     * 2025 只有 TonalSpot / Neutral / Vibrant / Expressive 支持，其余风格 miuix 会在
     * 运行期自动降回 2021 —— 所以这里选了也不会出错，只是不生效。
     */
    var colorSpec: ThemeColorSpec by enumPref(
        KEY_COLOR_SPEC,
        ThemeColorSpec.Spec2021,
        ThemeColorSpec.entries,
    )

    /** 顶栏/底栏的渐进式毛玻璃。硬件不支持时这个值没有意义，见 `BlurScaffold`。 */
    var blurEnabled: Boolean by boolPref(KEY_BLUR, true)

    /** 打开应用先落在哪个页签（[com.chuanyi.hooker.ui.screen.HomeTab] 的序号）。 */
    var startTab: Int by intPref(KEY_START_TAB, 0)

    /** 全部恢复默认。逐个赋值而不是 `prefs.clear()`：那样内存里的状态不会跟着回退。 */
    fun resetToDefaults() {
        themeMode = ThemeMode.System
        pureBlack = false
        colorSource = ColorSource.Default
        seedColor = DEFAULT_SEED
        paletteStyle = ThemePaletteStyle.TonalSpot
        colorSpec = ThemeColorSpec.Spec2021
        blurEnabled = true
        startTab = 0
    }

    // --- 委托 ---------------------------------------------------------------

    private fun boolPref(key: String, default: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        private var state by mutableStateOf(prefs.getBoolean(key, default))
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            state = value
            prefs.edit().putBoolean(key, value).apply()
        }
    }

    private fun intPref(key: String, default: Int) = object : ReadWriteProperty<Any?, Int> {
        private var state by mutableStateOf(prefs.getInt(key, default))
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            state = value
            prefs.edit().putInt(key, value).apply()
        }
    }

    /**
     * 按**名字**存枚举，不存序号：序号会随枚举增删漂移，改天在中间插一个值，所有人的
     * 设置就悄悄变成了别的选项。名字对不上（枚举改过名、或者是别的版本存的）就退回默认。
     */
    private fun <T : Enum<T>> enumPref(key: String, default: T, entries: List<T>) =
        object : ReadWriteProperty<Any?, T> {
            private var state by mutableStateOf(
                entries.firstOrNull { it.name == prefs.getString(key, null) } ?: default,
            )

            override fun getValue(thisRef: Any?, property: KProperty<*>) = state
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
                state = value
                prefs.edit().putString(key, value.name).apply()
            }
        }

    companion object {
        private const val PREFS = "hooker_ui"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_PURE_BLACK = "pure_black"
        private const val KEY_COLOR_SOURCE = "color_source"
        private const val KEY_SEED_COLOR = "seed_color"
        private const val KEY_PALETTE_STYLE = "palette_style"
        private const val KEY_COLOR_SPEC = "color_spec"
        private const val KEY_BLUR = "blur_enabled"
        private const val KEY_START_TAB = "start_tab"

        /** miuix 浅色主题的主色，当自定义主题色的默认值。 */
        const val DEFAULT_SEED: Int = 0xFF3482FF.toInt()

        /** 系统取色（Monet）从 Android 12 才有。低于这个版本 miuix 会退回一套固定紫。 */
        val canFollowWallpaper: Boolean
            get() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

        @Volatile
        private var instance: UiPreferences? = null

        /** 进程级单例，理由同 [ModuleSettings]：Activity 重建不该重新读一遍盘。 */
        fun get(context: Context): UiPreferences = instance ?: synchronized(this) {
            instance ?: UiPreferences(context).also { instance = it }
        }
    }
}
