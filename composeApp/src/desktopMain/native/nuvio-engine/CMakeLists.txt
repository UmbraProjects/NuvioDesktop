# Desktop build of Nuvio Engine (NuvioMedia/nuvio-engine) as one DLL that carries both the C API
# and the JNI bridge, mirroring platform/android in the engine repo. Kotlin loads it through
# com.nuvio.engine.internal.NativeBridge (System.load), the same class name the JNI symbols bind to.
#
# Configure/build via :composeApp:buildWindowsNuvioEngine (see composeApp/build.gradle.kts), which
# supplies the toolchain. The engine source is the sibling checkout ../nuvio-engine by default, the
# same layout upstream NuvioDesktop assumes for its Android/iOS builds.
cmake_minimum_required(VERSION 3.24)

project(nuvio_engine_desktop LANGUAGES C CXX)

if(NOT DEFINED NUVIO_ENGINE_ROOT)
    get_filename_component(
        NUVIO_ENGINE_ROOT
        "${CMAKE_CURRENT_LIST_DIR}/../../../../../../nuvio-engine"
        ABSOLUTE
    )
endif()
if(NOT EXISTS "${NUVIO_ENGINE_ROOT}/CMakeLists.txt")
    message(FATAL_ERROR "Nuvio Engine checkout not found at ${NUVIO_ENGINE_ROOT}; clone NuvioMedia/nuvio-engine next to NuvioDesktop or pass -DNUVIO_ENGINE_ROOT")
endif()

# The desktop peer-discovery patch (patches/0001-desktop-peer-discovery.patch) must be on the
# checkout; building without it silently ships the sparse-swarm behaviour it fixes. README.md here
# has the two-line setup.
file(READ "${NUVIO_ENGINE_ROOT}/src/torrent/libtorrent_backend.cpp" NUVIO_ENGINE_BACKEND_SOURCE)
string(FIND "${NUVIO_ENGINE_BACKEND_SOURCE}" "peer_search_interval" NUVIO_ENGINE_PATCH_SENTINEL)
if(NUVIO_ENGINE_PATCH_SENTINEL EQUAL -1)
    message(FATAL_ERROR "Nuvio Engine checkout at ${NUVIO_ENGINE_ROOT} lacks patches/0001-desktop-peer-discovery.patch; apply it (see README.md next to this file)")
endif()
string(FIND "${NUVIO_ENGINE_BACKEND_SOURCE}" "piece_request_timeout_seconds = 15" NUVIO_ENGINE_PATCH3_SENTINEL)
if(NUVIO_ENGINE_PATCH3_SENTINEL EQUAL -1)
    message(FATAL_ERROR "Nuvio Engine checkout at ${NUVIO_ENGINE_ROOT} lacks patches/0003-desktop-request-timeout.patch; apply it (see README.md next to this file)")
endif()
unset(NUVIO_ENGINE_BACKEND_SOURCE)
file(READ "${NUVIO_ENGINE_ROOT}/src/torrent/libtorrent_stream_bridge.cpp" NUVIO_ENGINE_BRIDGE_SOURCE)
string(FIND "${NUVIO_ENGINE_BRIDGE_SOURCE}" "readahead_for_budgets" NUVIO_ENGINE_PATCH2_SENTINEL)
if(NUVIO_ENGINE_PATCH2_SENTINEL EQUAL -1)
    message(FATAL_ERROR "Nuvio Engine checkout at ${NUVIO_ENGINE_ROOT} lacks patches/0002-desktop-download-window.patch; apply it (see README.md next to this file)")
endif()
unset(NUVIO_ENGINE_BRIDGE_SOURCE)
if(NOT EXISTS "${NUVIO_ENGINE_ROOT}/src/storage/windows_path.hpp")
    message(FATAL_ERROR "Nuvio Engine checkout at ${NUVIO_ENGINE_ROOT} lacks patches/0004-desktop-windows-long-paths.patch; apply it (see README.md next to this file)")
endif()
if(NOT EXISTS "${NUVIO_ENGINE_ROOT}/src/torrent/ram_disk_io.cpp")
    message(FATAL_ERROR "Nuvio Engine checkout at ${NUVIO_ENGINE_ROOT} lacks patches/0005-desktop-ram-only-mode.patch; apply it (see README.md next to this file)")
endif()

set(NUVIO_ENGINE_BUILD_SHARED ON CACHE BOOL "" FORCE)
set(NUVIO_ENGINE_ENABLE_LIBTORRENT ON CACHE BOOL "" FORCE)
set(NUVIO_ENGINE_BUILD_TESTS OFF CACHE BOOL "" FORCE)

# JNI headers come from the JDK that runs Gradle (JAVA_HOME / -DNUVIO_JNI_INCLUDE_DIR).
if(NOT DEFINED NUVIO_JNI_INCLUDE_DIR)
    set(NUVIO_JNI_INCLUDE_DIR "$ENV{JAVA_HOME}/include")
endif()
if(NOT EXISTS "${NUVIO_JNI_INCLUDE_DIR}/jni.h")
    message(FATAL_ERROR "jni.h not found under ${NUVIO_JNI_INCLUDE_DIR}; set NUVIO_JNI_INCLUDE_DIR")
endif()

add_subdirectory("${NUVIO_ENGINE_ROOT}" "${CMAKE_CURRENT_BINARY_DIR}/nuvio-engine-core")

# The JNI bridge is platform-neutral JNI over the C API. jni_bridge.cpp here is the engine repo's
# platform/android copy with one desktop-specific line (see its header comment).
target_sources(
    nuvio_engine
    PRIVATE
        "${CMAKE_CURRENT_LIST_DIR}/jni_bridge.cpp"
)
target_include_directories(
    nuvio_engine
    PRIVATE
        "${NUVIO_JNI_INCLUDE_DIR}"
        "${NUVIO_JNI_INCLUDE_DIR}/win32"
)
