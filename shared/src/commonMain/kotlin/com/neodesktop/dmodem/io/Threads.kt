// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.io

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The threads for blocking work: as many as there is work (I/O's 64, the
 * default, would leave the 65th blocking read waiting for one of the
 * others to end, which may be never)
 */
private val blocking = Dispatchers.IO.limitedParallelism(4096)

/** [f] on a thread of its own for blocking work (the JVM's daemon threads: coroutines' I/O threads) */
@OptIn(DelicateCoroutinesApi::class)
fun background(@Suppress("UNUSED_PARAMETER") name: String, f: () -> Unit): Job = GlobalScope.launch(blocking) { f() }

/** waited for until counted down, once (the JVM's CountDownLatch(1)) */
class Latch {
    private val done = CompletableDeferred<Unit>()
    fun countDown() {
        done.complete(Unit)
    }
    fun await() = runBlocking { done.await() }

    /** waited for up to [ms]: whether it was counted down */
    fun await(ms: Long): Boolean = runBlocking { withTimeoutOrNull(ms) { done.await() } != null }
}
