package com.chuanyi.hooker.nativehook

/**
 * Kotlin face of the Dobby-backed native layer.
 *
 * Loading is lazy and never fatal: a hooker that only needs Java hooks keeps
 * working on a device where the .so cannot be loaded, and every method here
 * returns a harmless value instead of throwing.
 *
 * Two levels of capability:
 *
 *  * From Kotlin — [findSymbol], [moduleBase], [readMemory], [patchMemory] and
 *    [returnConstant]. Enough to neutralise a native check or read a struct
 *    without writing any C++.
 *  * From C++ — register a `NativeHooker` (see `include/chuanyi/native_hook.h`)
 *    when a target needs a real inline hook with its own replacement, then call
 *    [install] with its id.
 */
object NativeHook {

    private const val LIBRARY = "chuanyihook"
    private const val FILE_NAME = "lib$LIBRARY.so"

    /** null = not tried yet, true/false = outcome of the single load attempt. */
    @Volatile
    private var loadResult: Boolean? = null

    /**
     * 模块自身的 `nativeLibraryDir`，由 HookerRuntime 在 attach 时注入。
     *
     * 只在 [System.loadLibrary] 走不通时用作兜底，见 [tryLoad]。
     */
    @Volatile
    private var fallbackLibraryDir: String? = null

    @Volatile
    var lastError: String? = null
        private set

    /** True once libchuanyihook.so is loaded and its JNI methods are bound. */
    val isAvailable: Boolean
        get() = loadResult ?: synchronized(this) {
            loadResult ?: tryLoad().also { loadResult = it }
        }

    /**
     * 告诉 [NativeHook] 模块 APK 解压出来的 native 库目录在哪。
     *
     * 必须在第一次碰 [isAvailable] 之前调用，否则加载已经定型。
     */
    fun setFallbackLibraryDir(path: String?) {
        if (loadResult == null) fallbackLibraryDir = path
    }

    /**
     * 两条加载路径，对应两种打包方式，缺一不可：
     *
     * 1. `System.loadLibrary` 走当前 classloader 的 native 搜索路径。LSPosed 给模块
     *    构造的 classloader 指向的是 APK **包内**路径（`base.apk!/lib/<abi>`），只有
     *    `extractNativeLibs=false`（未压缩 + 页对齐）时 linker 才能从那里 mmap。
     * 2. 兜底：直接 load 模块 `nativeLibraryDir` 下的绝对路径。这条覆盖
     *    `extractNativeLibs=true` 的包——那种包安装时把 .so 解压到磁盘，包内路径反而
     *    是空的。该目录是 world-readable，目标进程读得到。
     *
     * 两条都失败才算不可用，[lastError] 里会带上两次的原因，避免只看到后一条。
     */
    private fun tryLoad(): Boolean {
        val primary = runCatching { System.loadLibrary(LIBRARY) }
        if (primary.isSuccess) return true

        val dir = fallbackLibraryDir
        if (!dir.isNullOrEmpty()) {
            val path = "$dir/$FILE_NAME"
            val fallback = runCatching { System.load(path) }
            if (fallback.isSuccess) return true
            lastError = "loadLibrary: ${primary.reason()} / load($path): ${fallback.reason()}"
            return false
        }

        // UnsatisfiedLinkError on an ABI we did not ship, SecurityException in
        // a restricted process; either way the Java hooks should still run.
        lastError = primary.reason()
        return false
    }

    private fun Result<*>.reason(): String =
        exceptionOrNull()?.let { it.message ?: it.javaClass.name } ?: "unknown"

    /**
     * Loads another native library shipped inside the module APK, by soname.
     *
     * Same two paths as [tryLoad], and it exists because the first of them is
     * unreliable here: LSPosed's `LspModuleClassLoader` points at the APK-internal
     * `base.apk!/lib/<abi>`, and `System.loadLibrary` does not always resolve
     * through it — `libchuanyihook.so` itself routinely arrives via the absolute
     * path instead.
     *
     * A third-party library that calls `System.loadLibrary` from its own static
     * initialiser has no such fallback: it silently ends up with no
     * implementation bound, and the failure only surfaces later as
     * `UnsatisfiedLinkError: No implementation found for …` at the first native
     * call. DexKit is exactly that shape, so load its `.so` through here first
     * and the library's own attempt becomes a no-op — a soname is only ever
     * loaded once per process.
     *
     * @param name soname without the `lib` prefix or `.so` suffix
     */
    fun loadModuleLibrary(name: String): Boolean {
        if (runCatching { System.loadLibrary(name) }.isSuccess) return true
        val dir = fallbackLibraryDir ?: return false
        return runCatching { System.load("$dir/lib$name.so") }.isSuccess
    }

