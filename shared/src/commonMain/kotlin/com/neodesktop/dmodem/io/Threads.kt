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

/** [f] on a thread of its own for blocking work (the JVM's daemon threads: coroutines' I/O threads) */
@OptIn(DelicateCoroutinesApi::class)
fun background(@Suppress("UNUSED_PARAMETER") name: String, f: () -> Unit): Job = GlobalScope.launch(Dispatchers.IO) { f() }

/** waited for until counted down, once (the JVM's CountDownLatch(1)) */
class Latch {
    private val done = CompletableDeferred<Unit>()
    fun countDown() {
        done.complete(Unit)
    }
    fun await() = runBlocking { done.await() }
}
