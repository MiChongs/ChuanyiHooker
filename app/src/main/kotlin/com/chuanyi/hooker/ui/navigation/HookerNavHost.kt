package com.chuanyi.hooker.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
// entry 是 EntryProviderScope 的成员函数，在 entryProvider { } 的作用域里直接可用，
// 不能也不需要 import。
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.DialogSceneStrategy
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.NavDisplayTransitionEffects
import androidx.savedstate.serialization.SavedStateConfiguration
import com.chuanyi.hooker.core.HookerRegistry
import com.chuanyi.hooker.data.ModuleSettings
import com.chuanyi.hooker.ui.screen.DonateScreen
import com.chuanyi.hooker.ui.screen.HomeScreen
import com.chuanyi.hooker.ui.screen.HookerDetailScreen
import com.chuanyi.hooker.ui.screen.LibraryLicenseScreen
import com.chuanyi.hooker.ui.screen.LicensesScreen
import com.chuanyi.hooker.ui.screen.LogScreen
import com.chuanyi.hooker.ui.screen.NativeLayerScreen
import com.chuanyi.hooker.ui.screen.SettingsScreen
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * 导航宿主：建返回栈、登记路由、交给 miuix 的 [NavDisplay] 渲染。
 *
 * 整条链路：
 *
 * ```
 * rememberNavBackStack        栈本身（可保存/恢复，元素是 @Serializable 的 Route）
 *   -> entryProvider          路由 -> 页面 的映射
 *   -> rememberDecoratedNavEntries   套上 decorator，给每个页面独立的
 *                                    rememberSaveable 状态域 + ViewModelStore
 *   -> NavDisplay             渲染栈顶、跑转场动画、接管系统/预测式返回
 * ```
 *
 * [NavDisplay] 自己注册了预测式返回，并且只在栈深 > 1 时拦截返回事件；栈底时返回
 * 键会正常冒泡给系统退出 Activity。所以这里**不要**再写全局 BackHandler。
 *
 * ## nav3 的能力在这里都开着，逐项对应关系
 *
 * | 能力 | 落点 | 说明 |
 * |---|---|---|
 * | 可保存的返回栈 | [rememberNavBackStack] + [SerializersModule] | 进程重建后原样恢复 |
 * | 条目级 saveable 状态 | `rememberSaveableStateHolderNavEntryDecorator` | 滚动位置、展开态往返不丢 |
 * | 条目级 ViewModelStore | `rememberViewModelStoreNavEntryDecorator` | 出栈即清，不是配置变更才清 |
 * | 条目级 Lifecycle | 由 `rememberSceneState` 自动装 | 不在栈上的条目封顶到 CREATED，转场中封顶到 STARTED |
 * | 场景策略 | [DialogSceneStrategy] + [SinglePaneSceneStrategy] | 带 dialog 元数据的条目走弹窗，其余单页 |
 * | 预测式返回 | [NavDisplay] 内建 + [rememberNavPredictivePopTransition] | 手势跟手，AOSP 规格 |
 * | 逐条目转场覆盖 | `NavDisplay.transitionSpec()` 等三个元数据键 | 见下方 [entryProvider] 里的说明 |
 *
 * `sceneDecoratorStrategies` 传空列表：它的用途是给场景外面套一层公共内容（比如所有
 * 页面共用的侧边栏），这个模块是单窗格手机界面，没有要套的东西。
 *
 * ## 为什么共享元素是关的（`sharedTransitionScope = null`）
 *
 * 试过，在这个应用里是坏的 —— 图标飞到错位置、导航明显掉帧。原因不在调参，在
 * `sharedTransitionScope` 这个参数本身的语义：
 *
 * 传了它，`rememberSceneState` 就会自动装上 `SharedEntryInSceneNavEntryDecorator`，
 * 而那个装饰器包的**不是**某个图标，是**每一个 entry 的整屏内容**：
 *
 * ```kotlin
 * Box(Modifier.sharedElement(rememberSharedContentState(entry.contentKey), …)) {
 *     entry.Content()
 * }
 * ```
 *
 * 于是每次导航都要把两块整屏内容按共享元素的规则重算边界，外加
 * `SharedTransitionLayout` 给整棵导航树套的 `LookaheadScope`（每帧全树测两遍）——
 * 这是掉帧的来源。而元素级的共享（比如让应用图标从列表飞进详情页）会嵌在那层
 * 整屏共享元素**里面**，父节点自己正在被重定位，子节点算出来的目标边界就是错的，
 * 表现为图标乱飞。
 *
 * 真要做图标飞入，正确的做法是**不给 NavDisplay 传 sharedTransitionScope**（这样
 * 就没有整屏装饰器），只在外面套一层 `SharedTransitionLayout` 并把 scope 通过
 * CompositionLocal 单独发给那两个图标。那是一次独立的改动，不在「打开 nav3 特性」
 * 这个范围里 —— 打开这个开关的**默认后果**就是上面那套整屏行为。
 */
