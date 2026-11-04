# nuvio_whisper.dll: whisper.cpp + ggml (CPU only) + the JNI bridge for voice control, linked into
# one self-contained DLL. Static on purpose: MSYS2's own whisper.cpp package ships libwhisper-1.dll
# and a ggml*.dll per CPU variant, names prepareWindowsPlayerRuntime keeps out of the app image (see
# windowsUnusedLibmpvRuntimeDlls), and a DLL of ours with any of those names would collide with them.
#
# Built by :composeApp:buildWindowsWhisper from a whisper.cpp checkout (NUVIO_WHISPER_ROOT).
cmake_minimum_required(VERSION 3.21)
project(nuvio_whisper LANGUAGES C CXX)

set(NUVIO_WHISPER_ROOT "" CACHE PATH "whisper.cpp checkout")
set(NUVIO_JNI_INCLUDE_DIR "" CACHE PATH "The JDK's include directory (holds jni.h)")
if(NOT EXISTS "${NUVIO_WHISPER_ROOT}/include/whisper.h")
    message(FATAL_ERROR "NUVIO_WHISPER_ROOT (${NUVIO_WHISPER_ROOT}) is not a whisper.cpp checkout")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
# One CPU build for every PC it ships to, not tuned to this one: AVX2/FMA/F16C is every x64 CPU
# since Haswell (2013) / Excavator (2015). OpenMP off: ggml's own thread pool needs no libgomp.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_AVX ON CACHE BOOL "" FORCE)
set(GGML_AVX2 ON CACHE BOOL "" FORCE)
set(GGML_FMA ON CACHE BOOL "" FORCE)
set(GGML_F16C ON CACHE BOOL "" FORCE)
set(GGML_BMI2 ON CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)

add_subdirectory("${NUVIO_WHISPER_ROOT}" whisper EXCLUDE_FROM_ALL)

add_library(nuvio_whisper SHARED whisper_jni.cpp)
set_target_properties(nuvio_whisper PROPERTIES PREFIX "" OUTPUT_NAME "nuvio_whisper")
target_compile_features(nuvio_whisper PRIVATE cxx_std_17)
target_include_directories(nuvio_whisper PRIVATE "${NUVIO_JNI_INCLUDE_DIR}" "${NUVIO_JNI_INCLUDE_DIR}/win32")
target_link_libraries(nuvio_whisper PRIVATE whisper)
