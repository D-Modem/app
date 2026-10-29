// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem

import java.io.InputStream

/**
 * The tests' logs: what a test prints goes to its own file in the run's
 * folder (build/test-runs/<date_time>/..., the root build's listener; kept
 * only when something failed). Line tools' output and the like, printed.
 */
object TestLogs {
    /** [line] in the current test's log, [tag] before it */
    fun note(tag: String, line: String) = println("[$tag] $line")

    /** [input]'s lines into the test's log as they come, on a thread of their own; each to [also] too */
    fun echo(tag: String, input: InputStream, also: (String) -> Unit = {}) {
        Thread {
            runCatching { input.bufferedReader().forEachLine { l -> note(tag, l); also(l) } }
        }.apply { isDaemon = true; start() }
    }
}
