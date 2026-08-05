// JNI surface for com.chuanyi.hooker.nativehook.NativeHook.
//
// Registered dynamically in JNI_OnLoad so the exported symbol table stays
// empty; nothing here relies on name mangling, which keeps the Kotlin side free
// to move package or class without touching C++.

#include <jni.h>

#include <cstring>
#include <string>
#include <vector>

#include "chuanyi/native_hook.h"

namespace {

constexpr const char *kClassName = "com/chuanyi/hooker/nativehook/NativeHook";

std::string ToUtf8(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

jlong FindSymbol(JNIEnv *env, jclass, jstring image, jstring symbol) {
    const std::string imageName = ToUtf8(env, image);
    const std::string symbolName = ToUtf8(env, symbol);
    if (symbolName.empty()) return 0;
    void *addr = chuanyi::FindSymbol(image == nullptr ? nullptr : imageName.c_str(),
                                     symbolName.c_str());
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(addr));
}

jlong ModuleBase(JNIEnv *env, jclass, jstring image) {
    const std::string imageName = ToUtf8(env, image);
    if (imageName.empty()) return 0;
    return static_cast<jlong>(chuanyi::ModuleBase(imageName.c_str()));
}

jboolean PatchMemory(JNIEnv *env, jclass, jlong address, jbyteArray bytes) {
    if (address == 0 || bytes == nullptr) return JNI_FALSE;
    const jsize length = env->GetArrayLength(bytes);
    if (length <= 0) return JNI_FALSE;
    std::vector<uint8_t> buffer(static_cast<size_t>(length));
    env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(buffer.data()));
    const bool ok = chuanyi::PatchMemory(reinterpret_cast<void *>(static_cast<uintptr_t>(address)),
                                         buffer.data(), static_cast<uint32_t>(length));
    return ok ? JNI_TRUE : JNI_FALSE;
}

jboolean WriteMemory(JNIEnv *env, jclass, jlong address, jbyteArray bytes) {
    if (address == 0 || bytes == nullptr) return JNI_FALSE;
    const jsize length = env->GetArrayLength(bytes);
    if (length <= 0) return JNI_FALSE;
    std::vector<uint8_t> buffer(static_cast<size_t>(length));
    env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(buffer.data()));
    const bool ok = chuanyi::WriteMemory(reinterpret_cast<void *>(static_cast<uintptr_t>(address)),
                                         buffer.data(), static_cast<uint32_t>(length));
    return ok ? JNI_TRUE : JNI_FALSE;
}

/**
 * Address of the memory a direct ByteBuffer wraps, or 0.
 *
 * The point is that it ignores Java's read-only flag. `asReadOnlyBuffer()`
 * produces a view that refuses `putInt`, but the flag lives in the Java object,
 * not in the mapping — a target that hands such a view to its own native code
 * (so that side can write while Java only reads) is still writable through
 * here, which is exactly the case worth reaching.
 *
 * Returns 0 for a heap-backed buffer: there is no stable address to hand out,
 * since the GC may move the backing array at any time.
 */
jlong DirectBufferAddress(JNIEnv *env, jclass, jobject buffer) {
    if (buffer == nullptr) return 0;
    void *addr = env->GetDirectBufferAddress(buffer);
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(addr));
}

/** Byte length of a direct ByteBuffer's memory, or -1 when it is not direct. */
jlong DirectBufferCapacity(JNIEnv *env, jclass, jobject buffer) {
    if (buffer == nullptr) return -1;
    return static_cast<jlong>(env->GetDirectBufferCapacity(buffer));
}

jbyteArray ReadMemory(JNIEnv *env, jclass, jlong address, jint size) {
    if (address == 0 || size <= 0) return nullptr;
    std::vector<uint8_t> buffer(static_cast<size_t>(size));
    const bool ok = chuanyi::ReadMemory(
            reinterpret_cast<const void *>(static_cast<uintptr_t>(address)),
            buffer.data(), static_cast<uint32_t>(size));
    if (!ok) return nullptr;
    jbyteArray out = env->NewByteArray(size);
    if (out == nullptr) return nullptr;
    env->SetByteArrayRegion(out, 0, size, reinterpret_cast<const jbyte *>(buffer.data()));
    return out;
}

