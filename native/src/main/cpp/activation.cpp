// 自定义壳：把加密的判定代码解到匿名内存里执行。
//
// 这一层不认识 Telegram，也不认识群号 —— 它只做四件事：解密、映射、调用、抹掉。
// 真正的判据全在 payload/payload.cpp 里，而那段代码在磁盘上只以密文存在
// （见 payload/pack_payload.cmake）。
//
// ## 为什么是匿名内存
//
// 三个具体的收益，不是「听起来更安全」：
//
//  * **磁盘上没有可 patch 的目标。**判定逻辑不是 libchuanyihook.so 里的一个函数，
//    没有可以改成 `mov w0, #1; ret` 的入口，也没有可以拿 IDA 直接看的控制流。
//  * **符号表里没有它。**壳内代码是一段裸字节，没有 ELF 头、没有符号、没有导出，
//    `dlsym` / Dobby 的符号解析都够不到它，因此也 hook 不到它内部的任何一步。
//  * **窗口很短。**明文只在一次调用期间存在，调用完立刻抹零并 munmap。想在别的
//    进程里 dump 它，得先知道什么时候调、再抢在几十微秒内读出来。
//
// 这拦不住有 root + 会写 hook 的人 —— 那种人手里的下限是「hook 掉调用方」，任何
// 客户端校验都拦不住。这一层的目标是把「改个布尔值」和「搜个字符串」这两条最省事
// 的路堵掉，让绕过的成本和自己进群一次不成比例。
//
// ## SELinux
//
// `untrusted_app` 有 `execmem`（ART 的 JIT 就靠它），所以匿名可执行页是可用的。
// 先按 RW 映射、写完再 mprotect 成 RX；个别设备上 RW->RX 会被拒，退回一次性
// RWX 映射，两条都失败才算不可用。

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include <cstdint>
#include <cstring>

#include "chuanyi/native_hook.h"
#include "payload/payload_abi.h"
#include "payload_blob.h"

