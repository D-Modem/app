/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Neo-Desktop
 *
 *    peer_modem.h - the modem core on a line to another phone's modem
 *                   (the app's own link, over Wi-Fi or Bluetooth), its
 *                   DTE a descriptor
 *
 *    Off hook, the modem's line is one end of a socket pair: the other
 *    end goes to the app (on_line()), which carries the samples to the
 *    other phone and back.  The app says what the line does as SIP's user
 *    agent does: RING, CANCEL, ANSWERED, FAILED <SIP status>, DROPPED
 *    (event()).
 *
 *    The line runs on the samples themselves: each side answers every
 *    sample that comes with one of its own, with some silence ahead of
 *    all of them (lead_ms) for the samples on their way.  Nothing is ever
 *    lost or made up and the two phones' clocks can't drift apart: the
 *    modems see a perfect line with a delay.  Each side keeps it to the
 *    sample clock (else the loop would run as fast as the network does).
 *
 */

#ifndef DMODEM_ANDROID_PEER_MODEM_H
#define DMODEM_ANDROID_PEER_MODEM_H

#include <atomic>
#include <functional>
#include <memory>
#include <string>

#include <dcore.h>
#include <sip_modem.h>

namespace dmodem_android {

class PeerLine;

class PeerModem {
public:
	using Status = dmd::SipModem::Status;
	/* off hook: caller (we dial) or not (we answer), the dial string
	   as the DTE gave it, the app's end of the line's socket (the
	   app's to close: closing it ends the call) */
	using LineFn = std::function<void(bool caller, const char *dial,
					  int fd)>;

	/* the core as opt has it (opt.rate 8000) on dte; lead_ms of
	   silence ahead of our samples */
	PeerModem(dmd::Dte &dte, const dcore::Options &opt, int lead_ms);
	~PeerModem();
	PeerModem(const PeerModem &) = delete;
	PeerModem &operator=(const PeerModem &) = delete;

	bool ok() const;
	dcore::Modem &modem() { return *m_; }

	/* before run() */
	void on_status(std::function<void(const Status &)> fn) {
		status_fn_ = std::move(fn);
	}
	void on_line(LineFn fn);
	/* the speaker, as SipModem's (sip_modem.h) */
	void on_speaker(dmd::SipModem::Speaker s);

	/* served until stop() or the DTE has gone: 0; -1 on an error */
	int run();
	/* run() returns at its next turn (any thread) */
	void stop();
	/* a line of the app's about the line (any thread): RING, CANCEL,
	   ANSWERED, FAILED <code>, DROPPED */
	void event(const char *line);

private:
	int serve();
	void report();
	void act(const char *s);

	dmd::Dte &dte_;
	std::unique_ptr<PeerLine> line_;
	std::unique_ptr<dcore::Modem> m_;
	/* the app's lines and stop()'s wake: written to [1], read from [0] */
	int ev_[2] = { -1, -1 };
	std::string ev_buf_;
	std::atomic<bool> stopped_{false};
	std::function<void(const Status &)> status_fn_;
	Status status_;
};

} // namespace dmodem_android

#endif
