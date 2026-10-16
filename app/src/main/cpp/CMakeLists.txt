# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Neo-Desktop
# libdmodem_jni.so: D-Modem's modem on a SIP line (libdmodem_sip, from the
# D-Modem-RE submodule) with the app's engine and its JNI binding.
#
# D-Modem-RE fetches libre and Mbed TLS while configuring and builds them
# with the NDK's toolchain for each ABI (cmake/libre.cmake there).
cmake_minimum_required(VERSION 3.22)
project(dmodem_android LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(DMODEM_RE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../external/d-modem-re
	CACHE PATH "the D-Modem-RE tree")
if(NOT EXISTS ${DMODEM_RE_DIR}/CMakeLists.txt)
	message(FATAL_ERROR "D-Modem-RE not found in ${DMODEM_RE_DIR}: "
		"git submodule update --init")
endif()

# only the modem on SIP: no tests, no PBX modules
set(DMODEM_DMODEMD ON CACHE BOOL "" FORCE)
set(DMODEM_TESTS OFF CACHE BOOL "" FORCE)
set(DMODEM_ASTERISK OFF CACHE BOOL "" FORCE)
set(DMODEM_FREESWITCH OFF CACHE BOOL "" FORCE)
set(DMODEM_ARI OFF CACHE BOOL "" FORCE)
add_subdirectory(${DMODEM_RE_DIR} d-modem-re)

# the engine on its own (the host's tests build it too)
add_library(dmodem_engine STATIC engine.cpp)
target_include_directories(dmodem_engine PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(dmodem_engine PUBLIC dmodem_sip)
target_compile_options(dmodem_engine PRIVATE -Wall -Wextra)
set_target_properties(dmodem_engine PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(ANDROID)
	add_library(dmodem_jni SHARED jni.cpp)
	target_link_libraries(dmodem_jni PRIVATE dmodem_engine log)
	target_compile_options(dmodem_jni PRIVATE -Wall -Wextra)
	# 16 KB pages (Android 15 on)
	target_link_options(dmodem_jni PRIVATE
		-Wl,-z,max-page-size=16384 -Wl,--gc-sections)

	# the local shell's pseudo-terminals (plain C: the unit tests build it for the host too)
	add_library(dmodem_pty SHARED pty.c)
	target_compile_options(dmodem_pty PRIVATE -Wall -Wextra)
	target_link_options(dmodem_pty PRIVATE -Wl,-z,max-page-size=16384)
endif()
