cmake_minimum_required(VERSION 3.22.1)
project(vadpoc LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Oboe is pulled in via prefab from the com.google.oboe:oboe AAR.
find_package(oboe REQUIRED CONFIG)

add_library(vadpoc SHARED
    ring_buffer.cpp
    audio_engine.cpp
    jni_bridge.cpp
)

target_compile_options(vadpoc PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
)

target_link_libraries(vadpoc
    PRIVATE
        oboe::oboe
        log
        android
)
