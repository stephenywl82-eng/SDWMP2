cmake_minimum_required(VERSION 3.22.1)
project("oboe_bridge")

# Oboe via prefab
find_package(oboe REQUIRED CONFIG)

add_library(oboe_bridge SHARED
    oboe_bridge.cpp
)

target_include_directories(oboe_bridge PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(oboe_bridge
    oboe::oboe
    log
    android
    mediandk
)

set_target_properties(oboe_bridge PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)
