package com.chuanyi.hooker.hookers.chuckle

import android.content.Context
import android.os.Build
import android.util.Base64
import com.chuanyi.hooker.core.HookScope
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.Modifiers
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Field
import java.util.UUID

/**
 * 权益记录的读写与改写。
 *
 * 磁盘上的形态是 `settings_iap.xml` 里一条 Base64 —— 名字起得像加密，解开只是明文 JSON：
 *
 * ```json
 * {"vfc":0,"ac":{
 *   "createAt":1785894883521, "code":"e13b8c43-…", "type":0, "activeCount":1,
 *   "timestamp":1786499683530, "devices":[{"id":"…","name":"Xiaomi-…","platform":1}],
 *   "untrusted_devices":[], "expired":false, "st":1785894883536, "app_id":2,
 *   "info":{"freeTrial":true}}}
 * ```
 *
 * 三个字段决定一切：
 *
 * | 字段 | 作用 |
 * |---|---|
 * | `code` | 非空才算有记录 |
 * | `expired` | 判定的另一半：`code 非空 && !expired` |
 * | `info.freeTrial` | 为真时三态是**试用**而不是已订阅 —— 试用期一到就掉 |
 *
 * `timestamp` 是到期时间，判定本身不看它（服务端复验才看），但订阅页直接显示它，
 * 所以一并推到 2100 年，否则界面会写着「已激活」却挂一个上周的日期。
 *
 * ## 为什么改两处而不是一处
 *
 * 只写盘不够：应用会周期性拿 `code` 去自建服务器复验，服务端返回什么就覆盖什么，
 * 试用到期后它会把 `expired` 刷回 true。
 * 只改内存也不够：全新安装时磁盘上根本没有记录，`code` 为空，判定第一关就过不去。
 *
 * 所以写盘负责**从无到有**，读取端改写负责**扛住覆盖**。两者用同一份字段映射。
 *
 * ## 字段映射不靠猜
 *
 * 权益对象的 Java 字段名被 R8 重排成 `oO0OO00` 这种，但磁盘上那份 JSON 是明文的。
 * 于是拿 JSON 里的**值**回到对象里反查：`timestamp` 的值只会出现在 `timestamp` 那个
 * long 字段上。比按类型顺序猜可靠得多 —— 光看类型，三个 long 谁是谁分不出来。
 */
internal object Entitlement {

    /** 2100-01-01，落在 `Long` 的安全区里，也不会让界面显示出负数天数。 */
    const val FAREND = 4102416000000L

    /** `type` 的取值：0 = 激活码，1 = Google Play，2 = App Store。 */
    private const val TYPE_ACTIVATION_CODE = 0

    /** `devices[].platform`：1 = Android。 */
    private const val PLATFORM_ANDROID = 1

    /** 目标自己的默认值，合成时没有旧记录可继承就用它。 */
    private const val DEFAULT_APP_ID = 2

    // JSON 里的键，和服务端共享，不随 R8 变。
    private const val J_WALLET = "ac"
    private const val J_VERSION = "vfc"
    private const val J_CODE = "code"
    private const val J_TYPE = "type"
    private const val J_EXPIRED = "expired"
    private const val J_TIMESTAMP = "timestamp"
    private const val J_ST = "st"
    private const val J_CREATE_AT = "createAt"
    private const val J_ACTIVE_COUNT = "activeCount"
    private const val J_APP_ID = "app_id"
    private const val J_DEVICES = "devices"
    private const val J_UNTRUSTED = "untrusted_devices"
    private const val J_INFO = "info"
    private const val J_FREE_TRIAL = "freeTrial"
    private const val J_ID = "id"
    private const val J_NAME = "name"
    private const val J_PLATFORM = "platform"
    private const val J_TIME = "time"

