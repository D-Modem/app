/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Neo-Desktop
 *
 *    engine_test.cpp - the app's engine (app/src/main/cpp/engine.h) on the
 *                      build host, against a dmodemd over loopback SIP
 *                      (GoogleTest), each test with a dmodemd of its own:
 *
 *    - dialling out: CONNECT, data both ways, the status going online
 *      with the rates, a hangup;
 *    - stop() and start() again (the SIP user agent made anew), a call;
 *    - a call coming in (listening): RING, ATA, CONNECT; the app closing
 *      its end of the DTE: the engine ends by itself;
 *    - hung up while the call rings.
 *
 *    engine_test [gtest flags] [dmodemd]   (else the build's dmodemd)
 *    ENGINE_TEST_LOG=1: the engine's log as it comes, too
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

#include <chrono>
#include <condition_variable>
#include <mutex>
#include <regex>
#include <string>
#include <thread>

#include <gtest/gtest.h>

#include "engine.h"

using namespace dmodem_android;

namespace {

const int ENGINE_PORT = 5181, DMODEMD_PORT = 5182;

/* the dmodemd the tests call: argv's, else the build's */
const char *dmodemd_path = DMODEMD_PATH;

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

/* the engine's status kept, and the rates once it's online; the waiters woken */
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

/* the engine's exit counted, its result kept; the waiters woken */
void on_engine_exit(int r)
{
	std::lock_guard<std::mutex> lk(mu);
	exits++;
	exit_result = r;
	cv.notify_all();
}

/* whether the engine has exited n times within secs */
bool wait_exits(int n, int secs)
{
	std::unique_lock<std::mutex> lk(mu);
	return cv.wait_for(lk, std::chrono::seconds(secs),
			   [n] { return exits >= n; });
}

/* whether the status is idle and on hook within secs */
bool wait_idle(int secs)
{
	std::unique_lock<std::mutex> lk(mu);
	return cv.wait_for(lk, std::chrono::seconds(secs),
			   [] { return last.link == Status::IDLE &&
					   !last.offhook; });
}

/* text from fd until re matches (the text so far in *got), or secs;
   what came instead in the failure */
testing::AssertionResult expect(int fd, const std::string &re, int secs,
				std::string *got = nullptr)
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
				return testing::AssertionSuccess();
			}
		}
	}
	if (got)
		*got = buf;
	return testing::AssertionFailure() << "waited " << secs << " s for /"
		<< re << "/, got: \"" << buf << "\"";
}

/* s written to fd */
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
	if (m.empty()) {
		kill(pid, SIGTERM);
		waitpid(pid, nullptr, 0);
		return -1;
	}
	*pty = open(m[1].str().c_str(), O_RDWR | O_NOCTTY);
	struct termios t;
	tcgetattr(*pty, &t);
	cfmakeraw(&t);
	tcsetattr(*pty, TCSANOW, &t);
	return pid;
}

/* the engine's settings: user e at dmodemd's loopback port, SIP on ENGINE_PORT, listening or not */
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

/* each test: a dmodemd of its own answering the engine's calls itself
   (ATS0=1), what the engine reported cleared; after it the engine
   stopped, the app's end closed, dmodemd gone (and the engine's log
   shown if the test failed) */
class EngineTest : public testing::Test {
protected:
	Engine &e = Engine::instance();
	int pty = -1, app = -1;
	pid_t dm = -1;

	/* what was reported cleared; a dmodemd of its own, answering (ATS0=1) */
	void SetUp() override
	{
		{
			std::lock_guard<std::mutex> lk(mu);
			last = Status();
			was_online = false;
			online_tx = online_rx = 0;
			exits = 0;
			exit_result = 99;
			spk_level = 0;
			spk_was_on = false;
			spk_samples = 0;
			logs.clear();
		}
		dm = start_dmodemd(dmodemd_path, &pty);
		ASSERT_GT(dm, 0) << "dmodemd (" << dmodemd_path << ") started";
		ASSERT_GE(pty, 0) << "dmodemd's pty opened";
		put(pty, "ATS0=1\r");
		ASSERT_TRUE(expect(pty, "OK", 5)) << "dmodemd: ATS0=1";
	}

