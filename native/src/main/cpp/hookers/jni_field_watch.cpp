// Built-in native hooker: 看字符串池到底有没有人往里写。
//
// ── 要回答什么 ──
//
// 应用几乎所有字符串常量都在 util001.framework.init 的 AA/BA/CA/DA/EA 五个类里，
// 共 466 个 `public static String`。实测注入状态下**全是 null**，应用于是崩在任何
// 一个用到字符串的地方（先 ErrReport.j()，后 Firebase 的 IdManager）。
//
// 但填充者是谁，到现在没找到：
//
//   * 全工程 grep，**没有任何 Java 代码**给那些字段赋值；
//   * `lib__5885__.so` **没有导出 JNI_OnLoad**，dlsym 清单里也没有任何 JNI 符号 ——
//     它凭什么调 SetStaticObjectField？
//   * 那 5 个类都在 classes6.dex，整个才 10 KB，看着像存根。
//
// 于是有个假设：那几个类的"真身"是加密的，运行时解密后加载替换；检测失败就不解密，
// 类停在存根状态，字段自然全 null。
//
// 这个 hooker 就是来一锤定音的 —— 挂住 JNIEnv 函数表里三个槽位：
//
//   GetStaticFieldID     谁想写哪个字段（比 Set 更早、更容易命中）
//   SetStaticObjectField 实际写入，以及写进去的是不是 null
//   DefineClass          有没有在运行时定义新类（验证"存根被替换"）
//
// 一次都没触发 → 壳压根没走到填充，问题在更前面的分支；
// 触发了但写 null → 是解密产物为空，方向转到那份加密 dex。
//
// 拦的是 JNIEnv 函数表里的一个指针。那张表所有线程共用、布局由 JNI 规范固定，
// 所以既不依赖 ART 版本也不依赖符号名 —— 和 jni_register_watch 同一套路数。
//
// 用 NativeHook.install("jni_field_watch") 打开。**必须在壳加载之前装**。

#include "chuanyi/native_hook.h"

#include <dlfcn.h>

#include <atomic>
#include <cstring>
#include <string>

#include "dobby.h"

