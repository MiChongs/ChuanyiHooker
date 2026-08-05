package com.chuanyi.hooker.hookers.skypulse

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.hookers.skypulse.SkyPulseDex.ownerInstance
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createInterceptHook
import java.io.File
import java.lang.reflect.Method
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey

/**
 * 目标那套 `EncryptedSharedPreferences` 的护栏。
 *
 * 会员判定的第一件事就是打开加密存储，而那个工厂方法**没有任何 try-catch** ——
 * 它一路声明 `throws GeneralSecurityException, IOException` 直接往外抛，
 * 上层从判定到 ViewModel 也没人接。于是主密钥一旦失效，应用在 `MainActivity` 的
 * 第一帧就 `FATAL EXCEPTION: main`：
 *
 * ```
 * Caused by: android.security.keystore.KeyPermanentlyInvalidatedException: Key permanently invalidated
 *     at androidx.security.crypto…  ← 目标的加密存储工厂
 * ```
 *
 * AndroidKeyStore 判一把密钥「永久失效」的诱因不止一个 —— 锁屏凭据变更、生物识别重新
 * 录入、应用卸载重装后 uid 被复用而旧条目残留 —— 共同点是**应用自己无力恢复**：
 * 主密钥别名固定，下次启动照样取到那把死掉的密钥，于是每次冷启动都崩，成砖。
 *
 * 这层做两件事：
 *
 * 1. [guard] 挂在工厂方法上，把那次抛出接住，清掉失效的密钥和再也解不开的密文文件，
 *    然后让它重新走一遍。对应用来说就是「加密存储是空的」—— 那是它自己处理得了的状态。
 * 2. [openOrRepair] 给模块自己用。模块要往同一个存储里写永久凭证，不能假设它开得开。
 *
 * 代价说清楚：旧密钥没了，之前存进去的凭证也就解不开了，等于回到未激活。对本模块的
 * 使用场景没有损失（凭证本来就是模块签的，重签一张即可）；对真付费用户，应用自己会在
 * 下次联网时用 `reissue` 把凭证补回来。**崩着不能用**才是更大的损失。
 */
internal object SecureStore {

    /**
     * `androidx.security` 的默认主密钥别名。
     *
     * 目标用的是 `MasterKey.Builder(context)` 那条默认路径 —— 没传自定义别名，
     * 所以就是这个常量。别名对不上时 [deleteMasterKeys] 还会兜底扫一遍整个 keystore。
     */
    private const val DEFAULT_MASTER_KEY_ALIAS = "_androidx_security_master_key_"

    /**
     * 开工前先体检一次：主密钥还在、但已经用不了，就当场清掉。
     *
     * 这条**不依赖 DexKit，也不需要 Context** —— 别名是 `androidx.security` 的常量，
     * 数据目录从 [android.content.pm.ApplicationInfo.dataDir] 就能拿到，而那个在
     * `PACKAGE_READY` 阶段已经有值。这一点是有意为之：dex 扫描失败（换了 ABI、
     * 目标结构大改）时其余功能可以全部歇菜，**但防闪退这条不能跟着一起歇** ——
     * 崩溃比少解锁一个功能严重得多。
     *
     * 时机也正好：跑在应用自己第一次打开加密存储之前，等于把雷提前拆了，
     * [guard] 那条接住式的路就只是兜底。
     *
     * @return 是否做了清理
     */
    fun verifyOrRepair(scope: HookScope): Boolean {
        val keyStore = runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        }.getOrElse {
            scope.log.d("打不开 AndroidKeyStore，跳过体检：${it.message}")
            return false
        }

        // 还没建过就没什么可坏的 —— 应用会自己建一把新的。
        if (!runCatching { keyStore.containsAlias(DEFAULT_MASTER_KEY_ALIAS) }.getOrDefault(false)) {
            scope.log.d("主密钥尚未创建，无需体检")
            return false
        }