	/* the engine stopped, the app's end closed, dmodemd gone; the log if it failed */
	void TearDown() override
	{
		e.stop();
		if (app >= 0)
			close(app);
		if (dm > 0) {
			kill(dm, SIGTERM);
			waitpid(dm, nullptr, 0);
		}
		if (pty >= 0)
			close(pty);
		if (HasFailure()) {
			std::lock_guard<std::mutex> lk(mu);
			printf("--- the engine's log ---\n%s", logs.c_str());
		}
	}

	/* the engine started: its end of the DTE in app */
	void start(bool listen)
	{
		app = e.start(config(listen), on_engine_status, on_engine_exit);
		ASSERT_GE(app, 0) << "the engine started";
		ASSERT_TRUE(e.running());
	}

	/* stop(): the engine ended, its exit the n-th, 0; the app's end closed */
	void stop(int n)
	{
		e.stop();
		EXPECT_TRUE(wait_exits(n, 5)) << "stop(): the engine ended";
		EXPECT_EQ(exit_result, 0);
		EXPECT_FALSE(e.running());
		close(app);
		app = -1;
	}

	/* a call from the engine to dmodemd and back: CONNECT, data both
	   ways, the status online, a hangup from the engine's side */
	void call_out(bool speaker)
	{
		std::string got;
		long heard;

		{
			std::lock_guard<std::mutex> lk(mu);
			was_online = false;
			spk_was_on = false;
			spk_samples = 0;
		}
		/* (ATM1, the modem's own: the speaker on until CONNECT; ATM0 off) */
		put(app, speaker ? "ATX3M1\r" : "ATX3M0\r");
		ASSERT_TRUE(expect(app, "OK", 5)) << "ATX3";
		put(app, "ATD1234\r");
		ASSERT_TRUE(expect(app, "CONNECT[^\r]*", 90, &got))
			<< "the engine's CONNECT";
		printf("   the engine: %s\n",
		       got.substr(got.find("CONNECT")).c_str());
		{
			std::lock_guard<std::mutex> lk(mu);
			heard = spk_samples;
			if (speaker) {
				EXPECT_TRUE(spk_was_on) << "ATM1: the speaker on until CONNECT";
				EXPECT_EQ(spk_level, 0) << "the speaker off at CONNECT";
				EXPECT_GT(heard, 8000) << "a second of the line heard at least";
			} else {
				EXPECT_FALSE(spk_was_on) << "ATM0: the speaker stayed off";
				EXPECT_EQ(heard, 0);
			}
		}
		ASSERT_TRUE(expect(pty, "CONNECT", 30)) << "dmodemd: CONNECT";
		std::this_thread::sleep_for(std::chrono::seconds(1));

		std::string msg;
		for (int i = 0; i < 40; i++)
			msg += "data from the app " + std::to_string(i) + "; ";
		put(app, msg);
		EXPECT_TRUE(expect(pty, std::regex_replace(msg, std::regex("[;]"),
							   "\\;"), 20))
			<< msg.size() << " octets the app wrote reached dmodemd";
		put(pty, "an answer from dmodemd");
		EXPECT_TRUE(expect(app, "an answer from dmodemd", 20))
			<< "dmodemd's data reached the app";
		{
			std::lock_guard<std::mutex> lk(mu);
			EXPECT_EQ(spk_samples, heard) << "nothing to the speaker in data mode";
			EXPECT_TRUE(was_online) << "the status went online";
			EXPECT_NE(online_tx, 0u);
			EXPECT_NE(online_rx, 0u);
		}
		std::this_thread::sleep_for(std::chrono::milliseconds(1200));
		put(app, "+++");
		ASSERT_TRUE(expect(app, "OK", 5)) << "+++";
		put(app, "ATH\r");
		EXPECT_TRUE(expect(app, "OK", 10)) << "ATH";
		EXPECT_TRUE(expect(pty, "NO CARRIER", 20)) << "dmodemd: NO CARRIER";
		EXPECT_TRUE(wait_idle(5)) << "the status idle and on hook again";
	}
};

