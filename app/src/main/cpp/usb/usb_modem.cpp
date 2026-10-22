/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Neo-Desktop
 *
 *    usb_modem.cpp - the modem core on a USB modem's line (usb_modem.h)
 *
 */
#include "usb_modem.h"

#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <unistd.h>

#include <log.h>
#include <run_loop.h>
#include <usb_line.h>

#include "usb_android.h"

#define ERR(fmt, ...) dcore::log_printf(0, "error: " fmt, ##__VA_ARGS__)

namespace dmodem_android {

/* see usb_modem.h: the core first (its country to the line), then the
   device (the country's settings into its DAA) */
UsbModem::UsbModem(dmd::Dte &dte, const dcore::Options &opt, int fd)
	: dte_(dte), line_(new dmd::UsbLine(dte))
{
	m_.reset(new dcore::Modem(*line_, opt));
	if (!m_->ok())
		return;
	if (pipe(ev_) < 0) {
		ERR("pipe: %s\n", strerror(errno));
		ev_[0] = ev_[1] = -1;
		return;
	}
	for (int e : ev_) {
		fcntl(e, F_SETFD, FD_CLOEXEC);
		fcntl(e, F_SETFL, fcntl(e, F_GETFL) | O_NONBLOCK);
	}
	usb_use_fd(fd);
	open_ = line_->open("auto", (unsigned)opt.rate);
	usb_use_fd(-1);
}

/* the core first: it may still hang up on the line */
UsbModem::~UsbModem()
{
	m_.reset();
	line_.reset();
	for (int e : ev_)
		if (e >= 0)
			close(e);
}

bool UsbModem::ok() const
{
	return m_ && m_->ok() && open_ && ev_[0] >= 0;
}

void UsbModem::stop()
{
	stopped_ = true;
	if (ev_[1] >= 0 && write(ev_[1], "", 1) < 0) {
		/* (full: it wakes anyway) */
	}
}

/* the status to status_fn_ if it changed (as SipModem's) */
void UsbModem::report()
{
	Status s;

	if (!status_fn_)
		return;
	s.link = m_->link_state();
	s.offhook = m_->started();
	if (s.link == dcore::LinkState::Online) {
		s.tx_rate = m_->tx_rate();
		s.rx_rate = m_->rx_rate();
	}
	if (s != status_) {
		status_ = s;
		status_fn_(s);
	}
}

/* each turn (dmodemd's run_line()): the modem's timers, then select()
   on libusb's descriptors, the stop pipe and the DTE, then the samples
   that came */
int UsbModem::run()
{
	dcore::Modem &m = *m_;
	dcore::Modem *const modems[] = { &m };
	dmd::RunLoop loop;
	int ret = 0;

	while (!stopped_) {
		long wait = dmd::RunLoop::poll(modems, 1);
		int dte = dte_.fd();

		report();
		loop.clear();
		line_->watch(loop);
		loop.watch(ev_[0]);
		if (dte >= 0 && dte_.want_read(m))
			loop.watch(dte);
		if (dte >= 0 && dte_.want_write())
			loop.watch_write(dte);
		int n = loop.wait(wait);
		if (n < 0 && errno != EINTR) {
			ERR("select: %s\n", strerror(errno));
			ret = -1;
			break;
		}
		if (line_->pump(m) < 0) {
			ret = -1;
			break;
		}
		if (n <= 0)
			continue;
		if (loop.ready(ev_[0])) {
			char buf[64];
			while (read(ev_[0], buf, sizeof(buf)) > 0) {
			}
		}
		if (loop.ready_write(dte))
			dte_.flush();
		if (loop.ready(dte)) {
			int r = dte_.serve(m);
			if (r < 0) {
				ret = -1;
				break;
			}
			if (r > 0)
				break;
		}
	}
	if (m.link_state() != dcore::LinkState::Idle)
		m.hangup_now();
	dte_.flush();
	return ret;
}

} // namespace dmodem_android
