# app/jni/CMakeLists.txt — JNI 薄层（主机验证 + Android NDK 共用）
#
# 主机验证：
#   cmake -S app/jni -B /tmp/b-jni && cmake --build /tmp/b-jni -j4
#
# Android：由 App 的 Gradle externalNativeBuild 调；需要先给本 ABI 编好 imgtool。
#   -DIMGTOOL_LIB=<abi>/libimgtool.a -DIMGTOOL_EXTRA_LIBS="ntfs-3g;wim;z;hivex"
#
# ★ 这里【不修改】imgtool 的构建；只是链接它。Android 专属适配（NDK、arm32）
#   在一个单独的 worktree 里做（不要污染基线），见 app/README.md。
cmake_minimum_required(VERSION 3.16)
project(imgapp_jni C)

set(IMGTOOL_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../imgtool" CACHE PATH "imgtool 源码/构建根")
set(IMGTOOL_LIB  "${IMGTOOL_ROOT}/build/libimgtool.so" CACHE FILEPATH "imgtool 库（.so 或 .a）")
set(IMGTOOL_EXTRA_LIBS "" CACHE STRING "静态链接时的额外库（分号分隔）")

add_library(imgapp SHARED
    imgapp_bridge.c
    imgapp_jni.c
    img_vfd_real.c   # 给 imgtool 的 --wrap 补 __real_*（否则 libimgtool.so 载入失败）
)
target_include_directories(imgapp PRIVATE
    ${IMGTOOL_ROOT}/include
    ${CMAKE_CURRENT_SOURCE_DIR}
)

if(ANDROID)
    # NDK sysroot 自带 jni.h，不需要主机 JDK
else()
    # 不用 find_package(JNI)：headless JDK 没有 AWT，FindJNI 会误判失败；
    # 我们只需要 jni.h / jni_md.h（JNI 函数全走 env 指针，不需要链接 libjvm）。
    find_path(JNI_INCLUDE_DIR jni.h
        HINTS "$ENV{JAVA_HOME}/include" /usr/lib/jvm/*/include)
    find_path(JNI_MD_INCLUDE_DIR jni_md.h
        HINTS "$ENV{JAVA_HOME}/include/linux" /usr/lib/jvm/*/include/linux)
    if(NOT JNI_INCLUDE_DIR OR NOT JNI_MD_INCLUDE_DIR)
        message(FATAL_ERROR "找不到 jni.h / jni_md.h（装 openjdk-17-jdk-headless，或设 JAVA_HOME）")
    endif()
    target_include_directories(imgapp PRIVATE ${JNI_INCLUDE_DIR} ${JNI_MD_INCLUDE_DIR})
endif()

# pthread：glibc 要 -lpthread，Bionic 根本没有 libpthread（pthread 在 libc 里）。
# 用 Threads::Threads 让 CMake 按平台决定，两端都成立。
find_package(Threads REQUIRED)
target_link_libraries(imgapp PRIVATE ${IMGTOOL_LIB} ${IMGTOOL_EXTRA_LIBS} Threads::Threads)

# 主机冒烟测试（Android 上不编）
option(IMGAPP_BUILD_TEST "Build the host smoke test" ON)
if(IMGAPP_BUILD_TEST AND NOT ANDROID)
    add_executable(test_bridge test_bridge.c imgapp_bridge.c)
    target_include_directories(test_bridge PRIVATE ${IMGTOOL_ROOT}/include ${CMAKE_CURRENT_SOURCE_DIR})
    target_link_libraries(test_bridge PRIVATE ${IMGTOOL_LIB} Threads::Threads)
    get_filename_component(_imgtool_dir "${IMGTOOL_LIB}" DIRECTORY)
    set_target_properties(test_bridge PROPERTIES BUILD_RPATH "${_imgtool_dir}")
endif()