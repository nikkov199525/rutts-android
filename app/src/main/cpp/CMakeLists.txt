cmake_minimum_required(VERSION 3.22.1)
project(rutts_android LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_INTERPROCEDURAL_OPTIMIZATION_RELEASE ON)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC_LIBS ON CACHE BOOL "" FORCE)
set(PCRE2_BUILD_PCRE2_8 ON CACHE BOOL "" FORCE)
set(PCRE2_BUILD_PCRE2_16 OFF CACHE BOOL "" FORCE)
set(PCRE2_BUILD_PCRE2_32 OFF CACHE BOOL "" FORCE)
set(PCRE2_BUILD_PCRE2GREP OFF CACHE BOOL "" FORCE)
set(PCRE2_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(PCRE2_SUPPORT_UNICODE ON CACHE BOOL "" FORCE)
add_subdirectory(third_party/pcre2 pcre2-build EXCLUDE_FROM_ALL)

add_library(
    rutts_android
    SHARED
    covox.c
    native_bridge.cpp
    rulex_runtime.cpp
    synth_engine.cpp
    third_party/rulex/src/coder.c
    third_party/rulex/src/lexdb.c
    third_party/lmdb/libraries/liblmdb/mdb.c
    third_party/lmdb/libraries/liblmdb/midl.c
    third_party/ru_tts/src/female.c
    third_party/ru_tts/src/intonator.c
    third_party/ru_tts/src/male.c
    third_party/ru_tts/src/numerics.c
    third_party/ru_tts/src/sink.c
    third_party/ru_tts/src/soundproducer.c
    third_party/ru_tts/src/speechrate_control.c
    third_party/ru_tts/src/synth.c
    third_party/ru_tts/src/text2speech.c
    third_party/ru_tts/src/time_planner.c
    third_party/ru_tts/src/transcription.c
    third_party/ru_tts/src/utterance.c
    third_party/sonic/sonic.c
    third_party/libsamplerate/samplerate.c
    third_party/libsamplerate/src_linear.c
    third_party/libsamplerate/src_zoh.c
)

target_include_directories(
    rutts_android
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/rulex/src
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/lmdb/libraries/liblmdb
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/ru_tts/src
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/sonic
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libsamplerate
)

target_link_libraries(
    rutts_android
    PRIVATE
    android
    log
    m
    pcre2-posix-static
)

target_compile_definitions(rutts_android PRIVATE RULEX_USE_PCRE2=1)

target_compile_options(rutts_android PRIVATE
    -O2
    -ffunction-sections
    -fdata-sections
    -fvisibility=hidden
    -fvisibility-inlines-hidden
)

target_link_options(rutts_android PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,-z,max-page-size=16384
)
