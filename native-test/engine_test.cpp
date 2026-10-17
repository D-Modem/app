/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Neo-Desktop
 *
 *    engine_test.cpp - the app's engine (app/src/main/cpp/engine.h) on the
 *                      build host, against a dmodemd over loopback SIP:
 *
 *    - dialling out: CONNECT, data both ways, the status going online
 *      with the rates, a hangup;
 *    - stop() and start() again (the SIP user agent made anew), a second
 *      call;
 *    - a call coming in (listening): RING, ATA, CONNECT;
 *    - the app closing its end of the DTE: the engine ends by itself.
 *
 *    engine_test <dmodemd>
 *
 */

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <regex>
#include <string>
#include <thread>

#include "engine.h"

using namespace dmodem_android;

namespace {

int failures;

#define CHECK(cond, ...) do { \
	if (cond) { \
		printf("ok: " __VA_ARGS__); printf("\n"); \
	} else { \
		printf("FAIL (%s:%d): ", __FILE__, __LINE__); \
		printf(__VA_ARGS__); printf("\n"); failures++; \
	} } while (0)

const int ENGINE_PORT = 5181, DMODEMD_PORT = 5182;

/* what the engine reported */
std::mutex mu;
std::condition_variable cv;
Status last;
bool was_online;
unsigned online_tx, online_rx;
int exits, exit_result;
/* the speaker: its level now, whether it came on, its samples */
int spk_level;
bool spk_was_on;
long spk_samples;
std::string logs;

void on_engine_status(const Status &s)
{
	std::lock_guard<std::mutex> lk(mu);
	last = s;
	if (s.link == Status::ONLINE) {
		was_online = true;
		online_tx = s.tx_rate;
		online_rx = s.rx_rate;
	}
	cv.notify_all();
}

void on_engine_exit(int r)
{
	std::lock_guard<std::mutex> lk(mu);
	exits++;
	exit_result = r;
	cv.notify_all();
}

bool wait_exits(int n, int secs)
{
	std::unique_lock<std::mutex> lk(mu);
	return cv.wait_for(lk, std::chrono::seconds(secs),
			   [n] { return exits >= n; });
}

/* text from fd until re matches (the text so far in *got), or secs */
bool expect(int fd, const char *re, int secs, std::string *got = nullptr)
{
	std::string buf;
	std::regex r(re);
	auto end = std::chrono::steady_clock::now() + std::chrono::seconds(secs);

	while (std::chrono::steady_clock::now() < end) {
		struct pollfd p = { fd, POLLIN, 0 };
		if (poll(&p, 1, 200) > 0) {
			char c[4096];
			ssize_t n = read(fd, c, sizeof(c));
			if (n <= 0)
				break;
			buf.append(c, n);
			if (std::regex_search(buf, r)) {
				if (got)
					*got = buf;
				return true;
			}
		}
	}
	if (got)
		*got = buf;
	printf("   (waited for /%s/, got: \"%s\")\n", re, buf.c_str());
	return false;
}

void put(int fd, const std::string &s)
{
	if (write(fd, s.data(), s.size()) != (ssize_t)s.size())
		perror("write");
}

/* dmodemd on DMODEMD_PORT, its domain the engine's: its pid, its pty
   opened raw into *pty */
