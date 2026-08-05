// Built-in native hooker: 把加固壳看到的环境洗干净。
//
// 不再去拦它的自杀动作（那是治标，而且它连 exit 都是内联 svc），改为**让它的检测
// 得出「环境干净」的结论**。检测清单是实测抓出来的，不是猜的 —— linker_watch 挂住
// fopen / opendir / __system_property_get 后，壳依次做了这些事：
//
//   opendir("/root")                                  root 目录是否存在
//   fopen("/proc/self/mounts")                        自己的挂载表
//   fopen("/proc/self/mountinfo")
//   fopen("/proc/<pid>/mounts")                       比对别的进程的挂载命名空间
//   opendir("/proc")                                  遍历进程找可疑名字
//   fopen("/proc/kallsyms")                           内核符号（找 KSU/内核模块）
//   fopen("/proc/misc") / interrupts / timer_list     模拟器指纹
//   opendir("/data/app/~~.../jp.bookwalker...")       自己的 APK 目录
//   ro.kernel.qemu / microvirt.* / init.svc.microvirtd
//   ro.secure / ro.debuggable / ro.build.type / ro.build.fingerprint
//
// 注意清单里**没有** /proc/self/maps —— 但它对 exit 就是绕过 libc 直接下 svc 的，
// 读文件同理可能走内联 syscall，所以 maps 也一并按同样规则过滤，多做不亏。
//
// 手法：对需要过滤的文件，先用原函数读一遍，逐行剔掉带 root/hook 框架特征的行，
// 把剩下的写进一个 memfd（纯内存，不落盘、无痕迹），再把那个 fd 包成 FILE* 还回去。
// 壳拿到的是一份完全合法、只是"干净"的挂载表。
//
// 用 NativeHook.install("env_spoof") 打开。**必须在壳加载之前装**，也就是在
// feature 的 install 阶段。

#include "chuanyi/native_hook.h"

#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <atomic>
#include <cstring>

#include "dobby.h"