    /**
     * 权益对象里那几个要改的字段。
     *
     * [expired] 和 [freeTrial] 一定能定位到 —— 它们各自是所在类里唯一的 boolean。
     * [timestamp] 和 [code] 要靠磁盘上那份 JSON 的值反查，拿不到就留 null，
     * 少改一个只影响界面上的日期，不影响解锁。
     */
    class Layout(
        val code: Field?,
        val expired: Field?,
        val timestamp: Field?,
        val info: Field?,
        val freeTrial: Field?,
    ) {
        val usable: Boolean get() = expired != null || freeTrial != null

        override fun toString(): String =
            "expired=${expired?.name ?: "-"} freeTrial=${freeTrial?.name ?: "-"} " +
                "timestamp=${timestamp?.name ?: "-"} code=${code?.name ?: "-"}"
    }

    // -----------------------------------------------------------------------

    /**
     * 把磁盘上的记录推成永久，没有记录就地造一份。
     *
     * @param sample 调用方**刚刚读出来**的权益对象，用来做字段值反查。
     *   必须由调用方传进来而不是在这里自己去读一次 —— 这个方法是在权益读取的
     *   hook 回调里被调的，再调一次那个方法会直接递归。
     * @return 写盘后从 JSON 反查出的字段映射；失败返回 null
     */
    fun install(scope: HookScope, refs: ChuckleDex.Refs, context: Context, sample: Any?): Layout? {
        val prefs = runCatching {
            context.getSharedPreferences(ChuckleDex.PREFS_IAP, Context.MODE_PRIVATE)
        }.onFailure { scope.log.w("打不开 ${ChuckleDex.PREFS_IAP}", it) }.getOrNull() ?: return null

        val stored = prefs.getString(ChuckleDex.KEY_WALLET, null).orEmpty()
        val existing = stored.takeIf { it.isNotBlank() }?.let { decode(it) }

        // 反查要比的是**改写前**的值，而 promote 会就地改掉这个 JSONObject，
        // 所以先把要用的都取出来存好 —— 包括整串原文：`promote` 返回的就是
        // `existing` 本身，改完再拿它和「原来的」比会永远相等，写盘也就永远不会发生。
        val originalJson = existing?.toString()
        val originalTimestamp = existing?.optJSONObject(J_WALLET)?.optLong(J_TIMESTAMP, Long.MIN_VALUE)
        val originalCode = existing?.optJSONObject(J_WALLET)?.optString(J_CODE)

        val wallet = if (existing == null) {
            scope.log.i("没有权益记录（全新安装或已被清空），就地合成一份永久授权")
            synthesize(scope)
        } else {
            promote(existing)
        }

        val json = wallet.toString()
        val changed = originalJson == null || json != originalJson
        if (changed) {
            val ok = runCatching {
                prefs.edit().putString(ChuckleDex.KEY_WALLET, encode(json)).commit()
            }.onFailure { scope.log.w("权益记录写不回去", it) }.getOrDefault(false)

            if (ok) {
                // 应用可能已经反序列化过一次并缓存了旧对象，清掉它才会重读。
                invalidate(refs)
                scope.log.i("权益记录已推为永久（有效期 2100-01-01，非试用）")
            }
        } else {
            scope.log.d("权益记录本来就是永久的，保持原样")
        }

        return layoutFrom(scope, refs, sample, originalTimestamp, originalCode)
    }

    /**
     * 就地改写一个刚被读出来的权益对象。
     *
     * 服务端复验会把磁盘上的 `expired` 刷回去，所以每次读都要过一遍这里 ——
     * 写盘只解决「从无到有」，扛覆盖靠这一步。
     */
    fun rewrite(subscription: Any, layout: Layout) {
        runCatching { layout.expired?.setBoolean(subscription, false) }
        runCatching { layout.timestamp?.setLong(subscription, FAREND) }
        runCatching {
            val code = layout.code?.get(subscription) as? String
            if (code.isNullOrEmpty()) layout.code?.set(subscription, UUID.randomUUID().toString())
        }
        runCatching {
            val info = layout.info?.get(subscription) ?: return@runCatching
            layout.freeTrial?.setBoolean(info, false)
        }
    }

