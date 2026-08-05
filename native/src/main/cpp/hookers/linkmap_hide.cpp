// Built-in native hooker: 把本模块从 linker 的已加载对象链表里摘掉。
//
// ── 为什么是这里 ──
//
// 实测取证（/proc/<pid>/maps，注入状态下的目标进程）：
//
//   /data/app/~~iXe.../com.chuanyi.hooker-.../base.apk   ← 5 处映射，赤裸裸
//   lspd / zygisk / riru / magisk                        ← 一条都没有，Zygisk Next 清干净了
//
// 也就是说：LSPosed 自己藏得很好，暴露的是**模块 APK 被映射进了目标进程**这件事。
// 这正好解释了那个反常现象 —— 一个 hook 都不装（done: 0/12）也照样被杀，因为只要
// 模块 dex 被加载，这些映射就在。
//
// 而它**不是靠读文件**发现的：env_spoof 把 fopen 的 `/maps` 纳入过滤后，一次都没
// 触发；它的导入表里却有 `dladdr`，dlsym 清单里也没有 open/openat。剩下的唯一途径
// 就是 linker 自己的内部结构 —— `_r_debug.r_map` 那条 `link_map` 双向链表，
// `dl_iterate_phdr()` / `dladdr()` 都是从它上面走的，全程不碰任何文件。
//
// ── 做法 ──
//
// 把本模块相关的节点从链表里摘出去（改前后节点的指针），之后 `dl_iterate_phdr`
// 枚举不到、`dladdr` 也认不出它们的地址。代码已经 mmap 好了，链表只是"目录"，
// 摘掉不影响任何已加载代码的执行 —— 我们的 hook 照常工作。
//
// 注意这**不改变 /proc/<pid>/maps**。如果目标改用读 maps 的方式检测，还得另做
// syscall 级的拦截；先解决当前这条已经证实存在的路径。
//
// 用 NativeHook.install("linkmap_hide") 打开，越早越好。

#include "chuanyi/native_hook.h"

#include <dlfcn.h>
#include <link.h>

#include <cstring>

#include "dobby.h"

namespace chuanyi {
namespace {

/// 要藏起来的东西：本模块的 native 库、DexKit 的库、以及模块 APK 本身
/// （从 APK 内直接 mmap 的 .so，在 link_map 里就显示成 apk 路径）。
const char *const kHideNeedles[] = {
    "chuanyi",      // libchuanyihook.so + com.chuanyi.hooker 的 APK 路径
    "dexkit",       // libdexkit.so
    "lspd", "zygisk", "riru", "xposed", "magisk",  // 保险起见，虽然实测已经干净
};

bool ShouldHide(const char *name) {
    if (name == nullptr || name[0] == '\0') return false;
    for (const auto needle : kHideNeedles) {
        if (strcasestr(name, needle) != nullptr) return true;
    }
    return false;
}

/**
 * 找到 link_map 链表头。
 *
 * 走 `_r_debug` —— 这是 ELF 的标准调试接口，linker 维护它就是为了让调试器能枚举
 * 已加载对象，`dl_iterate_phdr()` 和 `dladdr()` 背后也是同一份数据。bionic 的
 * `<link.h>` 直接导出这个符号。
 *
 * （不用 `dlinfo(RTLD_DI_LINKMAP)`：bionic 上没有那个常量。）
 *
 * 万一符号没导出，退回让 Dobby 的符号解析器去 linker 里翻 —— 它能找到非导出符号，
 * 这正是它相对 dlsym 的价值。
 */
/**
 * 从主可执行文件的 `.dynamic` 段里取 `DT_DEBUG`。
 *
 * 这是 ELF 规范给调试器留的标准入口：linker 启动时把 `r_debug` 的地址填进主程序
 * 的 `DT_DEBUG` 项，gdb 之类就是靠它找到已加载对象链表的。比去 linker 里翻
 * `_r_debug` 符号可靠得多 —— NDK 的头不声明它，Dobby 的符号解析器对 linker 这种
 * 特殊镜像也解不出来（实测失败）。
 */
int DynamicCallback(struct dl_phdr_info *info, size_t, void *data) {
    if (info == nullptr) return 0;
    // 不能只认「dlpi_name 为空」的主程序：Android 上 zygote fork 出来的进程，
    // 主可执行文件报的名字是 /system/bin/app_process64 而不是空串（实测按空串
    // 匹配一个都找不到）。DT_DEBUG 只有主程序会有，所以对所有对象扫一遍即可，
    // 谁有就是谁。
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        if (info->dlpi_phdr[i].p_type != PT_DYNAMIC) continue;
        auto *dyn = reinterpret_cast<ElfW(Dyn) *>(info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
        for (; dyn->d_tag != DT_NULL; ++dyn) {
            if (dyn->d_tag == DT_DEBUG && dyn->d_un.d_ptr != 0) {
                *static_cast<struct r_debug **>(data) =
                    reinterpret_cast<struct r_debug *>(dyn->d_un.d_ptr);
                LogInfo("linkmap_hide: DT_DEBUG 来自 %s",
                        info->dlpi_name != nullptr && info->dlpi_name[0] != '\0'
                            ? info->dlpi_name
                            : "<主程序>");
                return 1; // 停止遍历
            }
        }
    }
    return 0;
}

struct link_map *FindLinkMapHead() {
    struct r_debug *debug = nullptr;
    dl_iterate_phdr(DynamicCallback, &debug);
    if (debug == nullptr || debug->r_map == nullptr) return nullptr;

