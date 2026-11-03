cmake_minimum_required(VERSION 3.22.1)
project("trackstudio_native")

add_library(
        trackstudio_native
        SHARED
        proxy_engine.cpp
        tracking_engine.cpp
)

target_include_directories(trackstudio_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(
        trackstudio_native
        ${log-lib}
        ${jnigraphics-lib}
)

target_compile_options(trackstudio_native PRIVATE
        -O3
)