/* out, the speaker on until CONNECT (ATM1); a second start() refused
   while it runs; SIP's lines through the log hook */
TEST_F(EngineTest, CallsOut)
{
	ASSERT_NO_FATAL_FAILURE(start(false));
	EXPECT_LT(e.start(config(false), on_engine_status, on_engine_exit), 0)
		<< "a second start() refused while it runs";
	ASSERT_NO_FATAL_FAILURE(call_out(true));
	stop(1);
	std::lock_guard<std::mutex> lk(mu);
	EXPECT_NE(logs.find("sip: "), std::string::npos)
		<< "SIP's lines came through the log hook";
}

/* stopped and started again (the SIP user agent made anew): out again,
   the speaker off (ATM0) */
TEST_F(EngineTest, CallsOutAfterARestart)
{
	ASSERT_NO_FATAL_FAILURE(start(false));
	stop(1);
	ASSERT_NO_FATAL_FAILURE(start(false));
	ASSERT_NO_FATAL_FAILURE(call_out(false));
	stop(2);
}

/* in, listening: RING, ATA, CONNECT, data; then the app closes its end
   in the call: the engine ends by itself, the call hung up */
TEST_F(EngineTest, AnswersAndEndsWhenTheAppCloses)
{
	ASSERT_NO_FATAL_FAILURE(start(true));
	std::this_thread::sleep_for(std::chrono::milliseconds(500));
	put(pty, "ATX3D1234\r");
	ASSERT_TRUE(expect(app, "RING", 20)) << "RING at the app";
	put(app, "ATA\r");
	ASSERT_TRUE(expect(app, "CONNECT", 90)) << "CONNECT at the app";
	ASSERT_TRUE(expect(pty, "CONNECT", 30)) << "dmodemd: CONNECT";
	std::this_thread::sleep_for(std::chrono::seconds(1));
	put(pty, "to the app");
	EXPECT_TRUE(expect(app, "to the app", 20)) << "data to the app";

	close(app);
	app = -1;
	EXPECT_TRUE(wait_exits(1, 10)) << "the engine ended by itself";
	EXPECT_EQ(exit_result, 0);
	EXPECT_FALSE(e.running());
	EXPECT_TRUE(expect(pty, "NO CARRIER", 20)) << "dmodemd: NO CARRIER";
}

/* hung up while the call rings (dmodemd not answering): the modem back
   at once, the status idle */
TEST_F(EngineTest, HangsUpWhileRinging)
{
	put(pty, "ATS0=0\r");
	ASSERT_TRUE(expect(pty, "OK", 5)) << "dmodemd: ATS0=0";
	ASSERT_NO_FATAL_FAILURE(start(false));
	put(app, "ATX3D1234\r");
	ASSERT_TRUE(expect(pty, "RING", 30)) << "dmodemd rings";

	auto t0 = std::chrono::steady_clock::now();
	put(app, "ATH\r");
	EXPECT_TRUE(expect(app, "(OK|NO CARRIER)\r\n", 30)) << "ATH answered";
	double secs = std::chrono::duration<double>(
		std::chrono::steady_clock::now() - t0).count();
	EXPECT_LT(secs, 3.0) << "the modem back at once";
	EXPECT_TRUE(wait_idle(3)) << "the status idle and on hook";
}

} // namespace

/* the tests (gtest's flags taken out first), dmodemd at argv[1] if given */
int main(int argc, char *argv[])
{
	testing::InitGoogleTest(&argc, argv);
	if (argc > 2) {
		fprintf(stderr, "usage: engine_test [gtest flags] [dmodemd]\n");
		return 2;
	}
	if (argc == 2)
		dmodemd_path = argv[1];
	signal(SIGPIPE, SIG_IGN);

	Engine &e = Engine::instance();
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
	return RUN_ALL_TESTS();
}
