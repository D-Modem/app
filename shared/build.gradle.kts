// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
//
// What the Android app and the iOS app share, in Kotlin Multiplatform:
// the terminal's emulator (VT/xterm, its screen, Sixel, the character
// sets, the keys, the characters' widths, the themes), the file
// transfers (X/Y/ZMODEM, Kermit), PPP, fax, the line to another phone,
// and the I/O they're on (io/). The Android app
// has its JVM side; the iOS app its framework (iOS devices, simulator).
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

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
    // (the two as one XCFramework for the iOS app: ./gradlew :shared:assembleDModemSharedXCFramework)
    val xcf = XCFramework("DModemShared")
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "DModemShared"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
}
