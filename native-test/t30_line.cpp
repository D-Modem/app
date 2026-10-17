/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Neo-Desktop
 *
 *    t30_line.cpp - a class 1 modem (D-Modem-RE's PipeModem, a pty for
 *                   its DTE) on a phone line to D-Modem-RE's own T.30
 *                   fax machine (dsp/t30.cpp), with or without error
 *                   correction mode, noise on the line if asked: the
 *                   app's fax (its T.30 on the DTE) against another
 *                   machine's (FaxCallTest).
 *
 *    t30_line send|receive <dir> [--no-ecm] [--snr <dB>] [--bps <max>]
 *      send:    the machine calls and sends two pages, written first
 *               as <dir>/sent1.pbm and sent2.pbm; the DTE answers (ATA)
 *      receive: the machine answers and receives, each page as
 *               <dir>/got<n>.pbm; the DTE dials (ATD)
 *
 *    On stderr "created modemA: <pty>", the log; on stdout, the call
 *    over: "RESULT <ok 1|0> pages <n> bps <rate> ecm <1|0> <why>".
 */

#include <math.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <random>
#include <string>
#include <thread>

#include <dsplibs.h>
#include <log.h>
#include <t30.h>
#include <t4.h>

#include "options.h"
#include "pipe_modem.h"

using namespace dsp;

namespace {

const int FRAME = 160;
const int WIDTH = 1728;

std::string dir;
int pages_sent = 0;
int pages_got = 0;

/* a page as PBM (P4: rows of 1 = black, the first pel in the top bit) */
void write_pbm(const std::string &path, const T4Page *p)
{
	FILE *f = fopen(path.c_str(), "wb");
	if (!f)
		return;
	fprintf(f, "P4\n%d %d\n", p->width, p->rows);
	fwrite(p->bits, T4_STRIDE(p->width), p->rows, f);
	fclose(f);
}

/* the machine's pages: bars, a frame, and noise (big in MH: blocks) */
int next_page(void *, T4Page *p)
{
	if (pages_sent == 2)
		return 0;
	int n = ++pages_sent;
	int rows = n == 1 ? 900 : 400;
	std::mt19937 r(n);
	unsigned char row[WIDTH / 8];
	if (p->init(WIDTH, 1) < 0)
		return 0;
	for (int y = 0; y < rows; y++) {
		memset(row, 0, sizeof(row));
		for (int x = 0; x < WIDTH; x++) {
			bool on;
			if (x < 4 || x >= WIDTH - 4 || y < 4 || y >= rows - 4)
				on = true;
			else if (y < 200)
				on = (x / (y % 9 + 1)) % 2 == 0;
			else if (n == 1 && y < 700)
				on = (r() & 3) == 0;
			else
				on = (x / 6 + y / 10) % 7 == 0;
			if (on)
				row[x >> 3] |= 0x80 >> (x & 7);
		}
		p->add_row(row);
	}
	write_pbm(dir + "/sent" + std::to_string(n) + ".pbm", p);
	return 1;
}

void page_received(void *, T4Page *p)
{
	write_pbm(dir + "/got" + std::to_string(++pages_got) + ".pbm", p);
}

/* the machine on the line: each of the modem's samples answered with
   one of its own (noise added both ways), until the call is over */
void machine(int in, int out, struct t30_config cfg, double snr)
{
	T30 *t = t30_create(&cfg);
	short a[FRAME], b[FRAME];
	double sigma = snr > 0 ? 3000 / pow(10, snr / 20) : 0;
	std::mt19937 r(5);
	std::normal_distribution<double> g(0, 1);
	struct t30_status s = {};
	size_t have = 0;
	for (;;) {
		ssize_t n = read(in, (char *)a + have, sizeof(a) - have);
		if (n <= 0)
			break;
		have += n;
		int count = (int)(have / 2);
		if (count == 0)
			continue;
		for (int i = 0; i < count && sigma > 0; i++)
			a[i] = (short)fmax(-32768, fmin(32767, a[i] + sigma * g(r)));
		t30_process(t, a, b, count);
		for (int i = 0; i < count && sigma > 0; i++)
			b[i] = (short)fmax(-32768, fmin(32767, b[i] + sigma * g(r)));
		if (write(out, b, 2 * count) != 2 * count)
			break;
		/* (an odd octet kept for the next) */
		if (have & 1)
			((char *)a)[0] = ((char *)a)[have - 1];
		have &= 1;
		t30_get_status(t, &s);
		if (s.done)
			break;
	}
	t30_get_status(t, &s);
	printf("RESULT %d pages %d bps %d ecm %d %s\n",
	       s.result == T30_OK || s.complete, s.pages, s.bps, s.ecm,
	       t30_result_text(s.result));
	fflush(stdout);
	t30_free(t);
	/* (the modem stays on the line a while: the DTE sees the end) */
	sleep(2);
	dmd::PipeModem::stop();
}

} // namespace

int main(int argc, char *argv[])
{
	dmd::Options opt;
	int to_modem[2], from_modem[2], ret;
	struct t30_config cfg = {};
	double snr = 0;

	if (argc < 3 || (strcmp(argv[1], "send") && strcmp(argv[1], "receive"))) {
		fprintf(stderr, "t30_line send|receive <dir> [--no-ecm] [--snr <dB>] [--bps <max>]\n");
		return 2;
	}
	dir = argv[2];
	cfg.sending = !strcmp(argv[1], "send");
	cfg.ident = cfg.sending ? "+1 555 0300" : "+1 555 0399";
	cfg.ecm = 1;
	cfg.max_bps = 14400;
	cfg.srate = 8000;
	cfg.next_page = next_page;
	cfg.page_received = page_received;
	for (int i = 3; i < argc; i++) {
		if (!strcmp(argv[i], "--no-ecm"))
			cfg.ecm = 0;
		else if (!strcmp(argv[i], "--snr") && i + 1 < argc)
			snr = atof(argv[++i]);
		else if (!strcmp(argv[i], "--bps") && i + 1 < argc)
			cfg.max_bps = atoi(argv[++i]);
	}
	signal(SIGPIPE, SIG_IGN);
	dcore::log_level = getenv("T30_LINE_LOG") ? atoi(getenv("T30_LINE_LOG")) : 1;
	if (getenv("DSP_DEBUG"))
		dsplibs_debug_level = atoi(getenv("DSP_DEBUG"));
	char *args[] = { argv[0], nullptr };
	dmd::parse_options(1, args, opt);
	dcore::log_open("test");
	prop_dp_init();
	if (pipe(to_modem) < 0 || pipe(from_modem) < 0) {
		perror("pipe");
		return 1;
	}
	std::thread(machine, from_modem[0], to_modem[1], cfg, snr).detach();
	dmd::PipeModem a("modemA", 8000, opt.country, to_modem[0], from_modem[1]);
	a.set_realtime(!getenv("MODEM_REALTIME") || atoi(getenv("MODEM_REALTIME")));
	fprintf(stderr, "created modemA: %s\n", a.pty().name());
	dmd::PipeModem *modems[] = { &a };
	ret = dmd::PipeModem::run(modems, 1);
	prop_dp_exit();
	dcore::log_close();
	return ret;
}
