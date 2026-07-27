cmake_minimum_required(VERSION 3.20)
project(DeviceShareHub LANGUAGES CXX)

add_executable(DeviceShareHub WIN32 src/main.cpp src/receiver.cpp src/usb_transport.cpp src/content_store.cpp resources.rc)
target_compile_features(DeviceShareHub PRIVATE cxx_std_17)
target_compile_definitions(DeviceShareHub PRIVATE UNICODE _UNICODE WIN32_LEAN_AND_MEAN NOMINMAX _WIN32_WINNT=0x0A00)
target_link_libraries(DeviceShareHub PRIVATE ws2_32 winhttp bcrypt crypt32 ole32 comctl32 shell32 iphlpapi uuid PortableDeviceGUIDs propsys setupapi winsqlite3)

include(CTest)
if(BUILD_TESTING)
  add_executable(content_store_tests tests/content_store_tests.cpp src/content_store.cpp)
  target_include_directories(content_store_tests PRIVATE src)
  target_compile_features(content_store_tests PRIVATE cxx_std_17)
  target_compile_definitions(content_store_tests PRIVATE UNICODE _UNICODE WIN32_LEAN_AND_MEAN NOMINMAX _WIN32_WINNT=0x0A00)
  target_link_libraries(content_store_tests PRIVATE winsqlite3)
  add_test(NAME content_store_tests COMMAND content_store_tests)
endif()

if(MSVC)
  set_property(TARGET DeviceShareHub PROPERTY
    MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
  target_compile_options(DeviceShareHub PRIVATE /W4 /permissive- /utf-8 /EHsc)
  target_link_options(DeviceShareHub PRIVATE /SUBSYSTEM:WINDOWS "/MANIFESTINPUT:${CMAKE_CURRENT_SOURCE_DIR}/app.manifest")
endif()

set_target_properties(DeviceShareHub PROPERTIES OUTPUT_NAME "素材投送中控")
