// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.io

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** an IPv4 address: on the JVM java.net.Inet4Address itself (the app's own as it was) */
expect class Inet4

/** the address of these four octets */
expect fun inet4(octets: ByteArray): Inet4

/** its four octets */
expect val Inet4.octets: ByteArray

/** its dotted text: 10.64.64.1 */
expect val Inet4.dotted: String

/**
 * Work later or now, one piece at a time in order, off the caller's
 * thread (the JVM's single-threaded scheduled executor).
 */
class Timer {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    /** [f] in [ms]: cancel() the job not to */
    fun schedule(ms: Long, f: () -> Unit): Job = scope.launch {
        if (ms > 0) delay(ms)
        f()
    }

    fun execute(f: () -> Unit): Job = schedule(0, f)

    /** nothing more run */
    fun shutdown() = scope.cancel()
}
