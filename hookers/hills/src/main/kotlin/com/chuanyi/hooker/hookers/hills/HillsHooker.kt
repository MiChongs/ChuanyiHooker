package com.chuanyi.hooker.hookers.hills

import android.os.Handler
import android.os.Looper
import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookFeature
import com.chuanyi.hooker.core.HookScope
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.core.findMethodOrNull
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createInterceptHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createReplaceHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createReturnConstantHook
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hills (`com.mountains.hills`) — a Flutter Emby/Jellyfin client.
 *
 * Pro is decided in Dart, in an `--obfuscate`d `libapp.so`, so none of it is
 * reachable by name. Billing however has to cross into Java: the
 * `in_app_purchase_android` plugin converts Play's purchases to a pigeon payload,
 * and `Translator.fromPurchasesList` is the single funnel every path goes
 * through — `queryPurchasesAsync` and the `onPurchasesUpdated` callback alike.
 *
 * That is enough to make the app believe the lifetime product is owned, but not
 * enough to keep it: the app posts the token to its own backend and revokes on a
 * negative verdict, over rhttp/reqwest which Java never sees. [VerifyBreaker]
 * redirects that one request into [VerifyServer], which answers it in-process —
 * the only part of this that needs native code.
 */
class HillsHooker : AppHooker {

    override val id = "hills"
    override val displayName = "Hills"
    override val description = "影视客户端，解锁终身 Pro"
    override val targetPackages = setOf("com.mountains.hills")
    override val stage = AppHooker.Stage.PACKAGE_READY

    override val features: List<HookFeature> = listOf(
        HookFeature(
            id = "lifetime",
            title = "解锁终身 Pro",
            summary = "让应用认为终身版已购买，Pro 功能全部开放",
            install = { installLifetime() },
        ),
        HookFeature(
            id = "billing_capability",
            title = "忽略「设备不支持 Google Play 订阅」",
            summary = "应用在买之前先问一句这台设备支不支持订阅，问不通就弹提示、停在那儿。" +
                "这一项让那句探问始终回答支持，后面的解锁才走得下去",
            install = { installBillingCapability() },
        ),
        HookFeature(
            id = "billing_resilient",
            title = "无视购买校验失败",
            summary = "没有应用商店服务、离线、或首次冷启动时，解锁一样成立",
            install = { installBillingResilience() },
        ),
        HookFeature(
            id = "announce_purchase",
            title = "主动补发购买结果",
            summary = "有些界面只在收到购买通知时才刷新，这一项负责把那条通知补上",
            install = { installPurchaseAnnounce() },
        ),
        HookFeature(
            id = "break_verify",
            title = "拦下联网复核",
            summary = "应用会把购买凭据交给自己的服务器复核，不通过就收回权限。" +
                "这一项在本机直接给出通过的答复，避免用着用着又变回未解锁",
            install = { installVerifyBreaker() },
        ),
        HookFeature(
            id = "player_pro",
            title = "解锁播放器功能",
            summary = "开放播放器的付费选项，如自定义解码与字幕样式",
            install = { installPlayerPro() },
        ),
        HookFeature(
            id = "spoof_signature",
            title = "伪装安装包签名",
            summary = "只有在用重新签名的安装包时才需要打开；从应用商店正常安装的无需理会",
            defaultEnabled = false,
            install = { installSignatureSpoof() },
        ),
        HookFeature(
            id = "log_preferences",
            title = "记录本地设置读写",
            summary = "排查用：记录应用读写了哪些本地设置项，并可按需把其中一部分强制为开启",
            defaultEnabled = false,
            install = { installPreferenceProbe() },
        ),
    )

    override fun isCompatible(scope: HookScope): Boolean {
        val present = HillsDex.translator(scope) != null
        if (!present) scope.log.w("in_app_purchase plugin not found — build too old or renamed")
        return present
    }

    override fun onHook(scope: HookScope) {
        scope.log.i("Hills ${scope.versionCode} in ${scope.processName}")
        // Unconditional: which product id means "lifetime" is an input to two
        // separate features, and the hooks that learn it only read.
        Skus.watch(scope)
        scope.traceBilling()
    }

