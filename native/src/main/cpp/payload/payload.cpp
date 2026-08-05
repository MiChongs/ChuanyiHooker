// 壳内代码：判断本机的 Telegram（或第三方 TG 客户端）有没有那个群，并签发/校验令牌。
//
// 这个文件**不是普通的 .cpp**。它被单独编成一个可执行文件，再 `objcopy -O binary`
// 抽成一段纯字节，加密后嵌进 libchuanyihook.so；运行时解密到匿名内存里直接跳进来。
// 因此有三条硬约束，违反了不会有编译错误，只会在真机上跑飞：
//
//  1. **不能引用任何外部符号。** 没有 linker 参与，libc 和 compiler-rt 都够不到。
//     构建期 `-Wl,--no-undefined` + `-nostdlib` 会挡住，所以 `memcpy` / `memset`
//     这类编译器可能隐式生成的调用在本文件里自带实现。
//  2. **不能有任何重定位。** 链接期地址（0）和运行时的 mmap 地址不是一回事。
//     `pack_payload.cmake` 在构建期用 readelf 检查，有一条就中断构建。
//  3. **不能取任何静态数据的地址。** 这条检查不出来，只能写的时候守住 ——
//     字符串字面量、静态数组、`const` 函数指针表全部禁止。x86 上取地址会编成
//     绝对地址，blob 一搬家就指向别处。所以下面比较表名用的是逐字节立即数，
//     路径和常量都从 [Request] 传进来或写成整数字面量。
//
// 整型立即数不受第 3 条影响（编进指令里），所以群号和 MAC 密钥放在这里是安全的 ——
// 它们在磁盘上只以密文形式存在，这正是把这段逻辑挪进壳里的意义。
//
// ## 判据
//
// Telegram-Android 及其全部分支都把会话列表存在 `cache4.db` 这个 SQLite 库里，
// 两张表的主键正好是我们要找的东西，而 `INTEGER PRIMARY KEY` 在 SQLite 里**就是
// rowid**：
//
//     dialogs(did INTEGER PRIMARY KEY, ...)    did  = 会话 id
//     chats(uid INTEGER PRIMARY KEY, ...)      uid  = 群/频道 id（正数）
//
// 于是判断「有没有这个群」退化成一次 B 树 rowid 查找，完全不用解记录、不用认字段
// 顺序，也就不会因为某个分支加了列而失效。
//
// 会话 id 的编码有两套，都查：
//
//     -4404720340        Telegram-Android 8.0 之后的内部表示（负的原始 id）
//     -1004404720340     Bot API / TDLib 系客户端的表示
//
// 库正被目标进程写着，读到撕裂页是可能的 —— 那只会让这次判成「没有」，下次启动
// 重来，不会误判成「有」。

#include "payload_abi.h"