    /** Mirrors the module's verbose-log switch into the native logger. */
    fun setVerbose(verbose: Boolean) {
        if (!isAvailable) return
        runCatching { nativeSetVerbose(verbose) }
    }

    /**
     * Address of [symbol] in [library] (e.g. `"libflutter.so"`), or 0.
     *
     * Resolves non-exported symbols too — it walks the ELF symbol tables rather
     * than going through `dlsym`. Pass a null [library] to search every loaded
     * image.
     */
    fun findSymbol(library: String?, symbol: String): Long =
        if (!isAvailable) 0L else runCatching { nativeFindSymbol(library, symbol) }.getOrDefault(0L)

    /** Load address of [library], or 0 if it is not mapped. */
    fun moduleBase(library: String): Long =
        if (!isAvailable) 0L else runCatching { nativeModuleBase(library) }.getOrDefault(0L)

    /** Reads [size] bytes at [address]; null when the range is not readable. */
    fun readMemory(address: Long, size: Int): ByteArray? {
        if (!isAvailable || address == 0L || size <= 0) return null
        return runCatching { nativeReadMemory(address, size) }.getOrNull()
    }

    /**
     * 改写 [address] 处的**指令**，负责页权限与 icache 失效。
     *
     * 底层是 `DobbyCodePatch`，它无论原本是什么权限，写完都把页恢复成
     * `PROT_READ|PROT_EXEC`。对代码页这是对的，对数据页是**致命**的：应用下一次
     * 正常写那一页就死于 `SEGV_ACCERR`，且崩溃点离这里很远、时间也晚得多。
     *
     * 所以只有指令流才用这个，其余一律用 [writeMemory]。
     */
    fun patchMemory(address: Long, bytes: ByteArray): Boolean {
        if (!isAvailable || address == 0L || bytes.isEmpty()) return false
        return runCatching { nativePatchMemory(address, bytes) }.getOrDefault(false)
    }

    /**
     * 改写 [address] 处的**数据**。
     *
     * 先从 `/proc/self/maps` 读该映射的真实权限，只补上 `PROT_WRITE`，写完原样恢复 ——
     * 可写的数据页写完仍然可写。跨页范围会一并处理，不动 icache，写后回读校验。
     *
     * 这是改 `.data` / `.bss` 里那些标志位、缓存字段、函数指针的正确入口。
     * 用 [patchMemory] 改它们会把页变成不可写，见那边的说明。
     */
    fun writeMemory(address: Long, bytes: ByteArray): Boolean {
        if (!isAvailable || address == 0L || bytes.isEmpty()) return false
        return runCatching { nativeWriteMemory(address, bytes) }.getOrDefault(false)
    }

    /**
     * Runtime address of [pattern] inside [library]'s executable segments, or 0.
     *
     * [findSymbol] for a library that has no symbols. A Dart AOT `libapp.so` is
     * the reason this exists: its code is one anonymous blob, so a patch site can
     * only be named by an address — and a hard-coded address is invalidated by
     * the target's next release, while a byte pattern lifted from the function's
     * *body* survives it.
     *
     * Only executable PT_LOAD segments are searched, so the Dart heap — where the
     * same bytes routinely appear once the isolate has deserialized — cannot
     * produce a false hit, and the scan stays in the low milliseconds.
     *
     * Pick a pattern that does **not** cover the bytes you are going to write:
     * anchor on the body, patch the entry. Overlapping the two breaks re-running
     * the search, and with it any verification that the patch is still where it
     * was.
     *
     * @param skip return the (skip+1)-th match instead of the first
     */
    fun findPattern(library: String, pattern: ByteArray, skip: Int = 0): Long {
        if (!isAvailable || library.isEmpty() || pattern.isEmpty()) return 0L
        return runCatching { nativeFindPatternInModule(library, pattern, skip) }.getOrDefault(0L)
    }

