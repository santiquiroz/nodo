cmake_minimum_required(VERSION 3.22)
project(nodo_llama)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/llama.cpp)

set(BUILD_SHARED_LIBS OFF)
set(LLAMA_BUILD_TESTS OFF)
set(LLAMA_BUILD_EXAMPLES OFF)
set(LLAMA_BUILD_SERVER OFF)
set(LLAMA_BUILD_TOOLS OFF)
set(LLAMA_CURL OFF)
set(GGML_OPENMP OFF)

add_subdirectory(${LLAMA_DIR} llama_build)

add_library(nodo_llama SHARED nodo_llama.cpp)
target_link_libraries(nodo_llama PRIVATE llama ggml log android)
target_include_directories(nodo_llama PRIVATE ${LLAMA_DIR}/include ${LLAMA_DIR}/ggml/include)
# Soporte páginas de 16KB (Android 15+)
target_link_options(nodo_llama PRIVATE "-Wl,-z,max-page-size=16384")