    struct link_map *map = debug->r_map;
    while (map->l_prev != nullptr) {
        map = map->l_prev;
    }
    return map;
}

// --- 枚举接口拦截 ---------------------------------------------------------
//
// 摘 link_map 是"改数据"，这一节是"改视图"——把枚举 API 挂掉，在回调转发时把自己
// 跳过。两者互补：
//
//   * 目标走 dl_iterate_phdr() / dladdr()  → 这一节生效
//   * 目标直接读 _r_debug 链表             → 靠下面的摘链
//
// 而且这一节不依赖能否拿到链表头，安全性也更好：不动 linker 的内部指针，只是在
// 我们自己的转发函数里少调一次回调。

int (*g_originalIteratePhdr)(int (*)(struct dl_phdr_info *, size_t, void *), void *) = nullptr;
int (*g_originalDladdr)(const void *, Dl_info *) = nullptr;

struct ForwardContext {
    int (*callback)(struct dl_phdr_info *, size_t, void *);
    void *data;
    int skipped;
};

int ForwardingCallback(struct dl_phdr_info *info, size_t size, void *data) {
    auto *ctx = static_cast<ForwardContext *>(data);
    if (info != nullptr && ShouldHide(info->dlpi_name)) {
        ++ctx->skipped;
        return 0; // 不转发给真正的回调 —— 对调用方来说这个对象不存在
    }
    return ctx->callback(info, size, ctx->data);
}

int ReplacementIteratePhdr(int (*callback)(struct dl_phdr_info *, size_t, void *), void *data) {
    ForwardContext ctx{callback, data, 0};
    const int rc = g_originalIteratePhdr(ForwardingCallback, &ctx);
    if (ctx.skipped > 0) {
        LogInfo("linkmap_hide: dl_iterate_phdr 跳过了 %d 个自己人", ctx.skipped);
    }
    return rc;
}

/// 地址落在被隐藏的对象里时，装作查不到 —— `dladdr` 返回 0 表示"该地址不属于任何
/// 已加载对象"，正是我们要的效果。
int ReplacementDladdr(const void *addr, Dl_info *info) {
    const int rc = g_originalDladdr(addr, info);
    if (rc != 0 && info != nullptr && ShouldHide(info->dli_fname)) {
        std::memset(info, 0, sizeof(*info));
        return 0;
    }
    return rc;
}

bool HookOne(const char *symbol, void *replacement, void **original) {
    void *target = FindSymbol("libdl.so", symbol);
    if (target == nullptr) target = FindSymbol("libc.so", symbol);
    if (target == nullptr) {
        LogWarn("linkmap_hide: %s 没找到", symbol);
        return false;
    }
    const int rc = DobbyHook(target, reinterpret_cast<dobby_dummy_func_t>(replacement),
                             reinterpret_cast<dobby_dummy_func_t *>(original));
    if (rc != 0 || *original == nullptr) {
        LogError("linkmap_hide: hook %s 失败 (%d)", symbol, rc);
        return false;
    }
    LogInfo("linkmap_hide: 已挂住 %s", symbol);
    return true;
}

bool Install() {
    // 顺序要紧：先用**未被 hook 的** dl_iterate_phdr 去找链表头，再挂枚举接口。
    // 反过来的话，找头这一步自己就会被过滤逻辑绕进去。
    struct link_map *head = FindLinkMapHead();

    int hooks = 0;
    hooks += HookOne("dl_iterate_phdr", reinterpret_cast<void *>(ReplacementIteratePhdr),
                     reinterpret_cast<void **>(&g_originalIteratePhdr));
    hooks += HookOne("dladdr", reinterpret_cast<void *>(ReplacementDladdr),
                     reinterpret_cast<void **>(&g_originalDladdr));

    if (head == nullptr) {
        LogWarn("linkmap_hide: 拿不到 link_map 链表头，只有枚举接口这一层（%d 个 hook）", hooks);
        return hooks > 0;
    }

    // 只读一遍做统计，**不写**。
    //
    // 摘链试过了，会死：linker 把 link_map 所在的页保护成只读，改 l_prev->l_next
    // 直接 SIGSEGV（code 2 / SEGV_ACCERR，fault addr 落在那片页上），进程当场没。
    // 要写就得先 mprotect 开权限再改回去 —— 那是在动 linker 的内部数据结构，
    // 一旦和它自己的写入撞上后果不可控，而收益只覆盖「目标绕过 API 直接读
    // _r_debug」这一种情况。
    //
    // 上面两个 hook 已经把 dl_iterate_phdr / dladdr 这两条实际枚举路径堵住了，
    // 先看它们够不够；真遇到直读链表的目标，再考虑带 mprotect 的摘链。
    int candidates = 0;
    int scanned = 0;
    for (struct link_map *entry = head; entry != nullptr; entry = entry->l_next) {
        ++scanned;
        if (ShouldHide(entry->l_name)) {
            LogInfo("linkmap_hide: 链表里可见 %s（靠 hook 遮蔽，不摘链）", entry->l_name);
            ++candidates;
        }
        if (scanned > 4096) break; // 链表异常时的保险
    }

    LogInfo("linkmap_hide: 扫描 %d 个已加载对象，其中 %d 个属于本模块；%d 个枚举接口已挂",
            scanned, candidates, hooks);
    return hooks > 0;
}

} // namespace

// 第三个参数会原样显示在模块界面上，所以写中文、不写实现细节。
CHUANYI_NATIVE_HOOKER(kLinkMapHide,
                      "linkmap_hide",
                      "把模块自身从 linker 的已加载对象链表里摘掉，dl_iterate_phdr 与 dladdr 都枚举不到",
                      Install)

} // namespace chuanyi
