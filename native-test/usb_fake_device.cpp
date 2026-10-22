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

void usb_use_fd(int) {}

} // namespace dmodem_android
