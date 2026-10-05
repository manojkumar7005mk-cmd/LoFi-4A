cmake_minimum_required(VERSION 3.22.1)
project(lofi_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp is a git submodule pinned to ONE revision (see README: "Pinning llama.cpp").
# All JNI code is written against that revision's llama.h.
set(LLAMA_DIR "${CMAKE_SOURCE_DIR}/../../../../third_party/llama.cpp")
if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "llama.cpp not found at ${LLAMA_DIR}. Run: git submodule update --init --recursive")
endif()

# ---- llama.cpp build options (static, minimal) -------------------------------------------
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)      # no network code in the inference engine
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)     # never tune for the build host
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)     # avoid libomp dependency on Android

add_subdirectory("${LLAMA_DIR}" "${CMAKE_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)

# ---- libllama_jni.so : Gemma text engine --------------------------------------------------
add_library(llama_jni SHARED llama_jni.cpp)
target_include_directories(llama_jni PRIVATE
    "${LLAMA_DIR}/include"
    "${LLAMA_DIR}/ggml/include")
target_link_libraries(llama_jni PRIVATE llama log android)

# ---- Later phases (kept as comments on purpose; nothing here pretends to work) ------------
# Phase 3: libmtmd_jni.so links llama's mtmd target and SHARES llama's ggml (one set of symbols).
# Phase 4: libwhisper_jni.so must NOT be linked into the same .so as llama: whisper.cpp ships its
#          own ggml copy. Build it as a separate shared library with hidden symbol visibility
#          (-fvisibility=hidden, -Wl,--exclude-libs,ALL) so the two ggml copies never collide.
