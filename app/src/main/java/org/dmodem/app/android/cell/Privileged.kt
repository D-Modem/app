// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.cell

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import org.dmodem.app.android.bridge.UsbGadget

/**
 * D-Modem as a privileged app on a rooted phone (Magisk): a module that
 * puts its APK in /system/priv-app and allows it the privileged
 * permissions it asks for (CALL_AUDIO_INTERCEPTION: a mobile call's
 * sound), from the next boot on. Every privileged permission the app
 * asks for is in the allow list: a priv-app asking one not there can
 * keep the phone from booting.
 */
object Privileged {
    const val MODULE = "/data/adb/modules/dmodem_priv"

    /** whether the phone is rooted with Magisk's modules */
    fun canInstall(): Boolean = UsbGadget.rootAvailable() && UsbGadget.su("test -d /data/adb/modules").first == 0

    /** whether the module is there (installed; privileged after a reboot) */
    fun installed(): Boolean = UsbGadget.su("test -f $MODULE/module.prop").first == 0

    /** the privileged permissions this app asks for */
    fun permissions(context: Context): List<String> {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        return info.requestedPermissions.orEmpty().filter { name ->
            val p = runCatching { pm.getPermissionInfo(name, 0) }.getOrNull() ?: return@filter false
            @Suppress("DEPRECATION")
            val flags = if (android.os.Build.VERSION.SDK_INT >= 28) p.protectionFlags else p.protectionLevel
            flags and PermissionInfo.PROTECTION_FLAG_PRIVILEGED != 0
        }.sorted()
    }

    /** the allow list for [pkg] and [perms] */
    fun allowList(pkg: String, perms: List<String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<permissions>\n")
        append("    <privapp-permissions package=\"$pkg\">\n")
        for (p in perms) append("        <permission name=\"$p\"/>\n")
        append("    </privapp-permissions>\n</permissions>\n")
    }

    /** the module's install script (as root) */
    fun script(apk: String, pkg: String, perms: List<String>, version: String): String {
        val xml = allowList(pkg, perms)
        return """
            set -e
            M=$MODULE
            rm -rf ${'$'}M
            mkdir -p ${'$'}M/system/priv-app/DModem ${'$'}M/system/etc/permissions
            cp '$apk' ${'$'}M/system/priv-app/DModem/DModem.apk
            chmod 644 ${'$'}M/system/priv-app/DModem/DModem.apk
            cat > ${'$'}M/system/etc/permissions/privapp-permissions-$pkg.xml <<'XML'
            ${xml.trimEnd().replace("\n", "\n            ")}
            XML
            chmod 644 ${'$'}M/system/etc/permissions/privapp-permissions-$pkg.xml
            cat > ${'$'}M/module.prop <<'PROP'
            id=dmodem_priv
            name=D-Modem privileged
            version=$version
            versionCode=1
            author=D-Modem
            description=D-Modem as a privileged app: a mobile call's sound for its modem
            PROP
        """.trimIndent()
    }

    /** the module installed: null, or what went wrong */
    fun install(context: Context): String? {
        val pkg = context.packageName
        val apk = context.applicationInfo.sourceDir
        val version = runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull() ?: "1"
        val (code, out) = UsbGadget.su(script(apk, pkg, permissions(context), version))
        return if (code == 0) null else out.ifBlank { "the root shell failed ($code)" }
    }

    /** the module taken out (the app a plain one again after a reboot) */
    fun remove(): String? {
        val (code, out) = UsbGadget.su("rm -rf $MODULE")
        return if (code == 0) null else out
    }
}
