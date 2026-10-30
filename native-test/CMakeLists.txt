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
# (D-Modem-RE's u8 .. s64, its include/dm_types.h)
include_directories(${DMODEM_RE_INCLUDE})

# GoogleTest for the engine's tests: the system's (libgtest-dev) or else
# fetched, as D-Modem-RE has it (its cmake/gtest.cmake)
find_package(GTest 1.10 QUIET)
if(NOT GTest_FOUND)
	include(FetchContent)
	FetchContent_Declare(googletest
		GIT_REPOSITORY https://github.com/google/googletest.git
		GIT_TAG v1.14.0
		GIT_SHALLOW TRUE)
	set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
	set(BUILD_GMOCK OFF CACHE BOOL "" FORCE)
	FetchContent_MakeAvailable(googletest)
	if(NOT TARGET GTest::gtest)
		add_library(GTest::gtest ALIAS gtest)
	endif()
endif()
include(GoogleTest)

# engine_test: the engine against a dmodemd over loopback SIP, each of
# its tests a ctest test of its own (each with a dmodemd of its own, on
# the same ports: one at a time)
add_executable(engine_test engine_test.cpp)
target_link_libraries(engine_test PRIVATE dmodem_engine GTest::gtest)
target_compile_options(engine_test PRIVATE -Wall -Wextra)
target_compile_definitions(engine_test PRIVATE DMODEMD_PATH="$<TARGET_FILE:dmodemd>")
add_dependencies(engine_test dmodemd)
gtest_discover_tests(engine_test TEST_PREFIX engine.
	PROPERTIES TIMEOUT 300 RESOURCE_LOCK loopback_sip)

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
# Swift has it (and its header from C: c_api_header.c)
add_executable(c_api_test c_api_test.cpp c_api_header.c)
target_link_libraries(c_api_test PRIVATE dmodem_engine GTest::gtest
	Threads::Threads)
target_compile_options(c_api_test PRIVATE -Wall -Wextra)
gtest_discover_tests(c_api_test TEST_PREFIX c_api. PROPERTIES TIMEOUT 120)

# usb_line_test: the engine on a USB modem's line (usb/usb_modem.h), two
# of them on D-Modem-RE's fake pico-serial Line sticks (its
# test/fake_pico_line.cpp linked in as libusb, so usb_line.cpp finds the
# stick by libusb's own calls: usb_fake_device.cpp) calling each other
# through its simulated exchange (test/line_exchange.cpp); libusb.h from
# the release the app builds
include(${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp/usb/libusb.cmake)
set(_app ${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp)
set(_dmd ${DMODEM_RE_DIR}/dmodemd)
add_library(dmodem_engine_usb STATIC ${_app}/engine.cpp
	${_app}/peer_modem.cpp ${_app}/dmodem_engine.cpp
	${_app}/usb/usb_modem.cpp ${_dmd}/usb_line.cpp ${_dmd}/usb_agere.cpp ${_dmd}/usb_wb.cpp ${_dmd}/si3056.cpp
	${_app}/usb/usb_transfers.cpp usb_fake_device.cpp
	${_dmd}/test/fake_pico_line.cpp)
target_include_directories(dmodem_engine_usb PUBLIC ${_app})
target_include_directories(dmodem_engine_usb SYSTEM PUBLIC
	${DMODEM_LIBUSB_SRC}/libusb)
target_compile_definitions(dmodem_engine_usb PUBLIC DMODEM_ENGINE_USB)
target_link_libraries(dmodem_engine_usb PUBLIC dmodem_sip)

# (the transfers counted as the app's are, for the log: usb_transfers.cpp)
set_source_files_properties(${_dmd}/usb_line.cpp ${_dmd}/usb_agere.cpp
	PROPERTIES COMPILE_DEFINITIONS
	"libusb_submit_transfer=dmodem_libusb_submit_transfer")

add_executable(usb_host usb_host.cpp)
target_link_libraries(usb_host PRIVATE dmodem_engine_usb Threads::Threads)
target_compile_options(usb_host PRIVATE -Wall -Wextra)

add_executable(usb_exchange ${_dmd}/test/line_exchange.cpp)
target_link_libraries(usb_exchange PRIVATE dmodem_core dmodem_dsp
	dmodem_link m Threads::Threads)

add_test(NAME usb_line COMMAND python3
	${CMAKE_CURRENT_SOURCE_DIR}/usb_line_test.py $<TARGET_FILE:usb_host>
	$<TARGET_FILE:usb_exchange>)
set_tests_properties(usb_line PROPERTIES TIMEOUT 300)

# the same on D-Modem-RE's fake Agere USB 2.0 soft modems
# (test/fake_agere.cpp): the ROM's isochronous stream
add_library(dmodem_engine_agere STATIC ${_app}/engine.cpp
	${_app}/peer_modem.cpp ${_app}/dmodem_engine.cpp
	${_app}/usb/usb_modem.cpp ${_dmd}/usb_line.cpp ${_dmd}/usb_agere.cpp
	${_dmd}/usb_wb.cpp ${_dmd}/si3056.cpp ${_app}/usb/usb_transfers.cpp usb_fake_device.cpp
	${_dmd}/test/fake_agere.cpp)
target_include_directories(dmodem_engine_agere PUBLIC ${_app})
target_include_directories(dmodem_engine_agere SYSTEM PUBLIC
	${DMODEM_LIBUSB_SRC}/libusb)
target_compile_definitions(dmodem_engine_agere PUBLIC DMODEM_ENGINE_USB)
target_link_libraries(dmodem_engine_agere PUBLIC dmodem_sip)

add_executable(agere_host usb_host.cpp)
target_link_libraries(agere_host PRIVATE dmodem_engine_agere Threads::Threads)
target_compile_options(agere_host PRIVATE -Wall -Wextra)

add_test(NAME agere_line COMMAND python3
	${CMAKE_CURRENT_SOURCE_DIR}/usb_line_test.py $<TARGET_FILE:agere_host>
	$<TARGET_FILE:usb_exchange> agere)
set_tests_properties(agere_line PROPERTIES TIMEOUT 300)
