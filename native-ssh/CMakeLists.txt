# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Neo-Desktop
#
# libdmodemssh: the apps' SSH client (dmodemssh.h) on libssh 0.11 and
# mbedTLS, both built here from their release tarballs (checked by hash).
# The same engine as D-Modem/gui's ssh/: Android loads it through JNI
# (app/src/main/cpp), iOS as ios/build-native.sh's DModemSSHNative
# XCFramework, and here on the build host for its tests:
#
#   cmake -S native-ssh -B build-ssh && cmake --build build-ssh
#   DMODEM_SSH_TEST=host:port:user:password build-ssh/dmodemssh_test
cmake_minimum_required(VERSION 3.22)
project(dmodemssh LANGUAGES C)

include(${CMAKE_CURRENT_LIST_DIR}/cmake/BundledLibssh.cmake)

add_library(dmodemssh STATIC src/dmodemssh.c)
target_include_directories(dmodemssh PUBLIC include)
target_link_libraries(dmodemssh PUBLIC dmodem_libssh)
set_target_properties(dmodemssh PROPERTIES POSITION_INDEPENDENT_CODE ON)
if(NOT WIN32)
  find_package(Threads REQUIRED)
  target_link_libraries(dmodemssh PUBLIC Threads::Threads)
endif()
target_compile_options(dmodemssh PRIVATE -Wall -Wextra -Wshadow)

if(NOT ANDROID AND NOT CMAKE_SYSTEM_NAME STREQUAL "iOS")
  enable_testing()
  add_executable(dmodemssh_test test/dmodemssh_test.c)
  target_link_libraries(dmodemssh_test PRIVATE dmodemssh)
  add_test(NAME dmodemssh COMMAND dmodemssh_test)
endif()
