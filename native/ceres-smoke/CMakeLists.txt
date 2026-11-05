cmake_minimum_required(VERSION 3.22)
project(trackstudio_ceres_sparse_smoke LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Ceres 2.2 REQUIRED CONFIG)

add_executable(ceres_sparse_smoke main.cpp)
target_link_libraries(ceres_sparse_smoke PRIVATE Ceres::ceres)
