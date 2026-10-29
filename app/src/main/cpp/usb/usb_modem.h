/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 D-Modem Authors
 *
 *    usb_modem.h - the modem core on a telephone line through a USB
 *                  modem (D-Modem-RE's UsbLine: a Smart Link SmartUSB56,
 *                  or a pico-serial Line stick), its DTE a descriptor
 *
 *    As dmodemd --usb runs it (its run_line()), on the device Android
 *    gave the app (usb_android.h), until stop() or the DTE gone.
 *
 */
#ifndef DMODEM_ANDROID_USB_MODEM_H
#define DMODEM_ANDROID_USB_MODEM_H

#include <atomic>
#include <functional>
#include <memory>

#include <dcore.h>
#include <sip_modem.h>

namespace dmd { class UsbLine; }

namespace dmodem_android {

class UsbModem {
public:
	using Status = dmd::SipModem::Status;

	/* the core as opt has it (its rate the device's: 8000 or 9600) on
	   dte, the device fd (Android's, kept open by the caller) opened;
	   ok() if all of it is */
	UsbModem(dmd::Dte &dte, const dcore::Options &opt, int fd);
	~UsbModem();
	UsbModem(const UsbModem &) = delete;
	UsbModem &operator=(const UsbModem &) = delete;

	bool ok() const;
	/* the core */
	dcore::Modem &modem() { return *m_; }

	/* before run(): the call's state each time it changes */
	void on_status(std::function<void(const Status &)> fn) {
		status_fn_ = std::move(fn);
	}

	/* until stop() (0), the DTE gone (0) or an error, the device
	   unplugged among them (-1) */
	int run();
	/* run() returns (any thread) */
	void stop();

private:
	void report();

	dmd::Dte &dte_;
	std::unique_ptr<dmd::UsbLine> line_;
	std::unique_ptr<dcore::Modem> m_;
	bool open_ = false;
	int ev_[2] = { -1, -1 };
	std::atomic<bool> stopped_{ false };
	std::function<void(const Status &)> status_fn_;
	Status status_;
};

} // namespace dmodem_android

#endif
