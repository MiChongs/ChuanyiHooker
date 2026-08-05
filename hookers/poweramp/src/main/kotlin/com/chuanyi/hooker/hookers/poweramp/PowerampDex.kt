package com.chuanyi.hooker.hookers.poweramp

import android.os.SystemClock
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.hookers.poweramp.Poweramp.isByteArrayTransform
import com.chuanyi.hooker.hookers.poweramp.Poweramp.isPrefDefault
import com.chuanyi.hooker.hookers.poweramp.Poweramp.isPrefValue
import com.chuanyi.hooker.nativehook.NativeHook
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.nio.ByteBuffer

/**
 * 把授权存储从 dex 里挖出来。
 *
 * ## 为什么非扫不可
 *
 * 授权链路上一个可用的名字都没有：存储类叫 `ׅ.i30`、偏好基类叫 `ׅ.n10`、
 * 授权助手叫 `ׅ.p5` —— 包名是一个希伯来标点，类名是 R8 按出现顺序发的两三个字符，
 * 下一次构建就会重排。能直接按名字取的只有 [Poweramp.BASE_APPLICATION]、
 * [Poweramp.SYNC] 和 [Poweramp.EXPIRED_ACTIVITY] 三个，因为它们分别由接口实现、
 * `RegisterNatives` 和 manifest 钉住。
 *
 * ## 两条锚点，都不指望名字
 *
 * | 锚点 | 命中 | 稳定性 |
 * |---|---|---|
 * | [Poweramp.ANCHOR_SBUF] | 授权存储类本身 | 绑在一个 Kotlin 属性名上，改名即失效 |
 * | [Poweramp.ANCHOR_PENDING_END] | 处理待处理购买的方法 | 是 native↔Java 的字段约定，改要两边一起改 |
 *
 * 第二条更稳但要多绕一步：命中的是方法，得再看它读写了哪些静态字段，才反推出
 * 存储类。两条都用 [StringMatchType.Equals]——包含匹配会连上 R8 合并出来的共享类。
 *
 * ## 命中之后一律按形状复核
 *
 * 锚点只负责把候选缩小到一两个，真正的判据是形状（见 [verify]）：一个类得同时
 * 拿得出「默认值是 `Integer.MIN_VALUE` 的 int 偏好」和「32 字节的 direct
 * ByteBuffer」才算是授权存储。这两样都是语义要求而不是巧合 ——
 * 前者要把「没查过」和「查到 0」分开，后者是与原生共享的那块状态。
 */
internal object PowerampDex {

    /**
     * 授权链路上要用到的全部引用。
     *
     * [licensePref] 是硬要求，[blob] 不是：拿不到共享缓冲区只是功能套餐解不开，
     * 完整版本身仍然成立，见 [PowerampHooker.onHook]。
     */
    class Refs(
        /** 授权存储类（`ׅ.i30`），只用来出日志。 */
        val store: Class<*>? = null,
        /** 授权结果那一项的实例，默认值 `Integer.MIN_VALUE` 认出来的。 */
        val licensePref: Any? = null,
        /** 上面那个实例的「当前值」字段，全应用二十多处直接读它。 */
        val licenseValue: Field? = null,
        /** 与原生共享的 32 字节状态块。Java 拿到的是只读视图。 */
        val blob: ByteBuffer? = null,
        /** [blob] 那块内存的真实地址；0 表示只能读不能写。 */
        val blobAddress: Long = 0L,
        /** 导出加密：`static (byte[]) -> byte[]`，内部交给原生的 `eS`。 */
        val encrypt: Method? = null,
        /** 导入解密：`static (byte[]) -> byte[]`，内部交给原生的 `dS`。 */
        val decrypt: Method? = null,
    ) {
        val canUnlock: Boolean get() = licensePref != null && licenseValue != null
        val canWriteBlob: Boolean get() = blob != null && blobAddress != 0L
        val canRewriteBackup: Boolean get() = encrypt != null && decrypt != null

        /** 当前授权结果码，读不到时返回 null。 */
        fun licenseCode(): Int? = runCatching { licenseValue?.getInt(licensePref) }.getOrNull()

        fun describe(): String = buildString {
            append("\n  授权存储  ").append(store?.name ?: "未定位")
            append("\n  授权结果  ").append(
                if (canUnlock) "${licenseValue?.name} = ${licenseCode()}" else "未定位",
            )
            append("\n  共享状态  ").append(
                when {
                    blob == null -> "未定位"
                    blobAddress == 0L -> "只读（拿不到地址，功能套餐无法解锁）"
                    else -> "${blob.capacity()} 字节 @ 0x${blobAddress.toString(16)}"
                },
            )
            append("\n  备份加解密").append(
                if (canRewriteBackup) {
                    " ${encrypt?.declaringClass?.name}.${encrypt?.name}() / " +
                        "${decrypt?.declaringClass?.name}.${decrypt?.name}()"
                } else {
                    " 未定位"
                },
            )
        }
    }