namespace chuanyi {
namespace {

using GetStaticFieldIdFn = jfieldID (*)(JNIEnv *, jclass, const char *, const char *);
using SetStaticObjectFieldFn = void (*)(JNIEnv *, jclass, jfieldID, jobject);
using DefineClassFn = jclass (*)(JNIEnv *, const char *, jobject, const jbyte *, jsize);

std::atomic<GetStaticFieldIdFn> g_origGetStaticFieldId{nullptr};
std::atomic<SetStaticObjectFieldFn> g_origSetStaticObjectField{nullptr};
std::atomic<DefineClassFn> g_origDefineClass{nullptr};

std::atomic<int> g_poolWrites{0};
std::atomic<int> g_poolNulls{0};
std::atomic<int> g_lookups{0};

JavaVM *JavaVmOrNull() {
    using GetVMsFn = jint (*)(JavaVM **, jsize, jsize *);

    // dlsym(RTLD_DEFAULT) 在应用进程里找不到它（实测），因为 libart 不在默认搜索
    // 链上。改走 Dobby 的符号解析器 —— 它直接读 ELF 符号表，非导出符号也翻得到，
    // 这正是它相对 dlsym 的价值。
    auto fn = reinterpret_cast<GetVMsFn>(dlsym(RTLD_DEFAULT, "JNI_GetCreatedJavaVMs"));
    if (fn == nullptr) {
        static const char *const kImages[] = {"libart.so", "libnativehelper.so", nullptr};
        for (int i = 0; kImages[i] != nullptr && fn == nullptr; ++i) {
            fn = reinterpret_cast<GetVMsFn>(FindSymbol(kImages[i], "JNI_GetCreatedJavaVMs"));
        }
    }
    if (fn == nullptr) {
        LogWarn("jni_field_watch: 解析不到 JNI_GetCreatedJavaVMs");
        return nullptr;
    }
    JavaVM *vm = nullptr;
    jsize count = 0;
    if (fn(&vm, 1, &count) != JNI_OK || count == 0) return nullptr;
    return vm;
}

JNIEnv *CurrentEnv() {
    JavaVM *vm = JavaVmOrNull();
    if (vm == nullptr) return nullptr;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return nullptr;
    return env;
}

/// 取 `clazz` 的名字。跑在别人的 JNI 调用里，所以借他们的局部帧，并且把异常状态
/// 原样还回去 —— 挂起的异常先存后抛，自己引发的一律吞掉。
std::string ClassNameOf(JNIEnv *env, jclass clazz) {
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
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->PopLocalFrame(nullptr);
    }
    if (pending != nullptr) env->Throw(pending);
    return out;
}

bool IsPoolClass(const std::string &name) {
    return name.find("util001") != std::string::npos;
}

jfieldID WatchedGetStaticFieldId(JNIEnv *env, jclass clazz, const char *name, const char *sig) {
    auto original = g_origGetStaticFieldId.load(std::memory_order_acquire);
    jfieldID result = original(env, clazz, name, sig);
    const std::string owner = ClassNameOf(env, clazz);
    if (IsPoolClass(owner)) {
        const int n = g_lookups.fetch_add(1) + 1;
        if (n <= 8) {
            LogInfo("jni_field_watch: GetStaticFieldID(%s.%s : %s)", owner.c_str(),
                    name != nullptr ? name : "?", sig != nullptr ? sig : "?");
        }
    }
    return result;
}

void WatchedSetStaticObjectField(JNIEnv *env, jclass clazz, jfieldID field, jobject value) {
    auto original = g_origSetStaticObjectField.load(std::memory_order_acquire);
    const std::string owner = ClassNameOf(env, clazz);
    if (IsPoolClass(owner)) {
        const int n = g_poolWrites.fetch_add(1) + 1;
        if (value == nullptr) g_poolNulls.fetch_add(1);
        if (n <= 8 || n % 100 == 0) {
            LogWarn("jni_field_watch: 第 %d 次写字符串池 %s，值%s", n, owner.c_str(),
                    value == nullptr ? "是 null" : "非空");
        }
    }
    original(env, clazz, field, value);
}

jclass WatchedDefineClass(JNIEnv *env, const char *name, jobject loader, const jbyte *buf,
                          jsize len) {
    LogWarn("jni_field_watch: DefineClass(\"%s\", %d 字节) —— 运行时定义了新类",
            name != nullptr ? name : "<null>", static_cast<int>(len));
    auto original = g_origDefineClass.load(std::memory_order_acquire);
    return original(env, name, loader, buf, len);
}

/// 换掉函数表里的一个槽位。表在 libart 的 RELRO 段里，等谁看到时已经是只读的，
/// [WriteMemory] 会把它原本的保护属性照原样恢复。
template <typename Fn>
bool PatchSlot(const char *what, Fn *slot, Fn replacement, std::atomic<Fn> &saved) {
    if (slot == nullptr || *slot == nullptr) {
        LogWarn("jni_field_watch: 函数表里没有 %s", what);
        return false;
    }
    if (*slot == replacement) return true;
    saved.store(*slot, std::memory_order_release);
    if (WriteMemory(slot, reinterpret_cast<const uint8_t *>(&replacement), sizeof(replacement))) {
        return true;
    }
    // 表改不动就退回 inline hook ART 的实现，效果一样，只是更具侵入性。
    dobby_dummy_func_t origin = nullptr;
    const int rc = DobbyHook(reinterpret_cast<void *>(*slot),
                             reinterpret_cast<dobby_dummy_func_t>(replacement), &origin);
    if (rc != 0 || origin == nullptr) {
        saved.store(nullptr, std::memory_order_release);
        LogError("jni_field_watch: %s 既改不了表也 hook 不了 (%d)", what, rc);
        return false;
    }
    saved.store(reinterpret_cast<Fn>(origin), std::memory_order_release);
    return true;
}

bool Install() {
    JNIEnv *env = CurrentEnv();
    if (env == nullptr) {
        LogWarn("jni_field_watch: 当前线程没有 JNIEnv");
        return false;
    }
    auto *table = const_cast<JNINativeInterface *>(env->functions);
    if (table == nullptr) {
        LogWarn("jni_field_watch: JNIEnv 没有函数表");
        return false;
    }

    int ok = 0;
    if (PatchSlot("GetStaticFieldID", &table->GetStaticFieldID, &WatchedGetStaticFieldId,
                  g_origGetStaticFieldId)) ok++;
    if (PatchSlot("SetStaticObjectField", &table->SetStaticObjectField,
                  &WatchedSetStaticObjectField, g_origSetStaticObjectField)) ok++;
    if (PatchSlot("DefineClass", &table->DefineClass, &WatchedDefineClass, g_origDefineClass)) ok++;

    if (ok == 0) return false;
    LogInfo("jni_field_watch: %d/3 个 JNIEnv 槽位就位，盯 util001 字符串池的写入", ok);
    return true;
}

} // namespace

// 第三个参数会原样显示在模块界面上，所以写中文、不写实现细节。
CHUANYI_NATIVE_HOOKER(kJniFieldWatch,
                      "jni_field_watch",
                      "监视谁在往字符串池写值，以及运行时有没有定义新类",
                      Install)

} // namespace chuanyi
