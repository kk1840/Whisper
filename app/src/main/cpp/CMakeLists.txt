cmake_minimum_required(VERSION 3.22.1)
project(whisper_ort_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(whisper-ort-jni SHARED ort_jni.cpp)
target_include_directories(whisper-ort-jni PRIVATE include)
target_link_libraries(whisper-ort-jni dl)
