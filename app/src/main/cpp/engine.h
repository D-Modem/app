/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Neo-Desktop
 *
 *    engine.h - the app's modem: D-Modem's modem on a SIP line
 *               (dmd::SipModem) in a thread of its own, its DTE one end
 *               of a socket pair whose other end is the app's
 *
 *    No JNI here (jni.cpp is the binding), so that it builds and is
 *    tested on the build host too (native-test/).
 *
 *    One modem in the process: the SIP user agent is the process's.
 *
 */

#ifndef DMODEM_ANDROID_ENGINE_H
#define DMODEM_ANDROID_ENGINE_H

#include <stdint.h>

#include <functional>
#include <mutex>
#include <string>
#include <thread>

namespace dmd { class SipModem; }

namespace dmodem_android {

class PeerModem;
class UsbModem;

struct Config {
	/* the SIP account: user, password, the registrar's or proxy's
	   host[:port]; the local SIP port (0: 5060) */
	std::string user, password, domain;
	int sip_port = 5060;
	/* register, and take calls in */
	bool listen = false;
	/* fax calls may go over to T.38 */
	bool t38 = true;
	/* the DNS servers for SRV and NAPTR (addresses apart by commas),
	   from the network Android has: it has no /etc/resolv.conf */
	std::string dns;
	/* the modem's country (by name, empty: USA) */
	std::string country;
	/* what the pumps learn of the line over calls, kept in this file
	   (empty: not kept) */
	std::string data_file;
	/* the modem's log level (0: errors only), SIP's (1 errors .. 4
	   the SIP messages, 0 none) */
	int log_level = 0;
	int sip_log_level = 1;
	/* the line another phone's modem through the app (peer_modem.h),
	   not SIP: the account above unused; its lead silence (ms) */
	bool peer = false;
	int peer_lead_ms = 100;
	/* the line a USB modem's (usb/usb_modem.h: a SmartUSB56 or a
	   pico-serial Line stick), the device Android opened for the app
	   (its descriptor, the caller's: open while the modem runs), not
	   SIP or a peer; its rate (8000 or 9600 Hz).  -1: none */
	int usb_fd = -1;
	int usb_rate = 9600;
};

/* what the modem's call is doing (dmd::SipModem::Status) */
struct Status {
	enum Link { IDLE, CONNECTING, ONLINE, HANGING_UP };
	Link link = IDLE;
	bool offhook = false;
	unsigned tx_rate = 0, rx_rate = 0;
};

class Engine {
public:
	using LogFn = std::function<void(const char *line)>;
	using StatusFn = std::function<void(const Status &)>;
	/* the thread's end: 0 stopped (or the DTE gone), -1 an error */
	using ExitFn = std::function<void(int result)>;
	/* a peer line's call (cfg.peer): off hook, caller or not, the dial
	   string, the app's end of the line's socket (the app's to close) */
	using LineFn = std::function<void(bool caller, const char *dial,
					  int fd)>;

	/* the process's one */
	static Engine &instance();

	/* the log's lines (the modem's and SIP's, from their threads) to
	   fn; NULL: stderr.  Before start() */
	void set_log(LogFn fn);

	/* the modem's speaker (ATM, ATL), from its thread: level(0 off,
	   1..3) as it changes, and while it is on the line's sound in
	   blocks (what came, with the modem's own at half its level, as a
	   modem's speaker has the line).  Before start() */
	using SpeakerFn = std::function<void(int level)>;
	using SoundFn = std::function<void(const int16_t *pcm, int n)>;
	void set_speaker(SpeakerFn level, SoundFn sound);

	/* the modem started in its thread with cfg: the app's end of the
	   DTE's socket pair (the caller's to close), or -1 if it is
	   running already or can't be started.  status and done are
	   called on the modem's thread */
	int start(const Config &cfg, StatusFn status, ExitFn done,
		  LineFn line = nullptr);
	/* a peer line's event to the modem (PeerModem::event()): RING,
	   CANCEL, ANSWERED, FAILED <code>, DROPPED; nothing if it isn't on
	   a peer line */
	void peer_event(const char *line);
	/* the modem stopped (its call hung up, SIP gone), its thread
	   joined; nothing if it isn't running */
	void stop();
	bool running();

private:
	Engine() = default;
	void run(Config cfg, int dte, StatusFn status, ExitFn done,
		 LineFn line);

	std::mutex mu_;
	std::thread thread_;
	dmd::SipModem *modem_ = nullptr;        /* while run() has one */
	PeerModem *peer_ = nullptr;             /* or this one */
	UsbModem *usb_ = nullptr;               /* or this one */
	SpeakerFn speaker_;
	SoundFn sound_;
	bool active_ = false;                   /* run() hasn't ended */
	bool stopping_ = false;
};

} // namespace dmodem_android

#endif
