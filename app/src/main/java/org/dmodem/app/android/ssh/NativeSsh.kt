// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.ssh

/**
 * libdmodemssh (native-ssh, on libssh), the SSH client every D-Modem app
 * shares: each connection a handle, polled on a thread of its own for its
 * events; the other calls from any thread. native-ssh/include/dmodemssh.h
 * tells what each does.
 */
object NativeSsh {
    const val EV_HOSTKEY = 1
    const val EV_AUTH = 2
    const val EV_PROMPTS = 3
    const val EV_READY = 4
    const val EV_OPEN = 5
    const val EV_DATA = 6
    const val EV_EOF = 7
    const val EV_EXIT = 8
    const val EV_CLOSE = 9
    const val EV_INCOMING = 10
    const val EV_LISTEN = 11
    const val EV_CLOSED = 12

    const val AUTH_PASSWORD = 1
    const val AUTH_PUBLICKEY = 2
    const val AUTH_INTERACTIVE = 4

    /** an incoming channel that is the agent's (ssh -A) */
    const val IN_AGENT = -1

    init {
        try {
            System.loadLibrary("dmodem_ssh_jni")
        } catch (e: UnsatisfiedLinkError) {
            // (a JVM's tests: loaded already by another class loader, Robolectric's sandboxes
            // each their own; a copy of the library is another library to the JVM)
            if (e.message?.contains(Regex("another class ?loader", RegexOption.IGNORE_CASE)) != true) throw e
            val name = System.mapLibraryName("dmodem_ssh_jni")
            val lib = (System.getProperty("java.library.path") ?: "").split(java.io.File.pathSeparator)
                .map { java.io.File(it, name) }.firstOrNull { it.isFile } ?: throw e
            val copy = java.io.File.createTempFile("dmodem_ssh_jni", ".so").apply { deleteOnExit() }
            lib.copyTo(copy, overwrite = true)
            System.load(copy.path)
        }
    }

    /** a connection to make to user@host:port (nothing done until polled): its handle, or 0 */
    @JvmStatic external fun create(host: String, port: Int, user: String): Long

    /** the connection dropped (if it's up) and freed */
    @JvmStatic external fun free(h: Long)

    /** an option, before the first poll ("config", "knownhosts", "hostkeys", "timeout", "compression"): 0, or -1 */
    @JvmStatic external fun option(h: Long, name: String, value: String): Int

    /**
     * the next event: [ev] is (1, type, channel, value), (0, ...) when [ms]
     * passed with none, or (-1, ...) once the connection is over; the
     * event's data, if it has any
     */
    @JvmStatic external fun poll(h: Long, ms: Int, ev: IntArray): ByteArray?

    /** the connection closed by the user: an EV_CLOSED with no reason comes */
    @JvmStatic external fun disconnect(h: Long)

    /** the server's key trusted ([ok]) or not; [remember]: added to the known_hosts option's file */
    @JvmStatic external fun trust(h: Long, ok: Boolean, remember: Boolean)

    /** a login by password */
    @JvmStatic external fun authPassword(h: Long, password: String)

    /** a login by a private key's text: 0, or -1 when it can't be read with [passphrase] (nothing tried) */
    @JvmStatic external fun authKey(h: Long, key: String, passphrase: String?): Int

    /** a login by the server's questions (keyboard-interactive) */
    @JvmStatic external fun authInteractive(h: Long)

    /** no more logins tried: the connection closes */
    @JvmStatic external fun authCancel(h: Long)

    /** an EV_PROMPTS round's instruction */
    @JvmStatic external fun instruction(h: Long): String

    /** an EV_PROMPTS round's question [i] ([echo]'s first: whether its answer may be shown) */
    @JvmStatic external fun prompt(h: Long, i: Int, echo: BooleanArray): String

    /** the answer to question [i] */
    @JvmStatic external fun answer(h: Long, i: Int, answer: String)

    /** the round's answers sent */
    @JvmStatic external fun answered(h: Long)

    /** a shell on a pty: its channel, or -1 */
    @JvmStatic external fun shell(h: Long, term: String, cols: Int, rows: Int, agent: Boolean): Int

    /** a command run, with no pty: its channel, or -1 */
    @JvmStatic external fun exec(h: Long, command: String, agent: Boolean): Int

    /** a TCP connection from the server to host:port, for one from [from]:[fromPort]: its channel, or -1 */
    @JvmStatic external fun direct(h: Long, host: String, port: Int, from: String, fromPort: Int): Int

    /** the server asked to listen on [address]:[port] (null: its default; 0: a port it picks): the request's number, or -1 */
    @JvmStatic external fun listen(h: Long, address: String?, port: Int): Int

    /** that listen stopped */
    @JvmStatic external fun unlisten(h: Long, address: String?, port: Int)

    /** data to a channel: the bytes still kept for the server's window, or -1 if it isn't open */
    @JvmStatic external fun write(h: Long, channel: Int, data: ByteArray, offset: Int, length: Int): Int

    /** the channel's end of data sent */
    @JvmStatic external fun eof(h: Long, channel: Int)

    /** the channel closed (an EV_CLOSE comes) */
    @JvmStatic external fun close(h: Long, channel: Int)

    /** a shell's pty resized */
    @JvmStatic external fun resize(h: Long, channel: Int, cols: Int, rows: Int)

    /** a private key (OpenSSH's, PEM) written again in OpenSSH's form under [newPassphrase] (empty: none), or null if it can't be read */
    @JvmStatic external fun keyExport(key: String, passphrase: String?, newPassphrase: String?): String?
}
