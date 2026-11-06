cmake_minimum_required(VERSION 3.20)
project(turbodl_c_abi_probe C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)

add_library(tdl_abi SHARED tdl_abi.c)
target_include_directories(tdl_abi PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
set_target_properties(tdl_abi PROPERTIES
    C_VISIBILITY_PRESET default
    WINDOWS_EXPORT_ALL_SYMBOLS ON
)

if(NOT ANDROID)
    enable_testing()
    add_executable(c_abi_test abi_test.c)
    target_link_libraries(c_abi_test PRIVATE tdl_abi)
    add_test(NAME c_abi_conformance COMMAND c_abi_test)
endif()