std::vector<uint8_t> ToBytes(JNIEnv *env, jbyteArray array) {
    if (array == nullptr) return {};
    const jsize length = env->GetArrayLength(array);
    if (length <= 0) return {};
    std::vector<uint8_t> out(static_cast<size_t>(length));
    env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

jlong FindPatternInModule(JNIEnv *env, jclass, jstring image, jbyteArray pattern, jint skip) {
    const std::string imageName = ToUtf8(env, image);
    const std::vector<uint8_t> bytes = ToBytes(env, pattern);
    if (imageName.empty() || bytes.empty()) return 0;
    return static_cast<jlong>(chuanyi::FindPatternInModule(imageName.c_str(), bytes.data(),
                                                           bytes.size(), skip < 0 ? 0 : skip));
}

jint CountPatternInModule(JNIEnv *env, jclass, jstring image, jbyteArray pattern) {
    const std::string imageName = ToUtf8(env, image);
    const std::vector<uint8_t> bytes = ToBytes(env, pattern);
    if (imageName.empty() || bytes.empty()) return -1;
    return chuanyi::CountPatternInModule(imageName.c_str(), bytes.data(), bytes.size());
}

/// Kotlin passes the scope as an ordinal; anything unexpected falls back to the
/// conservative choice rather than widening the scan.
chuanyi::MemoryScope ToScope(jint scope) {
    switch (scope) {
        case 1:
            return chuanyi::MemoryScope::kManagedHeap;
        case 2:
            return chuanyi::MemoryScope::kAll;
        default:
            return chuanyi::MemoryScope::kNative;
    }
}

jint ReplaceInMemory(JNIEnv *env, jclass, jbyteArray needle, jbyteArray replacement, jint limit,
                     jint scope) {
    const std::vector<uint8_t> from = ToBytes(env, needle);
    const std::vector<uint8_t> to = ToBytes(env, replacement);
    if (from.empty() || to.empty()) return -1;
    return chuanyi::ReplaceInMemory(from.data(), from.size(), to.data(), to.size(), limit,
                                    ToScope(scope));
}

jint CountInMemory(JNIEnv *env, jclass, jbyteArray needle, jint scope) {
    const std::vector<uint8_t> from = ToBytes(env, needle);
    if (from.empty()) return -1;
    return chuanyi::CountInMemory(from.data(), from.size(), ToScope(scope));
}

/**
 * Text runs starting with `prefix`, as a String[].
 *
 * NewStringUTF is safe here: FindAscii only ever returns bytes in 0x20..0x7E
 * plus tab/CR/LF, and that range is identical in modified UTF-8.
 */
jobjectArray FindAscii(JNIEnv *env, jclass, jstring prefix, jint minLen, jint maxLen, jint limit,
                       jint scope) {
    const std::string needle = ToUtf8(env, prefix);
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) return nullptr;
    if (needle.empty()) return env->NewObjectArray(0, stringClass, nullptr);

    const std::vector<std::string> found = chuanyi::FindAscii(
            needle.c_str(),
            minLen < 0 ? 0 : static_cast<size_t>(minLen),
            maxLen < 0 ? 0 : static_cast<size_t>(maxLen),
            limit,
            ToScope(scope));

    jobjectArray out = env->NewObjectArray(static_cast<jsize>(found.size()), stringClass, nullptr);
    if (out == nullptr) return nullptr;
    for (size_t i = 0; i < found.size(); ++i) {
        jstring value = env->NewStringUTF(found[i].c_str());
        if (value == nullptr) return nullptr;
        env->SetObjectArrayElement(out, static_cast<jsize>(i), value);
        env->DeleteLocalRef(value);
    }
    return out;
}

jboolean ReplaceWithConstant(JNIEnv *, jclass, jlong address, jlong value) {
    if (address == 0) return JNI_FALSE;
    const bool ok = chuanyi::ReplaceWithConstant(
            reinterpret_cast<void *>(static_cast<uintptr_t>(address)),
            static_cast<intptr_t>(value));
    return ok ? JNI_TRUE : JNI_FALSE;
}

jboolean Unhook(JNIEnv *, jclass, jlong address) {
    if (address == 0) return JNI_FALSE;
    return chuanyi::Unhook(reinterpret_cast<void *>(static_cast<uintptr_t>(address)))
           ? JNI_TRUE : JNI_FALSE;
}

jboolean InstallHooker(JNIEnv *env, jclass, jstring id) {
    const std::string name = ToUtf8(env, id);
    if (name.empty()) return JNI_FALSE;
    return chuanyi::InstallNativeHooker(name.c_str()) ? JNI_TRUE : JNI_FALSE;
}

jboolean IsHookerInstalled(JNIEnv *env, jclass, jstring id) {
    const std::string name = ToUtf8(env, id);
    if (name.empty()) return JNI_FALSE;
    return chuanyi::IsNativeHookerInstalled(name.c_str()) ? JNI_TRUE : JNI_FALSE;
}

