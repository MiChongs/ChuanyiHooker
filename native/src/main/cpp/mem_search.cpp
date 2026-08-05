// Equal-length byte search-and-replace over the process's own mappings.
//
// Written for Flutter targets. A Dart AOT snapshot's isolate *data* is a
// serialized cluster stream, not a heap image: string payloads are preceded by
// a `(len<<1)|0x80` varint length marker rather than an object header. It is
// deserialized into the heap at isolate start, so patching the mapped .so at
// runtime is useless — the objects already exist. Patching the copy in the heap
// works, and because a Dart string is plain contiguous ASCII there, an
// equal-length overwrite leaves the length field alone.
//
// That is what makes this useful on an obfuscated build: swapping a string
// literal needs no object-pool walk and no code offset at all.
//
// Two things this must never do, both learned the hard way:
//
//   * Never dereference a mapping directly. /proc/self/maps is a snapshot, and
//     this runs on a background thread while the app keeps working; ART's
//     concurrent collector re-protects and unmaps its spaces continuously. A
//     plain memchr over a region walks straight into SEGV_ACCERR. Everything
//     here goes through a pipe, where a bad address surfaces as EFAULT.
//   * Never install a SIGSEGV handler to paper over that. ART uses SIGSEGV
//     itself for implicit null checks and GC read barriers; taking that signal
//     over is far more dangerous than the fault it would hide.

#include "chuanyi/native_hook.h"

#include <fcntl.h>
#include <inttypes.h>
#include <sys/mman.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace chuanyi {
namespace {

/// Copy window, comfortably under a pipe's default 64 KiB capacity so a write
/// always fits in one go. Enlarging the pipe and writing a full capacity's worth
/// per call deadlocks the moment a single byte is left over from an earlier
/// window: the write blocks for space that only this same thread would free.
constexpr size_t kChunk = 32 * 1024;

/// Ceiling on a single run returned by [FindAscii]. Well past any URL or PEM,
/// and low enough that a run of text with no terminator in sight (a JSON blob, a
/// log buffer) costs one window rather than a whole region.
constexpr size_t kMaxRunLength = 8 * 1024;

/**
 * Whether this byte is still part of a text run.
 *
 * Printable ASCII plus the three whitespace controls a PEM body carries. Every
 * byte >= 0x80 ends the run, which is what makes this exact rather than
 * heuristic on a Dart target: the length marker that follows a snapshot string
 * is `(len<<1)|0x80`, so its top bit is always set.
 */
bool Printable(uint8_t b) {
    return (b >= 0x20 && b <= 0x7E) || b == '\n' || b == '\r' || b == '\t';
}

struct Region {
    uintptr_t start;
    uintptr_t end;
    std::string name;
    /// False for the C allocator's arenas — see [Writable].
    bool writable;
};

/**
 * Whether a hit inside this mapping may be overwritten.
 *
 * The C allocator's arenas are read but never written. A copy of the needle
 * sitting in scudo or libc_malloc is a transient buffer someone else owns — an
 * HTTP request being assembled, a JNI string — and rewriting it achieves
 * nothing while risking a live object. Dart allocates its heap with mmap
 * directly, so the copy that matters is never in there.
 */
bool Writable(const std::string &name) {
    static const char *const kReadOnlyPrefixes[] = {
            "[anon:scudo:",
            "[anon:libc_malloc",
            "[anon:GWP-ASan",
            "[anon:bionic_",
            "[anon:linker_alloc",
            "[stack",
    };
    for (const char *prefix : kReadOnlyPrefixes) {
        if (name.rfind(prefix, 0) == 0) return false;
    }
    return true;
}

/**
 * Reads memory without ever touching it from this thread's context.
 *
 * write(2) into a pipe validates the source address in the kernel, so an
 * unmapped or PROT_NONE page comes back as EFAULT instead of a signal. One pipe
 * is created per scan and reused for every window.
 *
 * Both ends are non-blocking. Producer and consumer are the same thread here, so
 * anything that blocks blocks forever; with O_NONBLOCK a full pipe surfaces as
 * EAGAIN and the residue can be drained instead.
 */
class SafeReader {
public:
    SafeReader() {
        if (pipe2(fds_, O_CLOEXEC | O_NONBLOCK) != 0) fds_[0] = fds_[1] = -1;
    }

