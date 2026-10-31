// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildInfoTest {
    /** a release build says its version and the two commits, nothing more */
    @Test
    fun releaseIsPlain() = assertEquals(
        "D-Modem 0.1.0 03a196a (D-Modem-RE e2b9d1c)",
        BuildInfo.describe("0.1.0", "03a196a", "e2b9d1c", "release", ""),
    )

    /** a dirty debug build with a special variant says so after the commits */
    @Test
    fun dirtyDebugSpecial() = assertEquals(
        "D-Modem 0.1.0 03a196a-dirty (D-Modem-RE e2b9d1c-dirty), debug, special: agere-iso",
        BuildInfo.describe("0.1.0", "03a196a-dirty", "e2b9d1c-dirty", "debug", "agere-iso"),
    )

    /** this build's line has a commit (or "unknown") after the version */
    @Test
    fun thisBuildHasACommit() = assertTrue(BuildInfo.described, Regex("D-Modem \\S+ ([0-9a-f]{7}(-dirty)?|unknown) .*").matches(BuildInfo.described))
}
