package com.chuanyi.hooker.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast

/**
 * 复制到剪贴板。
 *
 * [confirmation] 是「已复制」这类确认提示，只在 Android 12 及以下弹 —— 13 起系统自己会在
 * 屏幕左下角显示一个带内容预览的浮层（AOSP 的剪贴板提示），我们再 Toast 一次就是上下两条
 * 说同一件事的提示。传 null 表示不需要确认，比如调用方自己要说的是别的话
 * （见 [openExternalLink]：那句要讲的是「链接打不开」，不是「已复制」）。
 *
 * 取不到 ClipboardManager 时静默失败：该服务在正常设备上不会缺失，且没有可提供的补救
 * 手段 —— 需要复制的内容本身都显示在屏幕上。
 */
fun Context.copyToClipboard(label: String, text: String, confirmation: String? = null) {
    getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(label, text))

    if (confirmation != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, confirmation, Toast.LENGTH_SHORT).show()
    }
}