    ~SafeReader() {
        if (fds_[0] >= 0) close(fds_[0]);
        if (fds_[1] >= 0) close(fds_[1]);
    }

    SafeReader(const SafeReader &) = delete;
    SafeReader &operator=(const SafeReader &) = delete;

    bool valid() const { return fds_[0] >= 0 && fds_[1] >= 0; }

    size_t chunk() const { return kChunk; }

    /// Bytes actually copied, which is 0 as soon as the source stops being
    /// readable. Callers treat a short read as "this range moved, skip it".
    size_t read(const void *src, uint8_t *dst, size_t length) {
        if (!valid()) return 0;
        drain();
        const auto *from = static_cast<const uint8_t *>(src);
        size_t done = 0;
        while (done < length) {
            size_t want = length - done;
            if (want > kChunk) want = kChunk;

            const ssize_t written = write(fds_[1], from + done, want);
            // <= 0 means the source stopped being readable (EFAULT), or the pipe
            // still holds something (EAGAIN). Either way this window is over.
            if (written <= 0) break;

            size_t got = 0;
            while (got < static_cast<size_t>(written)) {
                const ssize_t n = ::read(fds_[0], dst + done + got,
                                         static_cast<size_t>(written) - got);
                if (n <= 0) {
                    drain();
                    return done + got;
                }
                got += static_cast<size_t>(n);
            }
            done += static_cast<size_t>(written);
        }
        return done;
    }

private:
    /// Throws away anything left in the pipe so the next write starts empty.
    void drain() {
        uint8_t scratch[4096];
        while (::read(fds_[0], scratch, sizeof(scratch)) > 0) {
        }
    }

