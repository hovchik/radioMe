cmake_minimum_required(VERSION 3.22.1)
project(radiome_voice C)

file(GLOB CODEC2_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/codec2/*.c)

# Codec2 (LGPL-2.1) as its own shared library, so it can be swapped for another build.
add_library(codec2 SHARED ${CODEC2_SOURCES})
target_include_directories(codec2 PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/codec2)
target_compile_options(codec2 PRIVATE -O2 -w)
target_link_libraries(codec2 m)

add_library(radiome_voice SHARED codec2_jni.c)
target_link_libraries(radiome_voice codec2 log)
