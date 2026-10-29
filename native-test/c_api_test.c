/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 D-Modem Authors
 *
 *    c_api_test.c - the engine through its C API (dmodem_engine.h), as a
 *                   program in C or Swift (iOS) has it: on a peer line,
 *                   AT commands answered, a dial handing the program
 *                   its line, the program's BUSY, the status and the
 *                   speaker reported, the modem ended by closing its
 *                   DTE.
 *
 */

#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include "dmodem_engine.h"

static int failures;

#define CHECK(cond, ...) do { \
	if (cond) { printf("ok: " __VA_ARGS__); printf("\n"); } \
	else { printf("FAIL (%s:%d): ", __FILE__, __LINE__); \
	       printf(__VA_ARGS__); printf("\n"); failures++; } } while (0)

static pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER;
static int line_fd = -1, line_caller = -1, exits = 0, exit_result = 99;
static int statuses, speaker_on, logs;
static char line_dial[64];

/* a log line counted */
static void on_log(void *ctx, const char *l) { (void)ctx; (void)l; __sync_fetch_and_add(&logs, 1); }
/* a status report counted */
static void on_status(void *ctx, int link, int offhook, unsigned tx, unsigned rx)
{
	(void)ctx; (void)link; (void)offhook; (void)tx; (void)rx;
	__sync_fetch_and_add(&statuses, 1);
}
/* the modem's exit counted, its result kept */
static void on_exit_(void *ctx, int r)
{
	(void)ctx;
	pthread_mutex_lock(&mu);
	exits++;
	exit_result = r;
	pthread_mutex_unlock(&mu);
}
/* a peer line's fd, which end and the number dialled kept */
static void on_line(void *ctx, int caller, const char *dial, int fd)
{
	(void)ctx;
	pthread_mutex_lock(&mu);
	line_fd = fd;
	line_caller = caller;
	snprintf(line_dial, sizeof(line_dial), "%s", dial);
	pthread_mutex_unlock(&mu);
}
/* the speaker seen on */
static void on_speaker(void *ctx, int level) { (void)ctx; if (level > 0) speaker_on = 1; }

/* text from fd until want (or secs) */
static int expect(int fd, const char *want, int secs)
{
	char buf[4096];
	size_t have = 0;
	for (int i = 0; i < secs * 10; i++) {
		struct pollfd p = { fd, POLLIN, 0 };
		if (poll(&p, 1, 100) > 0) {
			ssize_t n = read(fd, buf + have, sizeof(buf) - 1 - have);
			if (n <= 0)
				break;
			have += (size_t)n;
			buf[have] = 0;
			if (strstr(buf, want))
				return 1;
		}
	}
	buf[have] = 0;
	printf("   (waited for \"%s\", got \"%s\")\n", want, buf);
	return 0;
}

/* s written to fd */
static void put(int fd, const char *s)
{
	if (write(fd, s, strlen(s)) < 0)
		perror("write");
}

/* the engine on a peer line through its C API, each step checked: 1 if any failed */
int main(void)
{
	dmodem_config c;
	dmodem_callbacks cb = { 0 };
	int dte, fd = -1;

	signal(SIGPIPE, SIG_IGN);
	cb.log = on_log;
	cb.status = on_status;
	cb.exited = on_exit_;
	cb.line = on_line;
	cb.speaker = on_speaker;
	dmodem_set_callbacks(&cb);
	dmodem_config_init(&c);
	CHECK(c.sip_port == 5060 && c.t38 && c.peer_lead_ms == 100, "the defaults");
	c.peer = 1;
	c.log_level = 1;
	dte = dmodem_start(&c);
	CHECK(dte >= 0 && dmodem_running(), "started on a peer line");
	CHECK(dmodem_start(&c) < 0, "a second start refused");

	put(dte, "ATE0\r");
	CHECK(expect(dte, "OK", 5), "ATE0: OK");
	put(dte, "ATI3\r");
	CHECK(expect(dte, "peer", 5), "ATI3: the peer line's driver");

	/* a dial: the line handed over; the program says BUSY */
	put(dte, "ATX3D5551234\r");
	for (int i = 0; i < 50 && fd < 0; i++) {
		usleep(100000);
		pthread_mutex_lock(&mu);
		fd = line_fd;
		pthread_mutex_unlock(&mu);
	}
	CHECK(fd >= 0 && line_caller == 1 && strstr(line_dial, "5551234"),
	      "ATD: the line to the program (dialling %s)", line_dial);
	CHECK(speaker_on, "the speaker on as it dials (ATM1)");
	dmodem_peer_event("FAILED 486");
	if (fd >= 0)
		close(fd);
	CHECK(expect(dte, "BUSY", 10), "the program's FAILED 486: BUSY");

	/* no line at all (the app's "no line"): NO DIALTONE */
	pthread_mutex_lock(&mu);
	line_fd = -1;
	pthread_mutex_unlock(&mu);
	fd = -1;
	put(dte, "ATD1\r");
	for (int i = 0; i < 50 && fd < 0; i++) {
		usleep(100000);
		pthread_mutex_lock(&mu);
		fd = line_fd;
		pthread_mutex_unlock(&mu);
	}
	CHECK(fd >= 0, "ATD again: the line to the program");
	dmodem_peer_event("NO LINE");
	if (fd >= 0)
		close(fd);
	CHECK(expect(dte, "NO DIALTONE", 10), "the program's NO LINE: NO DIALTONE");
	CHECK(statuses > 0 && logs > 0, "status (%d) and log (%d) reported", statuses, logs);

	/* the DTE closed: the modem ends */
	close(dte);
	for (int i = 0; i < 50 && dmodem_running(); i++)
		usleep(100000);
	pthread_mutex_lock(&mu);
	CHECK(!dmodem_running() && exits == 1 && exit_result == 0,
	      "the DTE closed: the modem ended (%d)", exit_result);
	pthread_mutex_unlock(&mu);
	dmodem_stop();
	printf(failures ? "FAIL: %d\n" : "PASS\n", failures);
	return failures ? 1 : 0;
}
