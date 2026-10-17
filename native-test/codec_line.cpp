/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Neo-Desktop
 *
 *    codec_line.cpp - two modems back to back (D-Modem-RE's PipeModem,
 *                     a pty for each one's DTE) on a cellular call's
 *                     line: each way through a speech codec, 20 ms
 *                     frames encoded and decoded as a phone and the
 *                     network do.  What speeds survive a mobile call
 *                     (the app's cellular line, CellLineTest).
 *
 *    codec_line <codec>
 *    codec_line --filter <codec>
 *      (a filter: stdin's 20 ms frames through the codec to stdout, for
 *      the app's tests' cellular "network")
 *      codec: none, gsm (GSM full rate, 13 kbit/s), amr122 .. amr475
 *      (AMR-NB at 12.2, 10.2, 7.95, 7.4, 6.7, 5.9, 5.15, 4.75 kbit/s)
 *
 *    The modems' ptys on stderr, as modem_test has them ("created
 *    modemA: <pty>").  At the sample clock (MODEM_REALTIME=0: as fast
 *    as it goes).
 */

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <string>
#include <thread>

#include <dsplibs.h>
#include <log.h>

#include "options.h"
#include "line_speed.h"
#include "pipe_modem.h"

#ifdef HAVE_GSM
extern "C" {
#include <gsm/gsm.h>
}
#endif
#ifdef HAVE_AMRNB
extern "C" {
#include <opencore-amrnb/interf_dec.h>
#include <opencore-amrnb/interf_enc.h>
}
#endif

namespace {

const int FRAME = 160;  /* 20 ms at 8000 Hz */

/* a frame through the codec, in place */
class Codec {
public:
	explicit Codec(const std::string &name) : name_(name)
	{
#ifdef HAVE_GSM
		if (name == "gsm") {
			gsm_ = gsm_create();
			return;
		}
#endif
#ifdef HAVE_AMRNB
		static const struct { const char *n; int mode; } modes[] = {
			{ "amr475", 0 }, { "amr515", 1 }, { "amr59", 2 },
			{ "amr67", 3 }, { "amr74", 4 }, { "amr795", 5 },
			{ "amr102", 6 }, { "amr122", 7 },
		};
		for (auto &m : modes)
			if (name == m.n) {
				mode_ = m.mode;
				enc_ = Encoder_Interface_init(0);
				dec_ = Decoder_Interface_init();
				return;
			}
#endif
		if (name != "none") {
			fprintf(stderr, "codec_line: no codec %s\n", name.c_str());
			exit(2);
		}
	}

	void run(short *s)
	{
#ifdef HAVE_GSM
		if (gsm_) {
			gsm_frame f;
			gsm_encode(gsm_, s, f);
			gsm_decode(gsm_, f, s);
			return;
		}
#endif
#ifdef HAVE_AMRNB
		if (enc_) {
			unsigned char f[64];
			int n = Encoder_Interface_Encode(enc_, (enum Mode)mode_, s,
							 f, 0);
			(void)n;
			Decoder_Interface_Decode(dec_, f, s, 0);
			return;
		}
#endif
		(void)s;
	}

private:
	std::string name_;
#ifdef HAVE_GSM
	gsm gsm_ = nullptr;
#endif
#ifdef HAVE_AMRNB
	void *enc_ = nullptr, *dec_ = nullptr;
	int mode_ = 7;
#endif
};

/* in to out, a frame at a time through a codec of its own, until in ends */
void channel(int in, int out, std::string codec)
{
	Codec c(codec);
	short frame[FRAME];
	size_t have = 0;
	for (;;) {
		ssize_t n = read(in, (char *)frame + have, sizeof(frame) - have);
		if (n < 0 && errno == EINTR)
			continue;
		if (n <= 0)
			break;
		have += n;
		if (have < sizeof(frame))
			continue;
		c.run(frame);
		if (write(out, frame, sizeof(frame)) != (ssize_t)sizeof(frame))
			break;
		have = 0;
	}
	close(out);
}

} // namespace

int main(int argc, char *argv[])
{
	dmd::Options opt;
	int a_out[2], b_in[2], b_out[2], a_in[2], ret;
	std::string codec = argc > 1 ? argv[1] : "none";

	if (codec == "--filter") {
		Codec c(argc > 2 ? argv[2] : "none");
		short frame[FRAME];
		for (;;) {
			size_t have = 0;
			while (have < sizeof(frame)) {
				ssize_t n = read(0, (char *)frame + have,
						 sizeof(frame) - have);
				if (n <= 0)
					return 0;
				have += n;
			}
			c.run(frame);
			if (write(1, frame, sizeof(frame)) !=
			    (ssize_t)sizeof(frame))
				return 0;
		}
	}

	dcore::log_level = getenv("CODEC_LINE_LOG") ?
		atoi(getenv("CODEC_LINE_LOG")) : 1;
	/* (a lead of whole frames, room for what the codecs hold: the
	   line's samples never all wait in them) */
	setenv("PIPE_LEAD", "960", 0);
	char *args[] = { argv[0], nullptr };
	dmd::parse_options(1, args, opt);
	dcore::log_open("test");
	prop_dp_init();
	if (pipe(a_out) < 0 || pipe(b_in) < 0 || pipe(b_out) < 0 ||
	    pipe(a_in) < 0) {
		perror("pipe");
		return 1;
	}
	std::thread(channel, a_out[0], b_in[1], codec).detach();
	std::thread(channel, b_out[0], a_in[1], codec).detach();
	dmd::PipeModem a("modemA", 8000, opt.country, a_in[0], a_out[1]);
	dmd::PipeModem b("modemB", 8000, opt.country, b_in[0], b_out[1]);
	line_speed(a);
	line_speed(b);
	fprintf(stderr, "codec: %s\ncreated modemA: %s\ncreated modemB: %s\n",
		codec.c_str(), a.pty().name(), b.pty().name());
	dmd::PipeModem *modems[] = { &a, &b };
	ret = dmd::PipeModem::run(modems, 2);
	prop_dp_exit();
	dcore::log_close();
	return ret;
}
