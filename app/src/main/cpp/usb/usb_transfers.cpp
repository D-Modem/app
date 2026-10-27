/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Neo-Desktop
 *
 *    usb_transfers.cpp - the USB line's transfers counted, for the log
 *                        (usb_android.h)
 *
 */
#include "usb_android.h"

#include <inttypes.h>
#include <stdio.h>
#include <time.h>

#include <unordered_map>

#include <log.h>

namespace {

/* (one modem in the process: its thread alone uses these) a way's transfers: sent (and refused), back (completed, and their
   octets; failed, and the last failure's status) */
struct Way {
	uint64_t sent = 0, refused = 0, done = 0, octets = 0, failed = 0;
	int last_status = 0;
};
Way in_, out_;
/* the IN transfers' round trips (sent to back) and the longest gap
   between two coming back, since the last usb_transfer_stats() */
std::unordered_map<libusb_transfer *, uint64_t> sent_at_;
uint64_t trips_ = 0, trip_us_ = 0, trip_max_us_ = 0, gap_max_us_ = 0, last_back_us_ = 0;

/* the monotonic clock, in microseconds */
uint64_t now_us()
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return static_cast<uint64_t>(ts.tv_sec) * 1000000u +
		static_cast<uint64_t>(ts.tv_nsec) / 1000u;
}
/* the first IN transfers back: logged, their octets' head */
const int IN_SAID = 3;
int in_said_ = 0;
/* each transfer's own callback, called after it's counted */
std::unordered_map<libusb_transfer *, libusb_transfer_cb_fn> callbacks_;

/* whether t is an IN transfer */
bool is_in(const libusb_transfer *t)
{
	return t->endpoint & LIBUSB_ENDPOINT_IN;
}

/* t's callback in libusb: t counted (and its round trip, the first few logged), then its own callback */
void LIBUSB_CALL counted(libusb_transfer *t)
{
	Way &w = is_in(t) ? in_ : out_;
	w.done++;
	if (is_in(t)) {
		uint64_t now = now_us();
		auto s = sent_at_.find(t);
		if (s != sent_at_.end()) {
			uint64_t trip = now - s->second;
			trips_++;
			trip_us_ += trip;
			if (trip > trip_max_us_)
				trip_max_us_ = trip;
		}
		if (last_back_us_ && now - last_back_us_ > gap_max_us_)
			gap_max_us_ = now - last_back_us_;
		last_back_us_ = now;
	}
	if (t->status == LIBUSB_TRANSFER_COMPLETED) {
		w.octets += static_cast<uint64_t>(t->actual_length);
		/* (an isochronous one's are its packets') */
		for (int i = 0; i < t->num_iso_packets; i++)
			w.octets += t->iso_packet_desc[i].actual_length;
	} else {
		w.failed++;
		w.last_status = t->status;
	}
	if (is_in(t) && in_said_ < IN_SAID &&
	    t->type != LIBUSB_TRANSFER_TYPE_INTERRUPT) {
		/* (a bulk one's octets' head; an isochronous one's packets and
		   its first packet's head) */
		const unsigned char *b = t->buffer;
		int n = t->actual_length, packets = t->num_iso_packets;
		if (packets > 0)
			n = static_cast<int>(t->iso_packet_desc[0].actual_length);
		char head[3 * 8 + 1] = "";
		for (int i = 0; i < (n < 8 ? n : 8); i++)
			snprintf(head + 3 * i, 4, " %02x", b[i]);
		if (packets > 0)
			dcore::log_printf(1, "usb: IN transfer %d back: status %d, "
					  "%d packets, the first %d octets:%s\n",
					  in_said_ + 1, t->status, packets, n, head);
		else
			dcore::log_printf(1, "usb: IN transfer %d back: status %d, "
					  "%d octets:%s\n", in_said_ + 1, t->status,
					  n, head);
		in_said_++;
	}
	auto it = callbacks_.find(t);
	if (it != callbacks_.end() && it->second)
		it->second(t);
}

} // namespace

namespace dmodem_android {

/* see usb_android.h */
void usb_transfers_reset()
{
	in_ = Way();
	out_ = Way();
	in_said_ = 0;
	callbacks_.clear();
	sent_at_.clear();
	trips_ = trip_us_ = trip_max_us_ = gap_max_us_ = last_back_us_ = 0;
}

/* see usb_android.h (the round trips' and gaps' counts started again) */
void usb_transfer_stats(char *buf, size_t len)
{
	snprintf(buf, len,
		 "IN %" PRIu64 " sent, %" PRIu64 " back (%" PRIu64
		 " octets, %" PRIu64 " failed, last %d)%s; "
		 "OUT %" PRIu64 " sent, %" PRIu64 " back (%" PRIu64
		 " failed, last %d)%s; IN round trips %.1f ms, at most %.1f, "
		 "the longest wait %.1f",
		 in_.sent, in_.done, in_.octets, in_.failed, in_.last_status,
		 in_.refused ? ", some refused" : "",
		 out_.sent, out_.done, out_.failed, out_.last_status,
		 out_.refused ? ", some refused" : "",
		 trips_ ? trip_us_ / 1000.0 / static_cast<double>(trips_) : 0.0,
		 trip_max_us_ / 1000.0, gap_max_us_ / 1000.0);
	trips_ = trip_us_ = trip_max_us_ = gap_max_us_ = 0;
}

} // namespace dmodem_android

/* the transfer counted as it's sent and as it comes back (its own
   callback then: a resubmitted one keeps the one it had) */
int dmodem_libusb_submit_transfer(struct libusb_transfer *t)
{
	if (t->callback != counted) {
		callbacks_[t] = t->callback;
		t->callback = counted;
	}
	if (is_in(t))
		sent_at_[t] = now_us();
	int err = libusb_submit_transfer(t);
	Way &w = is_in(t) ? in_ : out_;
	if (err == 0)
		w.sent++;
	else
		w.refused++;
	return err;
}
