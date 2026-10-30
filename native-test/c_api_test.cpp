/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Neo-Desktop
 *
 *    c_api_test.cpp - the engine through its C API (dmodem_engine.h), as a
 *                     program in C or Swift (iOS) has it (GoogleTest): on
 *                     a peer line, AT commands answered, a dial handing
 *                     the program its line, the program's BUSY and NO
 *                     LINE, the status and the speaker reported, the
 *                     modem ended by closing its DTE.  (The header as C
 *                     compiles too: c_api_header.c.)
 *
 */

#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include <string>

#include <gtest/gtest.h>

#include "dmodem_engine.h"

/* (c_api_header.c's: the header's types and calls from C) */
extern "C" int c_api_header_from_c(void);

namespace {

pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER;
int line_fd = -1, line_caller = -1, exits = 0, exit_result = 99;
int statuses, speaker_on, logs;
char line_dial[64];

/* a log line counted */
void on_log(void *, const char *) { __sync_fetch_and_add(&logs, 1); }
/* a status report counted */
void on_status(void *, int, int, unsigned, unsigned)
{
	__sync_fetch_and_add(&statuses, 1);
}
/* the modem's exit counted, its result kept */
void on_exit_(void *, int r)
{
	pthread_mutex_lock(&mu);
	exits++;
	exit_result = r;
	pthread_mutex_unlock(&mu);
}
/* a peer line's fd, which end and the number dialled kept */
void on_line(void *, int caller, const char *dial, int fd)
{
	pthread_mutex_lock(&mu);
	line_fd = fd;
	line_caller = caller;
	snprintf(line_dial, sizeof(line_dial), "%s", dial);
	pthread_mutex_unlock(&mu);
}
/* the speaker seen on */
void on_speaker(void *, int level) { if (level > 0) speaker_on = 1; }

/* text from fd until want (or secs); what came instead in the failure */
testing::AssertionResult expect(int fd, const char *want, int secs)
{
	char buf[4096];
	size_t have = 0;
	buf[0] = 0;
	for (int i = 0; i < secs * 10; i++) {
		struct pollfd p = { fd, POLLIN, 0 };
		if (poll(&p, 1, 100) > 0) {
			ssize_t n = read(fd, buf + have, sizeof(buf) - 1 - have);
			if (n <= 0)
				break;
			have += (size_t)n;
			buf[have] = 0;
			if (strstr(buf, want))
				return testing::AssertionSuccess();
		}
	}
	return testing::AssertionFailure() << "waited " << secs << " s for \""
		<< want << "\", got \"" << buf << "\"";
}

/* s written to fd */
void put(int fd, const char *s)
{
	if (write(fd, s, strlen(s)) < 0)
		perror("write");
}

/* the line the program was handed within 5 s, -1 if none */
int wait_line()
{
	int fd = -1;
	for (int i = 0; i < 50 && fd < 0; i++) {
		usleep(100000);
		pthread_mutex_lock(&mu);
		fd = line_fd;
		pthread_mutex_unlock(&mu);
	}
	return fd;
}

/* dmodem_config_init(): SIP on 5060, T.38 on, a peer line's lead 100 ms */
TEST(CApi, Defaults)
{
	dmodem_config c;
	dmodem_config_init(&c);
	EXPECT_EQ(c.sip_port, 5060);
	EXPECT_TRUE(c.t38);
	EXPECT_EQ(c.peer_lead_ms, 100);
}

/* the header's types and calls from C (c_api_header.c) */
TEST(CApi, HeaderAsC)
{
	EXPECT_EQ(c_api_header_from_c(), 0);
}

/* each test: the engine started on a peer line (its DTE in dte), what it
   reported cleared; after it the DTE closed and the modem stopped */
class CApiTest : public testing::Test {
protected:
	dmodem_config c;
	int dte = -1;

	/* the callbacks, once for the suite */
	static void SetUpTestSuite()
	{
		dmodem_callbacks cb = {};
		cb.log = on_log;
		cb.status = on_status;
		cb.exited = on_exit_;
		cb.line = on_line;
		cb.speaker = on_speaker;
		dmodem_set_callbacks(&cb);
	}

	/* what was reported cleared; the engine started on a peer line */
	void SetUp() override
	{
		pthread_mutex_lock(&mu);
		line_fd = line_caller = -1;
		line_dial[0] = 0;
		exits = 0;
		exit_result = 99;
		pthread_mutex_unlock(&mu);
		statuses = speaker_on = logs = 0;

		dmodem_config_init(&c);
		c.peer = 1;
		c.log_level = 1;
		dte = dmodem_start(&c);
		ASSERT_GE(dte, 0) << "started on a peer line";
		ASSERT_TRUE(dmodem_running());
	}

	/* the DTE closed, the modem ended and stopped */
	void TearDown() override
	{
		if (dte >= 0)
			close(dte);
		for (int i = 0; i < 50 && dmodem_running(); i++)
			usleep(100000);
		dmodem_stop();
	}
};

/* a second start refused while it runs */
TEST_F(CApiTest, SecondStartRefused)
{
	EXPECT_LT(dmodem_start(&c), 0);
}

/* ATE0: OK; ATI3: the peer line's driver */
TEST_F(CApiTest, AnswersAtCommands)
{
	put(dte, "ATE0\r");
	EXPECT_TRUE(expect(dte, "OK", 5)) << "ATE0";
	put(dte, "ATI3\r");
	EXPECT_TRUE(expect(dte, "peer", 5)) << "ATI3: the peer line's driver";
}

/* a dial: the line handed over, the speaker on (ATM1); the program says
   BUSY (FAILED 486) */
TEST_F(CApiTest, DialHandsTheLineOverAndTheProgramSaysBusy)
{
	put(dte, "ATX3D5551234\r");
	int fd = wait_line();
	ASSERT_GE(fd, 0) << "ATD: the line to the program";
	EXPECT_EQ(line_caller, 1);
	EXPECT_NE(strstr(line_dial, "5551234"), nullptr) << "dialling " << line_dial;
	EXPECT_TRUE(speaker_on) << "the speaker on as it dials (ATM1)";
	dmodem_peer_event("FAILED 486");
	close(fd);
	EXPECT_TRUE(expect(dte, "BUSY", 10)) << "the program's FAILED 486";
}

/* no line at all (the app's "no line"): NO DIALTONE; status and log
   reported on the way */
TEST_F(CApiTest, NoLineIsNoDialtone)
{
	put(dte, "ATD1\r");
	int fd = wait_line();
	ASSERT_GE(fd, 0) << "ATD: the line to the program";
	dmodem_peer_event("NO LINE");
	close(fd);
	EXPECT_TRUE(expect(dte, "NO DIALTONE", 10)) << "the program's NO LINE";
	EXPECT_GT(statuses, 0) << "status reported";
	EXPECT_GT(logs, 0) << "log reported";
}

/* the DTE closed: the modem ends, 0 */
TEST_F(CApiTest, EndsWhenTheDteCloses)
{
	close(dte);
	dte = -1;
	for (int i = 0; i < 50 && dmodem_running(); i++)
		usleep(100000);
	EXPECT_FALSE(dmodem_running());
	pthread_mutex_lock(&mu);
	EXPECT_EQ(exits, 1);
	EXPECT_EQ(exit_result, 0);
	pthread_mutex_unlock(&mu);
}

} // namespace

/* the tests (SIGPIPE ignored: the line's fd closed under the modem) */
int main(int argc, char *argv[])
{
	signal(SIGPIPE, SIG_IGN);
	testing::InitGoogleTest(&argc, argv);
	return RUN_ALL_TESTS();
}
