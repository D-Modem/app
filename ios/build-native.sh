#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Neo-Desktop
#
# The modem for Apple's systems: the app's engine (app/src/main/cpp) with
# D-Modem-RE, libre and Mbed TLS, built for iOS (devices), the iOS
# simulator and macOS, each as one static library, together as
# build-apple/DModemEngine.xcframework (its header dmodem_engine.h, its
# module DModemEngine: Swift imports it).
#
#   ios/build-native.sh            (macOS with Xcode, CMake and Ninja)
#
# DEPLOYMENT: the oldest iOS (15.0) and macOS (12.0) it runs on.
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=$ROOT/build-apple
IOS_MIN=${IOS_MIN:-15.0}
MACOS_MIN=${MACOS_MIN:-12.0}
mkdir -p "$OUT"

# build <name> <system> <sdk> <archs> <min>: the engine's static libraries
# merged into $OUT/<name>/libDModemEngine.a
build() {
	name=$1 system=$2 sdk=$3 archs=$4 min=$5
	dir=$OUT/$name
	echo "== $name ($sdk, $archs)"
	set -- -S "$ROOT/app/src/main/cpp" -B "$dir/build" -G Ninja \
		-DCMAKE_BUILD_TYPE=Release \
		-DCMAKE_OSX_SYSROOT="$sdk" \
		-DCMAKE_OSX_ARCHITECTURES="$archs" \
		-DCMAKE_OSX_DEPLOYMENT_TARGET="$min" \
		-DDMODEM_CRYPTO=mbedtls
	if [ "$system" = iOS ]; then
		# (no app bundles: D-Modem-RE's dmodemd, which is never built
		# here, is an executable that CMake would make a bundle on iOS,
		# and its install rule has no BUNDLE DESTINATION for one)
		set -- "$@" -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_MACOSX_BUNDLE=OFF
	fi
	cmake "$@"
	cmake --build "$dir/build" --target dmodem_engine
	# the engine, the modem (libdmodem_sip) and what it needs (libre,
	# Mbed TLS's crypto): one library
	libs="$(find "$dir/build" -name libdmodem_engine.a -o \
		-name libdmodem_sip.a | sort) \
		$(find "$dir/build" -path '*libre-install/lib/*' \( \
		-name libre.a -o -name libmbedcrypto.a \) | sort)"
	echo "$libs"
	libtool -static -o "$dir/libDModemEngine.a" $libs
}

build ios iOS iphoneos arm64 "$IOS_MIN"
build ios-simulator iOS iphonesimulator "arm64;x86_64" "$IOS_MIN"
build macos Darwin macosx "arm64;x86_64" "$MACOS_MIN"

# the header and its module
inc=$OUT/include
rm -rf "$inc"
mkdir -p "$inc"
cp "$ROOT/app/src/main/cpp/dmodem_engine.h" "$inc/"
cat > "$inc/module.modulemap" <<MAP
module DModemEngine {
    header "dmodem_engine.h"
    link "c++"
    export *
}
MAP

rm -rf "$OUT/DModemEngine.xcframework"
xcodebuild -create-xcframework \
	-library "$OUT/ios/libDModemEngine.a" -headers "$inc" \
	-library "$OUT/ios-simulator/libDModemEngine.a" -headers "$inc" \
	-library "$OUT/macos/libDModemEngine.a" -headers "$inc" \
	-output "$OUT/DModemEngine.xcframework"
echo "built $OUT/DModemEngine.xcframework"
