// Built-in native hooker: 看加固壳向 linker 要了什么。
//
// ── 为什么盯 dlsym ──
//
// lib__5885__.so 的形态（rabin2 实测）：
//
//   导出表      只有 __bss_start / _edata —— 连 JNI_OnLoad 都没有
//   入口        .init_array 里单个构造函数 0x1d54 → 0x163c
//   节区        .text 51 KB + .data 401 KB + unknown0 1.1 MB（vaddr=0，不映射）
//   导入表      只有 dlopen / dlsym / dlclose / dladdr / dlerror
//
// 也就是说：这是个自解密壳，51 KB 的解密器带着 1.1 MB 加密载荷；而它用到的**每一个**
// 系统 API 都得在运行时经过 dlsym 拿。所以 dlsym 是它唯一的咽喉 —— 它想枚举已加载
// 模块也好、想读文件也好、想起线程也好，都得先在这儿把函数要走。
//
// 这一点有实测支撑：openat 探针挂了整套 /proc/self/maps、/data/adb、lspd、magisk
// 过滤词，**一条都没打**。它不是靠读文件判定环境的，那就只剩 dl_iterate_phdr
// 这类走 linker 内部链表的路子 —— 而那个符号不在它的导入表里，必须 dlsym。
//
// ── 顺带解释了为什么退出拦不住 ──
//
// Java 的 System.exit / Runtime.halt / Process.killProcess 四个出口挂了，libc 的
// exit / _exit / syscall(exit_group) / kill / tgkill 五个出口也挂了，**两层一次都
// 没触发**，进程照样 EXIT_SELF/status=0。那就只能是直接 svc 指令下的 syscall。
// 与其去拦一条内联汇编，不如让它**判定不出问题** —— 而那要先知道它在看什么。
//
// 纯观察，不改任何返回值。用 NativeHook.install("linker_watch") 打开。

#include "chuanyi/native_hook.h"

#include <dirent.h>
#include <dlfcn.h>
#include <stdio.h>

#include <cstring>
#include <mutex>
#include <string>
#include <unordered_set>

#include "dobby.h"

