#include "chuanyi/native_hook.h"

#include <android/log.h>
#include <dlfcn.h>
#include <link.h>
#include <inttypes.h>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <sys/mman.h>
#include <unistd.h>
#include <vector>

#include "dobby.h"

namespace chuanyi {
namespace {

constexpr const char *kTag = "ChuanyiHooker/native";

bool g_verbose = false;

void Vlog(int priority, const char *fmt, va_list args) {
    __android_log_vprint(priority, kTag, fmt, args);
}

struct HookerEntry {
    const NativeHooker *hooker;
    bool installed;
};

std::mutex &RegistryMutex() {
    static std::mutex m;
    return m;
}

std::vector<HookerEntry> &Registry() {
    static std::vector<HookerEntry> registry;
    return registry;
}

} // namespace

void SetVerbose(bool verbose) { g_verbose = verbose; }

bool IsVerbose() { return g_verbose; }

void LogDebug(const char *fmt, ...) {
    if (!g_verbose) return;
    va_list args;
    va_start(args, fmt);
    Vlog(ANDROID_LOG_DEBUG, fmt, args);
    va_end(args);
}

void LogInfo(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    Vlog(ANDROID_LOG_INFO, fmt, args);
    va_end(args);
}

void LogWarn(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    Vlog(ANDROID_LOG_WARN, fmt, args);
    va_end(args);
}

void LogError(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    Vlog(ANDROID_LOG_ERROR, fmt, args);
    va_end(args);
}

void *FindSymbol(const char *image, const char *symbol) {
    if (symbol == nullptr) return nullptr;

    // DobbySymbolResolver walks the ELF symbol tables, so it also finds symbols
    // that are not in .dynsym - which is the whole point of using it over dlsym.
    //
    // But it assumes the image is already mapped: hand it the name of a library
    // that has not been loaded yet and elf_ctx_init keeps walking an invalid
    // pointer and segfaults instead of returning nullptr. A hooker installing at
    // PACKAGE_READY routinely runs before the target calls System.loadLibrary,
    // so this is the common case, not a corner case. Confirm the image is
    // actually in the process first; only then let Dobby parse it.
    void *handle = nullptr;
    if (image != nullptr) {
        handle = dlopen(image, RTLD_NOW | RTLD_NOLOAD);
        if (handle == nullptr) {
            LogDebug("FindSymbol(%s, %s): image not loaded yet", image, symbol);
            return nullptr;
        }
    }

    void *addr = DobbySymbolResolver(image, symbol);
    if (addr == nullptr && handle != nullptr) addr = dlsym(handle, symbol);
    if (handle != nullptr) dlclose(handle);

    LogDebug("FindSymbol(%s, %s) = %p", image ? image : "*", symbol, addr);
    return addr;
}

namespace {

struct BaseQuery {
    const char *name;
    uintptr_t base;
};

int MatchModuleBase(struct dl_phdr_info *info, size_t, void *data) {
    auto *query = static_cast<BaseQuery *>(data);
    if (info->dlpi_name == nullptr || *info->dlpi_name == '\0') return 0;
    if (std::strstr(info->dlpi_name, query->name) == nullptr) return 0;
    query->base = static_cast<uintptr_t>(info->dlpi_addr);
    return 1; // 命中即停
}

} // namespace

uintptr_t ModuleBase(const char *image) {
    if (image == nullptr) return 0;

    // 走 dl_iterate_phdr 而不是 dlsym 探一个符号。
    //
    // 早先的实现是 dlopen(NOLOAD) 拿到 handle 后 dlsym("__bss_start") 再 dladdr。
    // 那要求目标库**导出** __bss_start —— 大量 release 库（包括目标那个 10 MB 的
    // 主库）根本没有这个符号，于是 dlsym 返回 null，函数恒返回 0，
    // 调用方看起来就像「库没加载」。而库其实好好地映射着。
    //
    // dlpi_addr 就是该 ELF 的加载基址，不依赖任何符号是否导出。
    BaseQuery query{image, 0};
    dl_iterate_phdr(MatchModuleBase, &query);
    if (query.base != 0) return query.base;

    // 退路：有些库以完整路径匹配不上短名，再用 dlopen + dladdr 兜一次。
    if (void *handle = dlopen(image, RTLD_NOW | RTLD_NOLOAD)) {
        Dl_info info{};
        uintptr_t base = 0;
        if (void *any = dlsym(handle, "JNI_OnLoad")) {
            if (dladdr(any, &info) != 0) base = reinterpret_cast<uintptr_t>(info.dli_fbase);
        }
        dlclose(handle);
        return base;
    }
    return 0;
}

namespace {

/// One executable range of a loaded image, in runtime addresses.
struct CodeRange {
    const uint8_t *start;
    size_t size;
};

struct SegmentQuery {
    const char *name;
    std::vector<CodeRange> *out;
};

/**
 * Collects the PF_X PT_LOAD segments of the image whose path contains `name`.
 *
 * Deliberately *not* /proc/self/maps: the linker merges and splits mappings, so
 * a segment can show up there as several rows and a row can cover padding that
 * belongs to no segment at all. `p_vaddr + dlpi_addr` is the authoritative
 * runtime location of a segment, straight from the program headers the loader
 * itself used.
 */
int CollectCodeSegments(struct dl_phdr_info *info, size_t, void *data) {
    auto *query = static_cast<SegmentQuery *>(data);
    if (info->dlpi_name == nullptr || *info->dlpi_name == '\0') return 0;
    if (std::strstr(info->dlpi_name, query->name) == nullptr) return 0;

    for (int i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr) &phdr = info->dlpi_phdr[i];
        if (phdr.p_type != PT_LOAD || (phdr.p_flags & PF_X) == 0) continue;
        // p_memsz, not p_filesz: the executable segment is never zero-padded, and
        // taking the smaller of the two would silently clip the search window.
        query->out->push_back({
            reinterpret_cast<const uint8_t *>(info->dlpi_addr + phdr.p_vaddr),
            static_cast<size_t>(phdr.p_memsz),
        });
    }
    return 1; // first matching image wins
}

std::vector<CodeRange> CodeSegments(const char *image) {
    std::vector<CodeRange> ranges;
    if (image == nullptr || *image == '\0') return ranges;
    SegmentQuery query{image, &ranges};
    dl_iterate_phdr(CollectCodeSegments, &query);
    return ranges;
}

/// Walks every match of `pattern`, calling `visit(address)` until it returns false.
template <typename Visit>
void ForEachPattern(const char *image, const uint8_t *pattern, size_t length, Visit visit) {
    if (pattern == nullptr || length == 0) return;
    for (const CodeRange &range : CodeSegments(image)) {
        if (range.size < length) continue;
        const uint8_t *const end = range.start + range.size - length + 1;
        for (const uint8_t *p = range.start; p < end; ++p) {
            // memchr first: the scan is a few MB and this keeps the inner loop
            // out of the way for all but the 1/256 of positions that can match.
            p = static_cast<const uint8_t *>(std::memchr(p, pattern[0],
                                                         static_cast<size_t>(end - p)));
            if (p == nullptr) break;
            if (std::memcmp(p, pattern, length) == 0) {
                if (!visit(reinterpret_cast<uintptr_t>(p))) return;
            }
        }
    }
}

} // namespace

