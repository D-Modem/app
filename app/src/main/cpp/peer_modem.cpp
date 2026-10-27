/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Neo-Desktop
 *
 *    peer_modem.cpp - the modem core on a line to another phone
 *                     (peer_modem.h)
 *
 */

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include <log.h>
#include <portable.h>
#include <run_loop.h>

#include "peer_modem.h"

#define ERR(fmt, ...) dcore::log_printf(0, "error: " fmt, ##__VA_ARGS__)
#define DBG(fmt, ...) dcore::log_printf(1, "peer: " fmt, ##__VA_ARGS__)

namespace dmodem_android {

namespace {

const int RATE = 8000;
/* the most samples one read takes */
const int AUDIO_MAX = 2048;
/* ahead of the sample clock by this much (ms), the line waits */
const long AHEAD_MS = 20;
/* behind it by this much (a stall on the way), the clock starts anew
   from here: no rush to make it up */
const long BEHIND_MS = 250;

/* the result for a dial that failed with a SIP status (as SIP's) */
dcore::Result failure(int code)
{
	switch (code) {
	case 486:
	case 600:
	case 603:
		return dcore::Result::Busy;
	case 408:
	case 480:
		return dcore::Result::NoAnswer;
	default:
		return dcore::Result::NoCarrier;
	}
}

/* all of n bytes to fd (a blocking socket); false if it is gone */
bool send_all(int fd, const void *buf, size_t n)
{
	const char *p = static_cast<const char *>(buf);
	while (n > 0) {
		ssize_t w = send(fd, p, n, dmd::SEND_NOSIGNAL);
		if (w < 0 && errno == EINTR)
			continue;
		if (w <= 0)
			return false;
		p += w;
		n -= (size_t)w;
	}
	return true;
}

} // namespace

/* the modem's Host: its line one end of a socket pair, the other the
   app's while off hook */
class PeerLine final : public dcore::Host {
public:
	/* on dte, lead_ms of silence ahead of the modem's samples */
	PeerLine(dmd::Dte &dte, int lead_ms) : dte_(dte), lead_ms_(lead_ms) {}
	/* the app's end of the line closed, if off hook */
	~PeerLine() { hang_up(); }

	s16 start(dcore::Modem &m) override;
	/* on hook: the line closed */
	s16 stop(dcore::Modem &) override {
		DBG("on hook\n");
		hang_up();
		return 0;
	}
	/* the driver's name, for the log */
	const char *driver_name() override { return "peer"; }
	/* the app's line says when the far end answers (ANSWERED) */
	bool signals_answer(dcore::Modem &) override { return true; }
	/* the modem's output to the DTE */
	s32 dte_write(dcore::Modem &, const char *buf, s32 n) override {
		return dte_.write(buf, n);
	}
	/* the core's clock: the monotonic ms */
	u64 now_ms() override { return dcore::monotonic_ms(); }
	/* ATM, ATL */
	void speaker(dcore::Modem &, s16 volume) override {
		speaker_level_ = volume;
		if (speaker_to.volume)
			speaker_to.volume(volume);
	}

	PeerModem::LineFn line_fn;
	dmd::SipModem::Speaker speaker_to;

	/* the line's socket, -1 on hook */
	int fd() const { return fd_; }
	/* the ms until the line may take samples again (0: now) */
	long wait_ms();
	/* fd_ is readable: its samples through the modem and back; 0, or -1
	   on an error */
	int audio(dcore::Modem &m);

private:
	/* the line's socket closed, if open */
	void hang_up() {
		if (fd_ >= 0) {
			close(fd_);
			fd_ = -1;
		}
	}

