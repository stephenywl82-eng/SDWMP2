cmake_minimum_required(VERSION 3.22.1)
project("oboe_bridge")

# Prebuilt liboboe.so from jniLibs (already in APK)
add_library(oboe SHARED IMPORTED)
set_target_properties(oboe PROPERTIES
    IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/liboboe.so"
)

add_library(oboe_bridge SHARED
    oboe_bridge.cpp
    usb_audio_driver.cpp
    usb_audio_jni.cpp
    flac_decoder_jni.cpp
)

target_include_directories(oboe_bridge PRIVATE
    "${CMAKE_SOURCE_DIR}"
    "${CMAKE_SOURCE_DIR}/oboe"
)

target_link_libraries(oboe_bridge
    oboe
    log
    android
    mediandk
)

set_target_properties(oboe_bridge PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)
