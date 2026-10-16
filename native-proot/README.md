# proot for the local terminal's Linux

`build.sh` builds [Termux's proot](https://github.com/termux/proot)
(`external/proot`, a submodule pinned to a commit) and its loader, with
[talloc](https://talloc.samba.org/) 2.4.2 (downloaded from samba.org and
checked against its SHA-256), for each Android ABI with the NDK, or for the
host (the unit tests). Gradle runs it (`:app:buildProot`) before each build.

The outputs are programs named `libproot.so` and `libproot-loader.so`, so
that Android installs them in the app's native library directory: from
Android 10 on, an app can run its own programs only from there (not from
its data directory). proot runs the Linux system's programs through the
loader, which maps them into memory rather than executing them.

Needs: a C compiler (the NDK's for Android), make, GNU awk (`gawk`) and curl.

`talloc-shim/replace.h` stands in for the part of Samba's `lib/replace` that
`talloc.c` uses, so that talloc builds as one file without Samba's waf.

The build copies proot's source tree and adds `#include <string.h>` to
`extension/ashmem_memfd/ashmem_memfd.c`, which needs it and doesn't include
it (an error with clang). The submodule itself is left as it is.

Licences: proot is GPL-2.0-or-later, talloc LGPL-3.0-or-later; their
sources are the submodule and the pinned tarball named above.