namespace chuanyi {
namespace {

using guard::HostOps;
using guard::Request;

// ---------------------------------------------------------------------------
// 壳内代码要用的系统能力
//
// 全部是定长包装函数：把 `open` 这类可变参数函数的地址按定长指针类型传过去，在
// 部分 ABI 上是未定义行为，包一层顺带也让壳里不必知道 O_RDONLY 是几。
// ---------------------------------------------------------------------------

int HostOpenRead(const char *path) {
    if (path == nullptr) return -1;
    return ::open(path, O_RDONLY | O_CLOEXEC);
}

int64_t HostSizeOf(int fd) {
    struct stat info {};
    if (::fstat(fd, &info) != 0) return -1;
    return static_cast<int64_t>(info.st_size);
}

int64_t HostReadAt(int fd, void *buffer, uint64_t length, int64_t offset) {
    if (buffer == nullptr || offset < 0) return -1;
    return static_cast<int64_t>(::pread64(fd, buffer, static_cast<size_t>(length),
                                          static_cast<off64_t>(offset)));
}

void HostCloseFd(int fd) {
    if (fd >= 0) ::close(fd);
}

uint64_t HostNowMillis() {
    struct timespec now {};
    if (::clock_gettime(CLOCK_REALTIME, &now) != 0) return 0;
    return static_cast<uint64_t>(now.tv_sec) * 1000ull +
           static_cast<uint64_t>(now.tv_nsec) / 1000000ull;
}

// ---------------------------------------------------------------------------
// 解密
// ---------------------------------------------------------------------------

uint32_t Fnv1a(const uint8_t *data, uint32_t length) {
    uint32_t hash = 2166136261u;
    for (uint32_t i = 0; i < length; ++i) {
        hash ^= data[i];
        hash *= 16777619u;
    }
    return hash;
}

/// 必须和 pack_payload.cmake 里那段逐位一致，改一边就要改另一边。
uint8_t Keystream(uint32_t index, const uint8_t *key) {
    return static_cast<uint8_t>(key[index & 15] ^ static_cast<uint8_t>(index * 167u) ^
                                static_cast<uint8_t>(index / 251u));
}

/**
 * 一次调用期间存在的可执行映射。析构里抹零并解除映射 —— 明文的存活时间就是这个
 * 对象的作用域。
 */
class ShellMapping {
public:
    ShellMapping() {
        const size_t pageSize = static_cast<size_t>(::sysconf(_SC_PAGESIZE));
        length_ = (guard::kBlobLength + pageSize - 1) & ~(pageSize - 1);

        // 密钥不是明文存的：和密文的 FNV 哈希异或。blob 被改过一个字节，这里推出来
        // 的就是错的密钥，解出垃圾，明文哈希对不上 —— 篡改检测和解密是同一件事。
        const uint32_t cipherHash = Fnv1a(guard::kBlob, guard::kBlobLength);
        if (cipherHash != guard::kBlobCipherHash) return;

        uint8_t key[16];
        for (uint32_t i = 0; i < 16; ++i) {
            key[i] = static_cast<uint8_t>(guard::kBlobStoredKey[i] ^
                                          static_cast<uint8_t>(cipherHash >> ((i % 4) * 8)));
        }

        void *memory = ::mmap(nullptr, length_, PROT_READ | PROT_WRITE,
                              MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (memory == MAP_FAILED) return;

        auto *bytes = static_cast<uint8_t *>(memory);
        for (uint32_t i = 0; i < guard::kBlobLength; ++i) {
            bytes[i] = static_cast<uint8_t>(guard::kBlob[i] ^ Keystream(i, key));
        }
        ::memset(key, 0, sizeof(key));

        if (Fnv1a(bytes, guard::kBlobLength) != guard::kBlobPlainHash) {
            ::memset(memory, 0, length_);
            ::munmap(memory, length_);
            LogError("activation: 壳内代码校验失败，模块被改过");
            return;
        }

        if (::mprotect(memory, length_, PROT_READ | PROT_EXEC) != 0) {
            // 少数设备上 RW->RX 会被拒。整段保持可写也能跑，只是窗口更宽。
            if (::mprotect(memory, length_, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
                ::memset(memory, 0, length_);
                ::munmap(memory, length_);
                LogError("activation: 匿名页拿不到执行权限");
                return;
            }
        }
        // 刚写过的字节还在 D-cache 里，ARM 上不刷新 I-cache 就跳进去会执行到旧内容。
        __builtin___clear_cache(static_cast<char *>(memory), static_cast<char *>(memory) + length_);

        memory_ = memory;
    }

    ~ShellMapping() {
        if (memory_ == nullptr) return;
        // 抹零之前先确保可写：上面可能已经把它降成 R|X 了。
        ::mprotect(memory_, length_, PROT_READ | PROT_WRITE);
        ::memset(memory_, 0, length_);
        ::munmap(memory_, length_);
        memory_ = nullptr;
    }

    ShellMapping(const ShellMapping &) = delete;
    ShellMapping &operator=(const ShellMapping &) = delete;

    bool ok() const { return memory_ != nullptr; }

    /**
     * 入口不一定就是映射的首字节：armeabi-v7a 上 [guard::kBlobEntryOffset] 是 1，
     * 那一位是 Thumb 标记 —— 少加它，CPU 会拿 ARM 的译码规则去解 Thumb 字节，当场
     * 跑飞。偏移由构建期从符号表读出来，不在这里假设指令集。
     */
    guard::PayloadEntry entry() const {
        return reinterpret_cast<guard::PayloadEntry>(static_cast<uint8_t *>(memory_) +
                                                     guard::kBlobEntryOffset);
    }

private:
    void *memory_ = nullptr;
    size_t length_ = 0;
};

/// 壳要用的暂存区。单独 mmap 而不是走 new：不进堆，用完立刻还给内核。
class Scratch {
public:
    Scratch() {
        void *memory = ::mmap(nullptr, guard::kScratchBytes, PROT_READ | PROT_WRITE,
                              MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (memory != MAP_FAILED) memory_ = static_cast<uint8_t *>(memory);
    }

    ~Scratch() {
        if (memory_ == nullptr) return;
        ::memset(memory_, 0, guard::kScratchBytes);
        ::munmap(memory_, guard::kScratchBytes);
    }

    Scratch(const Scratch &) = delete;
    Scratch &operator=(const Scratch &) = delete;

    uint8_t *get() const { return memory_; }

private:
    uint8_t *memory_ = nullptr;
};

Request MakeRequest(const HostOps &ops, uint8_t *scratch, uint32_t moduleVersion, uint32_t today) {
    Request request{};
    request.magic = guard::kAbiMagic;
    request.ops = &ops;
    request.scratch = scratch;
    request.scratchSize = guard::kScratchBytes;
    request.moduleVersion = moduleVersion;
    request.today = today;
    return request;
}

const HostOps &Ops() {
    static const HostOps ops{HostOpenRead, HostSizeOf, HostReadAt, HostCloseFd, HostNowMillis};
    return ops;
}

char HexDigit(uint8_t value) {
    return static_cast<char>(value < 10 ? '0' + value : 'a' + (value - 10));
}

int HexValue(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

} // namespace

int ActivationProbe(const char *dbPath, uint32_t moduleVersion, uint32_t today, uint32_t sourceHash,
                    char *outHex, size_t outHexCapacity) {
    if (outHex == nullptr || outHexCapacity < guard::kTokenBytes * 2 + 1) {
        return guard::kResultBadRequest;
    }
    outHex[0] = 0;

    ShellMapping shell;
    Scratch scratch;
    if (!shell.ok() || scratch.get() == nullptr) return guard::kResultBadRequest;

    uint8_t token[guard::kTokenBytes] = {};
    Request request = MakeRequest(Ops(), scratch.get(), moduleVersion, today);
    request.op = guard::kOpProbe;
    request.path = dbPath;
    request.sourceHash = sourceHash;
    request.out = token;
    request.outCapacity = sizeof(token);

    const uint32_t result = shell.entry()(&request);
    if (result != guard::kResultYes || request.outLength != guard::kTokenBytes) {
        return static_cast<int>(result);
    }

    for (uint32_t i = 0; i < guard::kTokenBytes; ++i) {
        outHex[i * 2] = HexDigit(static_cast<uint8_t>(token[i] >> 4));
        outHex[i * 2 + 1] = HexDigit(static_cast<uint8_t>(token[i] & 0x0f));
    }
    outHex[guard::kTokenBytes * 2] = 0;
    return guard::kResultYes;
}

bool ActivationVerify(const char *tokenHex, uint32_t moduleVersion, uint32_t today,
                      uint32_t ttlDays) {
    if (tokenHex == nullptr) return false;
    if (::strlen(tokenHex) != guard::kTokenBytes * 2) return false;

    uint8_t token[guard::kTokenBytes] = {};
    for (uint32_t i = 0; i < guard::kTokenBytes; ++i) {
        const int high = HexValue(tokenHex[i * 2]);
        const int low = HexValue(tokenHex[i * 2 + 1]);
        if (high < 0 || low < 0) return false;
        token[i] = static_cast<uint8_t>((high << 4) | low);
    }

    ShellMapping shell;
    Scratch scratch;
    if (!shell.ok() || scratch.get() == nullptr) return false;

    Request request = MakeRequest(Ops(), scratch.get(), moduleVersion, today);
    request.op = guard::kOpVerify;
    request.token = token;
    request.tokenLength = sizeof(token);
    request.ttlDays = ttlDays;

    return shell.entry()(&request) == guard::kResultYes;
}

} // namespace chuanyi