    /** 当前对象是不是已经是「永久且非试用」，用来决定日志说什么。 */
    fun describe(subscription: Any, layout: Layout): String = buildString {
        append("expired=").append(runCatching { layout.expired?.getBoolean(subscription) }.getOrNull())
        append(" 到期=").append(runCatching { layout.timestamp?.getLong(subscription) }.getOrNull())
        append(" 试用=").append(
            runCatching { layout.info?.get(subscription)?.let { layout.freeTrial?.getBoolean(it) } }.getOrNull(),
        )
    }

    // -----------------------------------------------------------------------

    /** 把已有记录推成永久，其余字段（激活码、设备、次数）原样保留。 */
    private fun promote(root: JSONObject): JSONObject {
        val wallet = root.optJSONObject(J_WALLET) ?: JSONObject().also { root.put(J_WALLET, it) }
        wallet.put(J_EXPIRED, false)
        wallet.put(J_TIMESTAMP, FAREND)

        val info = wallet.optJSONObject(J_INFO) ?: JSONObject().also { wallet.put(J_INFO, it) }
        info.put(J_FREE_TRIAL, false)

        if (wallet.optString(J_CODE).isEmpty()) {
            wallet.put(J_CODE, UUID.randomUUID().toString())
        }
        // 复验时会拿它和「本机在不在已激活列表里」对照，空列表会被判成新设备。
        if (wallet.optJSONArray(J_DEVICES)?.length() ?: 0 == 0) {
            wallet.put(J_DEVICES, JSONArray().put(thisDevice()))
        }
        return root
    }

    /**
     * 从零造一份记录。
     *
     * 走激活码那条路（`type=0`）而不是 Google Play（`type=1`）：后者会让订阅页显示
     * 「Google Play」并试图去 Play 拉订单，而这个模块的前提之一就是 Google 服务可能是停的。
     */
    private fun synthesize(scope: HookScope): JSONObject {
        val now = System.currentTimeMillis()
        val installedAt = runCatching {
            scope.appContextOrNull()?.packageManager
                ?.getPackageInfo(scope.packageName, 0)?.firstInstallTime
        }.getOrNull()?.takeIf { it > 0L } ?: now

        val wallet = JSONObject().apply {
            put(J_CREATE_AT, installedAt)
            put(J_CODE, UUID.randomUUID().toString())
            put(J_TYPE, TYPE_ACTIVATION_CODE)
            put(J_ACTIVE_COUNT, 1)
            put(J_TIMESTAMP, FAREND)
            put(J_DEVICES, JSONArray().put(thisDevice()))
            put(J_UNTRUSTED, JSONArray())
            put(J_EXPIRED, false)
            put(J_ST, installedAt)
            put(J_APP_ID, DEFAULT_APP_ID)
            put(J_INFO, JSONObject().put(J_FREE_TRIAL, false))
        }
        return JSONObject().put(J_VERSION, 0).put(J_WALLET, wallet)
    }

    private fun thisDevice(): JSONObject = JSONObject().apply {
        put(J_ID, UUID.randomUUID().toString())
        put(J_NAME, "${Build.MANUFACTURER}-${Build.MODEL}")
        put(J_PLATFORM, PLATFORM_ANDROID)
        put(J_TIME, System.currentTimeMillis())
    }

    // -----------------------------------------------------------------------

