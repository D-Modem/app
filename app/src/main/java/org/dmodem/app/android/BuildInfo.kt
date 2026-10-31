// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android

/** which build this is: logged when the modem starts, so a log says what made it */
object BuildInfo {
    /** "D-Modem 0.1.0 03a196a-dirty (D-Modem-RE e2b9d1c), debug, special: agere-iso" */
    val described: String
        get() = describe(BuildConfig.VERSION_NAME, BuildConfig.COMMIT, BuildConfig.RE_COMMIT, BuildConfig.BUILD_TYPE, BuildConfig.SPECIAL)

    /** see [described]: the build type left out if it's a release, the special build's name if it has one */
    fun describe(version: String, commit: String, reCommit: String, buildType: String, special: String): String = buildString {
        append("D-Modem $version $commit (D-Modem-RE $reCommit)")
        if (buildType != "release") append(", $buildType")
        if (special.isNotEmpty()) append(", special: $special")
    }
}
