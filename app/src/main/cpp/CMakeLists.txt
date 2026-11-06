cmake_minimum_required(VERSION 3.22.1)
project("trackstudio_native")

include(FetchContent)

# Ceres 2.2.0 is built for the active Android ABI by the same NDK toolchain
# as TrackStudio. Eigen is header-only; Ceres uses its EigenSparse backend.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(MINIGLOG ON CACHE BOOL "" FORCE)
set(GFLAGS OFF CACHE BOOL "" FORCE)
set(SUITESPARSE OFF CACHE BOOL "" FORCE)
set(EIGENSPARSE ON CACHE BOOL "" FORCE)
set(CUDA OFF CACHE BOOL "" FORCE)
set(LAPACK OFF CACHE BOOL "" FORCE)
set(ACCELERATESPARSE OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Populate Eigen's headers without configuring its optional desktop targets.
FetchContent_Declare(
        eigen
        GIT_REPOSITORY https://gitlab.com/libeigen/eigen.git
        GIT_TAG 3.4.0
        GIT_SHALLOW TRUE
)
FetchContent_GetProperties(eigen)
if(NOT eigen_POPULATED)
    FetchContent_Populate(eigen)
endif()

# Provide the minimal Eigen3 config package required by Ceres.
set(Eigen3_DIR "${CMAKE_BINARY_DIR}/eigen3-config" CACHE PATH "Eigen 3.4 package directory" FORCE)
file(MAKE_DIRECTORY "${Eigen3_DIR}")
file(WRITE "${Eigen3_DIR}/Eigen3Config.cmake"
"set(Eigen3_FOUND TRUE)\n"
"set(Eigen3_VERSION 3.4.0)\n"
"set(Eigen3_INCLUDE_DIRS \"${eigen_SOURCE_DIR}\")\n"
"if(NOT TARGET Eigen3::Eigen)\n"
"  add_library(Eigen3::Eigen INTERFACE IMPORTED)\n"
"  set_target_properties(Eigen3::Eigen PROPERTIES INTERFACE_INCLUDE_DIRECTORIES \"${eigen_SOURCE_DIR}\")\n"
"endif()\n"
)
file(WRITE "${Eigen3_DIR}/Eigen3ConfigVersion.cmake"
"set(PACKAGE_VERSION 3.4.0)\n"
"if(PACKAGE_FIND_VERSION VERSION_LESS_EQUAL PACKAGE_VERSION)\n"
"  set(PACKAGE_VERSION_COMPATIBLE TRUE)\n"
"  if(PACKAGE_FIND_VERSION VERSION_EQUAL PACKAGE_VERSION)\n"
"    set(PACKAGE_VERSION_EXACT TRUE)\n"
"  endif()\n"
"endif()\n"
)

# Avoid duplicate uninstall target creation between dependencies.
set(PROVIDE_UNINSTALL_TARGET OFF CACHE BOOL "Do not add Ceres uninstall target" FORCE)

FetchContent_Declare(
        ceres
        GIT_REPOSITORY https://ceres-solver.googlesource.com/ceres-solver
        GIT_TAG 2.2.0
        GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(ceres)

if(NOT TARGET Ceres::ceres)
    message(FATAL_ERROR "Ceres 2.2.0 did not export Ceres::ceres")
endif()

add_library(trackstudio_glomap SHARED glomap_positioner.cpp glomap_bundle_adjuster.cpp)
target_link_libraries(trackstudio_glomap PRIVATE Ceres::ceres)
target_compile_options(trackstudio_glomap PRIVATE -O3 -fno-fast-math)

add_library(trackstudio_native SHARED proxy_engine.cpp)
target_include_directories(trackstudio_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(trackstudio_native PRIVATE
        Ceres::ceres
        ${log-lib}
        ${jnigraphics-lib}
)

target_compile_options(trackstudio_native PRIVATE
        -O3
        -ffast-math
        -funroll-loops
        -fno-exceptions
        -fno-rtti
)

if(SUITESPARSE OR NOT EIGENSPARSE)
    message(FATAL_ERROR "TrackStudio requires Ceres EigenSparse with SuiteSparse disabled")
endif()
