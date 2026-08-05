// Public C++ surface of the module's native layer.
//
// Two ways in:
//   * from Kotlin, through NativeHook.kt - symbol lookup, memory patching and
//     "make this exported function return a constant";
//   * from C++, by registering a NativeHooker below when a target needs a real
//     inline hook with its own replacement function.
//
// Everything here is safe to call from any thread and never throws.

#pragma once

#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace chuanyi {

/// Substring filter for the built-in "openat_logger" hooker. Empty = log all.
void SetOpenatFilters(const std::vector<std::string> &filters);

// --------------------------------------------------------------------------
// Logging (goes to logcat under the module's tag)
// --------------------------------------------------------------------------
void LogInfo(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
void LogWarn(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
void LogError(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

/// Toggled from the module UI's "verbose log" switch.
void SetVerbose(bool verbose);
bool IsVerbose();
void LogDebug(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

// --------------------------------------------------------------------------
// Primitives
// --------------------------------------------------------------------------

/// Resolves `symbol` in `image` (e.g. "libflutter.so"). `image` may be null to
/// search every loaded image. Finds non-exported symbols too, via Dobby's
/// symbol resolver plugin. Returns nullptr when not found.
void *FindSymbol(const char *image, const char *symbol);

/// Load address of `image`, or 0.
uintptr_t ModuleBase(const char *image);

/// Overwrites `size` bytes of **code** at `address`, handling page protection
/// and icache.
///
/// Backed by DobbyCodePatch, which leaves the page as PROT_READ|PROT_EXEC
/// whatever it was before. That is right for a code page and fatal for a data
/// page: the next legitimate write by the app dies with SEGV_ACCERR, usually far
/// away from here and long afterwards. Use [WriteMemory] for anything that is
/// not an instruction stream.
bool PatchMemory(void *address, const uint8_t *bytes, uint32_t size);

/// Overwrites `size` bytes of **data** at `address`.
///
/// Reads the mapping's real protection first and restores exactly that, so a
/// writable page stays writable. Spans as many pages as the range needs, and
/// does not touch the icache. Returns false when the range is not mapped or the
/// write could not be verified.
bool WriteMemory(void *address, const uint8_t *bytes, uint32_t size);

/// Reads `size` bytes from `address` into `out`. False if unreadable.
bool ReadMemory(const void *address, uint8_t *out, uint32_t size);

/// Which mappings a memory scan covers.
///
/// The two heaps a target can keep a string literal in are mutually exclusive
/// and must never be scanned together by accident:
///
///   * `kNative` — anonymous mappings *outside* ART. A Dart AOT heap, a native
///     allocator arena, an mmap'd buffer. This is the default and what every
///     Flutter target wants.
///   * `kManagedHeap` — only `[anon:dalvik-*]`, ART's managed spaces. A Java
///     `static final String` has its character data inline in the mirror::String
///     object, which lives here; the copy in the mapped dex is a read-only image
///     ART never reads back. Overwriting a Java literal therefore means this
///     scope and nothing else.
///
/// ART's concurrent collector relocates objects in the moving spaces, so a write
/// here races with a copy. Two things make that acceptable: the write is
/// equal-length and in-place, so a subsequent relocation carries the *new* bytes
/// along, and every write is read back before it counts. A patch is worth doing
/// early — before the app has built a working set — and worth verifying rather
/// than assuming.
enum class MemoryScope {
    kNative,
    kManagedHeap,
    kAll,
};

/// Equal-length search-and-replace over the readable mappings named by `scope`.
///
/// The Flutter use case: a Dart AOT isolate snapshot's *data* is a serialized
/// cluster stream that is deserialized into the heap at isolate start, so
/// patching the mapped .so at runtime does nothing. The heap copy of a Dart
/// string is plain contiguous ASCII, and an equal-length overwrite leaves its
/// length field untouched — which lets a string literal be swapped on an
/// obfuscated build with no object-pool walk and no code offset.
///
/// The same trick reaches a Java literal under `kManagedHeap`: ART stores an
/// all-ASCII string compressed, one byte per character, directly in the object,
/// so an equal-length overwrite leaves the length field and the object header
/// alone. That is how a hard-coded RSA public key gets swapped for one the module
/// holds the private half of, without knowing a single class or method name.
///
/// `limit` > 0 stops after that many successful writes.
/// Returns the number of writes, or -1 when the lengths differ.
int ReplaceInMemory(const uint8_t *needle, size_t needleLen, const uint8_t *replacement,
                    size_t replacementLen, int limit,
                    MemoryScope scope = MemoryScope::kNative);

/// Occurrences of `needle`, without writing anything.
int CountInMemory(const uint8_t *needle, size_t needleLen,
                  MemoryScope scope = MemoryScope::kNative);

/// Every distinct printable run that starts with `prefix`, read out of the same
/// mappings ReplaceInMemory writes to.
///
/// This is the discovery half of ReplaceInMemory. That one needs to be told the
/// exact literal in advance, which means baking a target's URL or key into the
/// module and losing it the moment the app ships a new one. Scanning for a
/// *shape* instead — "https://", "-----BEGIN PUBLIC KEY-----" — survives that.
///
/// Where the run ends is not a guess on a Dart target. In an AOT snapshot a
/// string payload is followed by the next cluster's length marker, encoded as
/// `(len<<1)|0x80`, so the byte after the last character always has its top bit
/// set; in the heap the neighbour is an object header, which is not text either.
/// Stopping at the first byte outside printable ASCII (plus tab/CR/LF, which PEM
/// bodies need) therefore lands on the real boundary rather than near it.
///
/// Results are deduplicated — one literal is typically mapped several times —
/// and capped at `maxLen` bytes each. `limit` > 0 stops after that many distinct
/// strings. Runs shorter than `minLen` are dropped.
std::vector<std::string> FindAscii(const char *prefix, size_t minLen, size_t maxLen, int limit,
                                   MemoryScope scope = MemoryScope::kNative);

/// Byte-pattern search restricted to one loaded image's executable segments.
///
/// The counterpart to [FindSymbol] for a target that exports nothing worth
/// looking up — a Dart AOT `libapp.so` being the case this exists for. Its code
/// is one anonymous blob with no symbols at all, so a patch site can only be
/// named by an address, and a hard-coded address dies at the target's next
/// release. A byte pattern taken from the *body* of the function survives it:
/// recompiling shifts every address but leaves the instruction sequence intact
/// as long as that code was not itself edited.
///
/// Only PT_LOAD segments carrying PF_X are searched, which excludes the heap,
/// the snapshot's data section and every other image. That keeps the scan to a
/// few megabytes and — more importantly — makes a hit unambiguous, since the
/// same bytes routinely occur in the Dart heap once the isolate has
/// deserialized.
///
/// `skip` > 0 returns the (skip+1)-th match, for the rare pattern that is not
/// unique. Returns the runtime address of the match, or 0.
///
/// The caller is expected to have chosen a pattern that does **not** overlap the
/// bytes it intends to write: patch the entry, anchor on the body. Overlapping
/// the two makes the second application of a multi-site patch land at the wrong
/// offset, because the anchor it matched no longer exists.
uintptr_t FindPatternInModule(const char *image, const uint8_t *pattern, size_t length,
                              int skip);

/// Number of times `pattern` occurs in `image`'s executable segments.
///
/// Uniqueness is the property that makes a pattern a usable locator, so it is
/// worth asserting at install time rather than trusting the first hit.
int CountPatternInModule(const char *image, const uint8_t *pattern, size_t length);

/// Replaces the function at `address` with one that immediately returns
/// `value`. Works for any function whose return fits in a register (int, bool,
/// pointer). Reversible with Unhook(). Returns false when the stub pool is
/// exhausted or Dobby refuses the address.
bool ReplaceWithConstant(void *address, intptr_t value);

/// Undoes ReplaceWithConstant or a NativeHooker's DobbyHook on `address`.
bool Unhook(void *address);

/// A plain function pointer that returns `value` when called, hooking nothing.
///
/// ReplaceWithConstant needs an existing function to redirect; this is for the
/// case where the *pointer itself* is what gets handed to someone else — a
/// JNINativeMethod entry being the reason it exists. Comes out of the same stub
/// pool, so the two share a budget. Returns nullptr when the pool is full.
void *ConstantStub(intptr_t value);

namespace detail {
/// Frees the constant-stub slot bound to `address`, if any. Called by Unhook().
void ReleaseConstantSlot(void *address);
} // namespace detail

// --------------------------------------------------------------------------
// Dynamically registered JNI methods
//
// A library that binds its natives with RegisterNatives from JNI_OnLoad exports
// nothing else, so FindSymbol has no name to look up — and when the method
// names are assembled at runtime rather than stored, they are not in the binary
// either. The one moment where a name and its pointer exist side by side is the
// RegisterNatives call, so that is where they get read.
//
// Install the watch *before* the target loads its library. What is registered
// after that is recorded, and any pending rule is applied to the JNINativeMethod
// array on its way through — the library's own code is never written to, which
// also keeps it invisible to a native integrity check.
// --------------------------------------------------------------------------

struct JniRegistration {
    /// Dotted, as `Class.getName()` reports it.
    std::string className;
    std::string methodName;
    std::string signature;
    /// The function the library registered.
    void *address;
};

/// Remembers the JavaVM from JNI_OnLoad. The watch needs an env of its own.
void SetJavaVm(JavaVM *vm);

/// Installs the RegisterNatives interceptor. Idempotent; also reachable as the
/// "jni_register_watch" hooker.
bool InstallJniRegisterWatch();

/// Everything seen so far, oldest first.
std::vector<JniRegistration> JniRegistrations();

/// What `className.methodName` was registered with, or nullptr.
void *JniRegistrationAddress(const char *className, const char *methodName);

/// Makes `className.methodName` return `value` instead of running.
///
/// Applied when the method is registered, by substituting the pointer before
/// ART sees it. If it is already registered the stub goes in through
/// ReplaceWithConstant instead, which does patch the target. Only valid for a
/// method whose return fits in a register — anything returning a jobject would
/// hand Java a fabricated reference.
bool ConstantOnJniRegister(const char *className, const char *methodName, intptr_t value);

// --------------------------------------------------------------------------
// 模块激活校验
//
// 判据是「本机的 Telegram（或第三方 TG 客户端）里有没有那个群」，而判定代码不在
// 这个 .so 里 —— 它被单独编译、加密，运行时解到匿名内存里执行，见 activation.cpp
// 和 payload/。这一层只是把入口暴露给 JNI。
//
// 两个动作分居两处进程，中间靠一枚带 MAC 的令牌连起来：
//
//   探测   只能在 TG 客户端自己的进程里做（别人读不到它的 files/cache4.db），
//          命中就签发令牌
//   校验   在每一个被注入的进程里做，判断令牌是不是本模块签的、有没有过期
//
// 这样「有没有那个群」这件事只需要在 TG 进程里回答一次，其余进程只认令牌。
// --------------------------------------------------------------------------

/// 令牌的 hex 长度（不含结尾 0）。调用方按它备缓冲区。
constexpr size_t kActivationTokenHexLength = 48;

/// 探测 `dbPath` 指向的 `cache4.db`；命中时把令牌写成 hex 字符串到 `outHex`。
///
/// 同目录下的 `<dbPath>-wal` 会被一并考虑 —— Telegram 跑的是 WAL 模式，刚加进来的
/// 群很可能还没落进主库。
///
/// `sourceHash` 是签发方包名的 FNV-1a，会被签进令牌 —— 撤销时据此认人。
///
/// 返回值分三档，**0 和 3 的区别是「退群即停用」那条链路的关键**：
///
///   0  权威的「没有」：库读通了、表结构也认出来了，就是没这个群
///   1  有，`outHex` 已填
///   2  入参不合法
///   3  结论不可信：文件打不开、不是 SQLite 库、或者连表结构都没认出来
int ActivationProbe(const char *dbPath, uint32_t moduleVersion, uint32_t today, uint32_t sourceHash,
                    char *outHex, size_t outHexCapacity);

/// 令牌是不是本模块签发的，且签发日在 `today` 往前 `ttlDays` 天以内。
bool ActivationVerify(const char *tokenHex, uint32_t moduleVersion, uint32_t today,
                      uint32_t ttlDays);

// --------------------------------------------------------------------------
// C++ hookers
//
// For targets that need a genuine inline hook with a replacement function.
// Register at static-init time with CHUANYI_NATIVE_HOOKER, then install from
// Kotlin with NativeHook.install("<id>").
// --------------------------------------------------------------------------
struct NativeHooker {
    /// Stable id, matches what Kotlin passes to NativeHook.install().
    const char *id;
    /// One line for the module UI.
    const char *description;
    /// Called at most once; return false if the target was not found.
    bool (*install)();
};

void RegisterNativeHooker(const NativeHooker *hooker);

/// Installs a registered hooker by id. Repeat calls are no-ops.
bool InstallNativeHooker(const char *id);

/// Number of registered hookers, and accessor by index (for the UI listing).
size_t NativeHookerCount();
const NativeHooker *NativeHookerAt(size_t index);
bool IsNativeHookerInstalled(const char *id);

#define CHUANYI_NATIVE_HOOKER(varName, idStr, descStr, installFn)          \
    static const ::chuanyi::NativeHooker varName{idStr, descStr, installFn}; \
    __attribute__((constructor)) static void varName##_register() {        \
        ::chuanyi::RegisterNativeHooker(&varName);                         \
    }

} // namespace chuanyi