    /**
     * How many times [pattern] occurs in [library]'s executable segments; -1 when
     * the native layer is unavailable.
     *
     * Uniqueness is what makes a pattern a usable locator, so assert it at
     * install time rather than trusting the first hit — a pattern that has picked
     * up a second match after an app update is a signal to re-derive it, not to
     * patch blindly.
     */
    fun countPattern(library: String, pattern: ByteArray): Int {
        if (!isAvailable || library.isEmpty() || pattern.isEmpty()) return -1
        return runCatching { nativeCountPatternInModule(library, pattern) }.getOrDefault(-1)
    }

    /**
     * 内存扫描的范围。目标把字符串放在哪个堆里决定了选哪个，两者不重叠。
     */
    enum class Scope {
        /**
         * ART 之外的匿名映射 —— Dart AOT 堆、原生分配器、自己 mmap 的缓冲区。
         * Flutter 目标用这个。
         */
        NATIVE,

        /**
         * 只扫 `[anon:dalvik-*]`，即 ART 的托管堆。
         *
         * Java 的 `static final String` 字面量在这里：ART 把全 ASCII 字符串压缩存储
         * （一字符一字节）并**内联在对象里**，所以等长覆写既不碰长度字段也不碰对象头。
         * dex 文件里那份是只读镜像，ART 解析一次之后再也不读，改它没有意义。
         *
         * 这是「换掉硬编码公钥」唯一有效的范围。
         */
        MANAGED_HEAP,

        /** 两者都扫。查东西时用，写入时按需收窄。 */
        ALL,
    }

    /**
     * 等长搜索替换，范围由 [scope] 决定。
     *
     * 为 Flutter 目标而写：Dart AOT 快照的 data 段是序列化的 cluster 流，isolate 启动时
     * 反序列化进堆，所以改内存里映射的 `.so` 毫无作用。堆上那份 Dart 字符串是连续 ASCII，
     * 等长覆写不动它的长度字段 —— 混淆过的包里换一个字面量因此既不用遍历对象池也不用任何代码偏移。
     *
     * 同一招在 [Scope.MANAGED_HEAP] 下能够到 Java 字面量，这就是在不知道任何类名方法名的
     * 前提下，把目标硬编码的 RSA 公钥换成模块自己持有私钥的那一把的办法。
     *
     * ART 的并发回收器会搬动对象，所以这里的写入和一次拷贝存在竞态。两点让它可以接受：
     * 写入是原地等长的，之后即使被搬走也是连**新**字节一起搬；而且每次写入都会读回校验。
     * 尽早写（应用还没建立工作集时）比事后写稳。
     *
     * Dart 目标上字符串要等 isolate 反序列化之后才存在，调用方通常需要在进程启动后重试几秒。
     *
     * @param limit 写满这么多次就停；0 = 全部替换
     * @return 实际写入次数；长度不等或原生层不可用返回 -1
     */
    fun replaceInMemory(
        needle: ByteArray,
        replacement: ByteArray,
        limit: Int = 0,
        scope: Scope = Scope.NATIVE,
    ): Int {
        if (!isAvailable || needle.isEmpty()) return -1
        if (needle.size != replacement.size) return -1
        return runCatching { nativeReplaceInMemory(needle, replacement, limit, scope.ordinal) }
            .getOrDefault(-1)
    }

    /** [replaceInMemory] 的 ASCII 字面量版本。 */
    fun replaceAscii(
        from: String,
        to: String,
        limit: Int = 0,
        scope: Scope = Scope.NATIVE,
    ): Int {
        if (from.length != to.length) return -1
        return replaceInMemory(
            from.toByteArray(Charsets.US_ASCII),
            to.toByteArray(Charsets.US_ASCII),
            limit,
            scope,
        )
    }

