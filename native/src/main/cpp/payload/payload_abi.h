// 壳内代码与宿主之间的唯一接口。
//
// 这个头被两边同时包含：payload/payload.cpp（编成一段无重定位的扁平代码，运行时
// 解密进匿名内存执行）和 activation.cpp（宿主侧的壳，负责解密、映射、调用）。
// 改这里的任何一个字段都要两边一起改 —— 壳里那段代码是按结构体偏移访问的，
// 对不上不会报错，只会读到垃圾然后判定「未激活」。
//
// ## 为什么壳里一个 libc 符号都不能引用
//
// 那段代码被 `objcopy -O binary` 抽成纯字节，运行时整块拷进 `mmap` 出来的匿名页
// 直接跳进去。它没有 ELF 头、没有 GOT、没有 linker 参与，所以：
//
//  * 不能引用任何外部符号（libc、compiler-rt 都不行）—— 没人给它解析；
//  * 不能有任何重定位 —— 没人给它重定位，链接期的地址和运行时的 mmap 地址不同；
//  * 因此也不能取任何静态数据的地址（字符串字面量、静态数组、函数指针表）：
//    x86 上那会编成绝对地址，blob 一搬家就全错。
//
// 前两条由构建期的 `pack_payload.cmake` 检查并在违反时直接中断构建；第三条只能靠
// 写的时候注意（用逐字节立即数比较代替字符串字面量，见 payload.cpp）。
//
// 系统调用因此全部走 [HostOps] 这张表：宿主用普通 C++ 写好包装函数，把函数指针填
// 进来。壳里只会 `blr x8` 这种间接调用，不产生任何重定位。

#pragma once

#include <cstddef>
#include <cstdint>

namespace chuanyi::guard {

/// 结构体版本。宿主填，壳里校验 —— 两边版本不一致时直接失败，而不是照着错误的
/// 偏移去读内存。
inline constexpr uint32_t kAbiMagic = 0x43594731u; // 'CYG1'

/**
 * 令牌长度与布局。
 *
 * ```
 * [0..3]   magic 'CYT1'
 * [4..7]   签发日（Unix 纪元日）  u32 LE
 * [8..11]  nonce                  u32 LE
 * [12..15] 签发方包名的 FNV-1a    u32 LE
 * [16..23] MAC                    u64 LE = SipHash(key, [0..15] || moduleVersion)
 * ```
 *
 * 签发方进令牌、并且进 MAC，是为了让**定向撤销**成立：一台机器上可能同时装着好几个
 * TG 客户端，只有当初签发的那一个才有资格说「群没了」。要是把签发方存在令牌外面的
 * 普通配置项里，改一下就能让撤销永远打不中自己。
 */
inline constexpr uint32_t kTokenBytes = 24;

/// 壳需要的暂存区。两个页缓冲（各 64 KiB，SQLite 允许的最大页）+ WAL 索引。
/// 由宿主 mmap 出来传进去 —— 壳里没有分配器。
inline constexpr uint32_t kScratchBytes = 1u << 20;

enum Op : uint32_t {
    /// 探测：读 `path` 指向的 cache4.db，命中就在 `out` 里签发一枚令牌。
    kOpProbe = 1,
    /// 校验：`token` 是不是本模块签发的、且还在有效期内。
    kOpVerify = 2,
};

enum Result : uint32_t {
    /**
     * probe：**权威的「没有」**—— 库打开了、表结构也认出来了，就是查不到那个群。
     * verify：令牌无效。
     *
     * 和 [kResultUnreadable] 分开是有意义的，而且是「退群」这条链路成立的前提：
     * 只有权威的否定才敢拿去撤销一枚已经签发的令牌。读不出来就撤，等于一次文件
     * 被占用就能把人锁在外面。
     */
    kResultNo = 0,
    /// probe：库里有那个群；verify：令牌有效。
    kResultYes = 1,
    /// 入参不合法（结构体版本对不上、暂存区太小、缓冲区没给够）。
    kResultBadRequest = 2,
    /// 文件打不开、不是 SQLite 库、或者连 dialogs / chats 两张表都没找到 —— 结论不可信。
    kResultUnreadable = 3,
};

/**
 * 宿主提供的能力表。
 *
 * 全是宿主侧自己写的定长包装函数，不是 libc 符号本身 —— `openat` 之类的可变参数
 * 函数按定长指针类型调用在部分 ABI 上是未定义行为，包一层就没有这个问题，顺带也
 * 让壳里不必知道 `O_RDONLY` 是几。
 */
struct HostOps {
    /// 只读打开，失败返回 -1。
    int (*openRead)(const char *path);

    /// 文件字节数，拿不到返回 -1。
    int64_t (*sizeOf)(int fd);

    /// 定位读。返回实际读到的字节数，出错返回 -1。短读要由调用方判断。
    int64_t (*readAt)(int fd, void *buffer, uint64_t length, int64_t offset);

    void (*closeFd)(int fd);

    /// 单调性无所谓，只用来当 nonce 的来源。
    uint64_t (*nowMillis)();
};

/**
 * 一次调用的全部输入输出。壳只认这一个指针。
 */
struct Request {
    /// 必须是 [kAbiMagic]。
    uint32_t magic;
    /// [Op] 之一。
    uint32_t op;

    const HostOps *ops;

    /// probe：cache4.db 的绝对路径。同目录下的 `<path>-wal` 会被一并考虑。
    const char *path;

    /// 至少 [kScratchBytes] 字节的可读写内存。
    uint8_t *scratch;
    uint32_t scratchSize;

    /// verify：待校验的令牌。
    const uint8_t *token;
    uint32_t tokenLength;

    /// 模块的 versionCode。签进令牌里 —— 换一版模块，旧令牌自然失效。
    uint32_t moduleVersion;
    /// probe：签发方包名的 FNV-1a，由宿主算好传进来（壳里不处理字符串）。
    uint32_t sourceHash;
    /// 当前 Unix 纪元日（毫秒 / 86400000）。壳里不读时钟，避免多一处可欺骗面。
    uint32_t today;
    /// 令牌有效期，天。probe 时忽略。
    uint32_t ttlDays;

    /// probe 的输出缓冲，至少 [kTokenBytes] 字节。
    uint8_t *out;
    uint32_t outCapacity;
    /// 壳写回：实际写了多少字节。
    uint32_t outLength;
};

/// 壳的入口签名。blob 的第 0 字节就是它 —— 由 payload.ld 保证。
using PayloadEntry = uint32_t (*)(Request *);

} // namespace chuanyi::guard
