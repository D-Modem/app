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
#include <sip.h>
#include <sip_modem.h>

#include "engine.h"

namespace dmodem_android {

namespace {

/* the line's sample rate */
const int RATE = 8000;

/* the log's lines to the app's function (from any thread) */
std::mutex log_mu;
Engine::LogFn log_fn;

void log_hook(const char *line)
{
	std::lock_guard<std::mutex> lk(log_mu);
	if (log_fn)
		log_fn(line);
}

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
int Engine::start(const Config &cfg, StatusFn status, ExitFn done)
{
	std::lock_guard<std::mutex> lk(mu_);
	int sv[2];

	if (thread_.joinable()) {
		if (active_)
			return -1;
		/* (it ended by itself) */
		thread_.join();
	}
	if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, sv) < 0)
		return -1;
	active_ = true;
	stopping_ = false;
	try {
		thread_ = std::thread(&Engine::run, this, cfg, sv[0],
				      std::move(status), std::move(done));
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
	std::thread t = std::move(thread_);
	lk.unlock();
	t.join();
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
void Engine::run(Config cfg, int dte_fd, StatusFn status, ExitFn done)
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

	{
		dmd::FdDte dte(dte_fd);
		dmd::SipModem sm(dte, mo);
		std::unique_ptr<dmd::DataFile> data;

		if (sm.ok()) {
			if (!cfg.data_file.empty()) {
				data.reset(new dmd::DataFile(
					cfg.data_file.c_str()));
				data->load(&sm.modem().dsp_info());
			}
			if (status)
				sm.on_status([&status](const dmd::SipModem::
						       Status &s) {
					Status st;
					st.link = link_of(s.link);
					st.offhook = s.offhook;
					st.tx_rate = s.tx_rate;
					st.rx_rate = s.rx_rate;
					status(st);
				});
			{
				std::lock_guard<std::mutex> lk(mu_);
				modem_ = &sm;
				if (stopping_)
					sm.stop();
			}
			ret = sm.run(sc);
			{
				std::lock_guard<std::mutex> lk(mu_);
				modem_ = nullptr;
			}
			if (data)
				data->save(&sm.modem().dsp_info());
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
