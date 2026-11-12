#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Neo-Desktop
#
# The modem for Apple's systems: the app's engine (app/src/main/cpp) with
# D-Modem, libre and Mbed TLS, built for iOS (devices), the iOS
# simulator and macOS, each as one static library, together as
# build-apple/DModemEngine.xcframework (its header dmodem_engine.h, its
# module DModemEngine: Swift imports it).  And the SSH client
# (native-ssh) the same way, as build-apple/DModemSSHNative.xcframework.
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
		# (no app bundles: D-Modem's dmodemd, which is never built
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

# The SSH client (native-ssh: libdmodemssh on libssh and Mbed TLS) as
# build-apple/DModemSSHNative.xcframework (module CDModemSSH), for
# ios/DModemSSH.  Its Mbed TLS is not the engine's (libre's, another
# release, without threads), so each arch's libraries are linked into one
# object whose only global symbols are dmc_ssh_*: the two never meet.

# ssh_arch <name> <system> <sdk> <arch> <min>: $OUT/<name>/ssh-<arch>.o
ssh_arch() {
	name=$1 system=$2 sdk=$3 arch=$4 min=$5
	dir=$OUT/$name/ssh-$arch
	echo "== SSH $name ($sdk, $arch)"
	set -- -S "$ROOT/native-ssh" -B "$dir" -G Ninja \
		-DCMAKE_BUILD_TYPE=Release \
		-DCMAKE_OSX_SYSROOT="$sdk" \
		-DCMAKE_OSX_ARCHITECTURES="$arch" \
		-DCMAKE_OSX_DEPLOYMENT_TARGET="$min"
	[ "$system" = iOS ] && set -- "$@" -DCMAKE_SYSTEM_NAME=iOS
	cmake "$@"
	cmake --build "$dir" --target dmodemssh
	objs=$dir/objs
	rm -rf "$objs"
	mkdir -p "$objs"
	# (each archive's objects apart: their names repeat between them)
	i=0
	for a in "$dir/libdmodemssh.a" "$dir/dmodem-libssh/lib/libssh.a" \
		"$dir"/dmodem-libssh/lib/libmbed*.a "$dir/dmodem-libssh/lib/libeverest.a" \
		"$dir/dmodem-libssh/lib/libp256m.a"; do
		i=$((i + 1))
		mkdir -p "$objs/$i"
		(cd "$objs/$i" && ar x "$a")
	done
	printf '_dmc_ssh_*\n' > "$dir/exports"
	xcrun ld -r -arch "$arch" -exported_symbols_list "$dir/exports" \
		"$objs"/*/*.o -o "$OUT/$name/ssh-$arch.o"
}

# ssh_slice <name> <system> <sdk> <min> <arch>...: $OUT/<name>/libDModemSSHNative.a
ssh_slice() {
	name=$1 system=$2 sdk=$3 min=$4
	shift 4
	objs=
	for arch in "$@"; do
		ssh_arch "$name" "$system" "$sdk" "$arch" "$min"
		objs="$objs $OUT/$name/ssh-$arch.o"
	done
	rm -f "$OUT/$name/libDModemSSHNative.a"
	libtool -static -o "$OUT/$name/libDModemSSHNative.a" $objs
}

ssh_slice ios iOS iphoneos "$IOS_MIN" arm64
ssh_slice ios-simulator iOS iphonesimulator "$IOS_MIN" arm64 x86_64
ssh_slice macos Darwin macosx "$MACOS_MIN" arm64 x86_64

sinc=$OUT/include-ssh
rm -rf "$sinc"
mkdir -p "$sinc"
cp "$ROOT/native-ssh/include/dmodemssh.h" "$sinc/"
cat > "$sinc/module.modulemap" <<MAP
module CDModemSSH {
    header "dmodemssh.h"
    export *
}
MAP

rm -rf "$OUT/DModemSSHNative.xcframework"
xcodebuild -create-xcframework \
	-library "$OUT/ios/libDModemSSHNative.a" -headers "$sinc" \
	-library "$OUT/ios-simulator/libDModemSSHNative.a" -headers "$sinc" \
	-library "$OUT/macos/libDModemSSHNative.a" -headers "$sinc" \
	-output "$OUT/DModemSSHNative.xcframework"
echo "built $OUT/DModemSSHNative.xcframework"
