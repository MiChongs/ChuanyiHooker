// Built-in native hooker: log the files the target opens.
//
// Hooks libc's openat(2) - every fopen/open/AssetManager/dlopen path in the
// process funnels through it - and prints paths matching a substring filter.
// Purely observational; the original call always runs.
//
// Enable per app with NativeHook.install("openat_logger") and narrow it with
// NativeHook.setOpenatFilters(...); an empty filter list logs everything, which
// is very noisy and meant for short bursts only.

#include "chuanyi/native_hook.h"

#include <fcntl.h>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "dobby.h"

namespace chuanyi {
namespace {

std::mutex g_filterMutex;
std::vector<std::string> g_filters;

int (*g_originalOpenat)(int, const char *, int, int) = nullptr;

bool Matches(const char *path) {
    if (path == nullptr) return false;
    std::lock_guard<std::mutex> guard(g_filterMutex);
    if (g_filters.empty()) return true;
    for (const auto &needle : g_filters) {
        if (std::strstr(path, needle.c_str()) != nullptr) return true;
    }
    return false;
}

// openat is variadic (mode only matters with O_CREAT/O_TMPFILE). Declaring the
// replacement with a fixed fourth argument and forwarding it is safe on every
// ABI Dobby supports here: the extra register/stack slot is ignored when the
// original does not read it.
int ReplacementOpenat(int dirfd, const char *pathname, int flags, int mode) {
    if (Matches(pathname)) {
        LogInfo("openat: %s (flags=0x%x)", pathname != nullptr ? pathname : "<null>", flags);
    }
    return g_originalOpenat(dirfd, pathname, flags, mode);
}

bool Install() {
    void *target = FindSymbol("libc.so", "openat");
    if (target == nullptr) {
        LogWarn("openat_logger: libc.so!openat not found");
        return false;
    }
    const int rc = DobbyHook(target,
                             reinterpret_cast<dobby_dummy_func_t>(ReplacementOpenat),
                             reinterpret_cast<dobby_dummy_func_t *>(&g_originalOpenat));
    if (rc != 0 || g_originalOpenat == nullptr) {
        LogError("openat_logger: DobbyHook failed (%d)", rc);
        return false;
    }
    return true;
}

} // namespace

void SetOpenatFilters(const std::vector<std::string> &filters) {
    std::lock_guard<std::mutex> guard(g_filterMutex);
    g_filters = filters;
}

// 第三个参数会原样显示在模块界面上，所以写中文、不写实现细节。
CHUANYI_NATIVE_HOOKER(kOpenatLogger,
                      "openat_logger",
                      "记录目标应用打开过的文件路径（可按关键字过滤）",
                      Install)

} // namespace chuanyi