    /**
     * Every call Dart makes into the billing plugin, at debug level.
     *
     * Which pigeon method the app reaches for is the only visible difference
     * between the paths that grant Pro and the ones that do not — the decision
     * itself is in Dart and out of reach.
     */
    private fun HookScope.traceBilling() {
        if (!settings.isVerbose()) return
        val handler = HillsDex.handler(this) ?: return
        // Discovered, not named: the pigeon types nest inside whatever the
        // Messages class ended up being called.
        val pigeonPrefix = HillsDex.pigeon(this, "Result")?.name?.substringBeforeLast('$')
        var traced = 0
        handler.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) }
            .forEach { method ->
                runCatching {
                    method.createAfterHook("hills.trace.${method.name}") { param ->
                        // The trailing pigeon Result is a callback, not an input.
                        val inputs = param.args.filterNot { argument ->
                            pigeonPrefix != null && argument != null &&
                                argument.javaClass.name.startsWith("$pigeonPrefix\$")
                        }
                        log.d("billing <- ${method.name}(${inputs.joinToString()})")
                    }
                    traced++
                }
            }
        log.d("tracing $traced billing entry point(s)")
    }

    // -----------------------------------------------------------------------

    /**
     * `Translator.fromPurchasesList(List<Purchase>) : List<PlatformPurchase>`.
     *
     * Appending to the result rather than rewriting the argument keeps the
     * app's own conversion in charge of every field, and the guard on the
     * incoming list stops a genuine purchase from being duplicated.
     *
     * Which product to claim comes from [Skus]. On a cold first run it can still
     * be unknown here — the app queries its purchases before its catalogue — in
     * which case nothing is injected and [installPurchaseAnnounce] picks the
     * unlock up as soon as the id turns up.
     */
    private fun HookScope.installLifetime() {
        val translator = HillsDex.translator(this) ?: error("the in_app_purchase Translator was not found")

        val fromPurchasesList = translator.findMethod {
            name("fromPurchasesList")
            paramCount(1)
            isStatic()
            returnTypeExtendsFrom(List::class.java)
        }

        fromPurchasesList.createAfterHook("hills.lifetime.purchases") { param ->
            val sku = Skus.lifetime(this) ?: run {
                log.w("no product id known yet, leaving the purchase list alone")
                return@createAfterHook
            }
            if (Billing.containsSku(param.arg(0), sku)) return@createAfterHook
            val injected = Billing.platformPurchase(this, sku) ?: return@createAfterHook
            val current = param.result as? List<*> ?: emptyList<Any?>()
            param.result = current + injected
            log.i("injected $sku into the purchase list")
        }
        log.i("patched Translator.fromPurchasesList")
    }

    /**
     * The capability probe the app runs *before* it will even try to sell
     * anything — and the reason some devices only ever see
     * "设备不支持 Google Play 订阅" ("Device does not support Google Play
     * Subscriptions", both strings live in the Dart snapshot).
     *
     * That message comes from exactly one call:
     *
     * ```
     * Boolean isFeatureSupported(PlatformBillingClientFeature)   // SUBSCRIPTIONS
     * ```
     *
     * It is synchronous, and it has two ways of saying no:
     *
     *  * Play answers `FEATURE_NOT_SUPPORTED` — an old Play Store, a ROM
     *    without it, a region where the feature is off — and it returns false;
     *  * `billingClient` is still null, and it **throws**
     *    `FlutterError("UNAVAILABLE", "BillingClient is unset…")` instead.
     *
     * The second one is why this cannot be an after-hook: an exception never
     * reaches one. It also explains why fixing the purchase list further down is
     * not enough on those devices — the app gives up at the probe and never gets
     * as far as querying purchases. Everything else this hooker does is
     * downstream of a question that was already answered "no".
     *
     * So the probe is replaced outright rather than repaired. Answering "yes"
     * unconditionally is the honest form here: whether Play supports
     * subscriptions stops being relevant the moment the entitlement is granted
     * locally, and the useful question — "can this module deliver Pro" — is
     * answered by the feature being switched on at all. Which feature was asked
     * about is logged rather than filtered, so a build that gates something else
     * on a different capability shows up in the log instead of silently
     * misbehaving.
     */
    private fun HookScope.installBillingCapability() {
        val handler = HillsDex.handler(this) ?: error("the in_app_purchase pigeon handler was not found")

        // The first interception is reported at info: this feature exists to fix
        // a failure the user sees as a toast and nothing else, so "the probe
        // happened and was answered" has to be visible without turning verbose
        // logging on. Repeats drop to debug — the app asks per screen.
        val probeSeen = AtomicBoolean(false)
        handler.findMethod { name("isFeatureSupported"); paramCount(1) }
            .createInterceptHook("hills.capability.feature") { chain ->
                val asked = "isFeatureSupported(${chain.getArg(0)}) -> forced true"
                if (probeSeen.compareAndSet(false, true)) log.i(asked) else log.d(asked)
                true
            }

        // `isReady` is the other half of the same question and is normally
        // covered by 'billing_resilient'. Only take it over when that one is
        // off, so the two features never stack two constants on one method.
        if (!isEnabled(FEATURE_BILLING_RESILIENT, default = true)) {
            handler.findMethod { name("isReady"); noParams() }
                .createReturnConstantHook("hills.capability.ready", true)
        }

        // The config lookup fails the same way — `result.error(UNAVAILABLE)`
        // before a client exists. Nothing here needs the real country, but an
        // error is one more thing the app can read as "billing is not usable".
        handler.findMethodOrNull { name("getBillingConfigAsync"); paramCount(1) }
            ?.createInterceptHook("hills.capability.config") { chain ->
                val real = chain.getArg(0)
                if (real == null) {
                    chain.proceed()
                } else {
                    val proxy = Billing.wrapResult(
                        scope = this,
                        real = real,
                        onSuccess = { it },
                        onError = {
                            log.i("getBillingConfigAsync failed ($it), answering synthetically")
                            Billing.billingConfig(this)
                        },
                    )
                    chain.proceed(arrayOf(proxy))
                }
            }

        log.i("billing capability probes forced to supported")
    }

    /**
     * Everything that can make Dart discard a purchase list it already received.
     *
     * `queryPurchasesAsync` reports a null billing client synchronously and a
     * Play failure asynchronously through the same pigeon callback, so wrapping
     * the callback covers both. A non-OK `billingResult` alone is enough for
     * `in_app_purchase` to drop the whole list, which is why success is rewritten
     * as well as error.
     */
    private fun HookScope.installBillingResilience() {
        val handler = HillsDex.handler(this) ?: error("the in_app_purchase pigeon handler was not found")

        handler.findMethod { name("queryPurchasesAsync"); paramCount(2) }
            .createInterceptHook("hills.billing.query") { chain ->
                val real = chain.getArg(1)
                if (real == null) {
                    chain.proceed()
                } else {
                    val proxy = Billing.wrapResult(
                        scope = this,
                        real = real,
                        onSuccess = { Billing.forceOk(this, it) },
                        onError = {
                            // Only worth substituting a whole response when the
                            // product to claim is known; otherwise let the real
                            // error through so the app can retry.
                            Skus.lifetime(this)?.let { sku ->
                                log.i("queryPurchasesAsync failed ($it), answering synthetically")
                                Billing.purchasesResponse(this, sku)
                            }
                        },
                        dispatch = { forward -> afterEndpointReady(forward) },
                    )
                    chain.proceed(arrayOf(chain.getArg(0), proxy))
                }
            }

        // Dart stops before it ever queries when this says false.
        handler.findMethod { name("isReady"); noParams() }
            .createReturnConstantHook("hills.billing.ready", true)

        handler.findMethodOrNull { name("startConnection"); paramCount(4) }
            ?.createInterceptHook("hills.billing.connect") { chain ->
                val real = chain.getArg(3)
                if (real == null) {
                    chain.proceed()
                } else {
                    val proxy = Billing.wrapResult(
                        scope = this,
                        real = real,
                        onSuccess = { Billing.forceOk(this, it) ?: it },
                        onError = {
                            log.i("startConnection failed ($it), forcing OK")
                            Billing.okBillingResult(this)
                        },
                    )
                    chain.proceed(arrayOf(chain.getArg(0), chain.getArg(1), chain.getArg(2), proxy))
                }
            }

        // Our token is already flagged acknowledged, but a stray call must not
        // surface as an exception in the middle of the purchase flow.
        listOf("acknowledgePurchase", "consumeAsync").forEach { name ->
            handler.findMethodOrNull { name(name); paramCount(2) }
                ?.createInterceptHook("hills.billing.$name") { chain ->
                    val real = chain.getArg(1)
                    if (real == null) {
                        chain.proceed()
                    } else {
                        val proxy = Billing.wrapResult(
                            scope = this,
                            real = real,
                            onSuccess = { it },
                            onError = { Billing.okBillingResult(this) },
                        )
                        chain.proceed(arrayOf(chain.getArg(0), proxy))
                    }
                }
        }

        // A disconnect notice makes Dart tear the client down and stop querying.
        HillsDex.pigeon(this, "InAppPurchaseCallbackApi")
            ?.findMethodOrNull { name("onBillingServiceDisconnected"); paramCount(2) }
            ?.createReplaceHook("hills.billing.disconnect") { null }

        log.i("billing failure paths neutralised")
    }

    /**
     * Delivers [forward] only once the verification endpoint is in place.
     *
     * Measured on device: the app connects its billing client, queries its
     * purchases 55 ms later and posts the first verification 30 ms after that,
     * while the heap rewrite needs a few hundred milliseconds to find the
     * strings. The app decides Pro exactly once, from that first verdict, so
     * without this gate it is always the real backend's "no" that counts and
     * every later grant is ignored.
     *
     * The wait is bounded and the reply lands on the platform thread, which is
     * where the plugin's own callbacks come from.
     */
    private fun HookScope.afterEndpointReady(forward: () -> Unit) {
        if (!VerifyBreaker.isPending) {
            forward()
            return
        }
        Thread({
            val started = System.currentTimeMillis()
            if (!VerifyBreaker.awaitReady(ENDPOINT_WAIT_MS)) {
                log.w("verification endpoint still not ready after ${ENDPOINT_WAIT_MS}ms")
            } else {
                log.d("held the purchase list for ${System.currentTimeMillis() - started}ms")
            }
            Handler(Looper.getMainLooper()).post(forward)
        }, "hills-billing-gate").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Some builds only flip Pro from the `purchaseStream` listener and never call
     * `restorePurchases`, in which case fixing the query result is not reachable.
     * Firing the callback the SDK itself uses covers that path; Dart cannot tell
     * the difference.
     *
     * It also covers the cold-first-run gap in [Skus]: if the product id is not
     * known when the connection comes up, the announcement waits for it instead
     * of being dropped, so opening a store screen is enough to complete the
     * unlock without a restart.
     */
    private fun HookScope.installPurchaseAnnounce() {
        val handlerClass = HillsDex.handler(this) ?: error("the in_app_purchase pigeon handler was not found")
        val startConnection = handlerClass.findMethod { name("startConnection"); paramCount(4) }
        val announced = AtomicBoolean(false)

        startConnection.createAfterHook("hills.billing.announce") { param ->
            val self = param.thisObjectOrNull ?: return@createAfterHook
            if (!announced.compareAndSet(false, true)) return@createAfterHook
            // The connection callback has not run yet at this point.
            Handler(Looper.getMainLooper()).postDelayed({
                // Fires straight away when the id is already cached from an
                // earlier run, which is the usual case after the first launch.
                // The id is read here rather than handed in, so a catalogue that
                // arrives in a burst is announced under its best entry and not
                // under whichever one happened to land first.
                Skus.onLearned {
                    Handler(Looper.getMainLooper()).post {
                        val sku = Skus.lifetime(this) ?: return@post
                        runCatching { announce(self, sku) }
                            .onFailure {
                                announced.set(false)
                                log.e("announcing the purchase failed", it)
                            }
                    }
                }
            }, ANNOUNCE_DELAY_MS)
        }
        log.i("purchase announcement armed")
    }

    private fun HookScope.announce(handler: Any, sku: String) {
        val callbackApi = handler.javaClass
            .getDeclaredField(Billing.CALLBACK_FIELD)
            .apply { isAccessible = true }
            .get(handler) ?: run {
            log.w("${Billing.CALLBACK_FIELD} is null, nothing to announce through")
            return
        }

        val response = Billing.purchasesResponse(this, sku) ?: return
        val voidResult = Billing.voidResult(this) ?: return

        callbackApi.javaClass
            .findMethod { name("onPurchasesUpdated"); paramCount(2) }
            .invoke(callbackApi, response, voidResult)
        log.i("announced $sku on purchaseStream")
    }

    /**
     * The endpoint and the key it is answered with are both found in the heap
     * rather than named here, so this only has to start the search — see
     * [VerifyBreaker] for why discovery has to happen inside the retry ladder.
     */
    private fun HookScope.installVerifyBreaker() = VerifyBreaker.start(this)

    /**
     * `isPro` reaches the Kotlin player as part of the serialized PlayerConfig.
     *
     * The class is the app's own, so nothing keeps its name; [HillsDex] finds it
     * through its Moshi adapter, which has to spell every JSON key out.
     */
    private fun HookScope.installPlayerPro() {
        val config = HillsDex.playerConfig(this) ?: error("PlayerConfig was not found")
        var patched = 0
        // `val isPro` compiles to isPro(); a rename to getIsPro() is cheap to cover.
        listOf("isPro", "getIsPro").forEach { name ->
            config.findMethodOrNull {
                name(name)
                noParams()
                returnType(Boolean::class.javaPrimitiveType!!)
            }?.let {
                it.createReturnConstantHook("hills.player.$name", true)
                patched++
            }
        }
        if (patched == 0) error("PlayerConfig has no boolean isPro accessor")
        log.i("PlayerConfig.isPro forced ($patched accessor(s))")
    }

    /**
     * The app asks Kotlin for `SHA-256(apkContentsSigners[0])` over the
     * `com.mountains.signature` channel and sends the result to its backend;
     * there is no expected value baked into the APK to compare against locally.
     *
     * Rather than hunting for the obfuscated handler class, intercept the
     * registration: `MethodChannel.setMethodCallHandler` is public API, the
     * channel name is a field on the channel, and wrapping the handler at that
     * point survives any renaming of the plugin.
     */
    private fun HookScope.installSignatureSpoof() {
        val spoofed = string(KEY_SIGNATURE)?.takeIf { it.isNotBlank() }
        val channelClass = classOrNull(METHOD_CHANNEL) ?: error("$METHOD_CHANNEL not found")
        val handlerInterface = classOrNull(METHOD_CALL_HANDLER)
            ?: error("$METHOD_CALL_HANDLER not found")

        val nameField = channelClass.getDeclaredField("name").apply { isAccessible = true }

        channelClass.findMethod { name("setMethodCallHandler"); paramCount(1) }
            .createInterceptHook("hills.signature.channel") { chain ->
                val target = chain.getArg(0)
                val channelName = runCatching { nameField.get(chain.thisObject) as? String }.getOrNull()
                if (target == null || channelName != SIGNATURE_CHANNEL) {
                    chain.proceed()
                } else {
                    log.i("wrapping the $SIGNATURE_CHANNEL handler")
                    chain.proceed(arrayOf(wrapSignatureHandler(handlerInterface, target, spoofed)))
                }
            }

        if (spoofed == null) {
            log.w("no '$KEY_SIGNATURE' stored — the real hash will only be logged, not replaced")
        }
    }

    private fun HookScope.wrapSignatureHandler(
        handlerInterface: Class<*>,
        real: Any,
        spoofed: String?,
    ): Any {
        val invocation = InvocationHandler { _, method: Method, args: Array<Any?>? ->
            val call = args?.getOrNull(0)
            val result = args?.getOrNull(1)
            val callName = runCatching {
                call?.javaClass?.getField("method")?.get(call) as? String
            }.getOrNull()

            if (method.name != "onMethodCall" || callName != SIGNATURE_METHOD || result == null) {
                return@InvocationHandler if (args == null) method.invoke(real) else method.invoke(real, *args)
            }

            if (spoofed == null) {
                // Log-only mode: let the app answer, then report what it said so
                // the value can be captured and stored.
                method.invoke(real, *args)
                log.i("$SIGNATURE_METHOD answered by the app; store it as '$KEY_SIGNATURE' to spoof")
                return@InvocationHandler null
            }

            val success = result.javaClass.methods
                .firstOrNull { it.name == "success" && it.parameterCount == 1 }
                ?: return@InvocationHandler method.invoke(real, *args)
            success.isAccessible = true
            success.invoke(result, spoofed)
            log.i("$SIGNATURE_METHOD -> $spoofed")
            null
        }
        return Proxy.newProxyInstance(classLoader, arrayOf(handlerInterface), invocation)
    }

    /**
     * Where Dart keeps its own copy of the entitlement is not visible statically,
     * so this prints every key it touches. Once the key is known, listing it in
     * `force_true_keys` makes reads return true and drops writes of false —
     * which is what a revocation looks like from this side.
     */
    private fun HookScope.installPreferenceProbe() {
        val backend = HillsDex.prefsBackend(this) ?: error("SharedPreferencesBackend was not found")
        val forced = string(KEY_FORCE_TRUE_KEYS)
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        val accessors = backend.declaredMethods.filter { method ->
            method.parameterCount >= 1 &&
                (method.name.startsWith("get") || method.name.startsWith("set"))
        }
        if (accessors.isEmpty()) error("${backend.name} exposes no accessors")

        accessors.forEach { accessor ->
            accessor.createInterceptHook("hills.prefs.${accessor.name}") { chain ->
                val key = chain.getArg(0) as? String
                val hit = key != null && key in forced

                when {
                    hit && accessor.name == "getBool" -> {
                        log.i("forced ${accessor.name}(\"$key\") = true")
                        true
                    }

                    hit && accessor.name == "setBool" && chain.getArg(1) == false -> {
                        log.i("dropped ${accessor.name}(\"$key\", false)")
                        null
                    }

                    else -> chain.proceed().also { value ->
                        val extra = chain.args.drop(1).dropLast(1)
                        val suffix = if (extra.isEmpty()) "" else ", ${extra.joinToString()}"
                        log.i("${accessor.name}(\"$key\"$suffix) = $value")
                    }
                }
            }
        }
        log.i("preference probe on ${accessors.size} accessor(s), ${forced.size} forced key(s)")
    }

    private companion object {
        // The plugin and app classes are located by HillsDex rather than named
        // here. What is left is Flutter's own embedding API, which every plugin
        // in the process references and which Flutter's keep rules pin.
        const val METHOD_CHANNEL = "io.flutter.plugin.common.MethodChannel"
        const val METHOD_CALL_HANDLER = "io.flutter.plugin.common.MethodChannel\$MethodCallHandler"

        /** Referenced from installBillingCapability, which defers isReady to it. */
        const val FEATURE_BILLING_RESILIENT = "billing_resilient"

        const val SIGNATURE_CHANNEL = "com.mountains.signature"
        const val SIGNATURE_METHOD = "getSignature"

        /** Long enough for the connection callback and the Dart listener to be up. */
        const val ANNOUNCE_DELAY_MS = 2_000L

        /** Cap on holding the purchase list back; the scan needs ~500 ms. */
        const val ENDPOINT_WAIT_MS = 8_000L

        // Optional string settings, read through HookScope.string().
        // 'verify_url' and 'verify_url_match' belong to the components that read
        // them; see VerifyBreaker.KEY_VERIFY_URL and HillsHeap.KEY_URL_MATCH.
        const val KEY_SIGNATURE = "signature"
        const val KEY_FORCE_TRUE_KEYS = "force_true_keys"
    }
}