    fun resolve(scope: HookScope): Refs {
        val apkPath = scope.apkPath
        if (apkPath.isNullOrEmpty()) {
            scope.log.w("拿不到 APK 路径，无法扫 dex")
            return Refs()
        }

        // DexKit 在自己的 static 初始化里调 System.loadLibrary("dexkit")，而 LSPosed 给
        // 模块构造的 classloader 下那条路不通。症状有迷惑性：加载当时不报错，等到第一次
        // 调原生方法才抛 "No implementation found for nativeInitDexKit"。先用模块自己那套
        // 带绝对路径兜底的加载器装进来；soname 一个进程只加载一次，DexKit 自己那次随后
        // 就成了空操作。
        if (!NativeHook.loadModuleLibrary("dexkit")) {
            scope.log.e("libdexkit.so 加载不起来（本机 ABI 可能没打进来），无法定位授权链路")
            return Refs()
        }

        val startedAt = SystemClock.elapsedRealtime()
        val bridge = runCatching { DexKitBridge.create(apkPath) }
            .onFailure { scope.log.e("DexKit 打不开 $apkPath：${it.message}") }
            .getOrNull() ?: return Refs()

        val found = bridge.use { dex ->
            val store = bySbufAnchor(scope, dex) ?: byPendingEndAnchor(scope, dex)
            val encrypt = byCommand(scope, dex, Poweramp.ANCHOR_ENCRYPT)
            val decrypt = byCommand(scope, dex, Poweramp.ANCHOR_DECRYPT)
            Triple(store, encrypt, decrypt)
        }
        scope.log.d("dex 扫描用时 ${SystemClock.elapsedRealtime() - startedAt}ms")

        val store = found.first
        if (store == null) {
            scope.log.e("两条锚点都没命中授权存储，目标可能换了实现")
            return Refs(encrypt = found.second, decrypt = found.third)
        }
        return build(scope, store, found.second, found.third)
    }

    // -----------------------------------------------------------------------
    // 锚点
    // -----------------------------------------------------------------------

    /** 授权存储 `<clinit>` 里那条委托属性引用，直接命中类本身。 */
    private fun bySbufAnchor(scope: HookScope, dex: DexKitBridge): Class<*>? =
        runCatching {
            dex.findClass { matcher { addUsingString(Poweramp.ANCHOR_SBUF, StringMatchType.Equals) } }
                .asSequence()
                .mapNotNull { runCatching { it.getInstance(scope.classLoader) }.getOrNull() }
                .firstOrNull { verify(scope, it) }
        }.onFailure { scope.log.w("扫 '${Poweramp.ANCHOR_SBUF}' 失败：${it.message}") }
            .getOrNull()
            ?.also { scope.log.d("按属性引用锚点命中授权存储 ${it.name}") }

    /**
     * 兜底：从「处理待处理购买」那个方法读到的静态字段反推。
     *
     * 那个方法只碰两个静态字段，且都在授权存储上（待处理时间戳、待处理标记），
     * 所以候选最多两个类，逐个按形状复核即可。
     */
    private fun byPendingEndAnchor(scope: HookScope, dex: DexKitBridge): Class<*>? =
        runCatching {
            dex.findMethod {
                matcher { addUsingString(Poweramp.ANCHOR_PENDING_END, StringMatchType.Equals) }
            }
                .asSequence()
                .flatMap { it.usingFields.asSequence() }
                .map { it.field.declaredClassName }
                .distinct()
                .mapNotNull { scope.classOrNull(it) }
                .firstOrNull { verify(scope, it) }
        }.onFailure { scope.log.w("扫 '${Poweramp.ANCHOR_PENDING_END}' 失败：${it.message}") }
            .getOrNull()
            ?.also { scope.log.d("按 Bundle 键锚点命中授权存储 ${it.name}") }