pid_t start_dmodemd(const char *path, int *pty)
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
		snprintf(login, sizeof(login), "d:x@127.0.0.1:%d", ENGINE_PORT);
		snprintf(port, sizeof(port), "%d", DMODEMD_PORT);
		setenv("SIP_LOGIN", login, 1);
		setenv("SIP_PORT", port, 1);
		setenv("SIP_LOG_LEVEL", "1", 1);
		dup2(err[1], 2);
		close(err[0]);
		execl(path, path, "-n", "-L", "dmodem71", (char *)nullptr);
		_exit(127);
	}
	close(err[1]);
	std::smatch m;
	std::regex tty("TTY is `([^']+)'");
	for (int i = 0; i < 50; i++) {
		struct pollfd p = { err[0], POLLIN, 0 };
		if (poll(&p, 1, 200) > 0) {
			ssize_t n = read(err[0], buf, sizeof(buf));
			if (n <= 0)
				break;
			out.append(buf, n);
			if (std::regex_search(out, m, tty))
				break;
		}
	}
	/* (its stderr from now on to nowhere: it keeps writing) */
	std::thread([fd = err[0]] {
		char b[4096];
		while (read(fd, b, sizeof(b)) > 0)
			;
		close(fd);
	}).detach();
	if (m.empty())
		return -1;
	*pty = open(m[1].str().c_str(), O_RDWR | O_NOCTTY);
	struct termios t;
	tcgetattr(*pty, &t);
	cfmakeraw(&t);
	tcsetattr(*pty, TCSANOW, &t);
	return pid;
}

Config config(bool listen)
{
	Config c;
	c.user = "e";
	c.password = "x";
	c.domain = "127.0.0.1:" + std::to_string(DMODEMD_PORT);
	c.sip_port = ENGINE_PORT;
	c.listen = listen;
	c.dns = "127.0.0.1";
	c.log_level = 1;
	c.sip_log_level = 3;
	return c;
}

/* a call from the engine to dmodemd and back: CONNECT, data both ways,
   the status online, a hangup from the engine's side */
void call_out(int app, int pty, const char *what, bool speaker)
{
	std::string got;
	long heard;

	{
		std::lock_guard<std::mutex> lk(mu);
		was_online = false;
	}
	{
		std::lock_guard<std::mutex> lk(mu);
		spk_was_on = false;
		spk_samples = 0;
	}
	/* (ATM1, the modem's own: the speaker on until CONNECT; ATM0 off) */
	put(app, speaker ? "ATX3M1\r" : "ATX3M0\r");
	CHECK(expect(app, "OK", 5), "%s: ATX3 OK", what);
	put(app, "ATD1234\r");
	CHECK(expect(app, "CONNECT[^\r]*", 90, &got), "%s: the engine: %s",
	      what, got.substr(got.find("CONNECT") == std::string::npos ? 0 :
				got.find("CONNECT")).c_str());
	{
		std::lock_guard<std::mutex> lk(mu);
		heard = spk_samples;
		if (speaker)
			CHECK(spk_was_on && spk_level == 0 && heard > 8000,
			      "%s: the speaker on until CONNECT (%.1f s of the "
			      "line heard), off then", what, heard / 8000.0);
		else
			CHECK(!spk_was_on && heard == 0,
			      "%s: ATM0: the speaker stayed off", what);
	}
	CHECK(expect(pty, "CONNECT", 30), "%s: dmodemd: CONNECT", what);
	std::this_thread::sleep_for(std::chrono::seconds(1));
	std::string msg;
	for (int i = 0; i < 40; i++)
		msg += "data from the app " + std::to_string(i) + "; ";
	put(app, msg);
	CHECK(expect(pty, std::regex_replace(msg, std::regex("[;]"),
					     "\\;").c_str(), 20),
	      "%s: %zu octets the app wrote reached dmodemd", what, msg.size());
	put(pty, "an answer from dmodemd");
	CHECK(expect(app, "an answer from dmodemd", 20),
	      "%s: dmodemd's data reached the app", what);
	{
		std::lock_guard<std::mutex> lk(mu);
		CHECK(spk_samples == heard,
		      "%s: nothing to the speaker in data mode", what);
	}
	{
		std::lock_guard<std::mutex> lk(mu);
		CHECK(was_online && online_tx && online_rx,
		      "%s: the status went online at %u/%u bit/s", what,
		      online_tx, online_rx);
	}
	std::this_thread::sleep_for(std::chrono::milliseconds(1200));
	put(app, "+++");
	CHECK(expect(app, "OK", 5), "%s: +++ OK", what);
	put(app, "ATH\r");
	CHECK(expect(app, "OK", 10), "%s: ATH OK", what);
	CHECK(expect(pty, "NO CARRIER", 20), "%s: dmodemd: NO CARRIER", what);
	std::unique_lock<std::mutex> lk(mu);
	CHECK(cv.wait_for(lk, std::chrono::seconds(5),
			  [] { return last.link == Status::IDLE &&
				       !last.offhook; }),
	      "%s: the status idle and on hook again", what);
}

} // namespace

