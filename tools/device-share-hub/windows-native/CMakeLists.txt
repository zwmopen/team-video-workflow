cmake_minimum_required(VERSION 3.20)
project(DeviceShareHub LANGUAGES CXX)

add_executable(DeviceShareHub WIN32 src/main.cpp)
target_compile_features(DeviceShareHub PRIVATE cxx_std_17)
target_compile_definitions(DeviceShareHub PRIVATE UNICODE _UNICODE WIN32_LEAN_AND_MEAN NOMINMAX _WIN32_WINNT=0x0A00)
target_link_libraries(DeviceShareHub PRIVATE ws2_32 winhttp bcrypt crypt32 ole32 comctl32 shell32)

if(MSVC)
  set_property(TARGET DeviceShareHub PROPERTY
    MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
  target_compile_options(DeviceShareHub PRIVATE /W4 /permissive- /utf-8 /EHsc)
  target_link_options(DeviceShareHub PRIVATE /SUBSYSTEM:WINDOWS)
endif()

set_target_properties(DeviceShareHub PROPERTIES OUTPUT_NAME "素材投送中控")