    int fds_[2]{-1, -1};
};

/// True for ART's managed spaces — the Java heap, its JIT caches and GC spaces.
bool IsManagedHeap(const std::string &name) {
    return name.rfind("[anon:dalvik-", 0) == 0;
}

/**
 * Whether ART's own spaces are worth writing into.
 *
 * Only the object spaces are. The rest of `[anon:dalvik-*]` is ART's own
 * bookkeeping — JIT code caches, mark bitmaps, card tables, the zygote's
 * pre-loaded image — where a byte pattern that happens to match is never a Java
 * string, and a write is a corrupted runtime rather than a patched literal.
 *
 * Reading them is harmless, so this narrows writes only, and the caller's read
 * back check catches anything this list gets wrong.
 */
bool IsManagedObjectSpace(const std::string &name) {
    static const char *const kObjectSpaces[] = {
            "[anon:dalvik-main space",
            "[anon:dalvik-non moving space",
            "[anon:dalvik-large object space",
            "[anon:dalvik-free list large object space",
            "[anon:dalvik-zygote space",
    };
    for (const char *prefix : kObjectSpaces) {
        if (name.rfind(prefix, 0) == 0) return true;
    }
    return false;
}

/**
 * Mappings worth scanning, for a given scope.
 *
 * Only anonymous mappings ever qualify. A file-backed one holds the *image* of a
 * string — a Dart snapshot deserialized into the heap at isolate start, a dex
 * whose string data ART copies into `mirror::String` objects on resolve — so
 * overwriting it changes nothing either runtime will read back, while a private
 * copy-on-write page and a misleading hit count are real. The copy that matters
 * is always anonymous.
 *
 * Which anonymous mappings depends on where the target keeps the literal, and
 * the two answers do not overlap:
 *
 *   * [MemoryScope::kNative] excludes `[anon:dalvik-*]`. Dart objects never live
 *     there, and those are exactly the regions ART's collector keeps
 *     re-protecting — scanning them for a Flutter target is pure risk.
 *   * [MemoryScope::kManagedHeap] is the mirror image: a Java literal is *only*
 *     ever in ART's object spaces, so everything else is noise.
 *
 * Kernel and pseudo mappings are excluded throughout; reading them can fault or
 * have side effects.
 */
std::vector<Region> ScannableRegions(MemoryScope scope) {
    std::vector<Region> out;
    FILE *fp = fopen("/proc/self/maps", "re");
    if (fp == nullptr) return out;

    char line[1024];
    while (fgets(line, sizeof(line), fp) != nullptr) {
        uintptr_t start = 0;
        uintptr_t end = 0;
        char perms[8] = {0};
        char path[512] = {0};
        const int parsed = sscanf(line, "%" SCNxPTR "-%" SCNxPTR " %7s %*s %*s %*s %511[^\n]",
                                  &start, &end, perms, path);
        if (parsed < 3 || perms[0] != 'r' || end <= start) continue;

        std::string name(path);
        // sscanf leaves the leading blanks of the path field in place.
        const size_t first = name.find_first_not_of(' ');
        name = first == std::string::npos ? std::string() : name.substr(first);

        // Every file-backed mapping; see above.
        if (name.rfind("/", 0) == 0) continue;
        if (name == "[vvar]" || name == "[vdso]" || name == "[vsyscall]") continue;

        const bool managed = IsManagedHeap(name);
        switch (scope) {
            case MemoryScope::kNative:
                if (managed) continue;
                break;
            case MemoryScope::kManagedHeap:
                if (!managed) continue;
                break;
            case MemoryScope::kAll:
                break;
        }

        const bool writable = managed ? IsManagedObjectSpace(name) : Writable(name);
        out.push_back(Region{start, end, name, writable});
    }
    fclose(fp);
    return out;
}

/**
 * Calls `onHit(region, absoluteAddress)` for every occurrence; returning false
 * from it stops the scan.
 */
template <typename OnHit>
int Scan(const uint8_t *needle, size_t needleLen, MemoryScope scope, const OnHit &onHit) {
    if (needle == nullptr || needleLen == 0) return -1;

    SafeReader reader;
    if (!reader.valid()) {
        LogError("mem scan: cannot create the read pipe (%s)", strerror(errno));
        return -1;
    }

    const size_t window = reader.chunk() > needleLen ? reader.chunk() : needleLen * 2;
    std::vector<uint8_t> buffer(window);
    const size_t pageSize = static_cast<size_t>(sysconf(_SC_PAGESIZE));

    int hits = 0;
    size_t scanned = 0;

    for (const Region &region : ScannableRegions(scope)) {
        const size_t size = region.end - region.start;
        if (size < needleLen) continue;

        size_t offset = 0;
        while (offset + needleLen <= size) {
            size_t want = size - offset;
            if (want > window) want = window;

            const size_t got = reader.read(reinterpret_cast<const void *>(region.start + offset),
                                           buffer.data(), want);
            if (got < needleLen) {
                // Vanished or re-protected under us. Step a page and carry on
                // instead of abandoning the region.
                offset += got > 0 ? got : pageSize;
                continue;
            }
            scanned += got;

            size_t local = 0;
            while (local + needleLen <= got) {
                const auto *found = static_cast<const uint8_t *>(
                        memchr(buffer.data() + local, needle[0], got - local - needleLen + 1));
                if (found == nullptr) break;
                local = static_cast<size_t>(found - buffer.data());
                if (memcmp(buffer.data() + local, needle, needleLen) == 0) {
                    hits++;
                    if (!onHit(region, region.start + offset + local)) return hits;
                    local += needleLen;
                } else {
                    local++;
                }
            }

            if (got < want) {
                offset += got;
                continue;
            }
            // Overlap so a match straddling the window boundary is still found.
            offset += got - (needleLen - 1);
        }
    }

    LogDebug("mem scan: %zu KiB read, %d hit(s)", scanned / 1024, hits);
    return hits;
}

} // namespace

