/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 D-Modem Authors
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
 *    libusb_submit_transfer() is renamed too (usb_line.cpp, usb_agere.cpp):
 *    each transfer counted, each way, as it is sent and as it comes back,
 *    for the log (usb_transfer_stats()): a modem whose samples don't come
 *    says whether its transfers never come back or come back empty.
 *
 */
#ifndef DMODEM_ANDROID_USB_ANDROID_H
#define DMODEM_ANDROID_USB_ANDROID_H

#include <libusb.h>

namespace dmodem_android {

/* the device's descriptor for the next libusb_init() of usb_line.cpp
   (-1: none); the caller's, kept open while it is used */
void usb_use_fd(int fd);

/* the transfers since the last usb_use_fd() of a descriptor, each way, as a line for
   the log: sent, back (and how: their octets, those that failed) */
void usb_transfer_stats(char *buf, size_t len);

/* the counts started again (usb_use_fd() of a descriptor) */
void usb_transfers_reset();

} // namespace dmodem_android

extern "C" {
int dmodem_libusb_init(libusb_context **ctx);
ssize_t dmodem_libusb_get_device_list(libusb_context *ctx,
				      libusb_device ***list);
int dmodem_libusb_open(libusb_device *dev, libusb_device_handle **h);
int dmodem_libusb_submit_transfer(struct libusb_transfer *t);
}

#endif
