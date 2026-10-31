// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.local

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
 * The app's Linux systems (run with proot): where they live on this
 * phone (each in linux/<id>), their installs and removals (which go on
 * while the user moves about the app; one at a time), and the shared
 * storage they're given.
 */
@SuppressLint("StaticFieldLeak") // the application's context only
object Linux {

    sealed class Work {
        object Idle : Work()
        data class Busy(val id: String, val what: String, val fraction: Float?) : Work()
        data class Failed(val id: String, val why: String) : Work()
    }

    private val _work = MutableStateFlow<Work>(Work.Idle)
    val work: StateFlow<Work> = _work.asStateFlow()

    /** bumped when one is installed or removed (for the screens showing them) */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** where the systems live and what they're given: the phone's (the tests' own in theirs) */
    class Place(
        val root: File,
        val programDir: File,
        val tmpDir: File,
        val machine: Machine?,
        val storage: () -> File? = { null },
        val nameServers: () -> List<String> = { LinuxSystem.DEFAULT_DNS },
        val extraEnv: Map<String, String> = emptyMap(),
        val http: Http = Http(),
    )

    var place: (Context) -> Place = { ctx -> phonePlace(ctx) }

    /** where the systems live on the phone: the app's files, its programs, its cache, shared storage when allowed, the phone's DNS */
    private fun phonePlace(app: Context) = Place(
        root = File(app.filesDir, "linux"),
        programDir = File(app.applicationInfo.nativeLibraryDir ?: app.filesDir.path),
        tmpDir = File(app.cacheDir, "proot"),
        machine = machine(),
        storage = { if (storageGranted(app)) Environment.getExternalStorageDirectory() else null },
        nameServers = { nameServers(app) },
    )

    /** this phone's processor, or null where no Linux is built for it */
    fun machine(): Machine? = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { Machine.forAbi(it) }

    /** the system with [id] (installed or not), where [place] has it */
    fun system(context: Context, id: String): LinuxSystem {
        val p = place(context.applicationContext)
        return LinuxSystem(File(p.root, id), p.programDir, p.tmpDir, p.storage, p.nameServers, p.extraEnv)
    }

    /** the systems installed, the first installed first */
    fun systems(context: Context): List<LinuxSystem> {
        val root = place(context.applicationContext).root
        return (root.listFiles() ?: emptyArray()).filter { it.isDirectory }.map { system(context, it.name) }
            .filter { it.installed }.sortedBy { it.info?.installed ?: 0 }
    }

    /**
     * which of the catalog's distributions are built for this phone (the
     * Linux Containers index asked: blocking); all of them when it can't
     * be asked
     */
    fun available(context: Context): Map<String, Boolean> {
        val p = place(context.applicationContext)
        val m = p.machine ?: return Distro.CATALOG.associate { it.id to false }
        val index = runCatching { Distro.lxcIndex(p.http) }.getOrNull()
        return Distro.CATALOG.associate { d ->
            d.id to when (d) {
                is Distro.Lxc -> index == null || d.availableFor(m, index)
                is Distro.Ubuntu -> m.ubuntu != null
                else -> true
            }
        }
    }

    private val _done = MutableStateFlow<String?>(null)
    /** the id of the system installed last (for the screen that asked for it to open it) */
    val done: StateFlow<String?> = _done.asStateFlow()

    /** [d] installed (in the background); [done] says so when it is */
    fun install(context: Context, d: Distro) {
        if (_work.value is Work.Busy) return
        val p = place(context.applicationContext)
        val sys = system(context, d.id)
        _work.value = Work.Busy(d.id, "Finding ${d.name}…", null)
        scope.launch {
            try {
                if (sys.installed) throw java.io.IOException("${sys.name} is installed already")
                val m = p.machine ?: throw java.io.IOException("no Linux for this phone's processor")
                val r = d.resolve(m, p.http)
                sys.install(r, p.http) { what, f -> _work.value = Work.Busy(d.id, what, f) }
                _work.value = Work.Idle
                _done.value = sys.id
                _changes.value++
            } catch (e: Exception) {
                _work.value = Work.Failed(d.id, e.message ?: e.javaClass.simpleName)
                _changes.value++
            }
        }
    }

    /** the system with [id] removed (in the background), unless work is going on */
    fun remove(context: Context, id: String) {
        if (_work.value is Work.Busy) return
        if (_done.value == id) _done.value = null
        val sys = system(context, id)
        _work.value = Work.Busy(id, "Removing ${sys.name}…", null)
        scope.launch {
            try {
                sys.remove()
                _work.value = Work.Idle
            } catch (e: Exception) {
                _work.value = Work.Failed(id, e.message ?: e.javaClass.simpleName)
            }
            _changes.value++
        }
    }

    /** a failed install's or removal's word cleared */
    fun clearFailure() {
        if (_work.value is Work.Failed) _work.value = Work.Idle
    }

    /** the disk a system takes (links not followed) */
    fun size(sys: LinuxSystem): Long {
        var total = 0L
        sys.dir.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }.forEach {
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
