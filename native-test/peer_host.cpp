/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Neo-Desktop
 *
 *    peer_host.cpp - the app's engine on a peer line (engine.h,
 *                    peer_modem.h) as a program, for the app's tests of
 *                    a link between two phones (PeerLineTest): the
 *                    test's Kotlin is the app's side of each one.
 *
 *    On stdout, one line each:
 *      DTE <port>              the modem's DTE: connect to it on
 *                              127.0.0.1 (one connection)
 *      LINE <caller> <port> <dial>
 *                              off hook (caller 1: dialling, 0:
 *                              answering): the line's samples on a
 *                              connection to 127.0.0.1:<port>
 *      STATUS <link> <offhook> <tx> <rx>
 *      SPEAKER <level> <samples heard so far>
 *      EXIT <result>
 *    On stdin, the app's lines to the modem (RING, CANCEL, ANSWERED,
 *    FAILED <code>, DROPPED).  The log on stderr.
 *
 *    peer_host [lead_ms]
 *
 */

#include <arpa/inet.h>
#include <netinet/in.h>
#include <poll.h>
#include <stdarg.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include <mutex>
#include <string>
#include <thread>

#include "engine.h"

using namespace dmodem_android;

namespace {

std::mutex out_mu;

void say(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
/* a line (printf's) on stdout, flushed, one writer at a time */
void say(const char *fmt, ...)
{
	std::lock_guard<std::mutex> lk(out_mu);
	va_list ap;
	va_start(ap, fmt);
	vprintf(fmt, ap);
	va_end(ap);
	fflush(stdout);
}

/* a listening socket on 127.0.0.1, its port in *port */
int listen_local(int *port)
{
	int s = socket(AF_INET, SOCK_STREAM, 0);
	struct sockaddr_in a = {};
	socklen_t len = sizeof(a);
	a.sin_family = AF_INET;
	a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
	if (s < 0 || bind(s, (struct sockaddr *)&a, sizeof(a)) < 0 ||
	    listen(s, 1) < 0 ||
	    getsockname(s, (struct sockaddr *)&a, &len) < 0) {
		perror("listen");
		exit(1);
	}
	*port = ntohs(a.sin_port);
	return s;
}

/* a and b joined until either ends; both closed */
void relay(int a, int b)
{
	char buf[8192];
	struct pollfd p[2] = { { a, POLLIN, 0 }, { b, POLLIN, 0 } };
	for (;;) {
		if (poll(p, 2, -1) < 0)
			break;
		bool done = false;
		for (int i = 0; i < 2; i++) {
			if (!(p[i].revents & (POLLIN | POLLHUP | POLLERR)))
				continue;
			ssize_t n = read(p[i].fd, buf, sizeof(buf));
			if (n <= 0) {
				done = true;
				break;
			}
			int to = p[1 - i].fd;
			for (ssize_t o = 0; o < n;) {
				ssize_t w = send(to, buf + o, n - o,
						 MSG_NOSIGNAL);
				if (w <= 0) {
					done = true;
					break;
				}
				o += w;
			}
		}
		if (done)
			break;
	}
	close(a);
	close(b);
}

/* the fd offered on a port of its own, joined to who connects */
void offer(int fd, std::string what, std::string after = "")
{
	int port, s = listen_local(&port);
	say("%s %d%s\n", what.c_str(), port, after.c_str());
	std::thread([s, fd] {
		int c = accept(s, nullptr, nullptr);
		close(s);
		if (c < 0) {
			close(fd);
			return;
		}
		relay(c, fd);
	}).detach();
}

} // namespace

/* the engine on a peer line, its DTE and lines offered on ports and its news on stdout, the app's lines from stdin */
int main(int argc, char *argv[])
{
	Config cfg;
	Engine &e = Engine::instance();

	signal(SIGPIPE, SIG_IGN);
	cfg.peer = true;
	cfg.peer_lead_ms = argc > 1 ? atoi(argv[1]) : 100;
	cfg.log_level = getenv("PEER_LOG") ? atoi(getenv("PEER_LOG")) : 1;
	/* the speaker: its level, the samples heard; PEER_SPEAKER=<file>:
	   the sound too, raw */
	static FILE *sound = getenv("PEER_SPEAKER") ?
		fopen(getenv("PEER_SPEAKER"), "wb") : nullptr;
	static long heard;
	e.set_speaker(
		[](int level) {
			say("SPEAKER %d %ld\n", level, heard);
		},
		[](const int16_t *pcm, int n) {
			heard += n;
			if (sound) {
				fwrite(pcm, 2, n, sound);
				fflush(sound);
			}
		});
	int dte = e.start(cfg,
		[](const Status &s) {
			say("STATUS %d %d %u %u\n", (int)s.link, s.offhook,
			    s.tx_rate, s.rx_rate);
		},
		[](int r) {
			say("EXIT %d\n", r);
			_exit(0);
		},
		[](bool caller, const char *dial, int fd) {
			offer(fd, std::string("LINE ") + (caller ? "1" : "0"),
			      std::string(" ") + dial);
		});
	if (dte < 0) {
		fprintf(stderr, "the engine didn't start\n");
		return 1;
	}
	offer(dte, "DTE");
	char line[256];
	while (fgets(line, sizeof(line), stdin)) {
		line[strcspn(line, "\r\n")] = 0;
		e.peer_event(line);
	}
	e.stop();
	return 0;
}