@Composable
fun HookerNavHost(settings: ModuleSettings) {

    // nav3 把返回栈按 NavKey 的多态序列化存盘。sealed 层级不会自动注册，
    // 得显式列出每个具体子类，否则进程重建时恢复栈会抛
    // SerializationException。漏了哪个页面，就是那个页面恢复不回来。
    val serializersModule = remember {
        SerializersModule {
            polymorphic(NavKey::class) {
                subclass(Route.Home::class)
                subclass(Route.HookerDetail::class)
                subclass(Route.Settings::class)
                subclass(Route.NativeLayer::class)
                subclass(Route.Logs::class)
                subclass(Route.Donate::class)
                subclass(Route.Licenses::class)
                subclass(Route.LibraryLicense::class)
            }
        }
    }
    val savedStateConfiguration = remember(serializersModule) {
        SavedStateConfiguration { this.serializersModule = serializersModule }
    }

    val backStack = rememberNavBackStack(
        configuration = savedStateConfiguration,
        Route.Home,
    )
    val navigator = remember(backStack) { Navigator(backStack) }

    CompositionLocalProvider(LocalNavigator provides navigator) {

        val entryProvider = remember(settings) {
            entryProvider<NavKey> {
                entry<Route.Home> {
                    HomeScreen(settings = settings)
                }

                entry<Route.HookerDetail> { route ->
                    // 找不到就退回去：hooker 列表是运行期 ServiceLoader 决定的，
                    // 恢复出来的路由可能指向一个已经被移除的模块。
                    val hooker = HookerRegistry.byId(route.hookerId)
                    if (hooker == null) {
                        MissingRouteScreen(
                            title = route.hookerId,
                            message = "这个 hooker 已经不在模块里了。",
                        )
                    } else {
                        HookerDetailScreen(hooker = hooker, settings = settings)
                    }
                }

                entry<Route.Settings> {
                    SettingsScreen(settings = settings)
                }

                entry<Route.NativeLayer> {
                    NativeLayerScreen()
                }

                entry<Route.Logs> {
                    LogScreen(settings = settings)
                }

                entry<Route.Donate> {
                    DonateScreen()
                }

                entry<Route.Licenses> {
                    LicensesScreen()
                }

                entry<Route.LibraryLicense> { route ->
                    // 找不到的情况在页面内部处理（清单可能还在解析，也可能这个
                    // 依赖真的没了），不在这里判——这里判会把「还没读到」误当成
                    // 「不存在」。
                    LibraryLicenseScreen(uniqueId = route.uniqueId)
                }

                // 想让某一页走自己的转场，在它的 entry 上加元数据即可，不用改这里的
                // 全局 spec —— 三个键都由 NavDisplay 认，优先级高于下面的默认值：
                //
                //     entry<Route.Foo>(metadata = NavDisplay.transitionSpec { … }) { … }
                //     entry<Route.Foo>(metadata = DialogSceneStrategy.dialog()) { … }
                //
                // 目前这几页在层级上是同一类「往里走一层」，用同一套转场才是对的，
                // 所以一个覆盖都没写。
            }
        }

        // decorator 给每个 entry 独立的 SaveableStateHolder 与 ViewModelStore：
        // 详情页里的滚动位置、展开状态在压栈->出栈往返后还在，而不是被邻居页面串味；
        // ViewModelStore 则在真正出栈时清掉，而不是熬到 Activity 销毁。
        //
        // 顺序有意义：saveable 在外、ViewModelStore 在内，这样 ViewModel 的构造能读到
        // 已经恢复好的 SavedStateHandle。
        val entries = rememberDecoratedNavEntries(
            backStack = backStack,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider,
        )

        // 顺序即优先级：先问「这条目要不要当弹窗」，都不是才落到单窗格。
        val sceneStrategies = remember {
            listOf(DialogSceneStrategy<NavKey>(), SinglePaneSceneStrategy<NavKey>())
        }
        // 参考实现的做法：布局方向当普通参数传给 spec 工厂，不用把它们做成
        // @Composable + remember。spec 每次重组重建一个 lambda 是廉价的，
        // NavDisplay 只在转场开始时求值一次（Compose 把 enter/exit remember 住了）。
        val layoutDirection = LocalLayoutDirection.current

        NavDisplay(
            entries = entries,
            modifier = Modifier.fillMaxSize(),
            // 页面都是整屏的，转场期间两页尺寸一致，对齐取左上即可。
            contentAlignment = Alignment.TopStart,
            sceneStrategies = sceneStrategies,
            sceneDecoratorStrategies = emptyList(),
            // 见文件头「为什么共享元素是关的」。
            sharedTransitionScope = null,
            // 两页同尺寸，让 AnimatedContent 去 animate 尺寸只会多一层无谓计算。
            sizeTransform = null,
            transitionSpec = navPushTransition(layoutDirection),
            popTransitionSpec = navPopTransition(layoutDirection),
            predictivePopTransitionSpec = navPredictivePopTransition(layoutDirection),
            // 参考实现用的就是 NavDisplayTransitionEffects.Default，这里逐项写出来
            // 只为把每个值的理由留在原地。
            transitionEffects = NavDisplayTransitionEffects(
                // 转场中给顶层页面套上设备的物理圆角，和系统预测式返回一致。
                enableCornerClip = true,
                dimAmount = 0.5f,
                // 改成 true（默认值，也是参考实现的选择）。原来这里是 false，理由是
                // 「页面都很轻，连点比误触更烦人」—— 那条理由只对**点击**成立。
                // 预测式返回是在活动内容上拖手指，不挡输入的话手指底下的 LazyColumn
                // 会同时收到这串拖动，和返回手势抢。
                blockInputDuringTransition = true,
                // 必须 false。为 true 时 miuix 会在右边缘起手的情况下**整个跳过**
                // 上面那两条 pop spec，改用自己私有的 MIUI 曲线版本。
                popDirectionFollowsSwipeEdge = false,
            ),
            onBack = { navigator.pop() },
        )
    }
}
