/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 D-Modem Authors
 *
 *    usb_host.cpp - the app's engine on a USB modem's line (engine.h,
 *                   usb_modem.h) as a program, for usb_line_test.py: the
 *                   device D-Modem-RE's fake pico-serial Line stick
 *                   (test/fake_pico_line.cpp, linked in as libusb), its
 *                   line the simulated exchange's at $FAKE_PICO_LINE_SOCKET
 *
 *    The modem's DTE on stdin and stdout; on stderr the log, and
 *    STATUS <link> <offhook> <tx> <rx> and EXIT <result> lines.
 *
 *    usb_host [rate]
 *
 */
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

#include <atomic>

#include "engine.h"

using namespace dmodem_android;

namespace {

std::atomic<bool> exited{ false };

/* what came on from to to, false at from's end (or to's) */
bool pass(int from, int to)
{
	char buf[4096];
	ssize_t n = read(from, buf, sizeof(buf));
	if (n <= 0)
		return false;
	for (ssize_t o = 0; o < n;) {
		ssize_t w = write(to, buf + o, n - o);
		if (w <= 0)
			return false;
		o += w;
	}
	return true;
}

} // namespace

/* the engine on the fake stick at the rate in argv[1] (9600), its DTE between stdin/stdout until it exits */
int main(int argc, char *argv[])
{
	Config cfg;
	Engine &e = Engine::instance();

	signal(SIGPIPE, SIG_IGN);
	setvbuf(stderr, nullptr, _IOLBF, 0);
	cfg.usb_fd = 0;         /* (the fake has its own: any will do) */
	cfg.usb_rate = argc > 1 ? atoi(argv[1]) : 9600;
	cfg.log_level = getenv("USB_LOG") ? atoi(getenv("USB_LOG")) : 1;
	cfg.country = getenv("USB_COUNTRY") ? getenv("USB_COUNTRY") : "";
	int dte = e.start(cfg,
		[](const Status &s) {
			fprintf(stderr, "STATUS %d %d %u %u\n", (int)s.link,
				s.offhook ? 1 : 0, s.tx_rate, s.rx_rate);
		},
		[](int result) {
			fprintf(stderr, "EXIT %d\n", result);
			exited = true;
		});
	if (dte < 0) {
		fprintf(stderr, "EXIT -1\n");
		return 1;
	}
	struct pollfd p[2] = { { 0, POLLIN, 0 }, { dte, POLLIN, 0 } };
	while (!exited) {
		if (poll(p, 2, 200) < 0)
			break;
		if ((p[0].revents & (POLLIN | POLLHUP)) && !pass(0, dte))
			break;
		if ((p[1].revents & (POLLIN | POLLHUP)) && !pass(dte, 1))
			break;
	}
	e.stop();
	close(dte);
	return 0;
}