	dmd::Dte &dte_;
	int lead_ms_;
	int fd_ = -1;
	int speaker_level_ = 0;
	/* an odd octet of the line's, the next sample's first */
	bool have_half_ = false;
	unsigned char half_ = 0;
	/* the sample clock: since when, the samples since then */
	bool clock_on_ = false;
	unsigned long clock_start_ = 0;
	unsigned long samples_ = 0;
};

/* off hook: the socket pair, ours with the lead silence in it, the
   other end to the app */
s16 PeerLine::start(dcore::Modem &m)
{
	int sv[2];
	int lead = RATE / 1000 * (lead_ms_ > 0 ? lead_ms_ : 0);

	if (!line_fn) {
		ERR("peer: no line\n");
		return -1;
	}
	if (dmd::socketpair_cloexec(AF_UNIX, SOCK_STREAM, 0, sv) < 0) {
		ERR("socketpair: %s\n", strerror(errno));
		return -1;
	}
	/* (the silence and a call's worth of samples on the way: room) */
	int size = 256 * 1024;
	setsockopt(sv[0], SOL_SOCKET, SO_SNDBUF, &size, sizeof(size));
	setsockopt(sv[1], SOL_SOCKET, SO_SNDBUF, &size, sizeof(size));
	if (lead > 0) {
		int16_t *zeros = static_cast<int16_t *>(calloc(lead, 2));
		bool ok = zeros && send_all(sv[0], zeros, 2 * (size_t)lead);
		free(zeros);
		if (!ok) {
			ERR("peer: the lead silence: %s\n", strerror(errno));
			close(sv[0]);
			close(sv[1]);
			return -1;
		}
	}
	fd_ = sv[0];
	have_half_ = false;
	clock_on_ = false;
	samples_ = 0;
	if (m.caller())
		DBG("off hook: dialling %s\n", m.dial_string());
	else
		DBG("off hook: answering\n");
	line_fn(m.caller(), m.caller() ? m.dial_string() : "", sv[1]);
	return 0;
}

/* see above */
long PeerLine::wait_ms()
{
	if (!clock_on_)
		return 0;
	long due = (long)(dcore::monotonic_ms() - clock_start_);
	long at = (long)(samples_ * 1000 / RATE);
	if (due - at > BEHIND_MS) {
		/* (a stall: the clock from here) */
		clock_start_ = dcore::monotonic_ms() - at;
		return 0;
	}
	return at - due > AHEAD_MS ? at - due - AHEAD_MS / 2 : 0;
}

/* the samples that came (behind an odd octet from before) through the
   modem, as many back; the app's end closed: the call is over */
int PeerLine::audio(dcore::Modem &m)
{
	unsigned char buf[2 * AUDIO_MAX + 1];
	int16_t in[AUDIO_MAX], out[AUDIO_MAX];
	int off = have_half_ ? 1 : 0;
	ssize_t n;

	if (have_half_)
		buf[0] = half_;
	n = recv(fd_, buf + off, 2 * AUDIO_MAX, MSG_DONTWAIT);
	if (n < 0 && (errno == EINTR || errno == EAGAIN))
		return 0;
	if (n <= 0) {
		DBG("the line's other end hung up\n");
		hang_up();
		m.line_dropped();
		return 0;
	}
	n += off;
	int count = (int)n / 2;
	have_half_ = n & 1;
	if (have_half_)
		half_ = buf[n - 1];
	if (count == 0)
		return 0;
	memcpy(in, buf, 2 * (size_t)count);
	if (!clock_on_) {
		clock_on_ = true;
		clock_start_ = dcore::monotonic_ms();
	}
	samples_ += count;
	m.process(in, out, count);
	if (speaker_level_ > 0 && speaker_to.audio)
		speaker_to.audio(in, out, count);
	if (fd_ < 0)
		return 0;       /* (it hung up on these) */
	if (!send_all(fd_, out, 2 * (size_t)count)) {
		DBG("the line's other end hung up: %s\n", strerror(errno));
		hang_up();
		m.line_dropped();
	}
	return 0;
}

/* see peer_modem.h */
PeerModem::PeerModem(dmd::Dte &dte, const dcore::Options &opt, int lead_ms)
	: dte_(dte), line_(new PeerLine(dte, lead_ms))
{
	m_.reset(new dcore::Modem(*line_, opt));
	if (pipe(ev_) < 0) {
		ERR("pipe: %s\n", strerror(errno));
		ev_[0] = ev_[1] = -1;
		return;
	}
	for (int fd : ev_) {
		fcntl(fd, F_SETFD, FD_CLOEXEC);
		fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK);
	}
}

/* the core first: it may still hang up on the line */
PeerModem::~PeerModem()
{
	m_.reset();
	line_.reset();
	for (int fd : ev_)
		if (fd >= 0)
			close(fd);
}

/* the core up and the event pipe made */
bool PeerModem::ok() const
{
	return m_ && m_->ok() && ev_[0] >= 0;
}

