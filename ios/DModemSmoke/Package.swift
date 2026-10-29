// swift-tools-version:5.9
// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 D-Modem Authors
//
// The modem from Swift: the engine's XCFramework (ios/build-native.sh)
// and tests that run it on macOS through its C API, as the iOS app will.
import PackageDescription

let package = Package(
    name: "DModemSmoke",
    platforms: [.macOS(.v12), .iOS(.v15)],
    targets: [
        .binaryTarget(name: "DModemEngine", path: "../../build-apple/DModemEngine.xcframework"),
        .testTarget(
            name: "DModemSmokeTests",
            dependencies: ["DModemEngine"],
            linkerSettings: [.linkedLibrary("c++")]
        ),
    ]
)
