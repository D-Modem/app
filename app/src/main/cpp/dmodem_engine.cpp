/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Neo-Desktop
 *
 *    dmodem_engine.cpp - the engine for C (dmodem_engine.h)
 *
 */

#include <unistd.h>

#include <mutex>

#include "dmodem_engine.h"
#include "engine.h"

using dmodem_android::Config;
using dmodem_android::Engine;
using dmodem_android::Status;

namespace {

std::mutex mu;
dmodem_callbacks cbs;

dmodem_callbacks callbacks()
{
	std::lock_guard<std::mutex> lk(mu);
	return cbs;
}

const char *str(const char *s)
{
	return s ? s : "";
}

} // namespace

extern "C" void dmodem_config_init(dmodem_config *c)
{
	*c = dmodem_config();
	c->sip_port = 5060;
	c->t38 = 1;
	c->sip_log_level = 1;
	c->peer_lead_ms = 100;
}

extern "C" void dmodem_set_callbacks(const dmodem_callbacks *cb)
{
	{
		std::lock_guard<std::mutex> lk(mu);
		cbs = cb ? *cb : dmodem_callbacks();
	}
	dmodem_callbacks c = callbacks();
	Engine &e = Engine::instance();
	if (c.log)
		e.set_log([c](const char *line) { c.log(c.ctx, line); });
	else
		e.set_log(nullptr);
	e.set_speaker(
		c.speaker ? Engine::SpeakerFn([c](int level) {
			c.speaker(c.ctx, level);
		}) : nullptr,
		c.sound ? Engine::SoundFn([c](const int16_t *pcm, int n) {
			c.sound(c.ctx, pcm, n);
		}) : nullptr);
}

extern "C" int dmodem_start(const dmodem_config *c)
{
	Config cfg;
	dmodem_callbacks cb = callbacks();

	cfg.user = str(c->user);
	cfg.password = str(c->password);
	cfg.domain = str(c->domain);
	cfg.sip_port = c->sip_port;
	cfg.listen = c->listen;
	cfg.t38 = c->t38;
	cfg.dns = str(c->dns);
	cfg.country = str(c->country);
	cfg.data_file = str(c->data_file);
	cfg.log_level = c->log_level;
	cfg.sip_log_level = c->sip_log_level;
	cfg.peer = c->peer;
	cfg.peer_lead_ms = c->peer_lead_ms;
	return Engine::instance().start(cfg,
		[cb](const Status &s) {
			if (cb.status)
				cb.status(cb.ctx, (int)s.link, s.offhook,
					  s.tx_rate, s.rx_rate);
		},
		[cb](int r) {
			if (cb.exited)
				cb.exited(cb.ctx, r);
		},
		[cb](bool caller, const char *dial, int fd) {
			if (cb.line)
				cb.line(cb.ctx, caller, dial, fd);
			else
				close(fd);
		});
}

extern "C" void dmodem_stop(void)
{
	Engine::instance().stop();
}

extern "C" int dmodem_running(void)
{
	return Engine::instance().running();
}

extern "C" void dmodem_peer_event(const char *line)
{
	Engine::instance().peer_event(str(line));
}
