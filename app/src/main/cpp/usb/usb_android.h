/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Neo-Desktop
 *
 *    usb_android.h - D-Modem-RE's USB line (usb_line.cpp) on a device
 *                    Android has opened for the app
 *
 *    An app can't list the USB devices (no /dev/bus/usb to read): Android
 *    gives it the one the user allowed, as a descriptor (UsbManager's
 *    UsbDeviceConnection).  usb_line.cpp finds its modem as dmodemd does,
 *    with libusb_init(), libusb_get_device_list() and libusb_open(): it is
 *    built here with those three renamed to the ones below
 *    (CMakeLists.txt), which start libusb without its device discovery,
 *    wrap the descriptor given (libusb_wrap_sys_device()) and give that
 *    device as the only one on the bus, already open.
 *
 */
#ifndef DMODEM_ANDROID_USB_ANDROID_H
#define DMODEM_ANDROID_USB_ANDROID_H

#include <libusb.h>

namespace dmodem_android {

/* the device's descriptor for the next libusb_init() of usb_line.cpp
   (-1: none); the caller's, kept open while it is used */
void usb_use_fd(int fd);

} // namespace dmodem_android

extern "C" {
int dmodem_libusb_init(libusb_context **ctx);
ssize_t dmodem_libusb_get_device_list(libusb_context *ctx,
				      libusb_device ***list);
int dmodem_libusb_open(libusb_device *dev, libusb_device_handle **h);
}

#endif
