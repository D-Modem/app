/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Neo-Desktop
 *
 *    engine.cpp - the app's modem in a thread of its own (engine.h)
 *
 */

#include <fcntl.h>
#include <sys/socket.h>
#include <unistd.h>

#include <memory>
#include <utility>

#include <datafile.h>
#include <dcore.h>
#include <dsplibs.h>
#include <log.h>
#include <portable.h>
#include <sip.h>
#include <sip_modem.h>

#include "engine.h"
#include "peer_modem.h"
#ifdef DMODEM_ENGINE_USB
#include "usb/usb_modem.h"
#endif

namespace dmodem_android {

namespace {

/* the line's sample rate */
const int RATE = 8000;

/* the log's lines to the app's function (from any thread) */
std::mutex log_mu;
Engine::LogFn log_fn;

/* a log line to the app's function, if it set one */
void log_hook(const char *line)
{
	std::lock_guard<std::mutex> lk(log_mu);
	if (log_fn)
		log_fn(line);
}

/* the app's link state for the core's */
Status::Link link_of(dcore::LinkState s)
{
	switch (s) {
	case dcore::LinkState::Connecting:
		return Status::CONNECTING;
	case dcore::LinkState::Online:
		return Status::ONLINE;
	case dcore::LinkState::HangingUp:
		return Status::HANGING_UP;
	default:
		return Status::IDLE;
	}
}

} // namespace

/* see engine.h */
Engine &Engine::instance()
{
	static Engine e;
	return e;
}

/* see engine.h */
void Engine::set_log(LogFn fn)
{
	std::lock_guard<std::mutex> lk(log_mu);
	log_fn = std::move(fn);
	dcore::log_set_hook(log_fn ? log_hook : nullptr);
}

/* see engine.h: a socket pair, ours to the modem's thread */
int Engine::start(const Config &cfg, StatusFn status, ExitFn done,
		  LineFn line)
{
	std::lock_guard<std::mutex> lk(mu_);
	int sv[2];

	if (thread_.joinable()) {
		if (active_)
			return -1;
		/* (it ended by itself) */
		thread_.join();
	}
	if (dmd::socketpair_cloexec(AF_UNIX, SOCK_STREAM, 0, sv) < 0)
		return -1;
	active_ = true;
	stopping_ = false;
	try {
		thread_ = std::thread(&Engine::run, this, cfg, sv[0],
				      std::move(status), std::move(done),
				      std::move(line));
	} catch (...) {
		active_ = false;
		close(sv[0]);
		close(sv[1]);
		return -1;
	}
	return sv[1];
}

/* see engine.h */
void Engine::stop()
{
	std::unique_lock<std::mutex> lk(mu_);

	if (!thread_.joinable())
		return;
	stopping_ = true;
	if (modem_)
		modem_->stop();
	if (peer_)
		peer_->stop();
#ifdef DMODEM_ENGINE_USB
	if (usb_)
		usb_->stop();
#endif
	std::thread t = std::move(thread_);
	lk.unlock();
	t.join();
}

/* see engine.h */
void Engine::set_speaker(SpeakerFn level, SoundFn sound)
{
	std::lock_guard<std::mutex> lk(mu_);
	speaker_ = std::move(level);
	sound_ = std::move(sound);
}

/* see engine.h */
void Engine::peer_event(const char *line)
{
	std::lock_guard<std::mutex> lk(mu_);
	if (peer_)
		peer_->event(line);
}

/* see engine.h */
bool Engine::running()
{
	std::lock_guard<std::mutex> lk(mu_);
	return active_;
}

/* the modem's thread: the modem on dte (ours, closed at the end) with
   its data file, the SIP user agent started with cfg, until stop() or
   the app's end of dte closed */
void Engine::run(Config cfg, int dte_fd, StatusFn status, ExitFn done,
		 LineFn line_fn)
{
	dcore::Options mo;
	sip::Config sc;
	int ret = -1;

	dcore::log_level = cfg.log_level > 0 ? (unsigned)cfg.log_level : 0;
	/* (the pumps' level follows; no binary log) */
	dcore::log_open("dmodem0");
	prop_dp_init();

	mo.name = "dmodem0";
	mo.dev_name = "Android";
	mo.rate = RATE;
	mo.country = cfg.country.empty() ? nullptr : cfg.country.c_str();

	sc.user = cfg.user.c_str();
	sc.password = cfg.password.c_str();
	sc.domain = cfg.domain.c_str();
	sc.port = cfg.sip_port > 0 ? cfg.sip_port : 5060;
	sc.listen = cfg.listen;
	sc.record = nullptr;
	sc.t38 = cfg.t38;
	sc.dns = cfg.dns.empty() ? nullptr : cfg.dns.c_str();
	sc.log_level = cfg.sip_log_level;

	/* the status, as the engine's */
	auto report = [&status](const dmd::SipModem::Status &s) {
		Status st;
		st.link = link_of(s.link);
		st.offhook = s.offhook;
		st.tx_rate = s.tx_rate;
		st.rx_rate = s.rx_rate;
		status(st);
	};
	/* the speaker: the line as the modem's speaker has it */
	dmd::SipModem::Speaker spk;
	{
		std::lock_guard<std::mutex> lk(mu_);
		spk.volume = speaker_;
		if (sound_) {
			SoundFn sound = sound_;
			spk.audio = [sound](const int16_t *rx, const int16_t *tx,
					    int n) {
				int16_t mix[2048];
				while (n > 0) {
					int k = n < 2048 ? n : 2048;
					for (int i = 0; i < k; i++) {
						int v = rx[i] + tx[i] / 2;
						mix[i] = (int16_t)(v > 32767 ? 32767 :
							v < -32768 ? -32768 : v);
					}
					sound(mix, k);
					rx += k;
					tx += k;
					n -= k;
				}
			};
		}
	}

	/* the modem's data file loaded, the modem run, the file saved */
	auto serve = [&](dcore::Modem &m, auto &&run_it) {
		std::unique_ptr<dmd::DataFile> data;
		if (!cfg.data_file.empty()) {
			data.reset(new dmd::DataFile(cfg.data_file.c_str()));
			data->load(&m.dsp_info());
		}
		ret = run_it();
		if (data)
			data->save(&m.dsp_info());
	};

	{
		dmd::FdDte dte(dte_fd);

		if (cfg.usb_fd >= 0) {
#ifdef DMODEM_ENGINE_USB
			mo.rate = cfg.usb_rate;
			UsbModem um(dte, mo, cfg.usb_fd);
			if (um.ok()) {
				if (status)
					um.on_status(report);
				{
					std::lock_guard<std::mutex> lk(mu_);
					usb_ = &um;
					if (stopping_)
						um.stop();
				}
				serve(um.modem(), [&um] { return um.run(); });
				std::lock_guard<std::mutex> lk(mu_);
				usb_ = nullptr;
			}
#else
			dcore::log_printf(0, "error: a USB modem: not in this build\n");
#endif
		} else if (cfg.peer) {
			PeerModem pm(dte, mo, cfg.peer_lead_ms);
			if (pm.ok()) {
				if (status)
					pm.on_status(report);
				pm.on_line(std::move(line_fn));
				pm.on_speaker(spk);
				{
					std::lock_guard<std::mutex> lk(mu_);
					peer_ = &pm;
					if (stopping_)
						pm.stop();
				}
				serve(pm.modem(), [&pm] { return pm.run(); });
				std::lock_guard<std::mutex> lk(mu_);
				peer_ = nullptr;
			}
		} else {
			dmd::SipModem sm(dte, mo);
			if (sm.ok()) {
				if (status)
					sm.on_status(report);
				sm.on_speaker(spk);
				{
					std::lock_guard<std::mutex> lk(mu_);
					modem_ = &sm;
					if (stopping_)
						sm.stop();
				}
				serve(sm.modem(), [&sm, &sc] { return sm.run(sc); });
				std::lock_guard<std::mutex> lk(mu_);
				modem_ = nullptr;
			}
		}
	}
	prop_dp_exit();
	dcore::log_close();
	close(dte_fd);
	{
		std::lock_guard<std::mutex> lk(mu_);
		active_ = false;
	}
	if (done)
		done(ret);
}

} // namespace dmodem_android
