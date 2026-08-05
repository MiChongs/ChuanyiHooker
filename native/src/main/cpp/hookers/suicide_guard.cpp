// Built-in native hooker: stop a process from SIGKILL-ing itself.
//
// 针对「加固的 RASP 检测到注入就自杀」这一类保护。判据来自 ApplicationExitInfo：
//
//     reason=2 (SIGNALED) status=9
//
// 也就是 SIGKILL —— 不是崩溃（那是 reason=5/APP CRASH），也不是 exit（reason=1），
// 更不是系统回收（reason=3/LOW_MEMORY、reason=13）。SIGKILL 本身无法被捕获或忽略，
// 但**发出它的那次调用**可以拦，这就是这里做的事。
//
// 为什么不能用 NativeHook.returnConstant 把 libc 的 kill/tgkill/raise 直接改成返回 0：
// ART 自己重度依赖这几个函数 —— 线程挂起、GC checkpoint、SIGQUIT dump 全走 tgkill。
// 无差别拦截会把运行时打断，实测表现为
//
//     Check failed: self->tlsPtr_.jpeer != nullptr
//     art::Thread::CreateCallback + art::Runtime::Abort
//
// 即新建线程时 peer 为空。所以必须是带条件的 inline hook：**只挡对自己进程的
// SIGKILL，其余信号原样转发**，ART 用的那些一个都不受影响。
//
// 覆盖三条路径，因为加固通常不止用一条：
//
//   kill(pid, SIGKILL)              最直接的写法
//   tgkill(tgid, tid, SIGKILL)      raise() 内部也走它
//   syscall(__NR_kill / __NR_tgkill / __NR_exit_group, ...)
//                                   绕开 libc 包装的写法，专门用来躲 hook
//
// 有意**不拦** exit/_exit/abort：这个目标的死因是 SIGNALED 而不是 EXIT，拦了只会
// 让应用退不掉，还可能在 ART abort 路径上制造未定义行为。
//
// 用 NativeHook.install("suicide_guard") 打开。

#include "chuanyi/native_hook.h"

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <pthread.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>

#include <cstddef>
#include <cstring>
#include <csignal>
#include <atomic>

#include "dobby.h"

