# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 D-Modem Authors
# libusb-1.0 for Android, as the static library `dmodem_android_libusb`
# (the build host's tests take its libusb.h alone, DMODEM_LIBUSB_SRC):
# D-Modem-RE's pinned release (its cmake/libusb.cmake: the same version
# and SHA-256), downloaded once while configuring into the build tree,
# its core and Linux backend (usbfs) built with the NDK, with the config.h
# it ships for Android.  An app can't list the USB devices itself: the
# device comes from Android (UsbManager) as a descriptor, which libusb
# wraps (usb_android.cpp).
#
# libusb is LGPL-2.1-or-later.
set(DMODEM_LIBUSB_VERSION "1.0.27" CACHE STRING "libusb release to fetch")
set(DMODEM_LIBUSB_URL
	"https://github.com/libusb/libusb/releases/download/v${DMODEM_LIBUSB_VERSION}/libusb-${DMODEM_LIBUSB_VERSION}.tar.bz2"
	CACHE STRING "libusb release tarball")
set(DMODEM_LIBUSB_SHA256
	"ffaa41d741a8a3bee244ac8e54a72ea05bf2879663c098c82fc5757853441575"
	CACHE STRING "the tarball's SHA-256")
set(DMODEM_LIBUSB_DIR "" CACHE PATH "a libusb source tree (else fetched)")

set(_usb_src ${DMODEM_LIBUSB_DIR})
if(NOT _usb_src)
	set(_deps ${CMAKE_BINARY_DIR}/_deps)
	set(_usb_tar ${_deps}/libusb-${DMODEM_LIBUSB_VERSION}.tar.bz2)
	set(_usb_src ${_deps}/libusb-${DMODEM_LIBUSB_VERSION})
	if(NOT EXISTS ${_usb_src}/libusb/libusb.h)
		message(STATUS "libusb: fetching ${DMODEM_LIBUSB_VERSION}")
		file(DOWNLOAD ${DMODEM_LIBUSB_URL} ${_usb_tar}
			EXPECTED_HASH SHA256=${DMODEM_LIBUSB_SHA256}
			STATUS _st TLS_VERIFY ON)
		list(GET _st 0 _code)
		if(NOT _code EQUAL 0)
			message(FATAL_ERROR "libusb: can't fetch ${DMODEM_LIBUSB_URL}: ${_st} "
				"(or give -DDMODEM_LIBUSB_DIR=<source tree>)")
		endif()
		file(ARCHIVE_EXTRACT INPUT ${_usb_tar} DESTINATION ${_deps})
	endif()
endif()
if(NOT EXISTS ${_usb_src}/libusb/os/linux_usbfs.c)
	message(FATAL_ERROR "libusb: no source tree in ${_usb_src}")
endif()
set(DMODEM_LIBUSB_SRC ${_usb_src})
if(NOT ANDROID)
	return()
endif()

add_library(dmodem_android_libusb STATIC
	${_usb_src}/libusb/core.c
	${_usb_src}/libusb/descriptor.c
	${_usb_src}/libusb/hotplug.c
	${_usb_src}/libusb/io.c
	${_usb_src}/libusb/strerror.c
	${_usb_src}/libusb/sync.c
	${_usb_src}/libusb/os/events_posix.c
	${_usb_src}/libusb/os/threads_posix.c
	${_usb_src}/libusb/os/linux_usbfs.c
	${_usb_src}/libusb/os/linux_netlink.c)
# (its Android config.h, as its own ndk-build has it)
target_include_directories(dmodem_android_libusb PRIVATE ${_usb_src}/android
	${_usb_src}/libusb/os)
target_include_directories(dmodem_android_libusb SYSTEM PUBLIC ${_usb_src}/libusb)
target_compile_options(dmodem_android_libusb PRIVATE -w -fvisibility=hidden -pthread)
set_target_properties(dmodem_android_libusb PROPERTIES C_STANDARD 11
	POSITION_INDEPENDENT_CODE ON)
target_link_libraries(dmodem_android_libusb PUBLIC log)
