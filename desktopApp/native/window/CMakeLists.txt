cmake_minimum_required(VERSION 3.16)

project(bitchord_window LANGUAGES CXX)

if(BITCHORD_TARGET_ARCH STREQUAL "arm64")
    if(NOT MSVC OR NOT CMAKE_GENERATOR_PLATFORM STREQUAL "ARM64")
        message(FATAL_ERROR "Windows ARM64 requires MSVC with -A ARM64")
    endif()
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

foreach(suffix "" _DEBUG _RELEASE _RELWITHDEBINFO _MINSIZEREL)
    set(CMAKE_LIBRARY_OUTPUT_DIRECTORY${suffix} ${CMAKE_BINARY_DIR})
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY${suffix} ${CMAKE_BINARY_DIR})
endforeach()

add_library(bitchord_window SHARED window_jni.cpp)
set_target_properties(bitchord_window PROPERTIES PREFIX "")

if(DEFINED ENV{JAVA_HOME})
    target_include_directories(bitchord_window PRIVATE
        $ENV{JAVA_HOME}/include
        $ENV{JAVA_HOME}/include/win32
    )
else()
    find_package(JNI REQUIRED)
    target_include_directories(bitchord_window PRIVATE ${JNI_INCLUDE_DIRS})
endif()

target_link_libraries(bitchord_window PRIVATE user32 dwmapi ole32 uuid comctl32)

if(MINGW)
    # The desktop app loads this DLL from its private runtime directory. Gradle daemons and
    # installed builds do not inherit the developer toolchain's bin directory, so leaving MinGW's
    # libwinpthread dynamic makes the bridge silently unavailable even though the DLL itself was
    # packaged correctly.
    target_link_options(bitchord_window PRIVATE -static -static-libgcc -static-libstdc++)
endif()

option(BITCHORD_BUILD_WINDOW_TESTS "Build native frame regression tests" OFF)
if(BITCHORD_BUILD_WINDOW_TESTS)
    enable_testing()
    add_executable(bitchord_window_tests window_frame_test.cpp)
    add_executable(bitchord_audio_tests audio_output_test.cpp)
    foreach(test_target bitchord_window_tests bitchord_audio_tests)
    if(DEFINED ENV{JAVA_HOME})
        target_include_directories(${test_target} PRIVATE
            $ENV{JAVA_HOME}/include $ENV{JAVA_HOME}/include/win32)
    else()
        target_include_directories(${test_target} PRIVATE ${JNI_INCLUDE_DIRS})
    endif()
    target_link_libraries(${test_target} PRIVATE user32 dwmapi ole32 uuid comctl32)
    if(MINGW)
        target_link_options(${test_target} PRIVATE -static -static-libgcc -static-libstdc++)
    endif()
    endforeach()
    add_test(NAME window_frame COMMAND bitchord_window_tests)
    add_test(NAME audio_recovery COMMAND bitchord_audio_tests)
    set_tests_properties(audio_recovery PROPERTIES TIMEOUT 10)
endif()
