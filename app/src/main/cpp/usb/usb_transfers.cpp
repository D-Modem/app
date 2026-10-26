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
/* the first IN transfers back: logged, their octets' head */
const int IN_SAID = 3;
int in_said_ = 0;
/* each transfer's own callback, called after it's counted */
std::unordered_map<libusb_transfer *, libusb_transfer_cb_fn> callbacks_;

bool is_in(const libusb_transfer *t)
{
	return t->endpoint & LIBUSB_ENDPOINT_IN;
}

void LIBUSB_CALL counted(libusb_transfer *t)
{
	Way &w = is_in(t) ? in_ : out_;
	w.done++;
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
	    t->type == LIBUSB_TRANSFER_TYPE_BULK) {
		char head[3 * 8 + 1] = "";
		int n = t->actual_length < 8 ? t->actual_length : 8;
		for (int i = 0; i < n; i++)
			snprintf(head + 3 * i, 4, " %02x", t->buffer[i]);
		dcore::log_printf(1, "usb: IN transfer %d back: status %d, "
				  "%d octets:%s\n", in_said_ + 1, t->status,
				  t->actual_length, head);
		in_said_++;
	}
	auto it = callbacks_.find(t);
	if (it != callbacks_.end() && it->second)
		it->second(t);
}

} // namespace

namespace dmodem_android {

void usb_transfers_reset()
{
	in_ = Way();
	out_ = Way();
	in_said_ = 0;
	callbacks_.clear();
}

void usb_transfer_stats(char *buf, size_t len)
{
	snprintf(buf, len,
		 "IN %" PRIu64 " sent, %" PRIu64 " back (%" PRIu64
		 " octets, %" PRIu64 " failed, last %d)%s; "
		 "OUT %" PRIu64 " sent, %" PRIu64 " back (%" PRIu64
		 " failed, last %d)%s",
		 in_.sent, in_.done, in_.octets, in_.failed, in_.last_status,
		 in_.refused ? ", some refused" : "",
		 out_.sent, out_.done, out_.failed, out_.last_status,
		 out_.refused ? ", some refused" : "");
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
	int err = libusb_submit_transfer(t);
	Way &w = is_in(t) ? in_ : out_;
	if (err == 0)
		w.sent++;
	else
		w.refused++;
	return err;
}
