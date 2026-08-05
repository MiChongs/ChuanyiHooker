package com.chuanyi.hooker.core

import java.util.ServiceLoader

/**
 * Discovers every [AppHooker] on the module class loader.
 *
 * Loading is lazy and failure-tolerant: one broken hooker (missing class,
 * throwing constructor) is logged and skipped instead of taking the whole
 * module down in the target process.
 */
object HookerRegistry {

    @Volatile
    private var cached: List<AppHooker>? = null

    @Volatile
    private var loadErrors: List<String> = emptyList()

    /** All hookers found, in ServiceLoader order. */
    fun all(): List<AppHooker> = cached ?: synchronized(this) {
        cached ?: load().also { cached = it }
    }

    /** Names of services that failed to instantiate, for the UI to surface. */
    fun errors(): List<String> {
        all()
        return loadErrors
    }

    fun byId(id: String): AppHooker? = all().firstOrNull { it.id == id }

    /** Hookers claiming [packageName]. Usually zero or one. */
    fun forPackage(packageName: String): List<AppHooker> =
        all().filter { packageName in it.targetPackages }

    /** Every package any hooker claims — what `scope.list` should contain. */
    fun allTargetPackages(): Set<String> =
        all().flatMapTo(linkedSetOf()) { it.targetPackages }

    /** Test seam / manual override. Pass null to go back to ServiceLoader. */
    fun override(hookers: List<AppHooker>?) {
        synchronized(this) {
            cached = hookers
            if (hookers == null) loadErrors = emptyList()
        }
    }

    private fun load(): List<AppHooker> {
        val found = ArrayList<AppHooker>()
        val errors = ArrayList<String>()
        val seen = HashSet<String>()
        val loader = ServiceLoader.load(AppHooker::class.java, AppHooker::class.java.classLoader)
        val iterator = loader.iterator()
        while (true) {
            val hooker = try {
                if (!iterator.hasNext()) break
                iterator.next()
            } catch (t: Throwable) {
                // ServiceConfigurationError for one entry must not kill the rest.
                errors += (t.message ?: t.javaClass.name)
                continue
            }
            if (!seen.add(hooker.id)) {
                errors += "duplicate hooker id '${hooker.id}' (${hooker.javaClass.name}) ignored"
                continue
            }
            found += hooker
        }
        loadErrors = errors
        return found
    }
}
