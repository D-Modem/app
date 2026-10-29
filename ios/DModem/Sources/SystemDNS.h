/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 D-Modem Authors
 *
 *    SystemDNS.h - the DNS servers the phone's network has (the engine's
 *                  SIP needs them for SRV and NAPTR: iOS has no
 *                  resolv.conf it can read), from the resolver's state
 */
#ifndef DMODEM_SYSTEM_DNS_H
#define DMODEM_SYSTEM_DNS_H

/* the IPv4 servers into buf, apart by commas ("" for none): how many */
int dmodem_dns_servers(char *buf, int len);

#endif
