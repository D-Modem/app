/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Neo-Desktop
 *
 *    sip_fax_line.cpp - the app's engine (engine.h) and a dmodemd on a
 *                       SIP call over loopback, both with T.38: a fax
 *                       call that goes over to T.38 once the answerer
 *                       (dmodemd, in fax mode) asks, a second after it
 *                       answered.  Each modem's DTE a pty, for the app's
 *                       fax tests (FaxCallTest) to drive class 1 on.
 *
 *    sip_fax_line [--no-t38] [<dmodemd>]   (the one built with it, else)
 *
 *    On stderr: "created modemA: <pty>" (the engine's: it dials),
 *    "created modemB: <pty>" (dmodemd's: it answers), then the engine's
 *    log as it comes, "engine: " before each line.  It runs until
 *    killed.
 */

#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#include <regex>
#include <string>
#include <thread>

#include "engine.h"

using namespace dmodem_android;

namespace {

const int ENGINE_PORT = 5191, DMODEMD_PORT = 5192;

pid_t dmodemd_pid;

/* dmodemd on DMODEMD_PORT, its pty's name in *tty */
pid_t start_dmodemd(const char *path, bool t38, std::string *tty)
{
	int err[2];
	pid_t pid;
	char buf[4096];
	std::string out;

	if (pipe(err) < 0)
		return -1;
	pid = fork();
	if (pid == 0) {
		char login[64], port[16];
		/* (gone with us, however we end) */
		prctl(PR_SET_PDEATHSIG, SIGTERM);
		snprintf(login, sizeof(login), "d:x@127.0.0.1:%d", ENGINE_PORT);
		snprintf(port, sizeof(port), "%d", DMODEMD_PORT);
		setenv("SIP_LOGIN", login, 1);
		setenv("SIP_PORT", port, 1);
		setenv("SIP_LOG_LEVEL", "3", 1);
		setenv("SIP_T38", t38 ? "yes" : "no", 1);
		dup2(err[1], 2);
		close(err[0]);
		execl(path, path, "-n", "-L", "dmodem72", (char *)nullptr);
		_exit(127);
	}
	close(err[1]);
	std::smatch m;
	std::regex re("TTY is `([^']+)'");
	for (int i = 0; i < 50; i++) {
		struct pollfd p = { err[0], POLLIN, 0 };
		if (poll(&p, 1, 200) > 0) {
			ssize_t n = read(err[0], buf, sizeof(buf));
			if (n <= 0)
				break;
			out.append(buf, n);
			if (std::regex_search(out, m, re))
				break;
		}
	}
	/* (its log on from now on, "dmodemd: " before it) */
	std::thread([fd = err[0]] {
		char b[4096];
		ssize_t n;
		while ((n = read(fd, b, sizeof(b))) > 0)
			if (write(2, b, n) < 0)
				break;
		close(fd);
	}).detach();
	if (m.empty())
		return -1;
	*tty = m[1].str();
	return pid;
}

/* a and b joined until either ends */
void relay(int a, int b)
{
	char buf[8192];
	struct pollfd p[2] = { { a, POLLIN, 0 }, { b, POLLIN, 0 } };
	for (;;) {
		if (poll(p, 2, -1) < 0)
			break;
		for (int i = 0; i < 2; i++) {
			if (!(p[i].revents & (POLLIN | POLLHUP | POLLERR)))
				continue;
			ssize_t n = read(p[i].fd, buf, sizeof(buf));
			/* (a pty with nobody on it: EIO until it's opened) */
			if (n <= 0) {
				if (i == 0)
					usleep(20000);
				else
					return;
				continue;
			}
			for (ssize_t o = 0; o < n;) {
				ssize_t w = write(p[1 - i].fd, buf + o, n - o);
				if (w <= 0)
					return;
				o += w;
			}
		}
	}
}

void on_signal(int)
{
	if (dmodemd_pid > 0)
		kill(dmodemd_pid, SIGTERM);
	_exit(0);
}

} // namespace

int main(int argc, char *argv[])
{
	bool t38 = true;
	const char *dmodemd = DMODEMD_PATH;
	std::string tty;

	for (int i = 1; i < argc; i++) {
		if (!strcmp(argv[i], "--no-t38"))
			t38 = false;
		else
			dmodemd = argv[i];
	}
	signal(SIGPIPE, SIG_IGN);
	signal(SIGTERM, on_signal);
	signal(SIGINT, on_signal);
	dmodemd_pid = start_dmodemd(dmodemd, t38, &tty);
	if (dmodemd_pid < 0) {
		fprintf(stderr, "dmodemd didn't start\n");
		return 1;
	}

	Engine &e = Engine::instance();
	e.set_log([](const char *line) { fprintf(stderr, "engine: %s", line); });
	Config c;
	c.user = "e";
	c.password = "x";
	c.domain = "127.0.0.1:" + std::to_string(DMODEMD_PORT);
	c.sip_port = ENGINE_PORT;
	c.listen = false;
	c.dns = "127.0.0.1";
	c.t38 = t38;
	c.log_level = 1;
	c.sip_log_level = 3;
	int dte = e.start(c, [](const Status &) {}, [](int r) {
		fprintf(stderr, "engine: exit %d\n", r);
	});
	if (dte < 0) {
		fprintf(stderr, "the engine didn't start\n");
		on_signal(0);
	}
	/* the engine's DTE on a pty of its own */
	int master = posix_openpt(O_RDWR | O_NOCTTY);
	if (master < 0 || grantpt(master) < 0 || unlockpt(master) < 0) {
		perror("pty");
		on_signal(0);
	}
	struct termios t;
	int slave = open(ptsname(master), O_RDWR | O_NOCTTY);
	tcgetattr(slave, &t);
	cfmakeraw(&t);
	tcsetattr(slave, TCSANOW, &t);
	fprintf(stderr, "created modemA: %s\ncreated modemB: %s\n", ptsname(master), tty.c_str());
	fflush(stderr);
	relay(master, dte);
	close(slave);
	on_signal(0);
	return 0;
}
