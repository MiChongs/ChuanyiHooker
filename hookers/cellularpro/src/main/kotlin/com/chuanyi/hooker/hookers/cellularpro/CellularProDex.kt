package com.chuanyi.hooker.hookers.cellularpro

import android.os.SystemClock
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.hookers.cellularpro.CellularPro.isNativePredicate
import com.chuanyi.hooker.hookers.cellularpro.CellularPro.isRecordField
import com.chuanyi.hooker.hookers.cellularpro.CellularPro.isSelfSingleton
import com.chuanyi.hooker.nativehook.NativeHook
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType

/**
 * 把会员管理器从 dex 里挖出来。
 *
 * ## 为什么要扫
 *
 * 会员管理器叫 `A0B0.m3`、会员记录叫 `A0B0.lg0` —— 包名与类名都是构建期生成的，
 * 写死下一版必然失效。而方法体被 nmmp 虚拟化搬进 `.so` 之后，「按方法引用的字符串
 * 找方法」这条常规路子也断了：那些字面量已经不在 dex 常量池里。
 *
 * 剩下的唯一线索是会员记录构造器里那段反调用方校验 —— 它没被搬走（要读真实调用栈），
 * 所以那句日志串还在 dex 里，且全包唯一。以它命中记录类，再由「谁把记录当字段用」
 * 反查出管理器，两个类名都不用写。
 *
 * ## 只要类名
 *
 * 接管走的是 `RegisterNatives` 期的函数指针替换，[NativeHook.returnConstantOnJniRegister]
 * 需要的正是「类名 + 方法名」这一对。方法名只能按版本写死（见 [CellularPro]），
 * 类名则由这里扫出来 —— 于是重命名结果变了的时候，报出来的是「某某判定不存在」
 * 而不是「什么都没发生」。
 */
internal object CellularProDex {

    /**
     * 一次扫描的产物。
     *
     * [managerClass] 是硬要求 —— 拿不到就没有可挂号的类名。[recordClass] 只用来出日志，
     * 以及在管理器的形状复核里当判据。
     */
    class Refs(
        /** 会员记录类（`A0B0.lg0`）。 */
        val recordClass: Class<*>? = null,
        /** 会员管理器类（`A0B0.m3`）。 */
        val managerClass: Class<*>? = null,
    ) {
        val isReady: Boolean get() = managerClass != null

        fun describe(): String = buildString {
            append("\n  会员记录  ").append(recordClass?.name ?: "未定位")
            append("\n  会员管理  ").append(managerClass?.name ?: "未定位")
        }
    }

    fun resolve(scope: HookScope): Refs {
        val apkPath = scope.apkPath
        if (apkPath.isNullOrEmpty()) {
            scope.log.w("拿不到 APK 路径，无法扫 dex")
            return Refs()
        }

        // DexKit 2.x 不再在静态初始化里 loadLibrary，且 LSPosed 给模块构造的 classloader
        // 下 System.loadLibrary 未必找得到模块自己的 .so。先用模块那套带绝对路径兜底的
        // 加载器装进来；soname 一个进程只加载一次，之后 DexKit 自己那一次就成了空操作。
        if (!NativeHook.loadModuleLibrary("dexkit")) {
            scope.log.e("libdexkit.so 加载不起来（本机 ABI 可能没打进来），无法定位会员链路")
            return Refs()
        }

        val startedAt = SystemClock.elapsedRealtime()
        val names = runCatching { DexKitBridge.create(apkPath) }
            .onFailure { scope.log.e("DexKit 打不开 $apkPath：${it.message}") }
            .getOrNull()
            ?.use { dex ->
                val record = findRecordClassName(scope, dex)
                record to record?.let { findManagerClassName(scope, dex, it) }
            } ?: (null to null)
        scope.log.d("dex 扫描用时 ${SystemClock.elapsedRealtime() - startedAt}ms")

        val record = names.first?.let { scope.classOrNull(it) }
        if (record == null) {
            scope.log.e("没定位到会员记录类（锚点 '${CellularPro.ANCHOR_RECORD_GUARD}' 未命中）")
            return Refs()
        }
        val manager = names.second?.let { scope.classOrNull(it) }
        if (manager == null) scope.log.e("定位到了会员记录 ${record.name}，但没找到持有它的管理器")
        return Refs(recordClass = record, managerClass = manager)
    }

    /**
     * 会员记录类 —— 构造器里那句反调用方校验的日志串直接命中。
     *
     * 用 [StringMatchType.Equals]：包含匹配会连上 R8 横向合并出来的共享类。
     * 再要求同一个类里也有抛异常那句，作为复核。
     */
    private fun findRecordClassName(scope: HookScope, dex: DexKitBridge): String? =
        runCatching {
            dex.findClass {
                matcher {
                    addUsingString(CellularPro.ANCHOR_RECORD_GUARD, StringMatchType.Equals)
                    addUsingString(CellularPro.ANCHOR_RECORD_GUARD_THROW, StringMatchType.Equals)
                }
            }.singleOrNull()?.name
        }.onFailure { scope.log.w("扫锚点失败：${it.message}") }
            .getOrNull()
            ?.also { scope.log.d("按构造器锚点命中会员记录 $it") }

    /**
     * 会员管理器 —— **持有会员记录字段**的那个类。
     *
     * 记录自己不指回管理器，只能从字段这一侧反查：`findField { type = 记录类 }` 命中的
     * 字段，其声明类就是候选。命中通常不止一个（记录类型也出现在回调参数、局部变量里），
     * 所以再按形状复核，见 [isManagerShape]。
     */
    private fun findManagerClassName(scope: HookScope, dex: DexKitBridge, recordName: String): String? {
        val owners = runCatching {
            dex.findField { matcher { type = recordName } }
                .map { it.declaredClassName }
                .distinct()
        }.onFailure { scope.log.w("反查持有会员记录的类失败：${it.message}") }
            .getOrNull()
            .orEmpty()

        if (owners.isEmpty()) {
            scope.log.w("没有任何类把 $recordName 当字段用")
            return null
        }
        scope.log.d("持有会员记录的候选：${owners.joinToString()}")

        val record = scope.classOrNull(recordName)
        return owners.firstOrNull { name ->
            scope.classOrNull(name)?.let { isManagerShape(it, record) } == true
        } ?: run { scope.log.w("${owners.size} 个候选都不满足管理器的形状"); null }
    }

    /**
     * 管理器的形状，三条同时成立：
     *
     * * 有一个类型为记录类的**实例**字段 —— 当前的会员记录；
     * * 有一个类型是它自己的**静态**字段 —— 单例；
     * * [CellularPro.TIER_PREDICATES] 与 [CellularPro.CHANNEL_PREDICATES] 里的判定至少认得一半 ——
     *   这一条同时也是版本核对：全都对不上说明重命名结果已经变了。
     */
    private fun isManagerShape(candidate: Class<*>, record: Class<*>?): Boolean {
        if (record == null) return false
        val fields = runCatching { candidate.declaredFields }.getOrNull() ?: return false
        if (fields.none { it.isRecordField(record) }) return false
        if (fields.none { it.isSelfSingleton(candidate) }) return false

        val predicates = runCatching { candidate.declaredMethods }.getOrNull()
            ?.filter { it.isNativePredicate() }
            ?.mapTo(HashSet()) { it.name }
            ?: return false
        val wanted = CellularPro.TIER_PREDICATES + CellularPro.CHANNEL_PREDICATES
        return wanted.count { it in predicates } * 2 >= wanted.size
    }
}
