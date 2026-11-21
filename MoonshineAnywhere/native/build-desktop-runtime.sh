#!/usr/bin/env bash
# Builds libskainet_moonshine_stream.so for the desktop JVM (linux-x86_64): the SAME C source the Android cartridge
# runtime is built from (SKaiNET-transformers llm-runtime/iree-android/native/moonshine_stream_jni.c), linked against
# the IREE runtime with the local-task (CPU) and Vulkan (GPU) drivers. Nothing in that file is Android-specific
# except logging, which is already behind #ifdef __ANDROID__.
#
#   native/build-desktop-runtime.sh [path/to/moonshine_stream_jni.c]
#
# Uses the pinned skainet/iree-android:3.11.0 image only for its IREE source tree and clang/cmake/ninja; the NDK in
# it is not touched. The build tree is cached under ~/.cache/skainet-iree/3.11.0/build-linux-x86_64 (IREE_CACHE
# overrides), so a second run takes seconds. Output: native/out/linux-x86_64/libskainet_moonshine_stream.so, which
# :desktopApp packs into its resources.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC_C="${1:-${MOONSHINE_JNI_SRC:-$HERE/../../../SKaiNET-transformers/llm-runtime/iree-android/native/moonshine_stream_jni.c}}"
[ -f "$SRC_C" ] || { echo "moonshine_stream_jni.c not found at $SRC_C (pass its path, or set MOONSHINE_JNI_SRC)"; exit 1; }
JDK="${JAVA_HOME:-$(java -XshowSettings:properties -version 2>&1 | awk '/java.home/{print $3}')}"
[ -f "$JDK/include/jni.h" ] || { echo "no jni.h under $JDK/include (set JAVA_HOME to a JDK)"; exit 1; }
CACHE="${IREE_CACHE:-$HOME/.cache/skainet-iree/3.11.0}"
OUT="$HERE/out/linux-x86_64"
mkdir -p "$OUT" "$CACHE/build-linux-x86_64"

docker run --rm --entrypoint bash \
  -u "$(id -u):$(id -g)" -e HOME=/tmp \
  -v "$(cd "$(dirname "$SRC_C")" && pwd)":/src:ro -v "$OUT":/out \
  -v "$JDK/include":/jdk-include:ro \
  -v "$CACHE/build-linux-x86_64":/build \
  skainet/iree-android:3.11.0 -euo pipefail -c '
    # /iree belongs to root in the image; configure from a writable copy of the two files this build edits.
    NAME=skainet_moonshine_stream
    mkdir -p /build/overlay
    cat > /build/overlay/CMakeLists.txt <<CM
cmake_minimum_required(VERSION 3.21)
project(moonshine_desktop C CXX)
set(IREE_BUILD_COMPILER OFF CACHE BOOL "" FORCE)
set(IREE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(IREE_BUILD_SAMPLES OFF CACHE BOOL "" FORCE)
set(IREE_HAL_DRIVER_DEFAULTS OFF CACHE BOOL "" FORCE)
set(IREE_HAL_DRIVER_LOCAL_TASK ON CACHE BOOL "" FORCE)
set(IREE_HAL_DRIVER_LOCAL_SYNC ON CACHE BOOL "" FORCE)
set(IREE_HAL_DRIVER_VULKAN ON CACHE BOOL "" FORCE)
set(IREE_HAL_EXECUTABLE_LOADER_DEFAULTS OFF CACHE BOOL "" FORCE)
set(IREE_HAL_EXECUTABLE_LOADER_EMBEDDED_ELF ON CACHE BOOL "" FORCE)
set(IREE_HAL_EXECUTABLE_LOADER_SYSTEM_LIBRARY ON CACHE BOOL "" FORCE)
set(IREE_HAL_EXECUTABLE_PLUGIN_DEFAULTS OFF CACHE BOOL "" FORCE)
set(IREE_ENABLE_THREADING ON CACHE BOOL "" FORCE)
set(IREE_ENABLE_WERROR OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_subdirectory(/iree iree EXCLUDE_FROM_ALL)
add_library(\${NAME} SHARED /src/moonshine_stream_jni.c)
target_include_directories(\${NAME} PRIVATE /jdk-include /jdk-include/linux /iree/runtime/src \${CMAKE_BINARY_DIR}/iree/runtime/src)
target_link_libraries(\${NAME} PRIVATE iree_runtime_unified
  iree_modules_io_parameters_parameters iree_io_parameter_index iree_io_parameter_index_provider
  iree_io_formats_irpa_irpa dl m pthread)
CM
    sed -i "s/\${NAME}/$NAME/g" /build/overlay/CMakeLists.txt
    cmake -G Ninja -S /build/overlay -B /build/out -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ ${GEOMETRY:+-DCMAKE_C_FLAGS="$GEOMETRY"}
    cmake --build /build/out --target $NAME
    cp "$(find /build/out -name "lib$NAME.so" -type f | head -1)" /out/
    strip /out/lib$NAME.so || true
    ls -la /out/lib$NAME.so
  '