namespace chuanyi {
namespace {

FILE *(*g_originalFopen)(const char *, const char *) = nullptr;
DIR *(*g_originalOpendir)(const char *) = nullptr;
int (*g_originalAccess)(const char *, int) = nullptr;
int (*g_originalPropGet)(const char *, char *) = nullptr;
int (*g_originalStat)(const char *, void *) = nullptr;
int (*g_originalLstat)(const char *, void *) = nullptr;
int (*g_originalFstatat)(int, const char *, void *, int) = nullptr;
char *(*g_originalRealpath)(const char *, char *) = nullptr;
ssize_t (*g_originalReadlink)(const char *, char *, size_t) = nullptr;
int (*g_originalStatfs)(const char *, void *) = nullptr;

std::atomic<int> g_filtered{0};
std::atomic<int> g_denied{0};

/// 行内出现这些片段就整行丢掉：root 方案、hook 框架、注入模块的痕迹。
const char *const kDirtyNeedles[] = {
    "chuanyi", "hooker",                       // 本模块自己
    "lsposed", "lspd", "edxp", "xposed",       // Xposed 系
    "zygisk", "riru", "magisk", "shamiko",     // 注入/隐藏框架
    "ksu", "kernelsu", "sus4ksu", "susfs",     // KernelSU
    "frida", "gum-js", "substrate", "gadget",  // 动态插桩
    "/data/adb",                               // 模块与配置的家
};

/// 这些文件的**内容**要过滤。
const char *const kFilteredFiles[] = {
    "/maps", "/smaps", "/mounts", "/mountinfo", "/status", "/cmdline", "/kallsyms",
};

/// 这些目录直接当作不存在。/root 存在与否是最常见的 root 判据之一。
const char *const kDeniedDirs[] = {
    "/root", "/data/adb", "/sbin/.magisk", "/debug_ramdisk",
};

/// 这些路径存在即视为已 root，一律报不存在。
const char *const kDeniedPaths[] = {
    "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
    "/system/app/Superuser.apk", "/data/adb/ksud", "/data/adb/magisk",
    "/dev/ksu", "/proc/ksu",
};

bool Contains(const char *haystack, const char *needle) {
    return strcasestr(haystack, needle) != nullptr;
}

bool AnyOf(const char *text, const char *const *list, size_t n) {
    for (size_t i = 0; i < n; ++i) {
        if (Contains(text, list[i])) return true;
    }
    return false;
}

#define ARRAY_LEN(a) (sizeof(a) / sizeof((a)[0]))

bool IsDirtyLine(const char *line) {
    return AnyOf(line, kDirtyNeedles, ARRAY_LEN(kDirtyNeedles));
}

bool NeedsContentFilter(const char *path) {
    return AnyOf(path, kFilteredFiles, ARRAY_LEN(kFilteredFiles));
}

/**
 * 读原文件，剔掉脏行，把结果放进一个匿名内存文件再交回去。
 *
 * 用 memfd 而不是临时文件：不落盘、没有路径可查、进程一退就消失，而且 `fdopen`
 * 出来的 FILE* 在调用方看来和普通文件毫无区别（能 fgets、能 fseek）。
 */
FILE *FilteredCopy(const char *path, const char *mode) {
    FILE *origin = g_originalFopen(path, "r");
    if (origin == nullptr) return nullptr;

    const int fd = static_cast<int>(::syscall(__NR_memfd_create, "cfg", 0));
    if (fd < 0) {
        // 造不出内存文件就退回原文件，宁可不过滤也别让应用读不到东西
        return origin;
    }

    // kallsyms 要额外处理：内核在 kptr_restrict 生效时把每一行的地址显示成全 0，
    // 普通应用看到的就该是那样。KernelSU 放开了这个限制，于是这里能读到真实内核
    // 地址 —— 那本身就是「已 root」的铁证，光删几行含 ksu 的符号没用。所以把地址
    // 字段整体归零，还原成未 root 该有的样子。
    const bool isKallsyms = Contains(path, "kallsyms");

    char line[8192];
    int dropped = 0;
    int zeroed = 0;
    while (fgets(line, sizeof(line), origin) != nullptr) {
        if (IsDirtyLine(line)) {
            ++dropped;
            continue;
        }
        if (isKallsyms) {
            // 行首形如 "ffffffc008000000 T _text"，把前导的十六进制地址抹成 0
            bool touched = false;
            for (int i = 0; i < 16 && line[i] != '\0'; ++i) {
                const char c = line[i];
                const bool hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
                if (!hex) break;
                if (c != '0') touched = true;
                line[i] = '0';
            }
            if (touched) ++zeroed;
        }
        const size_t len = std::strlen(line);
        if (write(fd, line, len) != static_cast<ssize_t>(len)) break;
    }
    if (zeroed > 0) {
        LogInfo("env_spoof: %s 的 %d 行内核地址已归零", path, zeroed);
    }
    fclose(origin);
    lseek(fd, 0, SEEK_SET);

    if (dropped > 0) {
        g_filtered.fetch_add(dropped);
        LogInfo("env_spoof: %s 洗掉 %d 行", path, dropped);
    }

    FILE *fake = fdopen(fd, mode != nullptr && mode[0] == 'r' ? mode : "r");
    if (fake == nullptr) {
        close(fd);
        return g_originalFopen(path, mode);
    }
    return fake;
}

FILE *ReplacementFopen(const char *path, const char *mode) {
    if (path != nullptr && NeedsContentFilter(path)) {
        LogInfo("env_spoof: fopen(\"%s\") 走过滤", path);
        return FilteredCopy(path, mode);
    }
    if (path != nullptr && AnyOf(path, kDeniedPaths, ARRAY_LEN(kDeniedPaths))) {
        g_denied.fetch_add(1);
        LogInfo("env_spoof: fopen(\"%s\") 报不存在", path);
        errno = ENOENT;
        return nullptr;
    }
    return g_originalFopen(path, mode);
}

// --- 存在性探测 -----------------------------------------------------------
//
// dlsym 清单里除了 fopen/opendir，还有 stat / realpath / statfs / getpwnam ——
// 这些都能不读内容就判断"某个东西在不在"，是 root 检测的常规配件。挂载表本来就
// 干净（fopen 一条脏行都没洗掉），所以判定多半就落在这一组上。
//
// bionic 里 stat/lstat 通常内联成 fstatat，所以三个都挂，谁在就用谁。

bool IsDeniedPath(const char *path) {
    return path != nullptr &&
           (AnyOf(path, kDeniedPaths, ARRAY_LEN(kDeniedPaths)) ||
            AnyOf(path, kDeniedDirs, ARRAY_LEN(kDeniedDirs)));
}

int ReplacementStat(const char *path, void *buf) {
    if (IsDeniedPath(path)) {
        g_denied.fetch_add(1);
        LogInfo("env_spoof: stat(\"%s\") 报不存在", path);
        errno = ENOENT;
        return -1;
    }
    return g_originalStat(path, buf);
}

int ReplacementLstat(const char *path, void *buf) {
    if (IsDeniedPath(path)) {
        g_denied.fetch_add(1);
        LogInfo("env_spoof: lstat(\"%s\") 报不存在", path);
        errno = ENOENT;
        return -1;
    }
    return g_originalLstat(path, buf);
}

int ReplacementFstatat(int dirfd, const char *path, void *buf, int flags) {
    if (IsDeniedPath(path)) {
        g_denied.fetch_add(1);
        LogInfo("env_spoof: fstatat(\"%s\") 报不存在", path);
        errno = ENOENT;
        return -1;
    }
    return g_originalFstatat(dirfd, path, buf, flags);
}

char *ReplacementRealpath(const char *path, char *resolved) {
    if (IsDeniedPath(path)) {
        g_denied.fetch_add(1);
        errno = ENOENT;
        return nullptr;
    }
    return g_originalRealpath(path, resolved);
}

ssize_t ReplacementReadlink(const char *path, char *buf, size_t len) {
    if (IsDeniedPath(path)) {
        g_denied.fetch_add(1);
        errno = ENOENT;
        return -1;
    }
    return g_originalReadlink(path, buf, len);
}

int ReplacementStatfs(const char *path, void *buf) {
    if (IsDeniedPath(path)) {
        g_denied.fetch_add(1);
        errno = ENOENT;
        return -1;
    }
    return g_originalStatfs(path, buf);
}

DIR *ReplacementOpendir(const char *path) {
    if (path != nullptr && AnyOf(path, kDeniedDirs, ARRAY_LEN(kDeniedDirs))) {
        g_denied.fetch_add(1);
        LogInfo("env_spoof: opendir(\"%s\") 报不存在", path);
        errno = ENOENT;
        return nullptr;
    }
    return g_originalOpendir(path);
}

int ReplacementAccess(const char *path, int mode) {
    if (path != nullptr && AnyOf(path, kDeniedPaths, ARRAY_LEN(kDeniedPaths))) {
        g_denied.fetch_add(1);
        errno = ENOENT;
        return -1;
    }
    return g_originalAccess(path, mode);
}

/**
 * 系统属性里那几个能暴露环境的，给出"零售机 + 真机"该有的答案。
 *
 * 实测这台机器上 ro.secure / ro.debuggable 读回来是**空串**（属性不存在），
 * 而正规零售机上它们分别是 "1" 和 "0" —— 空值本身就是异常信号，所以这里补上。
 */
int ReplacementPropGet(const char *name, char *value) {
    const int rc = g_originalPropGet(name, value);
    if (name == nullptr || value == nullptr) return rc;

    struct Fix {
        const char *key;
        const char *val;
    };
    static const Fix kFixes[] = {
        {"ro.secure", "1"},
        {"ro.debuggable", "0"},
        {"ro.build.type", "user"},
        {"ro.build.tags", "release-keys"},
        {"ro.kernel.qemu", ""},
        {"ro.boot.selinux", "enforcing"},
        {"init.svc.adbd", "stopped"},
    };
    for (const auto &fix : kFixes) {
        if (std::strcmp(name, fix.key) != 0) continue;
        // 只在读到的值"不对"时才改，尽量少动
        if (std::strcmp(value, fix.val) == 0) return rc;
        std::strcpy(value, fix.val);
        return static_cast<int>(std::strlen(fix.val));
    }
    return rc;
}

bool HookOne(const char *symbol, void *replacement, void **original) {
    void *target = FindSymbol("libc.so", symbol);
    if (target == nullptr) {
        LogWarn("env_spoof: libc.so!%s 没找到", symbol);
        return false;
    }
    const int rc = DobbyHook(target, reinterpret_cast<dobby_dummy_func_t>(replacement),
                             reinterpret_cast<dobby_dummy_func_t *>(original));
    if (rc != 0 || *original == nullptr) {
        LogError("env_spoof: hook %s 失败 (%d)", symbol, rc);
        return false;
    }
    return true;
}

bool Install() {
    int ok = 0;
    ok += HookOne("fopen", reinterpret_cast<void *>(ReplacementFopen),
                  reinterpret_cast<void **>(&g_originalFopen));
    ok += HookOne("opendir", reinterpret_cast<void *>(ReplacementOpendir),
                  reinterpret_cast<void **>(&g_originalOpendir));
    ok += HookOne("access", reinterpret_cast<void *>(ReplacementAccess),
                  reinterpret_cast<void **>(&g_originalAccess));
    ok += HookOne("__system_property_get", reinterpret_cast<void *>(ReplacementPropGet),
                  reinterpret_cast<void **>(&g_originalPropGet));
    ok += HookOne("stat", reinterpret_cast<void *>(ReplacementStat),
                  reinterpret_cast<void **>(&g_originalStat));
    ok += HookOne("lstat", reinterpret_cast<void *>(ReplacementLstat),
                  reinterpret_cast<void **>(&g_originalLstat));
    ok += HookOne("fstatat", reinterpret_cast<void *>(ReplacementFstatat),
                  reinterpret_cast<void **>(&g_originalFstatat));
    ok += HookOne("realpath", reinterpret_cast<void *>(ReplacementRealpath),
                  reinterpret_cast<void **>(&g_originalRealpath));
    ok += HookOne("readlink", reinterpret_cast<void *>(ReplacementReadlink),
                  reinterpret_cast<void **>(&g_originalReadlink));
    ok += HookOne("statfs", reinterpret_cast<void *>(ReplacementStatfs),
                  reinterpret_cast<void **>(&g_originalStatfs));
    if (ok == 0) {
        LogError("env_spoof: 一个都没挂上");
        return false;
    }
    // 真正的暴露点在 linker 的已加载对象链表里（模块 APK 的映射），
    // 那条路不碰任何文件，光过滤 /proc 是看不住的 —— 一并装上。
    InstallNativeHooker("linkmap_hide");

    LogInfo("env_spoof: %d/10 就位 —— 挂载表/maps 逐行过滤，root 路径一律报不存在，属性给零售机答案", ok);
    return true;
}

} // namespace

// 第三个参数会原样显示在模块界面上，所以写中文、不写实现细节。
CHUANYI_NATIVE_HOOKER(kEnvSpoof,
                      "env_spoof",
                      "把加固壳读到的挂载表、目录与系统属性洗成未 root 的样子",
                      Install)

} // namespace chuanyi
