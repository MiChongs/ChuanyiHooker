// "Make this native function return a constant."
//
// Dobby replacement functions are plain pointers with no context, so a single
// generic stub cannot know which address it was called for. Instead we hand out
// one pre-compiled stub per active replacement, each closing over its own slot.
//
// This is architecture independent - Dobby builds the trampoline - and fully
// reversible, unlike patching a `mov x0,#imm; ret` over the prologue.

#include "chuanyi/native_hook.h"

#include <array>
#include <cstring>
#include <mutex>
#include <utility>

#include "dobby.h"

namespace chuanyi {
namespace {

constexpr size_t kSlotCount = 32;

struct Slot {
    /// Hook site this slot is bound to; null for a bare ConstantStub().
    void *address = nullptr;
    /// Set for a bare stub, which has no address to be recognised by.
    bool bare = false;
    intptr_t value = 0;
    dobby_dummy_func_t origin = nullptr;
};

bool InUse(const Slot &slot) { return slot.address != nullptr || slot.bare; }

std::mutex g_mutex;
Slot g_slots[kSlotCount];
intptr_t g_values[kSlotCount] = {};

template <size_t N>
intptr_t StubFn() {
    return g_values[N];
}

using StubPtr = intptr_t (*)();

template <size_t... Is>
constexpr std::array<StubPtr, sizeof...(Is)> MakeStubs(std::index_sequence<Is...>) {
    return {{&StubFn<Is>...}};
}

const std::array<StubPtr, kSlotCount> g_stubs = MakeStubs(std::make_index_sequence<kSlotCount>{});

} // namespace

bool ReplaceWithConstant(void *address, intptr_t value) {
    if (address == nullptr) return false;
    std::lock_guard<std::mutex> guard(g_mutex);

    // Already stubbed: just update the value, no second hook on the same site.
    for (size_t i = 0; i < kSlotCount; ++i) {
        if (g_slots[i].address == address) {
            g_values[i] = value;
            LogDebug("ReplaceWithConstant(%p) updated to %ld", address, static_cast<long>(value));
            return true;
        }
    }

    for (size_t i = 0; i < kSlotCount; ++i) {
        if (InUse(g_slots[i])) continue;
        g_values[i] = value;
        dobby_dummy_func_t origin = nullptr;
        int rc = DobbyHook(address,
                           reinterpret_cast<dobby_dummy_func_t>(g_stubs[i]),
                           &origin);
        if (rc != 0) {
            g_values[i] = 0;
            LogError("DobbyHook(%p) failed: %d", address, rc);
            return false;
        }
        g_slots[i].address = address;
        g_slots[i].value = value;
        g_slots[i].origin = origin;
        LogInfo("stubbed %p -> %ld (slot %zu)", address, static_cast<long>(value), i);
        return true;
    }

    LogError("constant stub pool exhausted (%zu slots)", kSlotCount);
    return false;
}

void *ConstantStub(intptr_t value) {
    std::lock_guard<std::mutex> guard(g_mutex);
    for (size_t i = 0; i < kSlotCount; ++i) {
        if (InUse(g_slots[i])) continue;
        g_values[i] = value;
        g_slots[i].bare = true;
        g_slots[i].value = value;
        LogDebug("ConstantStub(%ld) -> slot %zu", static_cast<long>(value), i);
        return reinterpret_cast<void *>(g_stubs[i]);
    }
    LogError("constant stub pool exhausted (%zu slots)", kSlotCount);
    return nullptr;
}

namespace detail {

void ReleaseConstantSlot(void *address) {
    if (address == nullptr) return;
    std::lock_guard<std::mutex> guard(g_mutex);
    for (size_t i = 0; i < kSlotCount; ++i) {
        if (g_slots[i].address != address) continue;
        g_slots[i] = Slot{};
        g_values[i] = 0;
        return;
    }
}

} // namespace detail
} // namespace chuanyi
