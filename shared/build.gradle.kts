// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
//
// What the Android app and the iOS app share, in Kotlin Multiplatform:
// the terminal's emulator (VT/xterm, its screen, Sixel, the character
// sets, the keys, the characters' widths, the themes), the file
// transfers (X/Y/ZMODEM, Kermit), and the I/O they're on (io/). The Android app
// has its JVM side; the iOS app its framework (iOS devices, simulator).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    // (Kotlin/Native on Linux: the common tests run native here and in CI, as on iOS)
    linuxX64()
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "DModemShared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
}
