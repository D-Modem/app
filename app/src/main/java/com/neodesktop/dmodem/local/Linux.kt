// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.local

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * The app's Linux (Alpine, run with proot): where it lives on this phone,
 * its install and removal (which go on while the user moves about the
 * app), and the shared storage it is given.
 */
@SuppressLint("StaticFieldLeak") // the application's context only
object Linux {

    sealed class Work {
        object Idle : Work()
        data class Busy(val what: String, val fraction: Float?) : Work()
        data class Failed(val why: String) : Work()
    }

    private val _work = MutableStateFlow<Work>(Work.Idle)
    val work: StateFlow<Work> = _work.asStateFlow()

    /** bumped when it's installed or removed (for the screens showing it) */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** the system on this phone (the tests' own in theirs) */
    var factory: (Context) -> LinuxSystem = { ctx -> phoneSystem(ctx) }

    fun system(context: Context): LinuxSystem = factory(context.applicationContext)

    /** this phone's ABI's Alpine, or null where there is none */
    fun arch(): String? = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { LinuxSystem.archFor(it) }

    private fun phoneSystem(app: Context) = LinuxSystem(
        dir = File(app.filesDir, "linux/alpine"),
        programDir = File(app.applicationInfo.nativeLibraryDir ?: app.filesDir.path),
        tmpDir = File(app.cacheDir, "proot"),
        arch = arch() ?: "aarch64",
        storage = { if (storageGranted(app)) Environment.getExternalStorageDirectory() else null },
        nameServers = { nameServers(app) },
    )

    /** installed (in the background); [onDone] on the main thread's caller's terms: called from IO */
    fun install(context: Context, onDone: () -> Unit = {}) {
        if (_work.value is Work.Busy) return
        val sys = system(context)
        _work.value = Work.Busy("Starting…", null)
        scope.launch {
            try {
                sys.install { what, f -> _work.value = Work.Busy(what, f) }
                _work.value = Work.Idle
                _changes.value++
                onDone()
            } catch (e: Exception) {
                _work.value = Work.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun remove(context: Context) {
        if (_work.value is Work.Busy) return
        val sys = system(context)
        _work.value = Work.Busy("Removing…", null)
        scope.launch {
            try {
                sys.remove()
                _work.value = Work.Idle
            } catch (e: Exception) {
                _work.value = Work.Failed(e.message ?: e.javaClass.simpleName)
            }
            _changes.value++
        }
    }

    fun clearFailure() {
        if (_work.value is Work.Failed) _work.value = Work.Idle
    }

    /** the disk it takes */
    fun size(context: Context): Long {
        var total = 0L
        system(context).dir.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }.forEach {
            if (it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath())) total += it.length()
        }
        return total
    }

    // shared storage --------------------------------------------------

    /** whether the app may read and write the phone's shared storage by its paths (all of it) */
    fun storageGranted(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }.getOrDefault(false) // (no storage to ask about: not granted)

    /** Android 11 on: the system's page to allow "all files access"; null before (a runtime permission there) */
    fun storageSettingsIntent(context: Context): Intent? = if (Build.VERSION.SDK_INT >= 30) {
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
    } else {
        null
    }

    /** the phone's name servers, or public ones */
    private fun nameServers(context: Context): List<String> = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val lp = cm?.getLinkProperties(cm.activeNetwork)
        lp?.dnsServers?.mapNotNull { it.hostAddress?.substringBefore('%') }?.takeIf { it.isNotEmpty() } ?: LinuxSystem.DEFAULT_DNS
    } catch (_: Exception) {
        LinuxSystem.DEFAULT_DNS
    }
}