        val failure = runCatching {
            val key = keyStore.getKey(DEFAULT_MASTER_KEY_ALIAS, null) as? SecretKey
                ?: error("$DEFAULT_MASTER_KEY_ALIAS 不是对称密钥")
            // androidx.security 的主密钥是 AES-256-GCM，拿它初始化一次就够 ——
            // 密钥失效正是在 Cipher.init 这一步暴露出来的，和应用崩的是同一行。
            Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.ENCRYPT_MODE, key)
        }.exceptionOrNull()

        if (failure == null) {
            scope.log.d("主密钥可用")
            return false
        }
        if (!failure.isKeyInvalidated()) {
            scope.log.w("主密钥体检异常（不是失效，保持原样）：${failure.message}")
            return false
        }

        scope.log.w("主密钥已永久失效 —— 应用会在启动第一帧闪退，正在清理")
        repair(scope, scope.appInfo.dataDir)
        return true
    }

    /**
     * 把工厂方法的抛出接住并自愈。[verifyOrRepair] 的兜底。
     *
     * 体检覆盖不到的情况仍然存在：密钥在应用运行期间才失效（用户当场改了锁屏），
     * 或者应用用的是自定义别名。这条挂在方法上，谁调都算数。
     *
     * 只接「密钥失效」这一类。其余异常原样放行 —— 把所有失败都吞掉会让真正的问题
     * （存储损坏、权限缺失）变成一个空存储，比崩溃更难查。
     */
    fun guard(scope: HookScope, factory: Method) {
        factory.createInterceptHook("skypulse.securestore.guard") { chain ->
            runCatching { chain.proceed() }.getOrElse { error ->
                if (!error.isKeyInvalidated()) throw error

                scope.log.w("加密存储的主密钥已失效，正在清理并重建 —— 这次会回到未激活状态")
                val dataDir = (chain.getArg(0) as? Context)?.applicationInfo?.dataDir
                    ?: scope.appInfo.dataDir
                repair(scope, dataDir)

                // 重来一次。密钥和密文都清掉了，这次会新建一把。
                chain.proceed()
            }
        }
        scope.log.d("加密存储护栏已挂在 ${factory.declaringClass.name}.${factory.name}")
    }

    /**
     * 打开目标的加密存储，开不开就修一次再开。
     *
     * [guard] 装上之后这里通常一次就成 —— 留着这条是因为两个功能可以各自单独开关，
     * 谁都不该假设另一个在。
     */
    fun openOrRepair(
        scope: HookScope,
        refs: SkyPulseDex.Refs,
        context: Context,
    ): SharedPreferences? {
        val factory = refs.securePrefs ?: return null

        fun open(): Result<SharedPreferences?> = runCatching {
            factory.invoke(factory.ownerInstance(), context) as? SharedPreferences
        }

        open().onSuccess { if (it != null) return it }
            .onFailure { error ->
                if (!error.isKeyInvalidated()) {
                    scope.log.w("打不开加密存储：${error.message}")
                    return null
                }
                scope.log.w("加密存储的主密钥已失效，正在清理并重建")
                repair(scope, context.applicationInfo.dataDir)
            }

        return open().onFailure { scope.log.e("重建之后仍然打不开加密存储", it) }.getOrNull()
    }

    // -----------------------------------------------------------------------

    /**
     * 清掉失效的密钥，以及用它加密、现在谁也解不开的那个文件。
     *
     * 顺序要紧：**先删文件再删密钥**。反过来的话，万一删密钥之后进程没了，
     * 下次启动会拿一把新密钥去解旧密文，那是另一种解不开 —— 而且这次连
     * `KeyPermanentlyInvalidatedException` 都不抛，只抛一个更难认的解密失败。
     */
    private fun repair(scope: HookScope, dataDir: String?) {
        deleteEncryptedPrefs(scope, dataDir)
        deleteMasterKeys(scope)
    }

    private fun deleteEncryptedPrefs(scope: HookScope, dataDir: String?) {
        if (dataDir.isNullOrEmpty()) {
            scope.log.w("不知道数据目录在哪，密文文件留在原地")
            return
        }
        // 走文件层而不是 getSharedPreferences：后者会把内容加载进 ContextImpl 的进程级
        // 缓存，之后删文件也去不掉那份缓存。删文件之后目标再创建时读到的是「不存在」，
        // 正是我们要的状态。
        val dir = File(dataDir, "shared_prefs")
        val name = SkyPulseDex.PREFS_NAME
        listOf(File(dir, "$name.xml"), File(dir, "$name.xml.bak"))
            .filter { it.exists() }
            .forEach { file ->
                val gone = runCatching { file.delete() }.getOrDefault(false)
                scope.log.i("${if (gone) "已删除" else "删不掉"}解不开的加密存储：${file.name}")
            }
    }

    /**
     * 删掉 AndroidKeyStore 里的主密钥。
     *
     * 先按默认别名删；没有的话再扫一遍整个 keystore，把带 `master_key` 字样的都删掉 ——
     * 应用换过别名时这条兜得住，而这个 keystore 是应用私有的，里面不会有别人的东西。
     */
    private fun deleteMasterKeys(scope: HookScope) {
        val keyStore = runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        }.getOrElse {
            scope.log.w("打不开 AndroidKeyStore：${it.message}")
            return
        }

        fun delete(alias: String): Boolean =
            runCatching { keyStore.deleteEntry(alias); true }
                .onFailure { scope.log.w("删不掉密钥 $alias：${it.message}") }
                .getOrDefault(false)

        if (runCatching { keyStore.containsAlias(DEFAULT_MASTER_KEY_ALIAS) }.getOrDefault(false)) {
            if (delete(DEFAULT_MASTER_KEY_ALIAS)) {
                scope.log.i("已删除失效的主密钥 $DEFAULT_MASTER_KEY_ALIAS")
                return
            }
        }

        val others = runCatching { keyStore.aliases().toList() }.getOrDefault(emptyList())
            .filter { it.contains("master_key", ignoreCase = true) }
        if (others.isEmpty()) {
            scope.log.w("keystore 里没有找到主密钥；别名可能是自定义的")
            return
        }
        others.forEach { if (delete(it)) scope.log.i("已删除失效的主密钥 $it") }
    }

    // -----------------------------------------------------------------------

    /**
     * 这条异常链里有没有「密钥永久失效」。
     *
     * 要走整条 cause 链：`androidx.security` 把它包在 `GeneralSecurityException` 里，
     * 而 Hilt 和 Compose 又各自再包一层，实际深度是四五层。类名兜底那条是为
     * 厂商 ROM 换了实现类的情况留的。
     */
    private fun Throwable.isKeyInvalidated(): Boolean =
        generateSequence(this) { it.cause }
            .take(16) // cause 链理论上可以成环，别转不出来
            .any {
                it is KeyPermanentlyInvalidatedException ||
                    it.javaClass.name.endsWith("KeyPermanentlyInvalidatedException")
            }

}
