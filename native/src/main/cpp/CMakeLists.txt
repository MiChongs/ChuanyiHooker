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
# Our library
# ---------------------------------------------------------------------------
add_library(chuanyihook SHARED
        jni_bridge.cpp
        native_hook.cpp
        const_stub.cpp
        mem_search.cpp
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
