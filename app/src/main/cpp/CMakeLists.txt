cmake_minimum_required(VERSION 3.22.1)
project("kighmu_relay")

# Bibliothèque principale relay (existante)
add_library(kighmu_relay SHARED relay.cpp)
target_compile_options(kighmu_relay PRIVATE -O3)
target_link_libraries(kighmu_relay log)

# Wrapper JNI hev
add_library(hev_jni SHARED hev_jni.cpp)
target_compile_options(hev_jni PRIVATE -O2)

if(ANDROID_ABI STREQUAL "armeabi-v7a")
    # Lier contre libtun2socks.so (hev) seulement sur 32-bit
    add_library(tun2socks SHARED IMPORTED)
    set_target_properties(tun2socks PROPERTIES
        IMPORTED_LOCATION
        "${CMAKE_SOURCE_DIR}/../jniLibs/armeabi-v7a/libtun2socks.so"
    )
    target_link_libraries(hev_jni tun2socks log)
else()
    # arm64 : stubs vides, pas de linkage hev
    target_link_libraries(hev_jni log)
endif()