uintptr_t FindPatternInModule(const char *image, const uint8_t *pattern, size_t length, int skip) {
    uintptr_t found = 0;
    int seen = 0;
    ForEachPattern(image, pattern, length, [&](uintptr_t address) {
        if (seen++ < skip) return true;
        found = address;
        return false;
    });
    LogDebug("FindPatternInModule(%s, %zu bytes, skip=%d) = %p", image ? image : "?", length, skip,
             reinterpret_cast<void *>(found));
    return found;
}

int CountPatternInModule(const char *image, const uint8_t *pattern, size_t length) {
    int count = 0;
    ForEachPattern(image, pattern, length, [&](uintptr_t) {
        ++count;
        return true;
    });
    return count;
}

bool PatchMemory(void *address, const uint8_t *bytes, uint32_t size) {
    if (address == nullptr || bytes == nullptr || size == 0) return false;
    // DobbyCodePatch handles mprotect + cache flush for us.
    auto err = DobbyCodePatch(address, const_cast<uint8_t *>(bytes), size);
    if (err != kMemoryOperationSuccess) {
        LogError("PatchMemory(%p, %u) failed: %d", address, size, static_cast<int>(err));
        return false;
    }
    LogDebug("PatchMemory(%p, %u) ok", address, size);
    return true;
}

namespace {

/// Current protection of the mapping holding `address`, from /proc/self/maps.
bool ReadPageProtection(uintptr_t address, int *prot) {
    FILE *fp = fopen("/proc/self/maps", "re");
    if (fp == nullptr) return false;
    char line[512];
    bool found = false;
    while (fgets(line, sizeof(line), fp) != nullptr) {
        uintptr_t start = 0, end = 0;
        char perms[8] = {0};
        if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR " %7s", &start, &end, perms) != 3) continue;
        if (address < start || address >= end) continue;
        int value = 0;
        if (perms[0] == 'r') value |= PROT_READ;
        if (perms[1] == 'w') value |= PROT_WRITE;
        if (perms[2] == 'x') value |= PROT_EXEC;
        *prot = value;
        found = true;
        break;
    }
    fclose(fp);
    return found;
}

} // namespace

