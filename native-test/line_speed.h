/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Neo-Desktop
 *
 *    line_speed.h - how fast the test lines run (fax_line, codec_line,
 *                   t30_line): at the sample clock, as a phone line does,
 *                   or LINE_SPEED=<x> times it (the tests' shorter
 *                   calls: the DTE's timing, a fax program's T.30, then
 *                   x times as slow beside the line's); MODEM_REALTIME=0
 *                   as fast as it goes
 */

#ifndef LINE_SPEED_H
#define LINE_SPEED_H

#include <stdlib.h>

#include "pipe_modem.h"

/* m's clock as the environment asks: the sample clock, LINE_SPEED times as fast, or (MODEM_REALTIME=0) as fast as it goes */
static inline void line_speed(dmd::PipeModem &m)
{
	const char *s = getenv("LINE_SPEED");
	double x = s ? atof(s) : 1;
	if (getenv("MODEM_REALTIME") && !atoi(getenv("MODEM_REALTIME"))) {
		m.set_realtime(false);
		m.set_speed(0);
	} else if (x > 1) {
		m.set_realtime(false);
		m.set_speed(x);
	} else {
		m.set_realtime(true);
	}
}

#endif
