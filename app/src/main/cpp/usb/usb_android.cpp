/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Neo-Desktop
 *
 *    usb_android.cpp - the device Android opened, as libusb's only one
 *                      (usb_android.h)
 *
 */
#include "usb_android.h"

#include <stdlib.h>

namespace {

/* (one modem in the process: its thread alone uses these) */
int fd_ = -1;
libusb_device_handle *wrapped_ = nullptr;

} // namespace

namespace dmodem_android {

void usb_use_fd(int fd)
{
	fd_ = fd;
	if (fd >= 0)
		usb_transfers_reset();
}

} // namespace dmodem_android

/* libusb without its device discovery, the descriptor wrapped */
int dmodem_libusb_init(libusb_context **ctx)
{
	struct libusb_init_option opt[1];
	int err;

	opt[0].option = LIBUSB_OPTION_NO_DEVICE_DISCOVERY;
	opt[0].value.ival = 0;
	err = libusb_init_context(ctx, opt, 1);
	if (err < 0)
		return err;
	wrapped_ = nullptr;
	if (fd_ >= 0) {
		err = libusb_wrap_sys_device(*ctx, (intptr_t)fd_, &wrapped_);
		if (err < 0) {
			libusb_exit(*ctx);
			*ctx = nullptr;
			wrapped_ = nullptr;
			return err;
		}
	}
	return 0;
}

/* the wrapped device alone (none if there is none) */
ssize_t dmodem_libusb_get_device_list(libusb_context *,
				      libusb_device ***list)
{
	libusb_device **l = static_cast<libusb_device **>(
		calloc(2, sizeof(libusb_device *)));
	if (!l)
		return LIBUSB_ERROR_NO_MEM;
	if (wrapped_)
		l[0] = libusb_ref_device(libusb_get_device(wrapped_));
	*list = l;
	return wrapped_ ? 1 : 0;
}

/* the wrapped device: its handle (open already) */
int dmodem_libusb_open(libusb_device *dev, libusb_device_handle **h)
{
	if (wrapped_ && dev == libusb_get_device(wrapped_)) {
		*h = wrapped_;
		wrapped_ = nullptr;
		return 0;
	}
	return libusb_open(dev, h);
}