    /** [needle] 在内存里出现了几次，不写入。 */
    fun countInMemory(needle: ByteArray, scope: Scope = Scope.NATIVE): Int {
        if (!isAvailable || needle.isEmpty()) return -1
        return runCatching { nativeCountInMemory(needle, scope.ordinal) }.getOrDefault(-1)
    }

    /** [countInMemory] 的 ASCII 字面量版本。 */
    fun countAscii(text: String, scope: Scope = Scope.NATIVE): Int =
        countInMemory(text.toByteArray(Charsets.US_ASCII), scope)

    /**
     * Distinct text runs in memory that start with [prefix].
     *
     * The discovery half of [replaceInMemory]. That one has to be handed the
     * exact literal, which means baking a target's URL or key into the module
     * and losing it the moment the app ships a new one; searching for a *shape*
     * instead — `"https://"`, `"-----BEGIN PUBLIC KEY-----"` — outlives the
     * value. Feed a result straight back into [replaceAscii] and the lengths
     * match by construction.
     *
     * A run ends at the first byte outside printable ASCII (tab, CR and LF still
     * count as text, which PEM bodies need). On a Dart target that is the exact
     * boundary rather than an approximation: a snapshot string is followed by
     * the next cluster's `(len<<1)|0x80` length marker, whose top bit is always
     * set.
     *
     * Costs a full scan of the process's anonymous mappings — hundreds of MB in
     * a Flutter app — so call it off the main thread and cache the answer.
     *
     * @param limit stop after this many distinct strings; 0 = all
     */
    fun findAscii(
        prefix: String,
        minLength: Int = prefix.length,
        maxLength: Int = 1024,
        limit: Int = 0,
        scope: Scope = Scope.NATIVE,
    ): List<String> {
        if (!isAvailable || prefix.isEmpty()) return emptyList()
        return runCatching { nativeFindAscii(prefix, minLength, maxLength, limit, scope.ordinal).toList() }
            .getOrDefault(emptyList())
    }

    /**
     * Makes the native function at [address] return [value] immediately.
     *
     * Works for any function whose return value fits in a register — int, bool,
     * pointer. The classic use is defanging a native integrity or root check
     * without touching its body. Reversible with [unhook].
     *
     * Backed by a fixed pool of 32 stubs; calling it again for an address that
     * is already stubbed just updates the value.
     */
    fun returnConstant(address: Long, value: Long): Boolean {
        if (!isAvailable || address == 0L) return false
        return runCatching { nativeReturnConstant(address, value) }.getOrDefault(false)
    }

    /** Convenience for the common boolean case. */
    fun returnConstant(library: String?, symbol: String, value: Long): Boolean {
        val address = findSymbol(library, symbol)
        return address != 0L && returnConstant(address, value)
    }

    /** Removes the hook installed at [address]. */
    fun unhook(address: Long): Boolean {
        if (!isAvailable || address == 0L) return false
        return runCatching { nativeUnhook(address) }.getOrDefault(false)
    }

    /** Installs a C++ `NativeHooker` by id. Repeat calls are no-ops. */
    fun install(hookerId: String): Boolean {
        if (!isAvailable) return false
        return runCatching { nativeInstallHooker(hookerId) }.getOrDefault(false)
    }

    fun isInstalled(hookerId: String): Boolean {
        if (!isAvailable) return false
        return runCatching { nativeIsHookerInstalled(hookerId) }.getOrDefault(false)
    }

    /** Everything the C++ side offers, for the module UI. */
    fun available(): List<NativeHookerInfo> {
        if (!isAvailable) return emptyList()
        val raw = runCatching { nativeListHookers() }.getOrNull() ?: return emptyList()
        return raw.mapNotNull { line ->
            val parts = line.split('\n', limit = 2)
            parts.getOrNull(0)?.takeIf { it.isNotEmpty() }?.let {
                NativeHookerInfo(it, parts.getOrElse(1) { "" })
            }
        }
    }

    /**
     * Substring filter for the built-in `openat_logger`. An empty list logs
     * every path the target opens, which floods logcat — set it before
     * `install("openat_logger")`.
     */
    fun setOpenatFilters(vararg filters: String) {
        if (!isAvailable) return
        runCatching { nativeSetOpenatFilters(arrayOf(*filters)) }
    }

    data class NativeHookerInfo(val id: String, val description: String)

