package com.chuanyi.hooker.hookers.tgguard

import android.content.Context
import android.content.SharedPreferences

/**
 * 认得出来的 TG 客户端，以及怎么定位到**当前账号**的会话库。
 *
 * 名单里全是 **Telegram-Android 的分支**。挑判据的时候正是冲着这一点去的：它们共用
 * 上游那套 `MessagesStorage` / `UserConfig` / `SharedConfig`，会话库的位置、多账号的
 * 分目录规则、当前账号存在哪，全都一致，所以一套读法覆盖全部，加一个新分支只要往
 * 下面那张表里补一行包名。
 *
 * **Telegram X（`org.thunderdog.challegram`）不在名单里**，它是 TDLib 系，本地库
 * 完全是另一套格式，同一套读法对它无效 —— 与其读出个「没有」把人误锁在外面，不如
 * 不认它。
 */
internal object TelegramClients {

    /**
     * 与 `META-INF/xposed/scope.list` 和 manifest 的 `<queries>` 必须逐条一致，
     * 这三处由 `:checkHookerScope` 在构建期比对。
     *
     * 名单**宁可多列**：列进来但没装的包，代价只是 LSPosed 作用域界面上少一个条目
     * （不显示未安装的应用）和几十字节的清单；漏掉一个分支的代价却是那个人明明在群里
     * 却永远激活不了，而且界面会告诉他「没有受支持的客户端」，方向完全错。
     *
     * 同一个 App 出现两个包名是常态：分支换过署名、或者 Play 版与直装版分开发布，
     * 两个都在野外跑着。下面成对出现的（Nagram、exteraGram）就是这种情况。
     */
    val PACKAGES: Set<String> = linkedSetOf(
        // --- 官方 ---
        "org.telegram.messenger",           // Telegram（Google Play）
        "org.telegram.messenger.web",       // Telegram（telegram.org 直装包）
        "org.telegram.messenger.beta",      // Telegram Beta
        "org.telegram.android",             // 早期包名，个别老分支仍在用
        "org.telegram.group",               // Telegram Group
        "org.telegram.plus",                // Plus Messenger

        // --- Neko 系 ---
        "nekox.messenger",                  // NekoX
        "tw.nekomimi.nekogram",             // Nekogram
        "xyz.nextalone.nagram",             // Nagram
        "nu.gpu.nagram",                    // Nagram（另一署名）
        "fork.risin42.nagramx",             // NagramX
        "top.qwq2333.nullgram",             // Nullgram

        // --- 其余分支 ---
        "it.owlgram.android",               // OwlGram
        "it.octogram.android",              // OctoGram
        "uz.unnarsx.cherrygram",            // Cherrygram
        "uz.unnarsx.cherrygram.beta",       // Cherrygram Beta
        "com.exteragram.messenger",         // exteraGram
        "com.exteragram.messenger.beta",    // exteraGram Beta
        "com.radolyn.ayugram",              // AyuGram
        "org.monogram",                     // Monogram
        "ua.itaysonlab.catogram",           // CatogramX
        "ua.itaysonlab.messenger",          // Catogram
        "org.telegram.mdgram",              // MDGram
        "app.nicegram",                     // Nicegram
        "com.iMe.android",                  // iMe Messenger
        "org.vidogram.messenger",           // Vidogram
        "org.telegram.BifToGram",           // BifToGram

        // --- 波斯语区的几个老分支，装机量仍然不小 ---
        "ir.ilmili.telegraph",              // Graph Messenger（Telegram طلایی）
        "com.hanista.mobogram",             // Mobogram
        "org.mobogram.messenger",           // Mobogram（另一署名）
        "ir.persianfox.messenger",          // PersianFox
        "com.wtelegram.messenger",          // WTelegram
    )

    /**
     * 账号 0 的 `UserConfig` 存在这个名字下 —— **`userconfing`，少一个 i**。
     *
     * 这不是笔误，是上游 Telegram-Android 里一个存在多年、且改不掉（改了就丢配置）的
     * 拼写错误：
     *
     * ```java
     * // UserConfig.getPreferences()
     * if (currentAccount == 0) {
     *     return ApplicationLoader.applicationContext
     *             .getSharedPreferences("userconfing", Context.MODE_PRIVATE);   // sic
     * } else {
     *     return ApplicationLoader.applicationContext
     *             .getSharedPreferences("userconfig" + currentAccount, Context.MODE_PRIVATE);
     * }
     * ```
     *
     * **当前选中的账号也存在这里**（`selectedAccount`），不在 `mainconfig` 里 ——
     * 猜成 `mainconfig` 是这套校验第一版没能跑通的唯一原因：读不到就退回 0，而多账号
     * 用户的账号 0 槽位往往是个 4 KiB 的空壳库，探测因此永远得不出结论。
     */
    private fun prefsNameOf(account: Int): String =
        if (account == 0) "userconfing" else "userconfig$account"

    /** 备用位置。个别分支把它挪走过，多试一处不花钱。 */
    private const val MAIN_CONFIG = "mainconfig"

    private const val KEY_SELECTED_ACCOUNT = "selectedAccount"

    /**
     * 登录判据：`UserConfig.saveConfig()` 把序列化后的 `TLRPC.User` 存在这个键下，
     * 没登录的槽位没有这个键。
     */
    private const val KEY_USER = "user"