/** Returns ["id\ndescription", ...] so the UI can list what native code offers. */
jobjectArray ListHookers(JNIEnv *env, jclass) {
    const size_t count = chuanyi::NativeHookerCount();
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) return nullptr;
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(count), stringClass, nullptr);
    if (out == nullptr) return nullptr;
    for (size_t i = 0; i < count; ++i) {
        const chuanyi::NativeHooker *hooker = chuanyi::NativeHookerAt(i);
        if (hooker == nullptr) continue;
        std::string line = std::string(hooker->id) + "\n" +
                           (hooker->description != nullptr ? hooker->description : "");
        jstring value = env->NewStringUTF(line.c_str());
        env->SetObjectArrayElement(out, static_cast<jsize>(i), value);
        env->DeleteLocalRef(value);
    }
    env->DeleteLocalRef(stringClass);
    return out;
}

void SetVerbose(JNIEnv *, jclass, jboolean verbose) {
    chuanyi::SetVerbose(verbose == JNI_TRUE);
}

/**
 * 探测一个 cache4.db。
 *
 * 返回状态码（见 chuanyi::ActivationProbe），命中时把令牌写进 `out[0]`。
 *
 * 之所以不是「返回令牌或 null」：调用方必须能分清**权威的「没有」**和「这个库读不
 * 出来」。前者才是撤销已签发令牌的依据，后者只是这次白跑一趟 —— 混成同一个 null，
 * 「退群之后立刻停用」就只能退化成等令牌过期。
 */
jint ActivationProbe(JNIEnv *env, jclass, jstring path, jint moduleVersion, jint today,
                     jint sourceHash, jobjectArray out) {
    const std::string dbPath = ToUtf8(env, path);
    if (dbPath.empty()) return 2;

    char token[chuanyi::kActivationTokenHexLength + 1] = {};
    const int result = chuanyi::ActivationProbe(dbPath.c_str(),
                                                static_cast<uint32_t>(moduleVersion),
                                                static_cast<uint32_t>(today),
                                                static_cast<uint32_t>(sourceHash), token,
                                                sizeof(token));
    if (result == 1 && out != nullptr && env->GetArrayLength(out) > 0) {
        jstring value = env->NewStringUTF(token);
        if (value != nullptr) {
            env->SetObjectArrayElement(out, 0, value);
            env->DeleteLocalRef(value);
        }
    }
    return result;
}

jboolean ActivationVerify(JNIEnv *env, jclass, jstring token, jint moduleVersion, jint today,
                          jint ttlDays) {
    const std::string value = ToUtf8(env, token);
    if (value.empty()) return JNI_FALSE;
    return chuanyi::ActivationVerify(value.c_str(), static_cast<uint32_t>(moduleVersion),
                                     static_cast<uint32_t>(today), static_cast<uint32_t>(ttlDays))
           ? JNI_TRUE : JNI_FALSE;
}

jboolean ConstantOnJniRegister(JNIEnv *env, jclass, jstring className, jstring methodName,
                               jlong value) {
    const std::string owner = ToUtf8(env, className);
    const std::string name = ToUtf8(env, methodName);
    if (owner.empty() || name.empty()) return JNI_FALSE;
    return chuanyi::ConstantOnJniRegister(owner.c_str(), name.c_str(),
                                          static_cast<intptr_t>(value))
           ? JNI_TRUE : JNI_FALSE;
}

jlong JniRegistrationAddress(JNIEnv *env, jclass, jstring className, jstring methodName) {
    const std::string owner = ToUtf8(env, className);
    const std::string name = ToUtf8(env, methodName);
    if (owner.empty() || name.empty()) return 0;
    void *addr = chuanyi::JniRegistrationAddress(owner.c_str(), name.c_str());
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(addr));
}

/** Returns ["class\nmethod\nsignature\naddress", ...]. */
jobjectArray JniRegistrations(JNIEnv *env, jclass) {
    const std::vector<chuanyi::JniRegistration> entries = chuanyi::JniRegistrations();
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) return nullptr;
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(entries.size()), stringClass, nullptr);
    if (out == nullptr) {
        env->DeleteLocalRef(stringClass);
        return nullptr;
    }
    for (size_t i = 0; i < entries.size(); ++i) {
        const chuanyi::JniRegistration &entry = entries[i];
        std::string line = entry.className + "\n" + entry.methodName + "\n" + entry.signature +
                           "\n" + std::to_string(reinterpret_cast<uintptr_t>(entry.address));
        jstring value = env->NewStringUTF(line.c_str());
        env->SetObjectArrayElement(out, static_cast<jsize>(i), value);
        env->DeleteLocalRef(value);
    }
    env->DeleteLocalRef(stringClass);
    return out;
}