    /**
     * 用 JSON 里的值到对象里反查字段。
     *
     * `expired` 和 `freeTrial` 各是所在类里唯一的 boolean，按类型就能定死；
     * `timestamp` 和 `code` 有同类型的兄弟（三个 long、两个 String），只能靠值认 ——
     * 光看类型分不出谁是谁，而认错一个 long 就会把「创建时间」写成 2100 年。
     *
     * 比的是 [sample]（此刻还是磁盘上那份反序列化出来的原样）和**改写前**的 JSON 值。
     * 没有 sample（全新安装，权益是刚合成的）时退回「同类型只有一个才认」，
     * 认不出就留 null —— 少改一个只影响界面上的日期，不影响解锁。
     */
    private fun layoutFrom(
        scope: HookScope,
        refs: ChuckleDex.Refs,
        sample: Any?,
        originalTimestamp: Long?,
        originalCode: String?,
    ): Layout? {
        val subType = refs.subscriptionClass ?: run {
            scope.log.d("不知道权益对象的类，跳过字段映射；写盘那条腿仍然有效")
            return null
        }

        // `this.type` 不能省：FieldCondition 的 type 会被外层同名变量遮蔽。
        val booleans = subType.instanceFields { this.type = Boolean::class.javaPrimitiveType }
        val longs = subType.instanceFields { this.type = Long::class.javaPrimitiveType }
        val strings = subType.instanceFields { this.type = String::class.java }

        // info：既不是基本类型、不是 String、也不是集合的那个字段。
        val infoField = subType.instanceFields {
            typeCondition = {
                !it.isPrimitive && it != String::class.java &&
                    !Collection::class.java.isAssignableFrom(it) && !it.isArray
            }
        }.singleOrNull()

        // 试用标记是 info 那个类里唯一的 boolean。
        val freeTrialField = infoField?.type
            ?.instanceFields { this.type = Boolean::class.javaPrimitiveType }
            ?.singleOrNull()

        val timestampField = sample?.takeIf { originalTimestamp != null }?.let { instance ->
            longs.singleOrNull { runCatching { it.getLong(instance) }.getOrNull() == originalTimestamp }
        } ?: longs.takeIf { it.size == 1 }?.first()

        val codeField = sample?.takeIf { !originalCode.isNullOrEmpty() }?.let { instance ->
            strings.singleOrNull { runCatching { it.get(instance) }.getOrNull() == originalCode }
        } ?: strings.takeIf { it.size == 1 }?.first()

        val layout = Layout(
            code = codeField,
            expired = booleans.singleOrNull(),
            timestamp = timestampField,
            info = infoField,
            freeTrial = freeTrialField,
        )
        if (!layout.usable) {
            scope.log.w("权益对象的字段映射一个都没认出来（${subType.name} 结构可能变了）")
            return null
        }
        scope.log.d("字段映射：$layout")
        return layout
    }

    /**
     * 清掉已经反序列化好的缓存，让下一次读取重新走磁盘。
     *
     * 到这一步才去读那个静态字段：此刻是在权益读取的 hook 回调里，
     * 目标类的 `<clinit>` 早就跑完了，不会重演定位阶段那次「初始化时序倒挂」。
     */
    private fun invalidate(refs: ChuckleDex.Refs) {
        val holder = runCatching { refs.walletHolderField?.get(null) }.getOrNull() ?: return
        runCatching { refs.walletCache?.set(holder, null) }
    }

    // -----------------------------------------------------------------------

    /**
     * 目标那套「加密」是标准 Base64 换了个类名 —— 字母表就是 `A-Za-z0-9+/`，
     * padding 是 `=`。所以直接用系统的，不必把它那份实现抄过来。
     */
    private fun decode(stored: String): JSONObject? = runCatching {
        JSONObject(String(Base64.decode(stored, Base64.DEFAULT), Charsets.UTF_8))
    }.getOrNull()

    private fun encode(json: String): String =
        Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    // -----------------------------------------------------------------------

    /**
     * 本类里符合条件的实例字段，已 setAccessible。
     *
     * 权益对象的字段名被 R8 重排成 `oO0OO00` 这种，只能按类型分组再靠值反查，
     * 所以这里要的是「某个类型的全部字段」而不是某一个 —— KavaRef 的
     * `field { }` 正好返回列表，配 `optional(silent = true)` 查不到就是空。
     */
    @Suppress("UNCHECKED_CAST")
    private inline fun Class<*>.instanceFields(
        crossinline condition: com.highcapable.kavaref.condition.FieldCondition<Any>.() -> Unit,
    ): List<Field> = (this as Class<Any>).resolve()
        .optional(silent = true)
        .field {
            modifiers { Modifiers.STATIC !in it }
            condition()
        }
        .map { it.self.apply { isAccessible = true } }
}
