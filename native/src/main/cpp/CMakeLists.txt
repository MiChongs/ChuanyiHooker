cmake_minimum_required(VERSION 3.22)
project(chuanyihook CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# Dobby (LSPosed fork), vendored under third_party/.
# Built static so everything ends up inside libchuanyihook.so and the module
# ships exactly one native library.
# ---------------------------------------------------------------------------
set(DOBBY_GENERATE_SHARED OFF CACHE BOOL "" FORCE)
set(DOBBY_DEBUG OFF CACHE BOOL "" FORCE)
set(NearBranch ON CACHE BOOL "" FORCE)
set(Plugin.SymbolResolver ON CACHE BOOL "" FORCE)
set(Plugin.Android.BionicLinkerUtil ON CACHE BOOL "" FORCE)
set(BUILD_EXAMPLE OFF CACHE BOOL "" FORCE)
set(BUILD_TEST OFF CACHE BOOL "" FORCE)

add_subdirectory(third_party/Dobby dobby EXCLUDE_FROM_ALL)

# Dobby is C++11 and warns a lot with a current NDK; it is a dependency, not
# our code, so stop its noise from drowning real warnings.
target_compile_options(dobby PRIVATE -w)

# ---------------------------------------------------------------------------
# 壳内代码（激活校验）
#
# 单独编成一个**没有 ELF 头、没有符号、没有重定位**的可执行文件，再 objcopy 抽成裸
# 字节、加密后嵌进 libchuanyihook.so。运行时解到匿名内存里执行，见 activation.cpp。
#
# 编译选项不是抄来的，每一条都对应一种「会往里塞外部引用或重定位」的编译器行为：
#
#   -ffreestanding -fno-builtin   不把循环识别成 memcpy/memset 调用
#   -fno-stack-protector          不引用 __stack_chk_guard 这个全局
#   -fno-jump-tables              switch 不生成 .rodata 跳转表（表项是地址）
#   -fno-*-unwind-tables          不生成 .eh_frame / .ARM.exidx
#   -no-pie + 链接脚本            单段输出，没有 .dynamic / .got
#   --no-undefined                任何外部符号都在链接期报错，而不是运行时跑飞
#
# 剩下那条编译器管不了的（不能取静态数据的地址）由 pack_payload.cmake 用 readelf
# 查重定位兜底，违反就中断构建。
# ---------------------------------------------------------------------------
set(PAYLOAD_DIR ${CMAKE_CURRENT_SOURCE_DIR}/payload)
set(PAYLOAD_GEN ${CMAKE_CURRENT_BINARY_DIR}/generated)
set(PAYLOAD_HEADER ${PAYLOAD_GEN}/payload_blob.h)

add_executable(chkpayload ${PAYLOAD_DIR}/payload.cpp)

# 产物不放进 AGP 收集 .so 的那个目录。它没有 .so 后缀，本来也不会被打包，
# 放开一点只是别让人误会它会进 APK。
set_target_properties(chkpayload PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/payload-out)

target_compile_options(chkpayload PRIVATE
        -Os
        -ffreestanding -fno-builtin
        -fno-exceptions -fno-rtti
        -fno-stack-protector
        -fno-jump-tables
        -fno-unwind-tables -fno-asynchronous-unwind-tables
        -fomit-frame-pointer
        -fvisibility=hidden
        -ffunction-sections -fdata-sections
        -Wall -Wextra
)

# x86 的 -fPIC 会为「取全局地址」铺一套 GOT 基址计算，连带 R_386_GOTPC。这段代码
# 里没有任何全局可取，用不上 GOT；显式关掉，免得编译器为一个不存在的需求先埋管子。
# ARM 两个 ABI 上 -fPIC 走的是 PC 相对，不产生重定位，保持默认即可。
if(ANDROID_ABI STREQUAL "x86" OR ANDROID_ABI STREQUAL "x86_64")
    target_compile_options(chkpayload PRIVATE -fno-pic)
endif()

# -static 不是为了省体积，是为了别产生 .interp / .dynsym / .dynamic。默认的动态可执行
# 文件会把它们排在 .text **前面**（低地址），抽字节时就成了 blob 的开头，入口不再在
# 偏移 0 —— 这个错不会有任何报错，只会跳进一段元数据然后跑飞。
# pack_payload.cmake 里还有一道断言（PayloadMain 必须正好落在 .text 起始）兜底。
target_link_options(chkpayload PRIVATE
        -nostdlib -nostartfiles -no-pie -static
        -T "${PAYLOAD_DIR}/payload.ld"
        -Wl,--build-id=none
        -Wl,--gc-sections
        -Wl,--no-undefined
        -Wl,-e,PayloadMain
)
set_property(TARGET chkpayload APPEND PROPERTY LINK_DEPENDS ${PAYLOAD_DIR}/payload.ld)

# readelf 和 objcopy 是同一套 LLVM 工具，NDK 把它们放在一起。
get_filename_component(CY_LLVM_BIN "${CMAKE_OBJCOPY}" DIRECTORY)
find_program(CY_READELF NAMES llvm-readelf readelf HINTS "${CY_LLVM_BIN}")

add_custom_command(
        OUTPUT ${PAYLOAD_HEADER}
        COMMAND ${CMAKE_COMMAND}
        -DELF=$<TARGET_FILE:chkpayload>
        -DBIN=${PAYLOAD_GEN}/payload.bin
        -DOUT=${PAYLOAD_HEADER}
        -DREADELF=${CY_READELF}
        -DOBJCOPY=${CMAKE_OBJCOPY}
        -P ${PAYLOAD_DIR}/pack_payload.cmake
        DEPENDS chkpayload ${PAYLOAD_DIR}/pack_payload.cmake
        COMMENT "打包壳内代码 -> payload_blob.h"
        VERBATIM
)

# ---------------------------------------------------------------------------
# Our library
# ---------------------------------------------------------------------------
add_library(chuanyihook SHARED
        jni_bridge.cpp
        native_hook.cpp
        const_stub.cpp
        mem_search.cpp
        activation.cpp
        ${PAYLOAD_HEADER}
        hookers/openat_logger.cpp
        hookers/jni_register_watch.cpp
        hookers/suicide_guard.cpp
        hookers/linker_watch.cpp
        hookers/env_spoof.cpp
        hookers/jni_field_watch.cpp
        hookers/linkmap_hide.cpp
)

target_include_directories(chuanyihook
        PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/third_party/Dobby/include
        # 生成的 payload_blob.h
        ${PAYLOAD_GEN}
)

target_compile_options(chuanyihook PRIVATE
        -Wall -Wextra -Wno-unused-parameter
        # 注意判据是「不是 Debug」而不是「是 Release」：AGP 的 release 变体把
        # CMAKE_BUILD_TYPE 配成 RelWithDebInfo，不是 Release。写成
        # $<$<CONFIG:Release>:...> 的话这个条件永远不成立，标志静默失效。
        $<$<NOT:$<CONFIG:Debug>>:-Oz>
)

# mem_search 是唯一的热路径（要扫遍进程所有可读映射），不跟着 -Oz 走。
# 其余文件都是几十行的胶水，体积优先。
set_source_files_properties(mem_search.cpp PROPERTIES COMPILE_OPTIONS "-O2")

target_link_options(chuanyihook PRIVATE
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
)

find_library(log-lib log)
target_link_libraries(chuanyihook PRIVATE dobby ${log-lib})

# ---------------------------------------------------------------------------
# 调试信息剥离
#
# RelWithDebInfo 带 -g，DWARF 占了产物的 95%（arm64 实测 2,456,496 -> 108,744 字节）。
# AGP 的 stripReleaseDebugSymbols 在本工程里跑了但输出与输入逐字节相同，等于没剥，
# 于是 9 MB 的调试信息一路进了 APK。不指望它，链接完自己剥。
#
# 剥之前先把调试信息单独存成 libchuanyihook.so.debug：崩溃日志还能用
# `llvm-symbolizer --obj=...so.debug` 还原。该文件不以 .so 结尾，AGP 不会打包它。
#
# 不加 --add-gnu-debuglink：AGP 链接时已经下了 -Wl,--build-id=sha1，剥离后的 .so
# 和 .so.debug 共享同一个 build-id，符号化工具照着它就能配对，debuglink 是重复的
# 机制（而且它的 = 形式在带空格的路径上会被 CMake 二次加引号，objcopy 直接报
# Invalid argument）。
# ---------------------------------------------------------------------------
if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_custom_command(TARGET chuanyihook POST_BUILD
            COMMAND "${CMAKE_OBJCOPY}" --only-keep-debug
            "$<TARGET_FILE:chuanyihook>" "$<TARGET_FILE:chuanyihook>.debug"
            COMMAND "${CMAKE_STRIP}" --strip-all "$<TARGET_FILE:chuanyihook>"
            COMMENT "Stripping chuanyihook (debug info -> .so.debug)"
            VERBATIM
    )
endif()