namespace chuanyi {
namespace {

int (*g_originalKill)(pid_t, int) = nullptr;
int (*g_originalTgkill)(int, int, int) = nullptr;
long (*g_originalSyscall)(long, long, long, long, long, long, long) = nullptr;
void (*g_originalExit)(int) = nullptr;
void (*g_originalUnderscoreExit)(int) = nullptr;
int (*g_originalPthreadCreate)(pthread_t *, const pthread_attr_t *, void *(*)(void *), void *) = nullptr;
pid_t (*g_originalFork)() = nullptr;

std::atomic<int> g_blocked{0};

/// 加固壳所在库的名字片段。它的代码解密后可能跑在匿名内存里，那种情况 dladdr
/// 查不到归属 —— 见 [IsPackerCaller]。
constexpr const char *kPackerHint = "5885";

/**
 * 这次 exit 是不是壳发起的。
 *
 * 两种都算：
 *
 *  1. 调用者落在 lib__5885__.so 的映射里 —— 壳的解密器本体；
 *  2. `dladdr` 查不到调用者属于哪个模块 —— 正常代码都有模块归属，查不到说明它在
 *     匿名可执行内存里，而这个进程里会那么干的只有自解密壳（51 KB 的 .text 之外
 *     还挂着 1.1 MB 不映射的载荷，解密后必然落到匿名页）。
 *
 * 反过来，应用自己、ART、各种 SDK 调 exit 时 `dladdr` 都查得到归属且不含 5885，
 * 一律放行 —— 所以这不会让应用退不掉，和无差别拦 exit 有本质区别。
 */
bool IsPackerCaller(void *caller, const char **outName) {
    Dl_info info;
    if (dladdr(caller, &info) == 0 || info.dli_fname == nullptr) {
        *outName = "<匿名内存>";
        return true;
    }
    *outName = info.dli_fname;
    return std::strstr(info.dli_fname, kPackerHint) != nullptr;
}

/// 只有「送给自己进程的 SIGKILL」才算自杀。
///
/// pid == 0 是「发给自己所在的整个进程组」，-getpid() 是「发给自己这个进程组」，
/// 两者都会连自己一起杀掉，所以一并算进来。发给别的进程的 SIGKILL 一律放行 ——
/// 那是应用管理自己子进程的正常行为，不该拦。
bool IsSelfKill(pid_t pid, int sig) {
    if (sig != SIGKILL) return false;
    const pid_t self = getpid();
    return pid == self || pid == 0 || pid == -self;
}

void NoteBlocked(const char *how, long a, long b) {
    const int n = g_blocked.fetch_add(1) + 1;
    LogWarn("suicide_guard: 挡下第 %d 次自杀 —— %s(%ld, %ld)，进程保留", n, how, a, b);
}

int ReplacementKill(pid_t pid, int sig) {
    if (IsSelfKill(pid, sig)) {
        NoteBlocked("kill", pid, sig);
        return 0; // 假装成功，调用方不会重试
    }
    return g_originalKill(pid, sig);
}

int ReplacementTgkill(int tgid, int tid, int sig) {
    // 只看 tgid：SIGKILL 送给本进程里的**任何**线程都会带走整个进程。
    if (sig == SIGKILL && tgid == getpid()) {
        NoteBlocked("tgkill", tgid, tid);
        return 0;
    }
    return g_originalTgkill(tgid, tid, sig);
}

/**
 * 壳的主要出口：干净地 `exit(0)`。
 *
 * 实测退出记账是 `reason=1 (EXIT_SELF) status=0 description=null` —— 没有崩溃、
 * 没有信号、退出码 0，就是主动调了 exit。时点卡在 `load.entry()` 返回之后、
 * `ApplicationStatus.xclinitx0_0_00()` 之前，正是壳跑起来的那一小段。
 *
 * 只挡壳发起的那一次，应用自己要退随时能退。
 */
void ReplacementExit(int code) {
    const char *from = nullptr;
    void *caller = __builtin_return_address(0);
    if (IsPackerCaller(caller, &from)) {
        NoteBlocked("exit", code, reinterpret_cast<long>(caller));
        LogWarn("suicide_guard:   调用者来自 %s", from);
        return; // exit 的调用点后面没有代码，直接返回是安全的
    }
    g_originalExit(code);
}

void ReplacementUnderscoreExit(int code) {
    const char *from = nullptr;
    void *caller = __builtin_return_address(0);
    if (IsPackerCaller(caller, &from)) {
        NoteBlocked("_exit", code, reinterpret_cast<long>(caller));
        LogWarn("suicide_guard:   调用者来自 %s", from);
        return;
    }
    g_originalUnderscoreExit(code);
}

/// syscall(2) 是变参的，但 arm64/arm/x86_64 上前若干个参数都在寄存器里，用固定
/// 七参数的替换函数接收再原样转发是安全的 —— 原函数不读的槽位会被忽略。
long ReplacementSyscall(long number, long a1, long a2, long a3, long a4, long a5, long a6) {
#ifdef __NR_exit_group
    // 绕开 libc exit 直接下 syscall 的写法，同样按调用者判定。
    if (number == __NR_exit_group) {
        const char *from = nullptr;
        void *caller = __builtin_return_address(0);
        if (IsPackerCaller(caller, &from)) {
            NoteBlocked("syscall/exit_group", a1, reinterpret_cast<long>(caller));
            LogWarn("suicide_guard:   调用者来自 %s", from);
            return 0;
        }
    }
#endif
#ifdef __NR_kill
    if (number == __NR_kill && IsSelfKill(static_cast<pid_t>(a1), static_cast<int>(a2))) {
        NoteBlocked("syscall/kill", a1, a2);
        return 0;
    }
#endif
#ifdef __NR_tgkill
    if (number == __NR_tgkill && static_cast<int>(a3) == SIGKILL &&
        static_cast<pid_t>(a1) == getpid()) {
        NoteBlocked("syscall/tgkill", a1, a3);
        return 0;
    }
#endif
    return g_originalSyscall(number, a1, a2, a3, a4, a5, a6);
}

// --- 不让检测跑起来 -------------------------------------------------------
//
// 拦出口这条路已经证明走不通：Java 的四个出口、libc 的 exit/_exit/kill/tgkill/
// syscall(exit_group) 全挂上了，**一次都没触发**，进程照样没了 —— 说明它是内联
// svc 直接下 syscall，专门躲 libc hook，而内联指令没有函数可挂。
//
// 那就换一端：让检测**根本不执行**。它的 dlsym 清单里有 pthread_create 和 fork，
// 而实测同一配置下应用有时能活下来，正是异步检测与主线程赛跑的样子。
//
// 线程照建、返回值照样是成功，只把线程体换成一个立刻返回的空函数 —— 壳看到的是
// "线程创建成功"，但那段检测逻辑一条都不会跑。比让 pthread_create 返回错误温和：
// 返回错误可能直接把它推进错误分支里去自杀。

// 试过、但**不能**这么干：把壳的线程体替换成空函数。
//
// 实测它只创建一种线程（4 次 pthread_create 全是同一个 start 地址），那个线程既做
// 检测**也做正常初始化**（字符串池的解密就在里面）。废掉它的结果是应用永远卡在
// 启动画面 —— 壳在等一个永远不会完成的初始化。所以这两个 hook 已经撤掉，只留下
// 按调用方过滤的 exit / SIGKILL 拦截，加上下面的 seccomp。

/**
 * 在 syscall 层拦 `exit_group`。
 *
 * 为什么必须走到这一层：函数级 hook 全部落空过一遍 —— Java 的 System.exit /
 * Runtime.halt / Process.killProcess 四个出口、libc 的 exit / _exit /
 * syscall(exit_group) / kill / tgkill 五个出口，**一次都没触发**，进程照样
 * EXIT_SELF/status=0 没了。结论只有一个：它是内联 `svc` 直接下的 syscall，而内联
 * 指令没有函数入口可挂。
 *
 * seccomp 恰好不关心调用方式 —— BPF 过滤器看的是内核收到的 syscall 号，内联 svc
 * 和 libc 包装在它眼里一模一样。把 `exit_group` 变成返回 EPERM，壳就退不掉了。
 *
 * 两个代价，都可接受：
 *
 *  * BPF 看不到返回地址，**没法只拦壳**——应用自己想正常退出也会失败。要关掉应用
 *    得用「强行停止」（那是 SIGKILL，不经过 syscall 过滤，照常有效）。
 *  * filter 一旦装上不可撤销，只能随进程消亡。
 *
 * `SECCOMP_FILTER_FLAG_TSYNC` 是关键：`prctl(PR_SET_SECCOMP)` 只作用于当前线程，
 * 而检测跑在壳自己的线程里。TSYNC 会把过滤器同步到进程内所有线程，之后新建的线程
 * 也自动继承。
 *
 * Android 的 zygote 本来就装了 seccomp 过滤器，多装一层是允许的：内核按链依次求值，
 * 取最严格的结果，所以这不会影响系统原有的限制。
 */
/**
 * SIGSYS 处理器：把想退出的那个线程就地冻住。
 *
 * 为什么不能只让 `exit_group` 返回错误 —— 实测过了：它是 noreturn，编译器认定它
 * 后面的代码不可达，于是那里往往根本不是有效指令。让它带着 EPERM 返回，壳一头撞
 * 进那段垃圾，进程死于 **SIGILL**（退出记账 status=4）。拦是拦住了，人还是没了。
 *
 * 所以这里用 `SECCOMP_RET_TRAP`：内核不让 syscall 执行，转而向线程投递 SIGSYS，
 * 控制权落到这个处理器手里 —— 而处理器**可以选择不返回**。让线程停在 `pause()`
 * 上永久休眠，调用点后面那段无效指令就永远执行不到，进程其余部分照常跑。
 *
 * 代价：那个线程从此挂起。对检测线程来说无所谓，它本来的使命就是杀掉进程。
 */
/// 白名单目录 fd。见 [InstallSeccompExitGuard] 里的说明：handler 自己要调 openat，
/// 得有办法让它不再被自己拦一次。
int g_bypassDirFd = -1;

/**
 * 生成一份"干净"的 /proc/self/maps，返回它的 fd。
 *
 * 只做一件事：把带本模块痕迹的行整行丢掉。maps 里那 5 处
 * `/data/app/.../com.chuanyi.hooker-.../base.apk` 是注入唯一藏不住的破绽 ——
 * 实测零 hook（done: 0/12）也被杀，就是因为它们在。
 *
 * 全程只用 read/write/openat/memfd_create 这几个裸 syscall，不碰 stdio：这段代码
 * 跑在信号处理器里，malloc 之类不可重入的东西一律不能用。
 */
int MakeCleanMaps(const char *path) {
    const int src = openat(g_bypassDirFd, path, O_RDONLY | O_CLOEXEC);
    if (src < 0) return -1;
    const int dst = static_cast<int>(::syscall(__NR_memfd_create, "m", 0));
    if (dst < 0) {
        close(src);
        return -1;
    }

    char chunk[4096];
    char line[2048];
    size_t used = 0;
    ssize_t got;
    int kept = 0;
    int dropped = 0;
    while ((got = read(src, chunk, sizeof(chunk))) > 0) {
        for (ssize_t i = 0; i < got; ++i) {
            if (used < sizeof(line) - 1) line[used++] = chunk[i];
            if (chunk[i] != '\n') continue;
            line[used] = '\0';
            if (std::strstr(line, "chuanyi") == nullptr &&
                std::strstr(line, "hooker") == nullptr &&
                std::strstr(line, "dexkit") == nullptr) {
                (void) write(dst, line, used);
                ++kept;
            } else {
                ++dropped;
            }
            used = 0;
        }
    }
    close(src);
    lseek(dst, 0, SEEK_SET);
    LogWarn("suicide_guard: maps 已换假货 —— 保留 %d 行，抹掉 %d 行", kept, dropped);
    return dst;
}

bool IsMapsPath(const char *path) {
    if (path == nullptr) return false;
    // /proc/self/maps、/proc/<pid>/maps、以及 task 下的每线程 maps
    return std::strstr(path, "/maps") != nullptr && std::strncmp(path, "/proc/", 6) == 0;
}

void SigsysHandler(int, siginfo_t *, void *context) {
    auto *uc = static_cast<ucontext_t *>(context);
#if defined(__aarch64__)
    const long nr = static_cast<long>(uc->uc_mcontext.regs[8]);
#elif defined(__arm__)
    const long nr = static_cast<long>(uc->uc_mcontext.arm_r7);
#else
    const long nr = -1;
#endif

#if defined(__aarch64__)
    // openat：把 maps 换成过滤后的版本，其余原样放行。
    //
    // seccomp 拦下 syscall 时它并没有执行，内核把控制权交给这里，PC 已经指向 svc
    // 的下一条指令 —— 所以只要把返回值写进 x0，对调用方来说就跟 syscall 正常返回
    // 一模一样。
    if (nr == __NR_openat) {
        const char *path = reinterpret_cast<const char *>(uc->uc_mcontext.regs[1]);
        const int flags = static_cast<int>(uc->uc_mcontext.regs[2]);
        const int mode = static_cast<int>(uc->uc_mcontext.regs[3]);

        long result;
        if (IsMapsPath(path)) {
            result = MakeCleanMaps(path);
            if (result >= 0) NoteBlocked("seccomp/openat(maps)", result, 0);
        } else {
            // 用白名单 dirfd 重放。路径是绝对路径时 dirfd 被内核忽略，语义不变；
            // 相对路径的情况下这里换了基准目录，但 /proc 这类检测用的都是绝对路径。
            result = openat(g_bypassDirFd, path, flags, mode);
        }
        if (result < 0) result = -errno;
        uc->uc_mcontext.regs[0] = static_cast<unsigned long>(result);
        return;
    }
#endif

    NoteBlocked("seccomp/exit_group", nr, 0);
    // 绝不返回。pause() 会被信号打断，所以要套在循环里。
    while (true) {
        pause();
    }
}

bool InstallSeccompExitGuard() {
    struct sigaction sa {};
    sa.sa_sigaction = SigsysHandler;
    sa.sa_flags = SA_SIGINFO | SA_NODEFER;
    sigemptyset(&sa.sa_mask);
    if (sigaction(SIGSYS, &sa, nullptr) != 0) {
        LogWarn("suicide_guard: SIGSYS 处理器装不上（%s）", std::strerror(errno));
        return false;
    }

    // handler 自己也要调 openat（去读真 maps、重放非 maps 的请求），得有办法不被
    // 自己再拦一次。用一个预先打开的目录 fd 当暗号：BPF 看到 args[0] 是它就放行。
    // 比在 flags 里塞魔法位安全 —— 内核会对未知 open flag 报 EINVAL，而 dirfd 在
    // 路径为绝对路径时本就被忽略，塞什么值都不改变语义。
    g_bypassDirFd = open("/", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (g_bypassDirFd < 0) {
        LogWarn("suicide_guard: 拿不到白名单 dirfd（%s），不拦 openat", std::strerror(errno));
    }

    struct sock_filter filter[] = {
        // A = seccomp_data.nr（syscall 号）
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, static_cast<__u32>(offsetof(struct seccomp_data, nr))),
#ifdef __NR_exit_group
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_exit_group, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#endif
#ifdef __NR_openat
        // openat 且不是 handler 自己发的 → 交给 SIGSYS 处理
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_openat, 0, 3),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                 static_cast<__u32>(offsetof(struct seccomp_data, args[0]))),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, static_cast<__u32>(g_bypassDirFd), 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#endif
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog prog = {
        .len = static_cast<unsigned short>(sizeof(filter) / sizeof(filter[0])),
        .filter = filter,
    };

