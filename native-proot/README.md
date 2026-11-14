# proot for the local terminal's Linux

`build.sh` builds [Termux's proot](https://github.com/termux/proot)
(`external/proot`, a submodule pinned to a commit) and its loader, with
[talloc](https://talloc.samba.org/) 2.4.2. The script downloads talloc from
samba.org and checks it against its SHA-256. It builds for each Android ABI
with the NDK, or for the host (for the unit tests). Gradle runs the script
(`:app:buildProot`) before each build.

The outputs are programs with the names `libproot.so` and `libproot-loader.so`.
With these names, Android installs them in the native library directory of
the app. From Android 10, an app can run its own programs only from that
directory (not from its data directory). proot runs the programs of the Linux
system through the loader. The loader maps them into memory and does not
execute them.

Necessary tools: a C compiler (the compiler of the NDK for Android), make, GNU awk (`gawk`) and curl.

`talloc-shim/replace.h` replaces the part of the Samba `lib/replace` that
`talloc.c` uses. Thus talloc builds as one file without the waf of Samba.

The build copies the source tree of proot and adds `#include <string.h>` to
`extension/ashmem_memfd/ashmem_memfd.c`. That file needs the include but does
not have it (clang gives an error). The build does not change the submodule.

Licences: proot is GPL-2.0-or-later, talloc LGPL-3.0-or-later. Their
sources are the submodule and the pinned tarball named above.