namespace chuanyi::guard {
namespace {

using u8 = uint8_t;
using u32 = uint32_t;
using u64 = uint64_t;
using i64 = int64_t;

// ---------------------------------------------------------------------------
// 要找的群
// ---------------------------------------------------------------------------

/// `chats.uid`：原始群 id，正数。
constexpr i64 kChatRowId = 4404720340LL;
/// `dialogs.did`：Telegram-Android 8.x 之后的内部表示。
constexpr i64 kDialogInternal = -4404720340LL;
/// `dialogs.did`：Bot API 表示，个别 TDLib 系分支用它。
constexpr i64 kDialogBotApi = -1004404720340LL;

/// 令牌头，用来把「明显不是我们签的东西」在算 MAC 之前就挡掉。
constexpr u32 kTokenMagic = 0x43595431u; // 'CYT1'

/// SipHash-2-4 的 128 位密钥。整数字面量 —— 不占 .rodata，也就不需要重定位。
constexpr u64 kMacKey0 = 0x5B7C4D1E9A3F26C8ULL;
constexpr u64 kMacKey1 = 0xE1A94F0DB7385C62ULL;

// ---------------------------------------------------------------------------
// 自带的 libc 片段
//
// `-ffreestanding -fno-builtin` 会阻止编译器把循环识别成 memcpy 调用，但结构体赋值
// 之类仍可能生成隐式调用。在这里给出定义，让它们解析到本 blob 内部的普通函数
// （PC 相对的 bl，不产生重定位），而不是变成一个解不掉的外部符号。
// ---------------------------------------------------------------------------

extern "C" void *memcpy(void *dst, const void *src, size_t n) {
    auto *d = static_cast<u8 *>(dst);
    const auto *s = static_cast<const u8 *>(src);
    for (size_t i = 0; i < n; ++i) d[i] = s[i];
    return dst;
}

extern "C" void *memset(void *dst, int value, size_t n) {
    auto *d = static_cast<u8 *>(dst);
    for (size_t i = 0; i < n; ++i) d[i] = static_cast<u8>(value);
    return dst;
}

// ---------------------------------------------------------------------------
// 字节序
// ---------------------------------------------------------------------------

inline u32 Be16(const u8 *p) { return (static_cast<u32>(p[0]) << 8) | p[1]; }

inline u32 Be32(const u8 *p) {
    return (static_cast<u32>(p[0]) << 24) | (static_cast<u32>(p[1]) << 16) |
           (static_cast<u32>(p[2]) << 8) | static_cast<u32>(p[3]);
}

inline void PutLe32(u8 *p, u32 v) {
    p[0] = static_cast<u8>(v);
    p[1] = static_cast<u8>(v >> 8);
    p[2] = static_cast<u8>(v >> 16);
    p[3] = static_cast<u8>(v >> 24);
}

inline u32 GetLe32(const u8 *p) {
    return static_cast<u32>(p[0]) | (static_cast<u32>(p[1]) << 8) |
           (static_cast<u32>(p[2]) << 16) | (static_cast<u32>(p[3]) << 24);
}

inline void PutLe64(u8 *p, u64 v) {
    for (u32 i = 0; i < 8; ++i) p[i] = static_cast<u8>(v >> (i * 8));
}

inline u64 GetLe64(const u8 *p) {
    u64 v = 0;
    for (u32 i = 0; i < 8; ++i) v |= static_cast<u64>(p[i]) << (i * 8);
    return v;
}

// ---------------------------------------------------------------------------
// SipHash-2-4
//
// 挑它而不是 HMAC-SHA256：短、无表、全部移位量都是编译期常量。后者在 32 位 ARM 上
// 很关键 —— 变量移位的 64 位运算会生成对 `__aeabi_llsl` 的调用，而那是个外部符号，
// 这段代码里解析不了。
// ---------------------------------------------------------------------------

#define CY_ROTL64(x, b) (((x) << (b)) | ((x) >> (64 - (b))))

#define CY_SIPROUND()                     \
    do {                                  \
        v0 += v1;                         \
        v1 = CY_ROTL64(v1, 13);           \
        v1 ^= v0;                         \
        v0 = CY_ROTL64(v0, 32);           \
        v2 += v3;                         \
        v3 = CY_ROTL64(v3, 16);           \
        v3 ^= v2;                         \
        v0 += v3;                         \
        v3 = CY_ROTL64(v3, 21);           \
        v3 ^= v0;                         \
        v2 += v1;                         \
        v1 = CY_ROTL64(v1, 17);           \
        v1 ^= v2;                         \
        v2 = CY_ROTL64(v2, 32);           \
    } while (0)

u64 SipHash(const u8 *data, u32 length) {
    u64 v0 = 0x736F6D6570736575ULL ^ kMacKey0;
    u64 v1 = 0x646F72616E646F6DULL ^ kMacKey1;
    u64 v2 = 0x6C7967656E657261ULL ^ kMacKey0;
    u64 v3 = 0x7465646279746573ULL ^ kMacKey1;

    const u32 whole = length & ~7u;
    for (u32 i = 0; i < whole; i += 8) {
        const u64 m = GetLe64(data + i);
        v3 ^= m;
        CY_SIPROUND();
        CY_SIPROUND();
        v0 ^= m;
    }

    u64 tail = static_cast<u64>(length & 0xff) << 56;
    for (u32 i = whole; i < length; ++i) {
        tail |= static_cast<u64>(data[i]) << ((i - whole) * 8);
    }
    v3 ^= tail;
    CY_SIPROUND();
    CY_SIPROUND();
    v0 ^= tail;

    v2 ^= 0xff;
    CY_SIPROUND();
    CY_SIPROUND();
    CY_SIPROUND();
    CY_SIPROUND();
    return v0 ^ v1 ^ v2 ^ v3;
}

// ---------------------------------------------------------------------------
// SQLite：只读、只做 rowid 查找
// ---------------------------------------------------------------------------

/// WAL 里一个已提交帧的位置。
struct WalFrame {
    u32 page;
    u32 offset; // 帧内数据（跳过 24 字节帧头）在 -wal 文件里的绝对偏移
};

struct Db {
    const HostOps *ops;
    int fd;
    int walFd;
    u32 pageSize;
    u8 *page;   // 当前页缓冲，pageSize 字节
    WalFrame *wal;
    u32 walCount;
    u32 walCapacity;
};

/// SQLite 的变长整数：大端 7 位一组，最多 9 字节（第 9 字节整字节参与）。
/// 返回消耗的字节数。
u32 GetVarint(const u8 *p, u32 available, i64 *out) {
    u64 value = 0;
    u32 i = 0;
    for (; i < 8 && i < available; ++i) {
        const u8 b = p[i];
        value = (value << 7) | static_cast<u64>(b & 0x7f);
        if ((b & 0x80) == 0) {
            *out = static_cast<i64>(value);
            return i + 1;
        }
    }
    if (available >= 9) {
        value = (value << 8) | static_cast<u64>(p[8]);
        *out = static_cast<i64>(value);
        return 9;
    }
    *out = static_cast<i64>(value);
    return i;
}

/**
 * 建立 WAL 覆盖索引。
 *
 * 开着 WAL 的库里，一个页的最新内容可能只存在于 `-wal` 文件中，主库那份是旧的。
 * Telegram 正是 WAL 模式，而「刚加进来的群」恰恰最可能只在 WAL 里 —— 不看 WAL 就
 * 会把刚入群的人判成没入。
 *
 * 只收**已提交事务**里的帧：帧头 dbSize 非 0 表示这一帧是某个事务的最后一帧，
 * 到它为止的帧才算数。盐值与 WAL 头不符即为回收后的陈旧帧，从那里截断。
 *
 * 校验和不验：我们只是读，验不验都不会让「没有」变成「有」，而算它要多读一遍全部
 * 页数据。索引装不下就整个放弃 WAL 覆盖（退回只读主库），不做部分覆盖 —— 半份索引
 * 比没有更糟。
 */
void BuildWalIndex(Db &db, const char *dbPath, u8 *pathBuffer, u32 pathCapacity) {
    db.walCount = 0;
    db.walFd = -1;

    // "<path>-wal"，在栈上拼 —— 字面量在这段代码里是禁止的，'-'/'w'/'a'/'l' 是立即数。
    u32 n = 0;
    while (dbPath[n] != 0 && n + 5 < pathCapacity) {
        pathBuffer[n] = static_cast<u8>(dbPath[n]);
        ++n;
    }
    if (dbPath[n] != 0) return; // 路径超长，放弃 WAL
    pathBuffer[n++] = '-';
    pathBuffer[n++] = 'w';
    pathBuffer[n++] = 'a';
    pathBuffer[n++] = 'l';
    pathBuffer[n] = 0;

    const int fd = db.ops->openRead(reinterpret_cast<const char *>(pathBuffer));
    if (fd < 0) return;

    u8 header[32];
    if (db.ops->readAt(fd, header, sizeof(header), 0) != static_cast<i64>(sizeof(header))) {
        db.ops->closeFd(fd);
        return;
    }
    const u32 magic = Be32(header);
    if (magic != 0x377F0682u && magic != 0x377F0683u) {
        db.ops->closeFd(fd);
        return;
    }
    if (Be32(header + 8) != db.pageSize) { // 页大小必须和主库一致
        db.ops->closeFd(fd);
        return;
    }
    const u32 salt1 = Be32(header + 16);
    const u32 salt2 = Be32(header + 20);

    const i64 size = db.ops->sizeOf(fd);
    const i64 frameSize = 24 + static_cast<i64>(db.pageSize);
    u32 committed = 0;

    for (u32 index = 0;; ++index) {
        const i64 offset = 32 + static_cast<i64>(index) * frameSize;
        if (size < 0 || offset + frameSize > size) break;
        if (db.walCount >= db.walCapacity) { // 索引装不下 -> 整个放弃
            committed = 0;
            break;
        }
        u8 frame[24];
        if (db.ops->readAt(fd, frame, sizeof(frame), offset) != static_cast<i64>(sizeof(frame))) break;
        if (Be32(frame + 8) != salt1 || Be32(frame + 12) != salt2) break;

        db.wal[db.walCount].page = Be32(frame);
        db.wal[db.walCount].offset = static_cast<u32>(offset + 24);
        ++db.walCount;

        if (Be32(frame + 4) != 0) committed = db.walCount; // 提交帧
    }

    db.walCount = committed;
    if (committed == 0) {
        db.ops->closeFd(fd);
        return;
    }
    db.walFd = fd;
}

/// 读一页到 [Db::page]。WAL 里有更新的版本就读 WAL 那份。
bool ReadPage(Db &db, u32 pageNo) {
    if (pageNo == 0) return false;

    for (u32 i = db.walCount; i-- > 0;) {
        if (db.wal[i].page != pageNo) continue;
        return db.ops->readAt(db.walFd, db.page, db.pageSize,
                              static_cast<i64>(db.wal[i].offset)) ==
               static_cast<i64>(db.pageSize);
    }

    const i64 offset = static_cast<i64>(pageNo - 1) * static_cast<i64>(db.pageSize);
    return db.ops->readAt(db.fd, db.page, db.pageSize, offset) ==
           static_cast<i64>(db.pageSize);
}

/// 记录格式里一个 serial type 占多少字节。
u32 SerialSize(i64 type) {
    if (type <= 0) return 0;
    if (type <= 4) return static_cast<u32>(type);
    if (type == 5) return 6;
    if (type == 6 || type == 7) return 8;
    if (type < 12) return 0; // 8/9 是常量 0/1，10/11 保留
    return static_cast<u32>((type - 12) >> 1);
}

/// 大端有符号整数，SQLite 记录体里的整数就是这个形状。
i64 ReadBeInt(const u8 *p, u32 size) {
    if (size == 0) return 0;
    i64 value = static_cast<int8_t>(p[0]);
    for (u32 i = 1; i < size; ++i) value = (value << 8) | static_cast<i64>(p[i]);
    return value;
}

/// 表名是不是 "dialogs" / "chats"。逐字节立即数比较，不能用字符串字面量。
bool IsDialogs(const u8 *s, u32 n) {
    return n == 7 && s[0] == 'd' && s[1] == 'i' && s[2] == 'a' && s[3] == 'l' && s[4] == 'o' &&
           s[5] == 'g' && s[6] == 's';
}

bool IsChats(const u8 *s, u32 n) {
    return n == 5 && s[0] == 'c' && s[1] == 'h' && s[2] == 'a' && s[3] == 't' && s[4] == 's';
}

/// 页 1 的 B 树头前面还有 100 字节的库头。
inline u32 BtreeHeaderOffset(u32 pageNo) { return pageNo == 1 ? 100 : 0; }

/**
 * 遍历 sqlite_master（根页恒为 1），取出 `dialogs` 和 `chats` 的根页号。
 *
 * 这是整段代码里唯一需要解记录的地方 —— 之后的两次查找都只比 rowid。
 * 溢出到 overflow page 的行直接跳过：sqlite_master 的行只有表名和建表语句，
 * 4 KiB 页下不可能溢出，为它引入一条读链不划算。
 */
void FindRoots(Db &db, u32 *dialogsRoot, u32 *chatsRoot) {
    *dialogsRoot = 0;
    *chatsRoot = 0;

    constexpr u32 kStackDepth = 64;
    u32 stack[kStackDepth];
    u32 depth = 0;
    stack[depth++] = 1;
    u32 budget = 256; // 防御损坏文件里的环

    while (depth > 0 && budget-- > 0) {
        const u32 pageNo = stack[--depth];
        if (!ReadPage(db, pageNo)) continue;

        const u32 base = BtreeHeaderOffset(pageNo);
        if (base + 12 > db.pageSize) continue;
        const u8 type = db.page[base];
        const u32 cells = Be16(db.page + base + 3);
        const bool interior = type == 0x05;
        if (type != 0x0D && !interior) continue;

        const u32 array = base + (interior ? 12u : 8u);
        if (array + cells * 2 > db.pageSize) continue;

        if (interior) {
            if (depth < kStackDepth) stack[depth++] = Be32(db.page + base + 8); // 最右子页
            for (u32 i = 0; i < cells && depth < kStackDepth; ++i) {
                const u32 cell = Be16(db.page + array + i * 2);
                if (cell + 4 > db.pageSize) continue;
                stack[depth++] = Be32(db.page + cell);
            }
            continue;
        }

        for (u32 i = 0; i < cells; ++i) {
            const u32 cell = Be16(db.page + array + i * 2);
            if (cell + 2 > db.pageSize) continue;
            u32 avail = db.pageSize - cell;

            i64 payloadSize = 0;
            u32 used = GetVarint(db.page + cell, avail, &payloadSize);
            i64 rowid = 0;
            used += GetVarint(db.page + cell + used, avail - used, &rowid);
            if (payloadSize <= 0 || used >= avail) continue;

            const u8 *record = db.page + cell + used;
            u32 recordAvail = avail - used;
            if (static_cast<i64>(recordAvail) > payloadSize) {
                recordAvail = static_cast<u32>(payloadSize);
            } else if (payloadSize > static_cast<i64>(recordAvail)) {
                continue; // 溢出行，跳过
            }

            i64 headerSize = 0;
            u32 hp = GetVarint(record, recordAvail, &headerSize);
            if (headerSize <= 0 || static_cast<u32>(headerSize) > recordAvail) continue;

            i64 serial[4] = {0, 0, 0, 0};
            for (u32 c = 0; c < 4 && hp < static_cast<u32>(headerSize); ++c) {
                hp += GetVarint(record + hp, static_cast<u32>(headerSize) - hp, &serial[c]);
            }

            // 列 1 = name（TEXT），列 3 = rootpage（INTEGER）
            u32 offset = static_cast<u32>(headerSize);
            const u32 size0 = SerialSize(serial[0]);
            const u32 size1 = SerialSize(serial[1]);
            const u32 size2 = SerialSize(serial[2]);
            const u32 size3 = SerialSize(serial[3]);
            const u32 nameAt = offset + size0;
            const u32 rootAt = nameAt + size1 + size2;
            if (rootAt + size3 > recordAvail) continue;
            if (serial[1] < 13 || (serial[1] & 1) == 0) continue; // 不是 TEXT

            const u8 *name = record + nameAt;
            i64 root = 0;
            if (serial[3] == 9) root = 1;
            else if (serial[3] >= 1 && serial[3] <= 6) root = ReadBeInt(record + rootAt, size3);

            if (root <= 0) continue;
            if (IsDialogs(name, size1)) *dialogsRoot = static_cast<u32>(root);
            else if (IsChats(name, size1)) *chatsRoot = static_cast<u32>(root);
        }
    }
}

/**
 * 表 B 树里有没有 rowid == [target] 的行。
 *
 * `dialogs.did` 和 `chats.uid` 都声明成 `INTEGER PRIMARY KEY`，在 SQLite 里那**就是**
 * rowid 的别名 —— 所以这一次查找等价于「有没有这条会话/这个群」，一个字段都不用解。
 */
bool RowidExists(Db &db, u32 rootPage, i64 target) {
    u32 pageNo = rootPage;
    for (u32 depth = 0; depth < 32; ++depth) {
        if (!ReadPage(db, pageNo)) return false;

        const u32 base = BtreeHeaderOffset(pageNo);
        if (base + 12 > db.pageSize) return false;
        const u8 type = db.page[base];
        const u32 cells = Be16(db.page + base + 3);
        const bool interior = type == 0x05;
        if (type != 0x0D && !interior) return false;

        const u32 array = base + (interior ? 12u : 8u);
        if (array + cells * 2 > db.pageSize) return false;

        if (!interior) {
            for (u32 i = 0; i < cells; ++i) {
                const u32 cell = Be16(db.page + array + i * 2);
                if (cell + 2 > db.pageSize) continue;
                const u32 avail = db.pageSize - cell;
                i64 payloadSize = 0;
                const u32 used = GetVarint(db.page + cell, avail, &payloadSize);
                if (used >= avail) continue;
                i64 rowid = 0;
                GetVarint(db.page + cell + used, avail - used, &rowid);
                if (rowid == target) return true;
            }
            return false;
        }

        u32 next = Be32(db.page + base + 8); // 最右子页
        for (u32 i = 0; i < cells; ++i) {
            const u32 cell = Be16(db.page + array + i * 2);
            if (cell + 5 > db.pageSize) continue;
            i64 key = 0;
            GetVarint(db.page + cell + 4, db.pageSize - cell - 4, &key);
            if (target <= key) {
                next = Be32(db.page + cell);
                break;
            }
        }
        if (next == 0 || next == pageNo) return false;
        pageNo = next;
    }
    return false;
}

// ---------------------------------------------------------------------------
// 令牌
// ---------------------------------------------------------------------------

/// MAC 覆盖的字节：magic | 签发日 | nonce | 签发方哈希 | 模块 versionCode。
///
/// 签发方进 MAC，是「只有当初签发的那个客户端才能撤销」这条规则的根 —— 它因此改不了，
/// 也伪造不了；令牌外面那份同名配置项只是给界面显示用的。
void BuildMacInput(u8 *out, u32 day, u32 nonce, u32 sourceHash, u32 moduleVersion) {
    PutLe32(out, kTokenMagic);
    PutLe32(out + 4, day);
    PutLe32(out + 8, nonce);
    PutLe32(out + 12, sourceHash);
    PutLe32(out + 16, moduleVersion);
}

void IssueToken(u8 *out, u32 day, u32 nonce, u32 sourceHash, u32 moduleVersion) {
    u8 input[20];
    BuildMacInput(input, day, nonce, sourceHash, moduleVersion);
    // 令牌的前 16 字节和 MAC 输入的前 16 字节是同一份内容，校验侧因此不需要任何外部
    // 输入就能把它重算出来。
    for (u32 i = 0; i < 16; ++i) out[i] = input[i];
    PutLe64(out + 16, SipHash(input, sizeof(input)));
}

// ---------------------------------------------------------------------------
// 两个入口动作
// ---------------------------------------------------------------------------

u32 Probe(Request *request) {
    const HostOps *ops = request->ops;
    if (request->scratchSize < kScratchBytes) return kResultBadRequest;
    if (request->out == nullptr || request->outCapacity < kTokenBytes) return kResultBadRequest;
    if (request->path == nullptr) return kResultBadRequest;

    const int fd = ops->openRead(request->path);
    if (fd < 0) return kResultUnreadable;

    u8 header[100];
    if (ops->readAt(fd, header, sizeof(header), 0) != static_cast<i64>(sizeof(header))) {
        ops->closeFd(fd);
        return kResultUnreadable;
    }
    // "SQLite format 3\0"，逐字节立即数。
    if (!(header[0] == 'S' && header[1] == 'Q' && header[2] == 'L' && header[3] == 'i' &&
          header[4] == 't' && header[5] == 'e' && header[6] == ' ' && header[7] == 'f' &&
          header[15] == 0)) {
        ops->closeFd(fd);
        return kResultUnreadable;
    }

    u32 pageSize = Be16(header + 16);
    if (pageSize == 1) pageSize = 65536;
    if (pageSize < 512 || pageSize > 65536 || (pageSize & (pageSize - 1)) != 0) {
        ops->closeFd(fd);
        return kResultUnreadable;
    }

    // 暂存区切三块：页缓冲、路径缓冲、WAL 索引。
    u8 *scratch = request->scratch;
    Db db{};
    db.ops = ops;
    db.fd = fd;
    db.walFd = -1;
    db.pageSize = pageSize;
    db.page = scratch;
    db.walCount = 0;

    u8 *pathBuffer = scratch + 65536;
    constexpr u32 kPathCapacity = 4096;
    db.wal = reinterpret_cast<WalFrame *>(scratch + 65536 + kPathCapacity);
    db.walCapacity = (kScratchBytes - 65536 - kPathCapacity) / sizeof(WalFrame);

    BuildWalIndex(db, request->path, pathBuffer, kPathCapacity);

    u32 dialogsRoot = 0;
    u32 chatsRoot = 0;
    FindRoots(db, &dialogsRoot, &chatsRoot);

    bool found = false;
    if (dialogsRoot != 0) {
        found = RowidExists(db, dialogsRoot, kDialogInternal) ||
                RowidExists(db, dialogsRoot, kDialogBotApi);
    }
    // 会话行被清掉但群本体还在缓存里也算数：分支之间存法有差异，宁可宽一格，
    // 也不要因为某个分支换了表结构就把人锁死在外面。
    if (!found && chatsRoot != 0) {
        found = RowidExists(db, chatsRoot, kChatRowId);
    }

    // 两张表一张都没认出来 = 这个库我们根本没读懂，结论不可信。区分这一点是「退群
    // 之后立刻停用」那条链路的前提：只有权威的否定才敢拿去撤销已签发的令牌。
    const bool understood = dialogsRoot != 0 || chatsRoot != 0;

    if (db.walFd >= 0) ops->closeFd(db.walFd);
    ops->closeFd(fd);

    if (!found) {
        request->outLength = 0;
        return understood ? kResultNo : kResultUnreadable;
    }

    const u64 now = ops->nowMillis();
    IssueToken(request->out, request->today, static_cast<u32>(now ^ (now >> 32)),
               request->sourceHash, request->moduleVersion);
    request->outLength = kTokenBytes;
    return kResultYes;
}

u32 Verify(Request *request) {
    if (request->token == nullptr || request->tokenLength != kTokenBytes) return kResultNo;

    const u8 *token = request->token;
    if (GetLe32(token) != kTokenMagic) return kResultNo;

    const u32 day = GetLe32(token + 4);
    const u32 nonce = GetLe32(token + 8);
    const u32 sourceHash = GetLe32(token + 12);

    u8 input[20];
    BuildMacInput(input, day, nonce, sourceHash, request->moduleVersion);
    if (GetLe64(token + 16) != SipHash(input, sizeof(input))) return kResultNo;

    // 有效期。允许比今天大一天：跨时区搬机器、或者签发那一刻正好跨零点。
    // 往回超过 ttl 就要求重新走一次探测 —— 这也是「退群之后模块会停」的实现方式。
    if (day > request->today + 1) return kResultNo;
    if (request->today > day + request->ttlDays) return kResultNo;
    return kResultYes;
}

} // namespace
} // namespace chuanyi::guard

/**
 * blob 的第 0 字节。
 *
 * 放在 `.text.entry` 里，payload.ld 把这个段排在最前面 —— 宿主拿到解密后的内存
 * 直接当函数指针跳进来，不做任何符号解析。
 */
extern "C" __attribute__((section(".text.entry"), used, visibility("default"))) uint32_t
PayloadMain(chuanyi::guard::Request *request) {
    using namespace chuanyi::guard;
    if (request == nullptr) return kResultBadRequest;
    if (request->magic != kAbiMagic) return kResultBadRequest;
    if (request->ops == nullptr) return kResultBadRequest;
    if (request->scratch == nullptr) return kResultBadRequest;

    if (request->op == kOpProbe) return Probe(request);
    if (request->op == kOpVerify) return Verify(request);
    return kResultBadRequest;
}
