/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Neo-Desktop
 *
 *    fax_line.cpp - D-Modem-RE's modem_test (two modems back to back on
 *                   an audio pipe, a pty for each one's DTE) paced as a
 *                   phone line is: at the sample clock (MODEM_REALTIME,
 *                   as the interop tests have it), so that a fax
 *                   program's T.30 timing meets the line's (modem_test
 *                   runs as fast as it can once off hook).  The app's
 *                   fax tests' line.
 */

#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

#include <dsplibs.h>
#include <log.h>

#include "options.h"
#include "line_speed.h"
#include "pipe_modem.h"

int main(int argc, char *argv[])
{
	dmd::Options opt;
	int pipe1[2], pipe2[2], ret;

	dcore::log_level = 1;
	dmd::parse_options(argc, argv, opt);
	dcore::log_open("test");
	prop_dp_init();
	if (pipe(pipe1) < 0 || pipe(pipe2) < 0) {
		perror("pipe");
		return 1;
	}
	dmd::PipeModem a("modemA", 9600, opt.country, pipe1[0], pipe2[1]);
	dmd::PipeModem b("modemB", 9600, opt.country, pipe2[0], pipe1[1]);
	/* (unpaced only when asked: MODEM_REALTIME=0) */
	line_speed(a);
	line_speed(b);
	fprintf(stderr, "created modemA: %s\ncreated modemB: %s\n",
		a.pty().name(), b.pty().name());
	dmd::PipeModem *modems[] = { &a, &b };
	ret = dmd::PipeModem::run(modems, 2);
	prop_dp_exit();
	dcore::log_close();
	return ret;
}
