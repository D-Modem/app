/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Neo-Desktop
 *
 *    c_api_header.c - dmodem_engine.h as a C program has it, for
 *                     c_api_test: its types and calls from C.
 *
 */

#include "dmodem_engine.h"

/* the C API's config and callbacks from C: 0 if the defaults came */
int c_api_header_from_c(void)
{
	dmodem_config c;
	dmodem_callbacks cb = { 0 };

	(void)cb;
	dmodem_config_init(&c);
	return c.sip_port == 5060 ? 0 : 1;
}
