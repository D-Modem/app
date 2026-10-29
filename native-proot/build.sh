#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 D-Modem Authors
#
# proot (Termux's: external/proot) and its loader, with talloc, built for an
# Android ABI with the NDK, or for this host (the unit tests):
#
#   native-proot/build.sh <arm64-v8a|armeabi-v7a|x86|x86_64|host> <out dir> [ndk dir]
#
# Out: libproot.so and libproot-loader.so: programs, named as libraries so
# that Android installs them in the app's native library directory, the
# one place an app may run programs from (Android 10 on). The loader is
# kept apart from proot (not extracted to a temporary file and run from
# there, which Android forbids); PROOT_LOADER names it at run time.
set -eu

ABI=$1
OUT=$2
NDK=${3:-${ANDROID_NDK_HOME:-}}
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
TALLOC_VERSION=2.4.2
TALLOC_SHA256=85ecf9e465e20f98f9950a52e9a411e14320bc555fa257d87697b7e7a9b1d8a6
API=26

WORK=${PROOT_WORK:-$OUT/.build}
mkdir -p "$WORK" "$OUT"

if [ "$ABI" = host ]; then
	CC=${CC:-cc}
	AR=${AR:-ar}
	STRIP=${STRIP:-strip}
	OBJCOPY=${OBJCOPY:-objcopy}
	OBJDUMP=${OBJDUMP:-objdump}
	EXTRA_LDFLAGS=
else
	[ -n "$NDK" ] || { echo "no NDK (third argument or ANDROID_NDK_HOME)" >&2; exit 1; }
	BIN=$(echo "$NDK"/toolchains/llvm/prebuilt/*/bin)
	case $ABI in
		arm64-v8a) TRIPLE=aarch64-linux-android ;;
		armeabi-v7a) TRIPLE=armv7a-linux-androideabi ;;
		x86) TRIPLE=i686-linux-android ;;
		x86_64) TRIPLE=x86_64-linux-android ;;
		*) echo "unknown ABI $ABI" >&2; exit 1 ;;
	esac
	CC="$BIN/$TRIPLE$API-clang"
	AR="$BIN/llvm-ar"
	STRIP="$BIN/llvm-strip"
	OBJCOPY="$BIN/llvm-objcopy"
	OBJDUMP="$BIN/llvm-objdump"
	# 16 KB pages (Android 15 on)
	EXTRA_LDFLAGS="-Wl,-z,max-page-size=16384"
fi

# GNU awk: proot's makefile makes the arm64 loader's symbol table with it (strtonum)
command -v gawk >/dev/null || { echo "proot's build needs GNU awk (gawk)" >&2; exit 1; }
mkdir -p "$WORK/bin"
ln -sf "$(command -v gawk)" "$WORK/bin/awk"
PATH=$WORK/bin:$PATH
export PATH

# talloc: its one source file, with the little of Samba's lib/replace it uses
TARBALL=$WORK/talloc-$TALLOC_VERSION.tar.gz
if [ ! -f "$TARBALL" ]; then
	curl -fsSL -o "$TARBALL.part" "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz"
	mv "$TARBALL.part" "$TARBALL"
fi
echo "$TALLOC_SHA256  $TARBALL" | sha256sum -c - >/dev/null || { echo "talloc's checksum doesn't match" >&2; rm -f "$TARBALL"; exit 1; }
rm -rf "$WORK/talloc"
mkdir -p "$WORK/talloc"
tar xzf "$TARBALL" -C "$WORK/talloc" --strip-components=1
$CC -c -O2 -fPIC -I"$HERE/talloc-shim" -I"$WORK/talloc" "$WORK/talloc/talloc.c" -o "$WORK/talloc.o"
rm -f "$WORK/libtalloc.a"
$AR rcs "$WORK/libtalloc.a" "$WORK/talloc.o"

# proot, built in a copy of its tree (its makefile's out-of-tree build doesn't work),
# string.h added to the one file that forgets it (an error with clang)
rm -rf "$WORK/proot"
cp -r "$ROOT/external/proot/src" "$WORK/proot"
sed -i '1i #include <string.h>' "$WORK/proot/extension/ashmem_memfd/ashmem_memfd.c"
# (the flags from the environment: the makefile adds its own to them, which it can't to ones on its command line)
CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I$WORK/talloc" \
CFLAGS="-Wall -Wextra -O2 -DARG_MAX=131072" \
LDFLAGS="-L$WORK -Wl,-z,noexecstack $EXTRA_LDFLAGS" \
make -C "$WORK/proot" -j"$(nproc 2>/dev/null || echo 4)" proot loader/loader \
	CC="$CC" STRIP="$STRIP" OBJCOPY="$OBJCOPY" OBJDUMP="$OBJDUMP" \
	PROOT_UNBUNDLE_LOADER=/nonexistent HAS_LOADER_32BIT= >"$WORK/make.log" 2>&1 \
	|| { tail -40 "$WORK/make.log" >&2; exit 1; }

cp "$WORK/proot/proot" "$OUT/libproot.so"
cp "$WORK/proot/loader/loader" "$OUT/libproot-loader.so"
$STRIP "$OUT/libproot.so"
chmod 755 "$OUT/libproot.so" "$OUT/libproot-loader.so"
echo "built $OUT/libproot.so $OUT/libproot-loader.so"
