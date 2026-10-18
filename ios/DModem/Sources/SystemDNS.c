/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Neo-Desktop
 *
 *    SystemDNS.c - see SystemDNS.h (res_ninit: libresolv)
 */
#include <arpa/inet.h>
#include <netinet/in.h>
#include <resolv.h>
#include <stdio.h>
#include <string.h>

#include "SystemDNS.h"

int dmodem_dns_servers(char *buf, int len)
{
	struct __res_state st;
	int i, n = 0, at = 0;

	if (len <= 0)
		return 0;
	buf[0] = 0;
	memset(&st, 0, sizeof(st));
	if (res_ninit(&st) != 0)
		return 0;
	for (i = 0; i < st.nscount && i < MAXNS; i++) {
		char a[INET_ADDRSTRLEN];
		if (st.nsaddr_list[i].sin_family != AF_INET ||
		    !inet_ntop(AF_INET, &st.nsaddr_list[i].sin_addr, a, sizeof(a)))
			continue;
		int w = snprintf(buf + at, (size_t)(len - at), "%s%s", n ? "," : "", a);
		if (w < 0 || w >= len - at)
			break;
		at += w;
		n++;
	}
#ifdef __APPLE__
	res_ndestroy(&st);      /* (Apple's: frees what res_ninit allocated) */
#else
	res_nclose(&st);
#endif
	return n;
}
