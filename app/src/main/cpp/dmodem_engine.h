/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Neo-Desktop
 *
 *    dmodem_engine.h - the app's modem (engine.h) for a program in C, or
 *                      one that calls C (Swift, on iOS): the modem on a
 *                      SIP line, or on a line to another phone through
 *                      the program (peer_modem.h), in a thread of its
 *                      own, its DTE a socket
 *
 *    One modem in the process.  The callbacks come on the modem's and
 *    SIP's threads.
 *
 */
#ifndef DMODEM_ENGINE_H
#define DMODEM_ENGINE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct dmodem_config {
	/* the SIP account: user, password, the registrar's or proxy's
	   host[:port]; the local SIP port (0: 5060) */
	const char *user, *password, *domain;
	int sip_port;
	/* register and take calls in (or, peer: take the other phone's) */
	int listen;
	/* fax calls may go over to T.38 */
	int t38;
	/* DNS servers for SRV and NAPTR, apart by commas (NULL: none) */
	const char *dns;
	/* the modem's country by name (NULL or "": USA) */
	const char *country;
	/* where the modem keeps what it learns of the line (NULL: nowhere) */
	const char *data_file;
	/* the modem's log level (0: errors), SIP's (0 none .. 4) */
	int log_level, sip_log_level;
	/* the line another phone's through the program (line callback),
	   not SIP; its lead silence (ms) */
	int peer, peer_lead_ms;
} dmodem_config;

/* the link's states (status) */
enum { DMODEM_IDLE, DMODEM_CONNECTING, DMODEM_ONLINE, DMODEM_HANGING_UP };

typedef struct dmodem_callbacks {
	void *ctx;
	/* a line of the log */
	void (*log)(void *ctx, const char *line);
	/* the call's state as it changes */
	void (*status)(void *ctx, int link, int offhook, unsigned tx_rate,
		       unsigned rx_rate);
	/* the modem stopped: 0 by dmodem_stop() or the DTE closed, -1 on
	   an error */
	void (*exited)(void *ctx, int result);
	/* a peer line's modem off hook: dialling (caller) dial, or
	   answering; fd its samples (16 bit, 8000 Hz), the program's to
	   close */
	void (*line)(void *ctx, int caller, const char *dial, int fd);
	/* the modem's speaker (ATM, ATL): 0 off, 1..3; while on, the
	   line's sound */
	void (*speaker)(void *ctx, int level);
	void (*sound)(void *ctx, const int16_t *pcm, int n);
} dmodem_callbacks;

/* *c with the defaults (SIP port 5060, T.38 on, SIP's log level 1,
   lead 100 ms) */
void dmodem_config_init(dmodem_config *c);

/* the callbacks (copied), before dmodem_start() */
void dmodem_set_callbacks(const dmodem_callbacks *cb);

/* the modem started: the program's end of its DTE (the program's to
   close: closing it stops the modem), or -1 if it runs already or
   can't start */
int dmodem_start(const dmodem_config *c);

/* the modem stopped (its call hung up, SIP gone); waits for it */
void dmodem_stop(void);

/* the modem runs */
int dmodem_running(void);

/* a peer line's event to the modem: RING, CANCEL, ANSWERED, FAILED
   <SIP status>, DROPPED; NO LINE (a dial with nothing plugged in: NO
   DIALTONE) */
void dmodem_peer_event(const char *line);

#ifdef __cplusplus
}
#endif

#endif
