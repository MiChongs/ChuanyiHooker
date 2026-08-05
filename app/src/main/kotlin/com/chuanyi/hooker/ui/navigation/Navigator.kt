package com.chuanyi.hooker.ui.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavKey

/**
 * 返回栈的操作封装。
 *
 * Navigation 3 没有 NavController，返回栈就是一个
 * `SnapshotStateList`，导航就是往里加减元素。这个类只是把常用操作收口，
 * 顺带处理两件容易出错的事：
 *
 * 1. **重复 push**：nav3 用路由实例做 contentKey，栈里出现两个相等的 key 会在
 *    重建 entry 时抛异常。连点两下按钮就能触发，所以 [push] 做幂等。
 * 2. **弹空栈**：栈空了 `NavDisplay` 会直接 `require` 失败崩溃，所以 [pop] 永远
 *    留住栈底。系统返回键在栈只剩一层时不该由我们消费，见 [canPop]。
 */
@Stable
class Navigator(val backStack: MutableList<NavKey>) {

    /** 幂等入栈：栈里已经有这个目的地就不重复压入。 */
    fun push(route: Route) {
        if (route !in backStack) backStack.add(route)
    }

    /** 替换栈顶，栈空时等价于 push。 */
    fun replace(route: Route) {
        if (backStack.isEmpty()) backStack.add(route) else backStack[backStack.lastIndex] = route
    }

    /** 出栈一层；已经在栈底时什么都不做并返回 false。 */
    fun pop(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    /** 一直弹到 [predicate] 命中栈顶为止，栈底始终保留。 */
    fun popUntil(predicate: (NavKey) -> Boolean) {
        while (backStack.size > 1 && !predicate(backStack.last())) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    /** 回到首页。 */
    fun popToHome() = popUntil { it is Route.Home }

    val current: NavKey? get() = backStack.lastOrNull()

    val depth: Int get() = backStack.size

    /** 栈里还有东西可退，也就是返回键应该由导航消费而不是退出应用。 */
    val canPop: Boolean get() = backStack.size > 1
}

/**
 * 让任意层级的页面拿到导航器，不用一路透传参数。
 */
val LocalNavigator = staticCompositionLocalOf<Navigator> {
    error("LocalNavigator 未提供：把内容包在 HookerNavHost 里")
}
