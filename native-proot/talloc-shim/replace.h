/* SPDX-License-Identifier: LGPL-3.0-or-later (as talloc) -- the little of Samba's lib/replace that talloc.c uses */
#ifndef DMODEM_TALLOC_REPLACE_H
#define DMODEM_TALLOC_REPLACE_H
#include <stdarg.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <sys/types.h>
#define _PUBLIC_ __attribute__((visibility("default")))
#ifndef likely
#define likely(x) __builtin_expect(!!(x), 1)
#define unlikely(x) __builtin_expect(!!(x), 0)
#endif
#ifndef discard_const_p
#define discard_const(ptr) ((void *)((uintptr_t)(ptr)))
#define discard_const_p(type, ptr) ((type *)discard_const(ptr))
#endif
#ifndef MIN
#define MIN(a, b) ((a) < (b) ? (a) : (b))
#endif
#define HAVE_VA_COPY 1
#define HAVE_INTPTR_T 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_DESTRUCTOR_ATTRIBUTE 1
#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 4
#define TALLOC_BUILD_VERSION_RELEASE 2
#endif