    // --- Dynamically registered JNI methods ----------------------------------

    /** Id of the built-in `RegisterNatives` watch, for [install] / [isInstalled]. */
    const val JNI_REGISTER_WATCH = "jni_register_watch"

    /**
     * A native method the target bound through `RegisterNatives`.
     *
     * [address] is what the library registered — the real function, even when
     * the library exports nothing but `JNI_OnLoad`.
     */
    data class JniRegistration(
        val className: String,
        val methodName: String,
        val signature: String,
        val address: Long,
    )

    /**
     * Starts recording `RegisterNatives` calls.
     *
     * Install it **before** the target loads the library in question; anything
     * registered earlier is already bound and cannot be seen. In practice that
     * means calling this from a feature's `install`, which runs before any of
     * the target's own code.
     */
    fun watchJniRegistrations(): Boolean = install(JNI_REGISTER_WATCH)

    /** Everything seen since [watchJniRegistrations], oldest first. */
    fun jniRegistrations(): List<JniRegistration> {
        if (!isAvailable) return emptyList()
        val raw = runCatching { nativeJniRegistrations() }.getOrNull() ?: return emptyList()
        return raw.mapNotNull { line ->
            val parts = line.split('\n')
            if (parts.size < 4) return@mapNotNull null
            JniRegistration(parts[0], parts[1], parts[2], parts[3].toLongOrNull() ?: 0L)
        }
    }

    /** Address bound to `className.methodName`, or 0 if it was never seen. */
    fun jniRegistrationAddress(className: String, methodName: String): Long {
        if (!isAvailable) return 0L
        return runCatching { nativeJniRegistrationAddress(className, methodName) }.getOrDefault(0L)
    }

    /**
     * Makes `className.methodName` return [value] rather than run.
     *
     * When the method has not been registered yet — the normal case — nothing is
     * patched: the pointer is substituted in the `JNINativeMethod` array as it
     * goes past, so the target's library is never written to. That also means
     * true here says "the rule is in place", not "it has fired".
     *
     * Installs the watch if it is not already in. Only sound for a method whose
     * return fits in a register: a `boolean`, `int` or pointer. Forcing one that
     * returns an object hands Java a fabricated reference and crashes it.
     */
    fun returnConstantOnJniRegister(className: String, methodName: String, value: Long): Boolean {
        if (!isAvailable) return false
        return runCatching { nativeConstantOnJniRegister(className, methodName, value) }
            .getOrDefault(false)
    }

    // --- JNI, bound dynamically in JNI_OnLoad ---------------------------------
    private external fun nativeFindSymbol(library: String?, symbol: String): Long
    private external fun nativeModuleBase(library: String): Long
    private external fun nativePatchMemory(address: Long, bytes: ByteArray): Boolean
    private external fun nativeWriteMemory(address: Long, bytes: ByteArray): Boolean
    private external fun nativeReadMemory(address: Long, size: Int): ByteArray?
    private external fun nativeFindPatternInModule(library: String, pattern: ByteArray, skip: Int): Long
    private external fun nativeCountPatternInModule(library: String, pattern: ByteArray): Int
    private external fun nativeReplaceInMemory(needle: ByteArray, replacement: ByteArray, limit: Int, scope: Int): Int
    private external fun nativeCountInMemory(needle: ByteArray, scope: Int): Int
    private external fun nativeFindAscii(prefix: String, minLength: Int, maxLength: Int, limit: Int, scope: Int): Array<String>
    private external fun nativeReturnConstant(address: Long, value: Long): Boolean
    private external fun nativeUnhook(address: Long): Boolean
    private external fun nativeInstallHooker(id: String): Boolean
    private external fun nativeIsHookerInstalled(id: String): Boolean
    private external fun nativeListHookers(): Array<String>
    private external fun nativeSetVerbose(verbose: Boolean)
    private external fun nativeSetOpenatFilters(filters: Array<String>)
    private external fun nativeConstantOnJniRegister(className: String, methodName: String, value: Long): Boolean
    private external fun nativeJniRegistrationAddress(className: String, methodName: String): Long
    private external fun nativeJniRegistrations(): Array<String>
}
