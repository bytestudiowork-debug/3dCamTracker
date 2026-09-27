cmake_minimum_required(VERSION 3.22.1)
project("trackstudio_native")

# Phase 1 only ships the proxy engine: decode a source frame, downscale it,
# re-encode it as a proxy. Everything else (tracking math, solvers) lands in
# later modules as separate .cpp units added to this same target, so the
# module boundary stays in the C++ file layout, not in extra CMake targets.
add_library(
        trackstudio_native
        SHARED
        proxy_engine.cpp
)

# stb_image / stb_image_resize2 / stb_image_write are vendored header-only
# libs (single .h files, no build step, no license/runtime cost beyond the
# code itself) - avoids pulling in libjpeg-turbo/libpng as external deps just
# to resize a picture, which matters on a low-end-laptop-built CI project.
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
        -ffast-math
        -funroll-loops
)
