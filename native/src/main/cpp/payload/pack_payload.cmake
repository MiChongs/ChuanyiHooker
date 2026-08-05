# 壳的打包器：把链接好的 payload 变成一段加密字节，生成 payload_blob.h。
#
# 用 CMake 脚本模式写而不是 Python / 主机侧小工具，理由只有一个：**不引入新的构建
# 依赖**。这个仓库在 Windows 上编，NDK + CMake 已经是硬前提，多要一个解释器就多一
# 种「换台机器编不过」。
#
# 由 CMakeLists 的 add_custom_command 调用：
#
#   cmake -DELF=<可执行文件> -DBIN=<中间产物> -DOUT=<生成的头>
#         -DREADELF=<llvm-readelf> -DOBJCOPY=<llvm-objcopy>
#         -P pack_payload.cmake
#
# 做三件事：
#
#   1. **验重定位。** 壳内代码运行时没有 linker，任何一条重定位都意味着运行时会去
#      读链接期的地址。这类错误在真机上表现为「探测永远失败」，查起来会一路查到
#      SQLite 解析上去，而根因和它无关 —— 所以在这里变成构建期的硬失败。
#   2. **抽字节。** objcopy -O binary，得到可以整块拷进匿名内存的裸代码。
#   3. **加密 + 校验和。** 密钥按密文的 FNV 哈希做混淆存储：blob 被改一个字节，
#      密钥推导就跟着变，解出来的是垃圾，明文哈希对不上，宿主直接判失败。

if(NOT DEFINED ELF OR NOT DEFINED BIN OR NOT DEFINED OUT)
    message(FATAL_ERROR "pack_payload.cmake：ELF / BIN / OUT 三个参数都必须给")
endif()

# ---------------------------------------------------------------------------
# 1. 重定位检查 + 入口位置检查
# ---------------------------------------------------------------------------
if(NOT READELF OR NOT EXISTS "${READELF}")
    message(FATAL_ERROR
            "pack_payload：找不到 readelf（${READELF}）。它不是可选的 —— 壳内代码有没有\n"
            "重定位、入口在不在偏移 0，只能靠它查，跳过检查等于把一次静默跑飞放进产物。")
endif()

execute_process(
        COMMAND "${READELF}" --relocations "${ELF}"
        OUTPUT_VARIABLE relocations
        ERROR_VARIABLE readelfError
        RESULT_VARIABLE readelfStatus
)
if(NOT readelfStatus EQUAL 0)
    message(FATAL_ERROR "pack_payload：readelf --relocations 失败（${readelfStatus}）\n${readelfError}")
endif()
if(relocations MATCHES "R_[A-Z0-9_]+")
    message(FATAL_ERROR
            "壳内代码里出现了重定位，而这段代码运行时没有 linker，重定位不会被处理。\n"
            "常见来源：取了字符串字面量 / 静态数组 / 函数指针表的地址，或者引用了外部符号。\n"
            "改法：把常量写成整数字面量、把字符串逐字节比较、把需要的指针从 Request 传进来。\n"
            "readelf 输出：\n${relocations}")
endif()

# 入口必须正好落在 .text 的起始 —— 宿主把解密后的首地址直接当函数指针跳进去。
#
# 这条断言不是多余的谨慎：默认的动态链接会生成 .interp / .dynsym / .dynamic 并把它们
# 排在 .text 前面，抽出来的字节就以那堆元数据开头，入口跑到 0x60 去了。这种错没有任何
# 报错，只会在真机上跳进一段不是代码的东西。
execute_process(
        COMMAND "${READELF}" --section-headers "${ELF}"
        OUTPUT_VARIABLE sections
        RESULT_VARIABLE sectionsStatus
)
execute_process(
        COMMAND "${READELF}" --symbols "${ELF}"
        OUTPUT_VARIABLE symbols
        RESULT_VARIABLE symbolsStatus
)
if(NOT sectionsStatus EQUAL 0 OR NOT symbolsStatus EQUAL 0)
    message(FATAL_ERROR "pack_payload：readelf 读不出段表 / 符号表")
endif()
if(NOT sections MATCHES "\\][ \t]+\\.text[ \t]+PROGBITS[ \t]+([0-9a-fA-F]+)")
    message(FATAL_ERROR "pack_payload：段表里找不到 .text\n${sections}")
endif()
set(textAddress "${CMAKE_MATCH_1}")
if(NOT symbols MATCHES "[ \t]([0-9a-fA-F]+)[ \t]+[0-9]+[ \t]+FUNC[^\n]*[ \t]PayloadMain")
    message(FATAL_ERROR "pack_payload：符号表里找不到 PayloadMain\n${symbols}")
endif()
set(entryAddress "${CMAKE_MATCH_1}")
math(EXPR entryOffset "0x${entryAddress} - 0x${textAddress}")

# 允许的偏移只有 0 和 1。
#
# 1 是 armeabi-v7a：NDK 默认编 Thumb，符号地址的最低位是**ISA 标记**而不是地址的一
# 部分 —— 调用时必须带着它，否则 CPU 按 ARM 指令去解 Thumb 字节。所以这里不把它抹掉，
# 而是原样交给宿主去加，让「用哪个指令集」这件事由编译器说了算。
#
# 除此以外的任何值都说明 .text 前面混进了别的东西（典型是动态链接生成的
# .interp / .dynsym），那时候 blob 的开头就不是代码了。
math(EXPR entryStrayBits "${entryOffset} & -2")
if(NOT entryStrayBits EQUAL 0)
    message(FATAL_ERROR
            "PayloadMain 不在 .text 的起始（偏移 ${entryOffset}）。\n"
            "宿主是按「blob 开头就是入口」调的，对不上就会跳进别的东西。\n"
            "检查 payload.ld 里 .text.entry 是不是仍排在最前、PayloadMain 上的\n"
            "__attribute__((section(\".text.entry\"))) 是不是还在，以及链接是不是\n"
            "还带着 -static（少了它会生成 .interp 并排到 .text 前面）。")
