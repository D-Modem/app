// swift-tools-version:5.9
// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
//
// DModemKit: the iOS app's modem, apart from its views: the engine
// (dmodem_engine.h) from Swift, its DTE, the settings, the session the
// views watch. On Apple's systems the engine is build-apple's XCFramework
// (ios/build-native.sh); on Linux the host build's libraries (cmake -S
// native-test -B build-host, or DMODEM_HOST_BUILD), so its tests run
// there too.
import Foundation
import PackageDescription

#if os(Linux)
let host = ProcessInfo.processInfo.environment["DMODEM_HOST_BUILD"] ?? "../../build-host"
let engine: Target = .systemLibrary(name: "DModemEngine", path: "Sources/DModemEngineHost")
let engineLinking: [LinkerSetting] = [
    .unsafeFlags([
        "-L\(host)/app", "-L\(host)/app/d-modem-re/dmodemd", "-L\(host)/_deps/libre-install/lib",
    ]),
    .linkedLibrary("dmodem_engine"), .linkedLibrary("dmodem_sip"), .linkedLibrary("re"),
    .linkedLibrary("ssl"), .linkedLibrary("crypto"), .linkedLibrary("stdc++"), .linkedLibrary("dl"),
    .linkedLibrary("m"), .linkedLibrary("resolv"),
]
#else
let engine: Target = .binaryTarget(name: "DModemEngine", path: "../../build-apple/DModemEngine.xcframework")
let engineLinking: [LinkerSetting] = [.linkedLibrary("c++"), .linkedLibrary("resolv")]
#endif

let package = Package(
    name: "DModemKit",
    platforms: [.macOS(.v12), .iOS(.v15)],
    products: [.library(name: "DModemKit", targets: ["DModemKit"])],
    targets: [
        engine,
        .target(name: "DModemKit", dependencies: ["DModemEngine"], linkerSettings: engineLinking),
        .testTarget(name: "DModemKitTests", dependencies: ["DModemKit"]),
    ]
)
