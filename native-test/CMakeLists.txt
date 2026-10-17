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

# sip_fax_line: the engine and dmodemd on a loopback SIP call with T.38,
# each one's DTE a pty: FaxCallTest's fax over T.38
add_executable(sip_fax_line sip_fax_line.cpp)
target_compile_options(sip_fax_line PRIVATE -Wall -Wextra)
target_compile_definitions(sip_fax_line PRIVATE DMODEMD_PATH="$<TARGET_FILE:dmodemd>")
target_link_libraries(sip_fax_line PRIVATE dmodem_engine Threads::Threads)
add_dependencies(sip_fax_line dmodemd)

# t30_line: a class 1 modem on a line to D-Modem-RE's own T.30 fax machine
# (with or without ECM, noise if asked): FaxCallTest's interop with it
add_executable(t30_line t30_line.cpp)
target_include_directories(t30_line PRIVATE ${DMODEM_RE_DIR}/dmodemd)
target_compile_options(t30_line PRIVATE -Wall -Wextra -O2)
target_link_libraries(t30_line PRIVATE dmodem_front dmodem_core dmodem_dsp
	dmodem_t30 dmodem_link m Threads::Threads)

# peer_host: the engine on a peer line (two phones' modems through the
# app), for the app's PeerLineTest
add_executable(peer_host peer_host.cpp)
target_link_libraries(peer_host PRIVATE dmodem_engine Threads::Threads)
target_compile_options(peer_host PRIVATE -Wall -Wextra)

# codec_line: two modems back to back through a cellular call's speech
# codec (GSM full rate, AMR-NB) each way: what a cellular line carries
# (CellLineTest); the codecs as the host has them (libgsm1-dev,
# libopencore-amrnb-dev)
find_library(GSM_LIB gsm)
find_library(AMRNB_LIB opencore-amrnb)
add_executable(codec_line codec_line.cpp)
target_include_directories(codec_line PRIVATE ${DMODEM_RE_DIR}/dmodemd)
target_compile_options(codec_line PRIVATE -Wall -Wextra -O2)
target_link_libraries(codec_line PRIVATE dmodem_front dmodem_core dmodem_dsp
	dmodem_link m Threads::Threads)
if(GSM_LIB)
	target_compile_definitions(codec_line PRIVATE HAVE_GSM)
	target_link_libraries(codec_line PRIVATE ${GSM_LIB})
endif()
if(AMRNB_LIB)
	target_compile_definitions(codec_line PRIVATE HAVE_AMRNB)
	target_link_libraries(codec_line PRIVATE ${AMRNB_LIB})
endif()

# c_api_test: the engine through its C API (dmodem_engine.h), as iOS's
# Swift has it
add_executable(c_api_test c_api_test.c)
target_link_libraries(c_api_test PRIVATE dmodem_engine Threads::Threads)
target_compile_options(c_api_test PRIVATE -Wall -Wextra)
set_target_properties(c_api_test PROPERTIES LINKER_LANGUAGE CXX)
add_test(NAME c_api COMMAND c_api_test)
set_tests_properties(c_api PROPERTIES TIMEOUT 120)