bool WriteMemory(void *address, const uint8_t *bytes, uint32_t size) {
    if (address == nullptr || bytes == nullptr || size == 0) return false;

    const size_t page_size = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    const auto target = reinterpret_cast<uintptr_t>(address);
    const uintptr_t first = target & ~(page_size - 1);
    const uintptr_t last = (target + size + page_size - 1) & ~(page_size - 1);

    int prot = 0;
    if (!ReadPageProtection(target, &prot)) {
        LogWarn("WriteMemory: %p is not mapped", address);
        return false;
    }

    // Only add write permission, and put back exactly what was there. Restoring
    // a blanket PROT_READ|PROT_EXEC — which is what DobbyCodePatch does — turns
    // a writable data page read-only and kills the app on its next store.
    const bool unlock = (prot & PROT_WRITE) == 0;
    if (unlock && mprotect(reinterpret_cast<void *>(first), last - first,
                           prot | PROT_WRITE) != 0) {
        LogWarn("WriteMemory: cannot make %p writable", address);
        return false;
    }

    memcpy(address, bytes, size);

    if (unlock && mprotect(reinterpret_cast<void *>(first), last - first, prot) != 0) {
        LogWarn("WriteMemory: cannot restore protection at %p", address);
    }

    const bool ok = memcmp(address, bytes, size) == 0;
    LogDebug("WriteMemory(%p, %u) %s", address, size, ok ? "ok" : "verify failed");
    return ok;
}

bool ReadMemory(const void *address, uint8_t *out, uint32_t size) {
    if (address == nullptr || out == nullptr || size == 0) return false;
    // Probe through a pipe: a bad address fails with EFAULT instead of killing
    // the target process with SIGSEGV.
    int fds[2];
    if (pipe(fds) != 0) return false;
    bool ok = true;
    const uint8_t *src = static_cast<const uint8_t *>(address);
    uint32_t done = 0;
    while (done < size) {
        uint32_t chunk = size - done;
        if (chunk > 4096) chunk = 4096;
        ssize_t written = write(fds[1], src + done, chunk);
        if (written <= 0) {
            ok = false;
            break;
        }
        ssize_t got = read(fds[0], out + done, static_cast<size_t>(written));
        if (got != written) {
            ok = false;
            break;
        }
        done += static_cast<uint32_t>(written);
    }
    close(fds[0]);
    close(fds[1]);
    return ok;
}

void RegisterNativeHooker(const NativeHooker *hooker) {
    if (hooker == nullptr || hooker->id == nullptr || hooker->install == nullptr) return;
    std::lock_guard<std::mutex> guard(RegistryMutex());
    for (const auto &entry : Registry()) {
        if (std::strcmp(entry.hooker->id, hooker->id) == 0) return;
    }
    Registry().push_back(HookerEntry{hooker, false});
}

bool InstallNativeHooker(const char *id) {
    if (id == nullptr) return false;
    const NativeHooker *target = nullptr;
    {
        std::lock_guard<std::mutex> guard(RegistryMutex());
        for (auto &entry : Registry()) {
            if (std::strcmp(entry.hooker->id, id) != 0) continue;
            if (entry.installed) return true;
            entry.installed = true;  // claim before releasing the lock
            target = entry.hooker;
            break;
        }
    }
    if (target == nullptr) {
        LogWarn("no native hooker registered as '%s'", id);
        return false;
    }
    bool ok = target->install();
    if (!ok) {
        std::lock_guard<std::mutex> guard(RegistryMutex());
        for (auto &entry : Registry()) {
            if (std::strcmp(entry.hooker->id, id) == 0) entry.installed = false;
        }
        LogWarn("native hooker '%s' did not install", id);
    } else {
        LogInfo("native hooker '%s' installed", id);
    }
    return ok;
}

size_t NativeHookerCount() {
    std::lock_guard<std::mutex> guard(RegistryMutex());
    return Registry().size();
}

const NativeHooker *NativeHookerAt(size_t index) {
    std::lock_guard<std::mutex> guard(RegistryMutex());
    if (index >= Registry().size()) return nullptr;
    return Registry()[index].hooker;
}

bool IsNativeHookerInstalled(const char *id) {
    if (id == nullptr) return false;
    std::lock_guard<std::mutex> guard(RegistryMutex());
    for (const auto &entry : Registry()) {
        if (std::strcmp(entry.hooker->id, id) == 0) return entry.installed;
    }
    return false;
}

bool Unhook(void *address) {
    if (address == nullptr) return false;
    bool ok = DobbyDestroy(address) == 0;
    if (ok) detail::ReleaseConstantSlot(address);
    return ok;
}

} // namespace chuanyi