namespace chuanyi {
namespace {

void *(*g_originalDlsym)(void *, const char *) = nullptr;
void *(*g_originalDlopen)(const char *, int) = nullptr;
FILE *(*g_originalFopen)(const char *, const char *) = nullptr;
DIR *(*g_originalOpendir)(const char *) = nullptr;
int (*g_originalSystem)(const char *) = nullptr;
FILE *(*g_originalPopen)(const char *, const char *) = nullptr;
int (*g_originalPropGet)(const char *, char *) = nullptr;

std::mutex g_seenMutex;
std::unordered_set<std::string> g_seen;

/// 壳所在库的名字片段。
constexpr const char *kPackerHint = "5885";

/**
 * 判断这次调用是不是壳发起的，并给出调用方名字。
 *
 * 解密后的壳代码会跑在匿名可执行内存里，`dladdr` 查不到归属 —— 那种情况同样算，
 * 因为这个进程里会在匿名页上执行代码的基本只有它。
 */
bool FromPacker(void *caller, const char **outName) {
    Dl_info info;
    if (dladdr(caller, &info) == 0 || info.dli_fname == nullptr) {
        *outName = "<匿名内存>";
        return true;
    }
    *outName = info.dli_fname;
    return std::strstr(info.dli_fname, kPackerHint) != nullptr;
}

/// 同一个 (调用方, 符号) 只报一次 —— 请求的**集合**才是检测清单，次数没有意义，
/// 而 dlsym 在整个进程里是高频调用，不去重会把 logcat 冲掉。
bool FirstTime(const char *from, const char *what) {
    std::string key(from);
    key += '!';
    key += what;
    std::lock_guard<std::mutex> guard(g_seenMutex);
    return g_seen.insert(key).second;
}

void *ReplacementDlsym(void *handle, const char *symbol) {
    void *result = g_originalDlsym(handle, symbol);
    const char *from = nullptr;
    void *caller = __builtin_return_address(0);
    if (FromPacker(caller, &from)) {
        const char *name = symbol != nullptr ? symbol : "<null>";
        if (FirstTime(from, name)) {
            LogInfo("linker_watch: dlsym(\"%s\") = %p   ← %s", name, result, from);
        }
    }
    return result;
}

void *ReplacementDlopen(const char *path, int flags) {
    void *result = g_originalDlopen(path, flags);
    const char *from = nullptr;
    void *caller = __builtin_return_address(0);
    if (FromPacker(caller, &from)) {
        const char *name = path != nullptr ? path : "<null>";
        if (FirstTime(from, name)) {
            LogInfo("linker_watch: dlopen(\"%s\") = %p   ← %s", name, result, from);
        }
    }
    return result;
}

// --- 它到底看了什么 -------------------------------------------------------
//
// dlsym 那份清单里有 fopen/fgets/sscanf（读 /proc 文本文件的标准组合）、
// opendir/readdir（遍历目录）、system/popen（执行 shell 命令）、
// __system_property_get（读系统属性）。路径和命令原文就是检测意图本身。
//
// 注意 openat 探针为什么一条都没抓到：bionic 内部 fopen → open 不经过 PLT，
// 挂导出的 openat 根本不在那条调用路径上。要看文件访问就得挂 fopen 本身。

FILE *ReplacementFopen(const char *path, const char *mode) {
    const char *from = nullptr;
    if (FromPacker(__builtin_return_address(0), &from) && path != nullptr) {
        if (FirstTime(from, path)) LogInfo("linker_watch: fopen(\"%s\")   ← %s", path, from);
    }
    return g_originalFopen(path, mode);
}

DIR *ReplacementOpendir(const char *path) {
    const char *from = nullptr;
    if (FromPacker(__builtin_return_address(0), &from) && path != nullptr) {
        if (FirstTime(from, path)) LogInfo("linker_watch: opendir(\"%s\")   ← %s", path, from);
    }
    return g_originalOpendir(path);
}

int ReplacementSystem(const char *command) {
    const char *from = nullptr;
    if (FromPacker(__builtin_return_address(0), &from) && command != nullptr) {
        LogInfo("linker_watch: system(\"%s\")   ← %s", command, from);
    }
    return g_originalSystem(command);
}

FILE *ReplacementPopen(const char *command, const char *mode) {
    const char *from = nullptr;
    if (FromPacker(__builtin_return_address(0), &from) && command != nullptr) {
        LogInfo("linker_watch: popen(\"%s\")   ← %s", command, from);
    }
    return g_originalPopen(command, mode);
}

int ReplacementPropGet(const char *name, char *value) {
    const int rc = g_originalPropGet(name, value);
    const char *from = nullptr;
    if (FromPacker(__builtin_return_address(0), &from) && name != nullptr) {
        if (FirstTime(from, name)) {
            LogInfo("linker_watch: __system_property_get(\"%s\") = \"%s\"   ← %s",
                    name, value != nullptr ? value : "", from);
        }
    }
    return rc;
}

bool HookOne(const char *symbol, void *replacement, void **original) {
    void *target = FindSymbol("libdl.so", symbol);
    if (target == nullptr) target = FindSymbol("libc.so", symbol);
    if (target == nullptr) {
        LogWarn("linker_watch: %s 没找到", symbol);
        return false;
    }
    const int rc = DobbyHook(target, reinterpret_cast<dobby_dummy_func_t>(replacement),
                             reinterpret_cast<dobby_dummy_func_t *>(original));
    if (rc != 0 || *original == nullptr) {
        LogError("linker_watch: hook %s 失败 (%d)", symbol, rc);
        return false;
    }
    LogInfo("linker_watch: 已挂住 %s @ %p", symbol, target);
    return true;
}

bool Install() {
    int ok = 0;
    ok += HookOne("dlsym", reinterpret_cast<void *>(ReplacementDlsym),
                  reinterpret_cast<void **>(&g_originalDlsym));
    ok += HookOne("dlopen", reinterpret_cast<void *>(ReplacementDlopen),
                  reinterpret_cast<void **>(&g_originalDlopen));
    ok += HookOne("fopen", reinterpret_cast<void *>(ReplacementFopen),
                  reinterpret_cast<void **>(&g_originalFopen));
    ok += HookOne("opendir", reinterpret_cast<void *>(ReplacementOpendir),
                  reinterpret_cast<void **>(&g_originalOpendir));
    ok += HookOne("system", reinterpret_cast<void *>(ReplacementSystem),
                  reinterpret_cast<void **>(&g_originalSystem));
    ok += HookOne("popen", reinterpret_cast<void *>(ReplacementPopen),
                  reinterpret_cast<void **>(&g_originalPopen));
    ok += HookOne("__system_property_get", reinterpret_cast<void *>(ReplacementPropGet),
                  reinterpret_cast<void **>(&g_originalPropGet));
    if (ok == 0) {
        LogError("linker_watch: 一个都没挂上");
        return false;
    }
    LogInfo("linker_watch: %d/7 就位，只报来自加固壳的请求", ok);
    return true;
}

} // namespace

// 第三个参数会原样显示在模块界面上，所以写中文、不写实现细节。
CHUANYI_NATIVE_HOOKER(kLinkerWatch,
                      "linker_watch",
                      "记录加固壳向 linker 索取了哪些函数（检测手段就写在这份清单上）",
                      Install)

} // namespace chuanyi
