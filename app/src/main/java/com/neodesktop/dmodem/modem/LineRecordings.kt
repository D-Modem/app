// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
package com.neodesktop.dmodem.modem

import android.content.Context
import java.io.File

/**
 * Calls on a USB modem's line recorded, for a bug report (the settings' "Record each call"): D-Modem-RE
 * writes each, off hook to on hook, as a stereo WAV (left what came from the line, right what the modem
 * sent) into the captures folder, where the log screen shares the latest. The last [KEEP] are kept.
 */
object LineRecordings {
    const val KEEP = 5
    private const val PREFIX = "line"

    /** the app's captures folder on external storage (null: none to have) */
    private fun dir(context: Context): File? = context.applicationContext.getExternalFilesDir("captures")

    /** the path D-Modem-RE's DMODEM_USB_REC is given (null: nowhere to record to) */
    fun path(context: Context): String? = dir(context)?.let { File(it, PREFIX).path }

    /** the recordings there are, the newest first */
    fun all(context: Context): List<File> =
        dir(context)?.listFiles { f -> f.name.startsWith("$PREFIX-") && f.name.endsWith(".wav") }
            ?.sortedByDescending { it.name }.orEmpty()

    /** all but the newest [keep] deleted */
    fun prune(context: Context, keep: Int = KEEP) {
        all(context).drop(keep).forEach { it.delete() }
    }
}
