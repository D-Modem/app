# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Neo-Desktop
# The app's native engine on the build host: the same CMake as the app's
# (app/src/main/cpp), with dmodemd from the same D-Modem-RE tree as the
# other end of the calls.
#
#   cmake -S native-test -B build-host && cmake --build build-host
#   ctest --test-dir build-host --output-on-failure
cmake_minimum_required(VERSION 3.22)
project(dmodem_android_test LANGUAGES C CXX)

enable_testing()
add_subdirectory(../app/src/main/cpp app)

add_executable(engine_test engine_test.cpp)
target_link_libraries(engine_test PRIVATE dmodem_engine)
target_compile_options(engine_test PRIVATE -Wall -Wextra)

add_test(NAME engine COMMAND engine_test $<TARGET_FILE:dmodemd>)
set_tests_properties(engine PROPERTIES TIMEOUT 600)

# fax_line: modem_test's two modems back to back, paced as a phone line
# (the app's fax tests, FaxCallTest, call over it)
find_package(Threads REQUIRED)
add_executable(fax_line fax_line.cpp)
target_include_directories(fax_line PRIVATE ${DMODEM_RE_DIR}/dmodemd)
target_compile_options(fax_line PRIVATE -Wall -Wextra -O2)
target_link_libraries(fax_line PRIVATE dmodem_front dmodem_core dmodem_dsp
	dmodem_link m Threads::Threads)