endif()

# ---------------------------------------------------------------------------
# 2. 抽成裸字节
#
# --only-section=.text：只要代码那一段。不加的话 objcopy 会按可分配段整体导出，
# 把链接器顺手生成的元数据一起卷进来 —— 零重定位保证了 .text 不会引用段外任何东西，
# 所以只取它是安全的，而且省掉「原点在哪」这个不确定性。
# ---------------------------------------------------------------------------
get_filename_component(binDir "${BIN}" DIRECTORY)
file(MAKE_DIRECTORY "${binDir}")
execute_process(
        COMMAND "${OBJCOPY}" -O binary --only-section=.text "${ELF}" "${BIN}"
        RESULT_VARIABLE objcopyStatus
        ERROR_VARIABLE objcopyError
)
if(NOT objcopyStatus EQUAL 0)
    message(FATAL_ERROR "pack_payload：objcopy 失败（${objcopyStatus}）\n${objcopyError}")
endif()

file(READ "${BIN}" hexData HEX)
string(LENGTH "${hexData}" hexLength)
math(EXPR blobLength "${hexLength} / 2")
if(blobLength LESS 256)
    message(FATAL_ERROR "pack_payload：抽出来的 blob 只有 ${blobLength} 字节，链接脚本大概率没生效")
endif()

# ---------------------------------------------------------------------------
# 3. 加密
#
# 密钥流：key[i % 16] ^ (u8)(i * 167) ^ (u8)(i / 251)
# 位置相关，所以同样的字节在不同偏移上密文不同 —— 熵不高，但这不是加密算法要解决的
# 问题：密钥本来就和密文一起躺在同一个 .so 里，任何壳都一样。它要挡的是「拿字符串
# 搜一下就找到群号」和「照着明文直接 patch 一个字节」。
#
# 下面这段 keystream 必须和 activation.cpp 里的 Keystream() 逐位一致。
# ---------------------------------------------------------------------------
set(packKey 0x8F 0x2D 0x41 0xB6 0x1C 0xE7 0x53 0xA0 0x9B 0x64 0xD8 0x37 0xF2 0x0E 0xC5 0x7A)

set(plainHash 2166136261)
set(cipherHash 2166136261)
set(body "")
set(column 0)

math(EXPR lastIndex "${blobLength} - 1")
foreach(index RANGE 0 ${lastIndex})
    math(EXPR at "${index} * 2")
    string(SUBSTRING "${hexData}" ${at} 2 byteHex)
    math(EXPR plain "0x${byteHex}")

    math(EXPR keyIndex "${index} & 15")
    list(GET packKey ${keyIndex} keyByte)
    math(EXPR stream "((${keyByte} ^ ((${index} * 167) & 255)) ^ ((${index} / 251) & 255)) & 255")
    math(EXPR cipher "${plain} ^ ${stream}")

    math(EXPR plainHash "((${plainHash} ^ ${plain}) * 16777619) & 4294967295")
    math(EXPR cipherHash "((${cipherHash} ^ ${cipher}) * 16777619) & 4294967295")

    if(column EQUAL 0)
        string(APPEND body "\n        ")
    endif()
    string(APPEND body "${cipher},")
    math(EXPR column "(${column} + 1) % 20")
endforeach()

# 密钥不明文存：和密文的 FNV 哈希异或。改一个字节的密文，推出来的密钥就是错的，
# 解密结果通不过明文哈希 —— 篡改检测因此不用额外写一遍。
set(storedKey "")
foreach(index RANGE 0 15)
    list(GET packKey ${index} keyByte)
    math(EXPR shift "(${index} % 4) * 8")
    math(EXPR mixed "(${keyByte} ^ ((${cipherHash} >> ${shift}) & 255)) & 255")
    string(APPEND storedKey "${mixed}, ")
endforeach()

# ---------------------------------------------------------------------------
# 4. 生成头文件
# ---------------------------------------------------------------------------
set(generated "// 由 payload/pack_payload.cmake 在构建期生成，不要手改，也不要提交进版本库。
//
// 内容是 payload/payload.cpp 编出来的裸代码，加密后嵌在这里。运行时由
// activation.cpp 解密进匿名内存执行 —— 磁盘上的 libchuanyihook.so 里既找不到
// 群号，也找不到判定逻辑的明文。

#pragma once

#include <cstdint>

namespace chuanyi::guard {

inline constexpr uint32_t kBlobLength = ${blobLength}u;
/// 入口相对 blob 起始的偏移。armeabi-v7a 上是 1 —— 那一位是 Thumb 标记，调用时
/// 必须带着，见 pack_payload.cmake 里的说明。其余 ABI 上是 0。
inline constexpr uint32_t kBlobEntryOffset = ${entryOffset}u;
/// 密文的 FNV-1a，兼作密钥的解混淆材料。
inline constexpr uint32_t kBlobCipherHash = ${cipherHash}u;
/// 明文的 FNV-1a，解密后校验。
inline constexpr uint32_t kBlobPlainHash = ${plainHash}u;

inline constexpr uint8_t kBlobStoredKey[16] = { ${storedKey}};

inline constexpr uint8_t kBlob[${blobLength}] = {${body}
};

} // namespace chuanyi::guard
")

get_filename_component(outDir "${OUT}" DIRECTORY)
file(MAKE_DIRECTORY "${outDir}")
file(WRITE "${OUT}" "${generated}")

message(STATUS "壳内代码已打包：${blobLength} 字节 -> ${OUT}")