    // 没有 CAP_SYS_ADMIN 时装 filter 要求 no_new_privs。app 进程通常已经置位，
    // 重复设置无害。
    prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);

    // 直接下 syscall 而不用 prctl(PR_SET_SECCOMP)：只有 seccomp(2) 这个入口才支持
    // TSYNC，把过滤器铺到所有线程上。
    const long rc = ::syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER,
                              SECCOMP_FILTER_FLAG_TSYNC, &prog);
    if (rc != 0) {
        LogWarn("suicide_guard: seccomp 装不上（%s），只能靠函数级拦截", std::strerror(errno));
        return false;
    }
    LogInfo("suicide_guard: seccomp 已就位 —— exit_group 转 SIGSYS，内联 svc 也拦得住");
    return true;
}

bool HookOne(const char *symbol, void *replacement, void **original) {
    void *target = FindSymbol("libc.so", symbol);
    if (target == nullptr) {
        LogWarn("suicide_guard: libc.so!%s 没找到", symbol);
        return false;
    }
    const int rc = DobbyHook(target, reinterpret_cast<dobby_dummy_func_t>(replacement),
                             reinterpret_cast<dobby_dummy_func_t *>(original));
    if (rc != 0 || *original == nullptr) {
        LogError("suicide_guard: hook %s 失败 (%d)", symbol, rc);
        return false;
    }
    LogInfo("suicide_guard: 已挂住 %s @ %p", symbol, target);
    return true;
}