int main(int argc, char *argv[])
{
	int pty = -1, app;
	pid_t dm;

	if (argc != 2) {
		fprintf(stderr, "usage: engine_test <dmodemd>\n");
		return 2;
	}
	signal(SIGPIPE, SIG_IGN);
	Engine &e = Engine::instance();
	/* ENGINE_TEST_LOG: the engine's log as it comes, too */
	static bool live = getenv("ENGINE_TEST_LOG") != nullptr;
	e.set_log([](const char *line) {
		std::lock_guard<std::mutex> lk(mu);
		logs += line;
		if (live)
			printf("   | %s", line);
	});

	e.set_speaker(
		[](int level) {
			std::lock_guard<std::mutex> lk(mu);
			spk_level = level;
			if (level > 0)
				spk_was_on = true;
		},
		[](const int16_t *, int n) {
			std::lock_guard<std::mutex> lk(mu);
			spk_samples += n;
		});

	dm = start_dmodemd(argv[1], &pty);
	CHECK(dm > 0 && pty >= 0, "dmodemd started");
	if (dm <= 0)
		return 1;
	/* it answers the engine's calls itself */
	put(pty, "ATS0=1\r");
	expect(pty, "OK", 5);

	/* 1: out */
	app = e.start(config(false), on_engine_status, on_engine_exit);
	CHECK(app >= 0 && e.running(), "the engine started");
	CHECK(e.start(config(false), on_engine_status, on_engine_exit) < 0,
	      "a second start() refused while it runs");
	call_out(app, pty, "call 1", true);
	e.stop();
	CHECK(wait_exits(1, 5) && exit_result == 0 && !e.running(),
	      "stop(): the engine ended (%d)", exit_result);
	close(app);

	/* 2: started again, out again */
	app = e.start(config(false), on_engine_status, on_engine_exit);
	CHECK(app >= 0, "the engine started again");
	call_out(app, pty, "call 2", false);
	e.stop();
	CHECK(wait_exits(2, 5) && exit_result == 0, "stop() again");
	close(app);

	/* 3: in */
	app = e.start(config(true), on_engine_status, on_engine_exit);
	CHECK(app >= 0, "the engine started, listening");
	std::this_thread::sleep_for(std::chrono::milliseconds(500));
	put(pty, "ATX3D1234\r");
	CHECK(expect(app, "RING", 20), "call 3: RING at the app");
	put(app, "ATA\r");
	CHECK(expect(app, "CONNECT", 90), "call 3: CONNECT at the app");
	CHECK(expect(pty, "CONNECT", 30), "call 3: dmodemd: CONNECT");
	std::this_thread::sleep_for(std::chrono::seconds(1));
	put(pty, "to the app");
	CHECK(expect(app, "to the app", 20), "call 3: data to the app");

	/* 4: the app closes its end in the call */
	close(app);
	CHECK(wait_exits(3, 10) && exit_result == 0 && !e.running(),
	      "the app's end closed: the engine ended by itself (%d)",
	      exit_result);
	CHECK(expect(pty, "NO CARRIER", 20), "call 3: dmodemd: NO CARRIER");
	e.stop();

	{
		std::lock_guard<std::mutex> lk(mu);
		CHECK(logs.find("sip: ") != std::string::npos,
		      "SIP's lines came through the log hook");
		if (failures)
			printf("--- the engine's log ---\n%s", logs.c_str());
	}
	kill(dm, SIGTERM);
	waitpid(dm, nullptr, 0);
	printf(failures ? "FAIL: %d\n" : "PASS\n", failures);
	return failures ? 1 : 0;
}
