package com.chuanyi.hooker.hookers.gboard

import android.view.inputmethod.EditorInfo
import com.chuanyi.hooker.core.HookScope
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook

/**
 * 终端里别把键盘降级成密码键盘。
 *
 * ## 为什么终端会变成英文 QWERTY
 *
 * Termux 这类终端把输入框声明成 `inputType = TYPE_NULL` —— 它要的是原始按键事件，
 * 不要联想、不要组词、不要自动更正。而 Gboard 挑用哪套键盘时是这么写的：
 *
 * ```java
 * // InputBundleManager.loadActiveInputBundleId()
 * if (isPassword(m) || isTypeNull(m)) {
 *     lang = isForceAscii(m) ? "und-Latn-x-password-ascii" : "und-Latn-x-password";
 * }
 * ```
 *
 * **`TYPE_NULL` 和密码框走同一个分支**，于是拿到的是那套专供密码用的键盘：
 * 固定 QWERTY、没有候选栏、不认用户选的语言和布局，上下滑自然也一起没了
 * （空格键上印的是「QWERTY」而不是语言名，就是认出它的标志）。
 *
 * 从 Gboard 的角度这不算错 —— 一个不说自己要什么的输入框，给个最保守的键盘。
 * 但终端要的恰恰是完整键盘。
 *
 * ## 为什么只能改这一处调用
 *
 * `isTypeNull` 在包里有五个调用点，另外那些**不能动**，尤其是：
 *
 * ```java
 * // InputConnectionWrapper
 * if (isTypeNull(editorInfo)) { sendKeyDataToTypeNullBox(…) }   // 发按键事件而不是上屏文本
 * ```
 *
 * 这一处正是终端能收到按键的原因，跟着改的话字都打不出去了。
 *
 * 所以做法是：在挑键盘的那个方法进出时立一个线程标记，`isTypeNull` 只在标记立着的
 * 时候才翻结果 —— 等于**只改那一个调用点**，别的调用一律照原样。挑键盘发生在切换
 * 输入框时、和按键不在同一条路径上，标记不会串。
 */
internal object GboardKeyboard {

    /**
     * @param apps 生效的包名，支持 `com.termux*` 这样的前缀。空表示所有这类输入框。
     */
    fun install(scope: HookScope, apps: List<String>) {
        val refs = GboardRefs.of(scope)

        // 只在「正在挑键盘」期间生效。挑键盘只在 UI 线程上跑，按键处理走的是别的
        // 调用路径，两者不会同时在同一个线程里。
        val picking = ThreadLocal.withInitial { false }

        refs.loadActiveBundle.createBeforeHook("gboard.bundle.enter") { picking.set(true) }
        // libxposed 的 after 在方法抛异常时照样会走，所以标记不会漏关。
        refs.loadActiveBundle.createAfterHook("gboard.bundle.exit") { picking.set(false) }

        refs.isTypeNull.createAfterHook("gboard.keyboard.type_null") { param ->
            if (param.result != true) return@createAfterHook
            if (picking.get() != true) return@createAfterHook
            val editor = param.args.getOrNull(0) as? EditorInfo ?: return@createAfterHook
            if (!matches(apps, editor.packageName)) return@createAfterHook
            param.result = false
        }

        scope.log.i("终端保留常规键盘：${if (apps.isEmpty()) "所有应用" else apps.joinToString()}")
    }

    /** 尾部 `*` 按前缀匹配 —— Termux 的浮窗、X11 各是一个独立包。 */
    private fun matches(patterns: List<String>, packageName: String?): Boolean {
        if (patterns.isEmpty()) return true
        if (packageName.isNullOrEmpty()) return false
        return patterns.any {
            if (it.endsWith('*')) packageName.startsWith(it.dropLast(1)) else packageName == it
        }
    }

    /** `com.termux*, org.connectbot` -> 列表。空串（用户清空了）就是「不限应用」。 */
    fun parseApps(raw: String): List<String> = raw
        .split(',', '，', ';', '；', '\n', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}