    /**
     * 按交给原生引擎的命令选择子找那个包装方法。
     *
     * `"eS"` 与 `"dS"` 各自在整个 dex 里只出现一次，所以命中即唯一；仍然按形状复核
     * （`static (byte[]) -> byte[]`），免得将来某个无关的类里冒出同名字面量。
     */
    private fun byCommand(scope: HookScope, dex: DexKitBridge, command: String): Method? =
        runCatching {
            dex.findMethod { matcher { addUsingString(command, StringMatchType.Equals) } }
                .asSequence()
                .mapNotNull { runCatching { it.getMethodInstance(scope.classLoader) }.getOrNull() }
                .firstOrNull { it.isByteArrayTransform() }
                ?.apply { isAccessible = true }
        }.onFailure { scope.log.w("扫命令 '$command' 失败：${it.message}") }
            .getOrNull()
            ?: run { scope.log.w("没找到命令 '$command' 的包装方法"); null }

    // -----------------------------------------------------------------------
    // 形状
    // -----------------------------------------------------------------------

    /** 同时拿得出授权结果项与共享状态块，才算是授权存储。 */
    private fun verify(scope: HookScope, candidate: Class<*>): Boolean =
        findLicensePref(scope, candidate) != null && findBlob(scope, candidate) != null

    private fun build(
        scope: HookScope,
        store: Class<*>,
        encrypt: Method?,
        decrypt: Method?,
    ): Refs {
        val pref = findLicensePref(scope, store)
        val blob = findBlob(scope, store)
        val address = blob?.let { NativeHook.directBufferAddress(it) } ?: 0L
        if (blob != null && address == 0L) {
            scope.log.w(
                "拿不到共享状态块的地址（原生层：${NativeHook.lastError ?: "不可用"}）—— " +
                    "完整版仍可解锁，功能套餐不行",
            )
        }
        return Refs(
            store = store,
            licensePref = pref?.first,
            licenseValue = pref?.second,
            blob = blob,
            blobAddress = address,
            encrypt = encrypt,
            decrypt = decrypt,
        )
    }

    /**
     * 授权结果那一项，连同它的「当前值」字段。
     *
     * 判据是**默认值等于 `Integer.MIN_VALUE`**：这个存储里另外四个 int 项
     * （商店编号、待处理时间戳、两个徽章计数）默认值都是 0，只有授权结果需要一个
     * 「还没查过」的哨兵值和「查过，结果是 0」区分开。
     */
    private fun findLicensePref(scope: HookScope, store: Class<*>): Pair<Any, Field>? =
        store.declaredFields.asSequence()
            .mapNotNull { field ->
                val owner = runCatching { field.apply { isAccessible = true }.get(null) }
                    .getOrNull() ?: return@mapNotNull null
                val base = owner.javaClass.superclass ?: return@mapNotNull null
                val ints = base.declaredFields.filter { it.type == Int::class.javaPrimitiveType }
                val default = ints.firstOrNull { it.isPrefDefault() } ?: return@mapNotNull null
                val value = ints.firstOrNull { it.isPrefValue() } ?: return@mapNotNull null
                default.isAccessible = true
                value.isAccessible = true
                val defaultValue = runCatching { default.getInt(owner) }.getOrNull()
                if (defaultValue != Poweramp.LICENSE_PREF_DEFAULT) null else owner to value
            }
            .firstOrNull()
            ?: run { scope.log.d("${store.name} 里没有默认值为 MIN_VALUE 的 int 项"); null }

    /**
     * 与原生共享的状态块。
     *
     * 存储里那个 `ByteBufferPref` 持有的就是它，`Sync.native_process` 把同一块内存
     * 交给了原生侧。按形状找：某个静态项的实例身上挂着一个 direct 的 `ByteBuffer`，
     * 且容量够放下那几个槽位。
     */
    private fun findBlob(scope: HookScope, store: Class<*>): ByteBuffer? =
        store.declaredFields.asSequence()
            .mapNotNull { runCatching { it.apply { isAccessible = true }.get(null) }.getOrNull() }
            .flatMap { owner -> owner.javaClass.declaredFields.asSequence().map { owner to it } }
            .mapNotNull { (owner, field) ->
                if (!ByteBuffer::class.java.isAssignableFrom(field.type)) return@mapNotNull null
                field.isAccessible = true
                runCatching { field.get(owner) as? ByteBuffer }.getOrNull()
            }
            .firstOrNull { it.isDirect && it.capacity() >= Poweramp.BLOB_BYTES }
            ?: run { scope.log.d("${store.name} 里没有 direct 的共享状态块"); null }
}
