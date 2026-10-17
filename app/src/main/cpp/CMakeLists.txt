cmake_minimum_required(VERSION 3.22.1)
project(jarvisnode LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(libnode SHARED IMPORTED)
set_target_properties(libnode PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnode.so
)

include_directories(${CMAKE_CURRENT_SOURCE_DIR}/../../../libnode/include/node)

add_library(jarvisnode SHARED native-lib.cpp)
find_library(log-lib log)
target_link_libraries(jarvisnode PRIVATE libnode ${log-lib})