    /** 账号槽位的上限。官方 4 个，分支通常放宽到 8～16，取个够用的上界。 */
    const val MAX_ACCOUNTS = 16

    /** 这个槽位当前登录着没有。 */
    private fun isLoggedIn(context: Context, account: Int): Boolean = runCatching {
        !context.getSharedPreferences(prefsNameOf(account), Context.MODE_PRIVATE)
            .getString(KEY_USER, null)
            .isNullOrEmpty()
    }.getOrDefault(false)

    /** 当前登录着的全部槽位，升序。 */
    fun loggedInAccounts(context: Context): List<Int> =
        (0 until MAX_ACCOUNTS).filter { isLoggedIn(context, it) }

    /**
     * 当前正在使用的账号槽位；**确定不了就返回 null**，绝不猜一个。
     *
     * 猜错的代价是不对称的：猜到一个空壳槽位，探测永远得不出结论，用户在群里却一直
     * 激活不了，而界面还会告诉他别的原因。所以宁可这一轮什么都不做，下一轮重来。
     *
     * 三级解析，从最权威到最兜底：
     *
     * 1. **反射读目标进程内存里的 `UserConfig.selectedAccount`。**最准，而且不依赖
     *    任何文件名 —— 配置文件还没落盘时它也是对的。Telegram 及其分支都不混淆类名，
     *    这个字段十年没动过。
     * 2. **读配置文件。**反射失败（分支重构过、或者类还没加载）时兜住。
     * 3. **只有一个账号登录着，那就是它。**前两条都失败时，这个结论没有歧义。
     *
     * 每一级的结果都要求「该槽位确实登录着」才采纳 —— 退出登录之后 `selectedAccount`
     * 会短暂地指向一个已经没人的槽位。
     */
    fun currentAccount(context: Context, classLoader: ClassLoader): Int? {
        val loggedIn = loggedInAccounts(context)
        if (loggedIn.isEmpty()) return null

        selectedAccountFromRuntime(classLoader)?.takeIf { it in loggedIn }?.let { return it }
        selectedAccountFromPrefs(context)?.takeIf { it in loggedIn }?.let { return it }
        return loggedIn.singleOrNull()
    }

    /** `org.telegram.messenger.UserConfig.selectedAccount`，一个公开静态字段。 */
    private fun selectedAccountFromRuntime(classLoader: ClassLoader): Int? = runCatching {
        Class.forName("org.telegram.messenger.UserConfig", false, classLoader)
            .getDeclaredField(KEY_SELECTED_ACCOUNT)
            .apply { isAccessible = true }
            .getInt(null)
    }.getOrNull()?.takeIf { it in 0 until MAX_ACCOUNTS }

    /**
     * 盯住「当前账号变了」这件事，变了立刻回调。
     *
     * 切账号时上游走的是：
     *
     * ```java
     * // LaunchActivity.switchToAccount()
     * UserConfig.selectedAccount = account;
     * UserConfig.getInstance(0).saveConfig(false);   // -> 落盘到 userconfing
     * ```
     *
     * 所以监听那个文件的 `selectedAccount` 就够了，**不需要认目标的任何一个类**——
     * 这条路对全部分支都成立，而 hook `switchToAccount` 之类的方法一换分支就废。
     * 退出登录同样会写这个文件（Telegram 会把当前账号切到别的槽位），一并覆盖。
     *
     * 只对这两个键起反应：`userconfing` 里还塞着 `dialogsLoadOffset*`、`lastLocalId`
     * 这类高频写入的东西，见什么都醒会把探测变成一个忙循环。
     *
     * @return 注册好的监听器。**调用方必须持有强引用** —— SharedPreferences 用
     *   WeakHashMap 存监听器，不留引用的话下一次 GC 就没了，而且不会有任何提示。
     */
    fun watchAccountChanges(
        context: Context,
        onChanged: () -> Unit,
    ): SharedPreferences.OnSharedPreferenceChangeListener? {
        val prefs = runCatching {
            context.getSharedPreferences(prefsNameOf(0), Context.MODE_PRIVATE)
        }.getOrNull() ?: return null

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_SELECTED_ACCOUNT || key == KEY_USER) onChanged()
        }
        return runCatching {
            prefs.registerOnSharedPreferenceChangeListener(listener)
            listener
        }.getOrNull()
    }

    private fun selectedAccountFromPrefs(context: Context): Int? {
        for (name in arrayOf(prefsNameOf(0), MAIN_CONFIG)) {
            val value = runCatching {
                context.getSharedPreferences(name, Context.MODE_PRIVATE)
                    .getInt(KEY_SELECTED_ACCOUNT, -1)
            }.getOrDefault(-1)
            if (value in 0 until MAX_ACCOUNTS) return value
        }
        return null
    }

    /**
     * 某个账号槽位的会话库路径。
     *
     * 上游 `MessagesStorage` 的分目录规则：
     *
     * ```java
     * File filesDir = ApplicationLoader.getFilesDirFixed();
     * if (currentAccount != 0) filesDir = new File(filesDir, "account" + currentAccount + "/");
     * cacheFile = new File(filesDir, "cache4.db");
     * ```
     */
    fun databaseOf(dataDir: String, account: Int): String {
        val files = "$dataDir/files"
        return if (account <= 0) "$files/cache4.db" else "$files/account$account/cache4.db"
    }
}
