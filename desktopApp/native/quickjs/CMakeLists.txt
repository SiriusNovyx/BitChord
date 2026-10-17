cmake_minimum_required(VERSION 3.22)
project(bitchord_quickjs LANGUAGES C)
if(NOT CMAKE_C_COMPILER_TARGET MATCHES "aarch64" AND NOT CMAKE_C_COMPILER MATCHES "aarch64-w64-mingw32")
    message(FATAL_ERROR "Use the LLVM-MinGW aarch64-w64-mingw32 compiler, not x64 MinGW")
endif()
if(NOT EXISTS "${QUICKJS_KT_SOURCE}/quickjs/native/jni/quickjs_jni.c")
    message(FATAL_ERROR "QUICKJS_KT_SOURCE must contain quickjs-kt v1.0.14 and its pinned submodules")
endif()
file(TO_CMAKE_PATH "${QUICKJS_KT_SOURCE}/quickjs/native" native)
file(READ "${native}/quickjs/VERSION" quickjs_version)
string(STRIP "${quickjs_version}" quickjs_version)
file(GLOB_RECURSE jni_sources CONFIGURE_DEPENDS "${native}/jni/*.c")
add_library(quickjs SHARED ${jni_sources}
    "${native}/quickjs/quickjs.c" "${native}/quickjs/cutils.c"
    "${native}/quickjs/dtoa.c" "${native}/quickjs/libregexp.c" "${native}/quickjs/libunicode.c"
    "${native}/common/quickjs_version.c" "${native}/common/quickjs_interrupt.c"
    "${native}/quickjs.def")
set_target_properties(quickjs PROPERTIES C_STANDARD 99 PREFIX "lib" RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}")
target_include_directories(quickjs PRIVATE "${native}/quickjs" "${native}/common" "${native}/c-vector"
    "${native}/jni" "${native}/jni/mapping" "$ENV{JAVA_HOME}/include" "$ENV{JAVA_HOME}/include/win32")
target_compile_definitions(quickjs PRIVATE CONFIG_WIN32 CONFIG_BIGNUM CONFIG_VERSION="${quickjs_version}" WINPTHREAD_STATIC)
target_compile_options(quickjs PRIVATE -O2)
target_link_options(quickjs PRIVATE -static-libgcc)
target_link_libraries(quickjs PRIVATE -Wl,-Bstatic winpthread -Wl,-Bdynamic)