void SetOpenatFilters(JNIEnv *env, jclass, jobjectArray filters) {
    std::vector<std::string> values;
    if (filters != nullptr) {
        const jsize count = env->GetArrayLength(filters);
        values.reserve(static_cast<size_t>(count));
        for (jsize i = 0; i < count; ++i) {
            auto item = reinterpret_cast<jstring>(env->GetObjectArrayElement(filters, i));
            std::string value = ToUtf8(env, item);
            if (item != nullptr) env->DeleteLocalRef(item);
            if (!value.empty()) values.push_back(std::move(value));
        }
    }
    chuanyi::SetOpenatFilters(values);
}

const JNINativeMethod kMethods[] = {
        {"nativeFindSymbol",  "(Ljava/lang/String;Ljava/lang/String;)J", reinterpret_cast<void *>(FindSymbol)},
        {"nativeModuleBase",  "(Ljava/lang/String;)J",                   reinterpret_cast<void *>(ModuleBase)},
        {"nativePatchMemory", "(J[B)Z",                                  reinterpret_cast<void *>(PatchMemory)},
        {"nativeWriteMemory", "(J[B)Z",                                  reinterpret_cast<void *>(WriteMemory)},
        {"nativeReadMemory",  "(JI)[B",                                  reinterpret_cast<void *>(ReadMemory)},
        {"nativeDirectBufferAddress", "(Ljava/lang/Object;)J",           reinterpret_cast<void *>(DirectBufferAddress)},
        {"nativeDirectBufferCapacity", "(Ljava/lang/Object;)J",          reinterpret_cast<void *>(DirectBufferCapacity)},
        {"nativeFindPatternInModule", "(Ljava/lang/String;[BI)J",        reinterpret_cast<void *>(FindPatternInModule)},
        {"nativeCountPatternInModule", "(Ljava/lang/String;[B)I",        reinterpret_cast<void *>(CountPatternInModule)},
        {"nativeReplaceInMemory", "([B[BII)I",                           reinterpret_cast<void *>(ReplaceInMemory)},
        {"nativeCountInMemory", "([BI)I",                                reinterpret_cast<void *>(CountInMemory)},
        {"nativeFindAscii",   "(Ljava/lang/String;IIII)[Ljava/lang/String;", reinterpret_cast<void *>(FindAscii)},
        {"nativeReturnConstant", "(JJ)Z",                                reinterpret_cast<void *>(ReplaceWithConstant)},
        {"nativeUnhook",      "(J)Z",                                    reinterpret_cast<void *>(Unhook)},
        {"nativeInstallHooker", "(Ljava/lang/String;)Z",                 reinterpret_cast<void *>(InstallHooker)},
        {"nativeIsHookerInstalled", "(Ljava/lang/String;)Z",             reinterpret_cast<void *>(IsHookerInstalled)},
        {"nativeListHookers", "()[Ljava/lang/String;",                   reinterpret_cast<void *>(ListHookers)},
        {"nativeSetVerbose",  "(Z)V",                                    reinterpret_cast<void *>(SetVerbose)},
        {"nativeSetOpenatFilters", "([Ljava/lang/String;)V",             reinterpret_cast<void *>(SetOpenatFilters)},
        {"nativeConstantOnJniRegister", "(Ljava/lang/String;Ljava/lang/String;J)Z", reinterpret_cast<void *>(ConstantOnJniRegister)},
        {"nativeJniRegistrationAddress", "(Ljava/lang/String;Ljava/lang/String;)J", reinterpret_cast<void *>(JniRegistrationAddress)},
        {"nativeJniRegistrations", "()[Ljava/lang/String;",              reinterpret_cast<void *>(JniRegistrations)},
        {"nativeActivationProbe", "(Ljava/lang/String;III[Ljava/lang/String;)I", reinterpret_cast<void *>(ActivationProbe)},
        {"nativeActivationVerify", "(Ljava/lang/String;III)Z",           reinterpret_cast<void *>(ActivationVerify)},
};

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    // The only place the VM is handed to us; jni_register_watch needs it to get
    // an env of its own later, on whichever thread installs it.
    chuanyi::SetJavaVm(vm);
    jclass clazz = env->FindClass(kClassName);
    if (clazz == nullptr) {
        chuanyi::LogError("JNI_OnLoad: %s not found", kClassName);
        return JNI_ERR;
    }
    const int rc = env->RegisterNatives(clazz, kMethods,
                                        static_cast<jint>(sizeof(kMethods) / sizeof(kMethods[0])));
    env->DeleteLocalRef(clazz);
    if (rc != JNI_OK) {
        chuanyi::LogError("JNI_OnLoad: RegisterNatives failed (%d)", rc);
        return JNI_ERR;
    }
    chuanyi::LogInfo("native layer ready (%zu hooker(s) registered)", chuanyi::NativeHookerCount());
    return JNI_VERSION_1_6;
}
