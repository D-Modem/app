// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.local

import java.io.File

/**
 * The local shell: Android's own (mksh, /system/bin/sh, with toybox's
 * commands), in a home of the app's. Its environment is the app's (so
 * am, pm, cmd and the like work) with a home, a tmp, the terminal's type
 * and colours, and a start-up file of its own (~/.mkshrc: the system's
 * first, then a coloured prompt and a few aliases; the user's to change).
 *
 * The app can't run programs it writes itself (Android 10 on forbids
 * executing files in an app's own storage), so ~/bin holds scripts run
 * with "sh script", not commands on the PATH.
 */
class LocalShell(
    private val filesDir: File,
    private val cacheDir: File,
    /** the shell (Android's; the host's in the tests) */
    val shell: String = DEFAULT_SHELL,
    private val base: Map<String, String> = System.getenv(),
) {
    val home: File get() = File(filesDir, "home")
    val tmp: File get() = File(cacheDir, "tmp")
    val rc: File get() = File(home, ".mkshrc")

    /** the home, tmp and start-up file made (the start-up file only if there is none: it may be the user's) */
    fun prepare() {
        home.mkdirs()
        tmp.mkdirs()
        if (!rc.exists()) rc.writeText(DEFAULT_RC)
    }

    /** the shell's environment: the app's with its home, tmp, terminal, start-up file and the system's bins first on the PATH */
    fun environment(): Map<String, String> {
        val env = LinkedHashMap(base)
        env["HOME"] = home.path
        env["TMPDIR"] = tmp.path
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["LANG"] = "C.UTF-8"
        env["SHELL"] = shell
        env["ENV"] = rc.path
        env["PWD"] = home.path
        env["PATH"] = listOf("/system/bin", "/system/xbin", "/product/bin", "/vendor/bin", "/apex/com.android.runtime/bin")
            .let { sys -> (sys + (base["PATH"]?.split(':') ?: emptyList())).distinct().joinToString(":") }
        return env
    }

    /** a login shell, interactive */
    fun command(): List<String> = listOf(shell, "-l")

    /** a link to a new shell, prepared, not started */
    fun link(cols: Int = 80, rows: Int = 24): LocalLink {
        prepare()
        return LocalLink(command(), environment(), home.path, cols, rows)
    }

    companion object {
        const val DEFAULT_SHELL = "/system/bin/sh"

        /** (mksh: \001 marks the prompt's escapes, the first \001\r telling it so, for line editing) */
        val DEFAULT_RC = """
            |# D-Modem's local shell (mksh). Yours to change: it is kept as it is.
            |[ -r /system/etc/mkshrc ] && . /system/etc/mkshrc
            |
            |: "${'$'}{USER:=${'$'}(id -un 2>/dev/null || echo user)}"
            |export USER
            |PS1=${'$'}'\001\r\001\e[1;32m\001'"${'$'}USER@dmodem"${'$'}'\001\e[0m\001:\001\e[1;34m\001''${'$'}{PWD/#${'$'}HOME/\~}'${'$'}'\001\e[0m\001${'$'} '
            |
            |alias ls='ls --color=auto'
            |alias ll='ls -l'
            |alias la='ls -la'
            |alias grep='grep --color=auto'
            |
            |HISTFILE=${'$'}HOME/.mksh_history
            |HISTSIZE=2000
            |set -o emacs
            |""".trimMargin()
    }
}
