// Built-in native hooker: watch RegisterNatives.
//
// A library that binds its natives dynamically exports only JNI_OnLoad, so
// there is no symbol to resolve for the individual functions. libastraflow_verify
// goes one further and assembles the method names at runtime, so they are not in
// the binary at all — `strings` on it yields "ss1_v1", "mfin_v1" and friends,
// never "nVrfFinal". The name and the pointer meet in exactly one place: the
// JNINativeMethod array passed to RegisterNatives. This intercepts that call.
//
// Rules registered through ConstantOnJniRegister are applied to the array *on
// the way through*, before ART binds anything: the entry's fnPtr is swapped for
// a constant stub. The library's own text is never written to — which matters
// for a target that reads /proc/self/maps looking for exactly that.
//
// The interception itself is a single pointer in the JNIEnv function table.
// That table is shared by every thread and its layout is fixed by the JNI spec,
// so this does not depend on an ART version or a mangled symbol name. If the
// table cannot be made writable we fall back to inline-hooking whatever it
// points at, which is ART's own RegisterNatives.

#include "chuanyi/native_hook.h"

#include <cstring>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>

#include "dobby.h"

namespace chuanyi {
namespace {

using RegisterNativesFn = jint (*)(JNIEnv *, jclass, const JNINativeMethod *, jint);

struct Rule {
    std::string className;
    std::string methodName;
    intptr_t value;
    /// Set once the substitution has gone through. From then on the binding is
    /// fixed for the life of the process — the array it went into is long gone.
    bool applied;
};

std::mutex g_mutex;
std::vector<JniRegistration> g_registrations;
std::vector<Rule> g_rules;

std::atomic<RegisterNativesFn> g_original{nullptr};
JavaVM *g_vm = nullptr;

/// JNI accepts both "a/b/C" and "a.b.C"; Class.getName() only ever returns the
/// second, so everything is compared in that form.
std::string Dotted(const char *name) {
    std::string out(name != nullptr ? name : "");
    for (char &c : out) {
        if (c == '/') c = '.';
    }
    return out;
}

JNIEnv *CurrentEnv() {
    if (g_vm == nullptr) return nullptr;
    JNIEnv *env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return nullptr;
    return env;
}

/// `clazz` as Class.getName() would print it.
///
/// This runs inside someone else's JNI call, so it borrows their local frame
/// and hands back a clean exception state: a pending exception is stashed and
/// re-thrown, and anything we cause ourselves is swallowed.
std::string ClassName(JNIEnv *env, jclass clazz) {
    if (env == nullptr || clazz == nullptr) return {};

    jthrowable pending = env->ExceptionOccurred();
    if (pending != nullptr) env->ExceptionClear();

    std::string out;
    if (env->PushLocalFrame(8) == JNI_OK) {
        jclass classClass = env->GetObjectClass(clazz);
        if (classClass != nullptr) {
            jmethodID getName = env->GetMethodID(classClass, "getName", "()Ljava/lang/String;");
            if (getName != nullptr) {
                auto name = reinterpret_cast<jstring>(env->CallObjectMethod(clazz, getName));
                if (!env->ExceptionCheck() && name != nullptr) {
                    if (const char *chars = env->GetStringUTFChars(name, nullptr)) {
                        out = chars;
                        env->ReleaseStringUTFChars(name, chars);
                    }
                }
            }
        }
        env->ExceptionClear();
        env->PopLocalFrame(nullptr);
    }

    if (pending != nullptr) {
        env->Throw(pending);
        env->DeleteLocalRef(pending);
    }
    return out;
}

/// Caller holds g_mutex.
Rule *FindRule(const std::string &className, const std::string &methodName) {
    for (auto &rule : g_rules) {
        if (rule.className == className && rule.methodName == methodName) return &rule;
    }
    return nullptr;
}

/// Caller holds g_mutex. Later registrations of the same method win.
void Record(const std::string &className, const JNINativeMethod &method, void *address) {
    for (auto &existing : g_registrations) {
        if (existing.className != className) continue;
        if (existing.methodName != (method.name != nullptr ? method.name : "")) continue;
        existing.signature = method.signature != nullptr ? method.signature : "";
        existing.address = address;
        return;
    }
    g_registrations.push_back(JniRegistration{
            className,
            method.name != nullptr ? method.name : "",
            method.signature != nullptr ? method.signature : "",
            address,
    });
}

jint WatchedRegisterNatives(JNIEnv *env, jclass clazz, const JNINativeMethod *methods, jint count) {
    RegisterNativesFn original = g_original.load(std::memory_order_acquire);
    if (original == nullptr) return JNI_ERR;
    if (env == nullptr || methods == nullptr || count <= 0) {
        return original(env, clazz, methods, count);
    }

    const std::string className = ClassName(env, clazz);

    // Copied unconditionally: the substitution has to happen before ART reads
    // the array, and the caller's copy is not ours to write to (it is often in
    // the library's read-only data).
    std::vector<JNINativeMethod> patched(methods, methods + static_cast<size_t>(count));
    bool substituted = false;
    {
        std::lock_guard<std::mutex> guard(g_mutex);
        for (jint i = 0; i < count; ++i) {
            const JNINativeMethod &entry = methods[i];
            Record(className, entry, entry.fnPtr);
            LogDebug("RegisterNatives: %s.%s%s -> %p", className.c_str(),
                     entry.name != nullptr ? entry.name : "?",
                     entry.signature != nullptr ? entry.signature : "", entry.fnPtr);

            Rule *rule = FindRule(className, entry.name != nullptr ? entry.name : "");
            if (rule == nullptr) continue;
            void *stub = ConstantStub(rule->value);
            if (stub == nullptr) continue;
            patched[static_cast<size_t>(i)].fnPtr = stub;
            rule->applied = true;
            substituted = true;
            LogInfo("JNI %s.%s bound to a constant (%ld) instead of %p", className.c_str(),
                    entry.name, static_cast<long>(rule->value), entry.fnPtr);
        }
    }

    return original(env, clazz, substituted ? patched.data() : methods, count);
}

} // namespace

void SetJavaVm(JavaVM *vm) { g_vm = vm; }

bool InstallJniRegisterWatch() {
    if (g_original.load(std::memory_order_acquire) != nullptr) return true;

    JNIEnv *env = CurrentEnv();
    if (env == nullptr) {
        LogWarn("jni_register_watch: no JNIEnv on this thread");
        return false;
    }
    auto *table = const_cast<JNINativeInterface *>(env->functions);
    if (table == nullptr || table->RegisterNatives == nullptr) {
        LogWarn("jni_register_watch: JNIEnv has no function table");
        return false;
    }
    if (table->RegisterNatives == &WatchedRegisterNatives) return true;

    // Publish the original before the table starts routing calls at us, or a
    // RegisterNatives on another thread lands in WatchedRegisterNatives with
    // nothing to forward to.
    g_original.store(table->RegisterNatives, std::memory_order_release);

    // The table lives in libart's RELRO, so it is read-only by the time anyone
    // sees it; WriteMemory restores exactly the protection it found.
    RegisterNativesFn replacement = &WatchedRegisterNatives;
    if (WriteMemory(&table->RegisterNatives, reinterpret_cast<const uint8_t *>(&replacement),
                    sizeof(replacement))) {
        LogInfo("jni_register_watch: JNIEnv table slot patched");
        return true;
    }

    // Fallback: hook ART's implementation itself. Same effect, more invasive.
    dobby_dummy_func_t origin = nullptr;
    const int rc = DobbyHook(reinterpret_cast<void *>(g_original.load(std::memory_order_acquire)),
                             reinterpret_cast<dobby_dummy_func_t>(&WatchedRegisterNatives),
                             &origin);
    if (rc != 0 || origin == nullptr) {
        g_original.store(nullptr, std::memory_order_release);
        LogError("jni_register_watch: table not writable and DobbyHook failed (%d)", rc);
        return false;
    }
    g_original.store(reinterpret_cast<RegisterNativesFn>(origin), std::memory_order_release);
    LogInfo("jni_register_watch: inline hook on ART RegisterNatives");
    return true;
}

std::vector<JniRegistration> JniRegistrations() {
    std::lock_guard<std::mutex> guard(g_mutex);
    return g_registrations;
}

void *JniRegistrationAddress(const char *className, const char *methodName) {
    if (className == nullptr || methodName == nullptr) return nullptr;
    const std::string owner = Dotted(className);
    std::lock_guard<std::mutex> guard(g_mutex);
    for (const auto &entry : g_registrations) {
        if (entry.className == owner && entry.methodName == methodName) return entry.address;
    }
    return nullptr;
}

bool ConstantOnJniRegister(const char *className, const char *methodName, intptr_t value) {
    if (className == nullptr || methodName == nullptr) return false;
    const std::string owner = Dotted(className);
    const std::string name = methodName;

    // Ordering is the whole game here, so the watch goes in first even if the
    // caller forgot — a rule added after the library loaded is worth much less.
    if (!InstallJniRegisterWatch()) return false;

    void *known = nullptr;
    bool alreadyApplied = false;
    {
        std::lock_guard<std::mutex> guard(g_mutex);
        Rule *rule = FindRule(owner, name);
        if (rule != nullptr) {
            alreadyApplied = rule->applied;
            // A value change after the substitution went in cannot be honoured —
            // ART holds the stub pointer now — so do not pretend otherwise.
            if (!alreadyApplied) rule->value = value;
        } else {
            // Kept even when the method is already bound: a re-registration (a
            // second JNI_OnLoad after a reload) has to be covered too.
            g_rules.push_back(Rule{owner, name, value, false});
        }
        if (!alreadyApplied) {
            for (const auto &entry : g_registrations) {
                if (entry.className == owner && entry.methodName == name) {
                    known = entry.address;
                    break;
                }
            }
        }
    }

    // Re-issued on a hot reload, and already in effect. Falling through here
    // would patch the real function for no reason — ART is not calling it.
    if (alreadyApplied) {
        LogDebug("JNI %s.%s is already bound to a constant", owner.c_str(), name.c_str());
        return true;
    }

    if (known == nullptr) {
        LogInfo("JNI %s.%s will return %ld once registered", owner.c_str(), name.c_str(),
                static_cast<long>(value));
        return true;
    }

    // Already bound — the array is gone, so the function itself has to be
    // patched. Louder than the substitution, but it is that or nothing.
    LogWarn("JNI %s.%s was already registered; patching %p in place", owner.c_str(), name.c_str(),
            known);
    return ReplaceWithConstant(known, value);
}

// 第三个参数会原样显示在模块界面上，所以写中文、不写实现细节。
CHUANYI_NATIVE_HOOKER(kJniRegisterWatch,
                      "jni_register_watch",
                      "记录目标动态注册的 native 方法，并可按名字接管其返回值",
                      InstallJniRegisterWatch)

} // namespace chuanyi
