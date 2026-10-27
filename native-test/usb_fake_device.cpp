/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Neo-Desktop
 *
 *    usb_fake_device.cpp - the build host's usb_android.h: the fake
 *                          stick (fake_pico_line.cpp) is found by
 *                          libusb's own calls, no descriptor to wrap
 *
 */
#include "usb/usb_android.h"

namespace dmodem_android {

/* (the fake needs no descriptor) a descriptor's use only starts the transfer counts again */
void usb_use_fd(int fd)
{
	if (fd >= 0)
		usb_transfers_reset();
}

} // namespace dmodem_android

/* (weak: a fake that has its own wins) what the fake leaves to the real libusb (upstream's tests preload the
   fake in front of it; here it is linked in alone): the device's ports,
   for wake on ring's sysfs switch (UsbLine::arm_wake(), with set_wake()
   alone), unknown */
extern "C" __attribute__((weak)) int LIBUSB_CALL libusb_get_port_numbers(libusb_device *,
						   uint8_t *, int)
{
	return LIBUSB_ERROR_NOT_SUPPORTED;
}

/* (the Agere modem's speed check: the fake stick isn't one) */
extern "C" __attribute__((weak)) int LIBUSB_CALL libusb_get_device_speed(libusb_device *)
{
	return LIBUSB_SPEED_HIGH;
}