bool Install() {
    int ok = 0;
    ok += HookOne("kill", reinterpret_cast<void *>(ReplacementKill),
                  reinterpret_cast<void **>(&g_originalKill));
    ok += HookOne("tgkill", reinterpret_cast<void *>(ReplacementTgkill),
                  reinterpret_cast<void **>(&g_originalTgkill));
    ok += HookOne("syscall", reinterpret_cast<void *>(ReplacementSyscall),
                  reinterpret_cast<void **>(&g_originalSyscall));
    ok += HookOne("exit", reinterpret_cast<void *>(ReplacementExit),
                  reinterpret_cast<void **>(&g_originalExit));
    ok += HookOne("_exit", reinterpret_cast<void *>(ReplacementUnderscoreExit),
                  reinterpret_cast<void **>(&g_originalUnderscoreExit));
    // 真正拦得住内联 svc 的是这一条；上面那些函数级 hook 留着，用于打日志和
    // 覆盖走 libc 的调用方。
    const bool seccomp = InstallSeccompExitGuard();

    if (ok == 0 && !seccomp) {
        LogError("suicide_guard: 一个出口都没挂上");
        return false;
    }
    LogInfo("suicide_guard: %d/5 个函数出口 + seccomp(%s)", ok, seccomp ? "已装" : "失败");
    return true;
}

} // namespace

// 第三个参数会原样显示在模块界面上，所以写中文、不写实现细节。
CHUANYI_NATIVE_HOOKER(kSuicideGuard,
                      "suicide_guard",
                      "挡住应用检测到注入后给自己发的 SIGKILL（不影响其它信号）",
                      Install)

} // namespace chuanyi
