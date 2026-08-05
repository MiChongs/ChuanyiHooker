package com.chuanyi.hooker.hookers.tgguard

/**
 * 认得出来的 TG 客户端，以及它们把会话库放在哪。
 *
 * 名单里全是 **Telegram-Android 的分支**。挑判据的时候正是冲着这一点去的：它们共用
 * 上游那套 `MessagesStorage`，会话和群都存在同一个 `cache4.db` 里、表结构也一致，
 * 所以一套读法覆盖全部，加一个新分支只要往下面这张表里补一行包名。
 *
 * **Telegram X（`org.thunderdog.challegram`）不在名单里**，它是 TDLib 系，本地库
 * 完全是另一套格式，同一套读法对它无效 —— 与其读出个「没有」把人误锁在外面，不如
 * 不认它。用 Telegram X 的人需要装一个上面名单里的客户端登一次。
 */
internal object TelegramClients {

    /**
     * 与 `META-INF/xposed/scope.list` 和 manifest 的 `<queries>` 必须逐条一致，
     * 这三处由 `:checkHookerScope` 在构建期比对。
     */
    val PACKAGES: Set<String> = linkedSetOf(
        "org.telegram.messenger",       // 官方（Google Play）
        "org.telegram.messenger.web",   // 官方（telegram.org 直装包）
        "org.telegram.messenger.beta",  // 官方 Beta
        "org.telegram.plus",            // Plus Messenger
        "nekox.messenger",              // NekoX
        "tw.nekomimi.nekogram",         // Nekogram
        "xyz.nextalone.nagram",         // Nagram
        "it.owlgram.android",           // OwlGram
        "uz.unnarsx.cherrygram",        // Cherrygram
        "com.exteragram.messenger",     // exteraGram
        "org.telegram.mdgram",          // MDGram
        "ir.ilmili.telegraph",          // Graph Messenger
    )

    /**
     * 多账号的上限。上游 `MessagesStorage` 是这么分目录的：
     *
     * ```java
     * File filesDir = ApplicationLoader.getFilesDirFixed();
     * if (currentAccount != 0) filesDir = new File(filesDir, "account" + currentAccount + "/");
     * cacheFile = new File(filesDir, "cache4.db");
     * ```
     *
     * 也就是说主账号在 `files/cache4.db`，其余每个账号各有一个 `files/accountN/`。
     * 只查主账号会漏掉「用小号进的群」这种再正常不过的情况，所以整排都查。
     * 取 8 是留了余量：官方免费 3 个、Premium 4 个，分支通常放宽到 6～8。
     */
    private const val MAX_ACCOUNTS = 8

    /**
     * 这个客户端可能存在的全部会话库路径，主账号排在最前。
     *
     * 不做存在性过滤 —— 判存在要 stat，而调用方无论如何都要挨个交给原生层去开，
     * 开不开得了那边自然会说。
     */
    fun databaseCandidates(dataDir: String): List<String> {
        val files = "$dataDir/files"
        return buildList(MAX_ACCOUNTS) {
            add("$files/cache4.db")
            for (account in 1 until MAX_ACCOUNTS) add("$files/account$account/cache4.db")
        }
    }
}
