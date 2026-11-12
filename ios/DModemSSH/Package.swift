// swift-tools-version:5.9
// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
//
// DModemSSH: the iOS app's SSH client, an interactive shell on a pty, on
// Apple's SwiftNIO SSH. Apart from the app so that it builds and its tests
// run on Linux too (against a local sshd, when DMODEM_SSH_TEST names one).
import PackageDescription

let package = Package(
    name: "DModemSSH",
    platforms: [.macOS(.v12), .iOS(.v15)],
    products: [.library(name: "DModemSSH", targets: ["DModemSSH"])],
    dependencies: [
        .package(url: "https://github.com/apple/swift-nio.git", from: "2.65.0"),
        .package(url: "https://github.com/apple/swift-nio-ssh.git", from: "0.9.0"),
    ],
    targets: [
        .target(name: "DModemSSH", dependencies: [
            .product(name: "NIOCore", package: "swift-nio"),
            .product(name: "NIOPosix", package: "swift-nio"),
            .product(name: "NIOSSH", package: "swift-nio-ssh"),
        ]),
        .testTarget(name: "DModemSSHTests", dependencies: ["DModemSSH"]),
    ]
)
