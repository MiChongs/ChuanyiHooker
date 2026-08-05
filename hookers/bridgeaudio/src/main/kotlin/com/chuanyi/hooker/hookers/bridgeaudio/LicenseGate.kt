package com.chuanyi.hooker.hookers.bridgeaudio

import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.hookers.bridgeaudio.BridgeAudio.isStaticContextAction
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createInterceptHook
import java.lang.reflect.Modifier

/**
 * PairIP 许可校验 —— 这个应用**唯一**真正依赖 Google 服务的地方。
 *
 * ## 冻结 Google 之后为什么会退出
 *
 * 这个包是 Google Play 出包时注入 PairIP 的（`com.pairip.licensecheck`，在应用自己的
 * R8 之后加进来，所以名字是明文）。入口在 `Application.attachBaseContext` 的第一句，
 * 早于应用任何代码：
 *
 * ```
 * checkLicense(ctx)
 *   → performLocalInstallerCheck()        安装来源是 com.android.vending → LOCAL_CHECK_OK
 *   → connectToLicensingService()         bindService("com.android.vending.licensing.ILicensingService")
 *        ├─ 绑定成功 → transact(2) 校验，成功则 FULL_CHECK_OK
 *        └─ 绑定失败 → 每秒重试，共 3 次
 *              → handleError()            当前状态不是 FULL_CHECK_OK，于是：
 *                   ├─ startErrorDialogActivity()   弹 LicenseActivity 错误对话框
 *                   └─ scheduleAppShutdown()        30 秒后 System.exit(0)
 * ```
 *
 * 冻结 Play 商店时 `bindService` 直接返回 false，走的正是下面那条 —— 应用先弹一个
 * 无法关闭的错误框，再自杀。**这与购买状态无关**：即使已经真实购买、即使解锁标记
 * 已经写进存档，只要 Play 商店不可绑定，应用照样起不来。所以「解锁」和「没有 Google
 * 也能用」是两件独立的事，必须分别处理。
 *
 * ## 三道处置，从根到梢
 *
 * 1. **拦住入口** —— `checkLicense(Context)` 与 `initializeLicenseCheck()` 直接空转。
 *    校验不发起，就不存在绑定、重试、失败、退出这一整条链。两个都拦是因为它们是
 *    两个独立入口：前者被 `Application` 调用，后者还被 `LicenseContentProvider`
 *    调用（当前 manifest 里没注册这个 provider，但那是 Google 出包时决定的，
 *    换一次包就可能有）。
 * 2. **改写状态** —— 把静态的 `licenseCheckState` 置成 `FULL_CHECK_OK`。
 *    `handleError()` 的第一句就是「已经 FULL_CHECK_OK 就直接返回」，于是万一有哪条
 *    路径绕过了第 1 步，它也走不到弹框与退出。顺带把 `eventualShutdownEnabled`
 *    关掉，那 30 秒的定时自杀不再排期。
 * 3. **兜住退出** —— `Runtime.exit` / `Runtime.halt` 上加一道过滤：调用栈里出现
 *    `com.pairip.` 的那一次不执行。这条不认任何类名与方法名，PairIP 换版本也仍然
 *    成立；应用自己要退出时栈里没有那个前缀，照常放行。
 *
 * 三道都只针对许可校验，应用自身的结算、购买、恢复购买一处都没动。
 */
internal object LicenseGate {

    fun HookScope.installLicenseGate() {
        val client = classOrNull(BridgeAudio.LICENSE_CLIENT)
        if (client == null) {
            // 不是错误：Google 可能出了一个不带 PairIP 的包。此时本项无事可做，
            // 但退出兜底仍然装上 —— 它不依赖这个类。
            log.i("这个版本没有 PairIP 许可校验，只装退出兜底")
            guardExit()
            return
        }

        var intercepted = 0

        client.declaredMethods
            .filter { it.isStaticContextAction() && it.name == "checkLicense" }
            .ifEmpty { client.declaredMethods.filter { it.isStaticContextAction() } }
            .forEach { method ->
                method.createInterceptHook("bridgeaudio.license.check") { null }
                intercepted++
                log.d("已拦下 ${method.name}(Context)")
            }

        client.declaredMethods
            .filter {
                it.returnType == Void.TYPE && it.parameterCount == 0 &&
                    !Modifier.isStatic(it.modifiers) && it.name == "initializeLicenseCheck"
            }
            .forEach { method ->
                method.createInterceptHook("bridgeaudio.license.init") { null }
                intercepted++
                log.d("已拦下 ${method.name}()")
            }

        if (intercepted == 0) error("PairIP 的校验入口一个都没拦下")

        forceCheckedState(client)
        guardExit()
        log.i("许可校验已豁免，冻结 Google 服务也不会被强制退出")
    }

    /**
     * 把静态状态改成「已通过完整校验」，并取消定时退出。
     *
     * 反射写静态字段会触发 `LicenseClient` 的类初始化 —— 那里只是建了一个主线程
     * Handler，没有副作用，而且应用启动时本来就会初始化它。
     *
     * 失败不算错误：入口已经拦住了，这一步是备份。
     */
    private fun HookScope.forceCheckedState(client: Class<*>) {
        runCatching {
            val field = client.getDeclaredField("licenseCheckState").apply { isAccessible = true }
            val ok = field.type.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == "FULL_CHECK_OK" }
                ?: error("枚举里没有 FULL_CHECK_OK")
            field.set(null, ok)
            log.d("许可状态已置为 FULL_CHECK_OK")
        }.onFailure { log.d("改写许可状态失败（入口已拦住，不影响）：${it.message}") }

        runCatching {
            client.getDeclaredField("eventualShutdownEnabled").apply { isAccessible = true }
                .setBoolean(null, false)
            log.d("30 秒定时退出已关闭")
        }.onFailure { log.d("关闭定时退出失败（入口已拦住，不影响）：${it.message}") }
    }

    /**
     * 只挡许可校验发起的那一次退出。
     *
     * `System.exit` 最终落到 `Runtime.exit`，`Runtime.halt` 是绕过关闭钩子的那条。
     * 判据是调用栈里有没有 `com.pairip.` —— 应用自己、ART、各种 SDK 调这两个方法时
     * 栈里都没有它，一律放行，所以这不会让应用退不掉。
     *
     * 用 before 钩子而不是 intercept：intercept 里若想「放行」就得自己把原方法调回去，
     * 而反射调用同样会被这个钩子拦住，直接递归。before 钩子只在该挡的时候写一次
     * `result`（写入即置 skipped），其余情况什么都不做，原方法照常执行。
     */
    private fun HookScope.guardExit() {
        val runtime = Runtime::class.java
        listOf("exit", "halt").forEach { name ->
            runCatching {
                runtime.getDeclaredMethod(name, Int::class.javaPrimitiveType)
            }.onSuccess { method ->
                method.createBeforeHook("bridgeaudio.license.$name") { param ->
                    if (BridgeAudio.isPairipCaller()) {
                        log.w("挡下许可校验发起的 Runtime.$name(${param.args.getOrNull(0)})")
                        param.result = null
                    }
                }
            }.onFailure { log.d("Runtime.$name 拿不到，跳过：${it.message}") }
        }
        log.d("退出兜底已就位")
    }
}
