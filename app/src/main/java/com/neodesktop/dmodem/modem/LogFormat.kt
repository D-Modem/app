// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.modem

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The log as it's shown, copied and saved: each line after its time stamp, a line between the days. */
object LogFormat {
    /** the time stamp's pattern unless the settings say otherwise: "[04:12:03.417]" */
    const val DEFAULT = "[HH:mm:ss.SSS]"

    /** whether [pattern] is one (SimpleDateFormat's; empty for no time stamp) */
    fun valid(pattern: String): Boolean = pattern.isEmpty() || runCatching { SimpleDateFormat(pattern, Locale.US) }.isSuccess

    /** "---- Monday 26 October 2026 ----": before a day's first line */
    fun day(ms: Long, zone: TimeZone = TimeZone.getDefault()): String =
        "---- " + SimpleDateFormat("EEEE d MMMM yyyy", Locale.US).apply { timeZone = zone }.format(Date(ms)) + " ----"

    /** the lines, each after its time stamp in [pattern] ([DEFAULT] if it isn't one; none if empty), the days between */
    fun render(lines: List<LogLine>, pattern: String = DEFAULT, zone: TimeZone = TimeZone.getDefault()): List<String> {
        val stamp = if (pattern.isEmpty()) null
        else SimpleDateFormat(if (valid(pattern)) pattern else DEFAULT, Locale.US).apply { timeZone = zone }
        val key = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = zone }
        val out = ArrayList<String>(lines.size + 2)
        var last: String? = null
        for (l in lines) {
            val d = Date(l.ms)
            val k = key.format(d)
            if (k != last) {
                out += day(l.ms, zone)
                last = k
            }
            out += if (stamp == null) l.text else stamp.format(d) + " " + l.text
        }
        return out
    }

    /** "2026-10-26 04:12:03 PDT": when the app started, in its first line */
    fun now(ms: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US).apply { timeZone = zone }.format(Date(ms))
}