/* where an off hook's line goes (before run()) */
void PeerModem::on_line(LineFn fn)
{
	line_->line_fn = std::move(fn);
}

/* see peer_modem.h */
void PeerModem::on_speaker(dmd::SipModem::Speaker s)
{
	line_->speaker_to = std::move(s);
}

/* see peer_modem.h */
void PeerModem::stop()
{
	stopped_ = true;
	event("");
}

/* see peer_modem.h: the line in one write (atomic in a pipe) */
void PeerModem::event(const char *line)
{
	std::string s(line);
	s += '\n';
	if (ev_[1] >= 0 && write(ev_[1], s.data(), s.size()) < 0) {
		/* (full: the loop is that far behind; dropped) */
	}
}

/* an app's line */
void PeerModem::act(const char *s)
{
	dcore::Modem &m = *m_;
	if (!*s)
		return;
	DBG("line: %s\n", s);
	if (!strcmp(s, "RING"))
		m.ring_signal(true);
	else if (!strcmp(s, "CANCEL"))
		m.ring_signal(false);
	else if (!strcmp(s, "ANSWERED"))
		m.line_answered();
	else if (!strncmp(s, "FAILED ", 7))
		m.line_failed(failure(atoi(s + 7)));
	else if (!strcmp(s, "NO LINE"))
		/* (nothing plugged in: as a modem with no phone cable) */
		m.line_failed(dcore::Result::NoDialtone);
	else if (!strcmp(s, "DROPPED")) {
		if (m.started())
			m.line_dropped();
	}
}

/* the call's state to status_fn_, when it changed */
void PeerModem::report()
{
	Status s;
	if (!status_fn_)
		return;
	s.link = m_->link_state();
	s.offhook = m_->started();
	if (s.link == dcore::LinkState::Online) {
		s.tx_rate = m_->tx_rate();
		s.rx_rate = m_->rx_rate();
	}
	if (s != status_) {
		status_ = s;
		status_fn_(s);
	}
}

/* see peer_modem.h */
int PeerModem::run()
{
	int ret;
	if (!ok())
		return -1;
	ret = serve();
	if (m_->link_state() != dcore::LinkState::Idle)
		m_->hangup_now();
	dte_.flush();
	return ret;
}

/* each turn: the modem's timers, then select() on the line (when the
   sample clock lets it), the app's lines and the DTE */
int PeerModem::serve()
{
	dcore::Modem &m = *m_;
	dcore::Modem *const modems[] = { &m };
	PeerLine &line = *line_;
	dmd::RunLoop loop;

	while (!stopped_) {
		long wait = dmd::RunLoop::poll(modems, 1);
		int audio = m.started() ? line.fd() : -1;
		int dte = dte_.fd();
		int ret;

		report();
		loop.clear();
		if (audio >= 0) {
			long hold = line.wait_ms();
			if (hold > 0) {
				if (wait > hold)
					wait = hold;
				audio = -1;
			} else {
				loop.watch(audio);
			}
		}
		loop.watch(ev_[0]);
		if (dte >= 0 && dte_.want_read(m))
			loop.watch(dte);
		if (dte >= 0 && dte_.want_write())
			loop.watch_write(dte);
		ret = loop.wait(wait);
		if (ret < 0) {
			ERR("select: %s\n", strerror(errno));
			return -1;
		}
		if (ret == 0)
			continue;
		if (loop.ready(ev_[0])) {
			char buf[512];
			ssize_t n;
			while ((n = read(ev_[0], buf, sizeof(buf))) > 0) {
				for (ssize_t i = 0; i < n; i++) {
					if (buf[i] != '\n') {
						if (ev_buf_.size() < 255)
							ev_buf_ += buf[i];
						continue;
					}
					act(ev_buf_.c_str());
					ev_buf_.clear();
				}
			}
		}
		if (audio >= 0 && audio == line.fd() && loop.ready(audio) &&
		    line.audio(m) < 0)
			return -1;
		if (loop.ready_write(dte))
			dte_.flush();
		if (loop.ready(dte)) {
			ret = dte_.serve(m);
			if (ret < 0)
				return -1;
			if (ret > 0)
				return 0;
		}
	}
	return 0;
}

} // namespace dmodem_android
