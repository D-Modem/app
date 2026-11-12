// swift-tools-version:5.9
// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
//
// DModemSSH: the iOS app's SSH client, an interactive shell on a pty, on
// libdmodemssh (native-ssh: the same engine as the Android and desktop
// apps', on libssh). On Apple's systems that is build-apple's XCFramework
// (ios/build-native.sh); on Linux the host build's libraries (cmake -S
// native-ssh -B build-ssh, or DMODEM_SSH_HOST_BUILD), so that its tests
// run there too (against a local sshd, when DMODEM_SSH_TEST names one).
import Foundation
import PackageDescription

#if os(Linux)
let host = ProcessInfo.processInfo.environment["DMODEM_SSH_HOST_BUILD"] ?? "../../build-ssh"
let native: Target = .systemLibrary(name: "CDModemSSH", path: "Sources/CDModemSSHHost")
let nativeLinking: [LinkerSetting] = [
    .unsafeFlags(["-L\(host)", "-L\(host)/dmodem-libssh/lib"]),
    .linkedLibrary("dmodemssh"), .linkedLibrary("ssh"),
    .linkedLibrary("mbedtls"), .linkedLibrary("mbedx509"), .linkedLibrary("mbedcrypto"),
    .linkedLibrary("everest"), .linkedLibrary("p256m"), .linkedLibrary("pthread"),
]
#else
let native: Target = .binaryTarget(name: "CDModemSSH", path: "../../build-apple/DModemSSHNative.xcframework")
let nativeLinking: [LinkerSetting] = []
#endif

let package = Package(
    name: "DModemSSH",
    platforms: [.macOS(.v12), .iOS(.v15)],
    products: [.library(name: "DModemSSH", targets: ["DModemSSH"])],
    targets: [
        native,
        .target(name: "DModemSSH", dependencies: ["CDModemSSH"], linkerSettings: nativeLinking),
        .testTarget(name: "DModemSSHTests", dependencies: ["DModemSSH"]),
    ]
)