int ReplaceInMemory(const uint8_t *needle, size_t needleLen, const uint8_t *replacement,
                    size_t replacementLen, int limit, MemoryScope scope) {
    if (needleLen != replacementLen) {
        LogError("ReplaceInMemory needs equal lengths (%zu vs %zu)", needleLen, replacementLen);
        return -1;
    }
    if (replacement == nullptr) return -1;

    SafeReader verifier;
    std::vector<uint8_t> current(needleLen);
    int written = 0;

    Scan(needle, needleLen, scope, [&](const Region &region, uintptr_t address) {
        const char *where = region.name.empty() ? "[anon]" : region.name.c_str();

        if (!region.writable) {
            LogDebug("ReplaceInMemory: skipping %p in %s (not an object space)",
                     reinterpret_cast<void *>(address), where);
            return true;
        }

        // Re-check through the pipe: between the scan and the write the region
        // may already be gone, and the write would dereference it.
        if (verifier.read(reinterpret_cast<const void *>(address), current.data(), needleLen)
                != needleLen ||
            memcmp(current.data(), needle, needleLen) != 0) {
            LogWarn("ReplaceInMemory: %p changed before the write",
                    reinterpret_cast<void *>(address));
            return true;
        }

        // WriteMemory, not PatchMemory: this is data. PatchMemory goes through
        // DobbyCodePatch, which restores the page as PROT_READ|PROT_EXEC no
        // matter what it was, and a data page that loses write permission takes
        // the app down on its next store.
        if (WriteMemory(reinterpret_cast<void *>(address), replacement,
                        static_cast<uint32_t>(replacementLen))) {
            written++;
            LogInfo("ReplaceInMemory: patched %p (%s)", reinterpret_cast<void *>(address), where);
        } else {
            LogWarn("ReplaceInMemory: write refused at %p (%s)",
                    reinterpret_cast<void *>(address), where);
        }
        return limit <= 0 || written < limit;
    });
    return written;
}

int CountInMemory(const uint8_t *needle, size_t needleLen, MemoryScope scope) {
    return Scan(needle, needleLen, scope, [](const Region &, uintptr_t) { return true; });
}

std::vector<std::string> FindAscii(const char *prefix, size_t minLen, size_t maxLen, int limit,
                                   MemoryScope scope) {
    std::vector<std::string> out;
    if (prefix == nullptr) return out;

    const size_t prefixLen = strlen(prefix);
    if (prefixLen == 0) return out;
    if (maxLen < prefixLen) maxLen = prefixLen;
    if (maxLen > kMaxRunLength) maxLen = kMaxRunLength;

    SafeReader reader;
    std::vector<uint8_t> window(maxLen);

    Scan(reinterpret_cast<const uint8_t *>(prefix), prefixLen, scope,
         [&](const Region &region, uintptr_t address) {
             // A short read is the normal case at the tail of a mapping, so take
             // whatever came back rather than discarding the hit.
             const size_t got = reader.read(reinterpret_cast<const void *>(address),
                                            window.data(), maxLen);
             if (got < prefixLen) return true;

             size_t length = prefixLen;
             while (length < got && Printable(window[length])) length++;
             if (length < minLen) return true;

             std::string text(reinterpret_cast<const char *>(window.data()), length);
             // The same literal is reachable through several mappings; the
             // caller wants the set of distinct values, not the hit count.
             for (const std::string &seen : out) {
                 if (seen == text) return true;
             }
             LogDebug("FindAscii: %p in %s -> %zu bytes",
                      reinterpret_cast<void *>(address),
                      region.name.empty() ? "[anon]" : region.name.c_str(), length);
             out.push_back(std::move(text));
             return limit <= 0 || static_cast<int>(out.size()) < limit;
         });

    LogInfo("FindAscii(\"%s\"): %zu distinct match(es)", prefix, out.size());
    return out;
}

} // namespace chuanyi
