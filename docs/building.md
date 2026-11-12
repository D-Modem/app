# Building the Android app


    git submodule update --init
    ./gradlew assembleDebug testDebugUnitTest

You need JDK 17 or later and the Android SDK with platform 37, NDK
29.0.14206865 and CMake 3.31.6 (`sdkmanager "platforms;android-37.0"
"ndk;29.0.14206865" "cmake;3.31.6"`). D-Modem's CMake fetches libre and
Mbed TLS (git) while configuring and builds them with the NDK's toolchain
for each ABI; the SSH client (`native-ssh`) fetches libssh's and its own
Mbed TLS's release tarballs (pinned by SHA-256) the same way. The unit
tests build that SSH client for the build host too (`buildHostSsh`: CMake,
a C compiler and the JDK's JNI headers). `./gradlew connectedDebugAndroidTest` runs the instrumented
tests on a device or emulator (the native library loads and answers AT
commands).

Or in Docker, with the same toolchain as CI (the submodules checked out
first: the build takes them from the tree), the debug APK into `out/`:

    docker build --target apk --output out .

`docker build -t dmodem-android .` makes an image with the toolchain and
the source to run Gradle in; the `Dockerfile` has the rest (a release
build, the unit tests, a proxy's CA).

The native library alone, without Gradle:

    cmake -S app/src/main/cpp -B build-arm64 -G Ninja \
      -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26
    cmake --build build-arm64 --target dmodem_jni

The host test needs OpenSSL's headers (libssl-dev), and GoogleTest
(libgtest-dev, else CMake fetches it):

    cmake -S native-test -B build-host -G Ninja
    cmake --build build-host
    ctest --test-dir build-host --output-on-failure

Each engine test is a ctest test of its own (`ctest -R engine.` for
those alone); `build-host/engine_test --gtest_filter=*Ringing` runs one
directly, `ENGINE_TEST_LOG=1` with the engine's log as it comes.

With `build-host` built, the unit tests also make calls on its test
lines (fax between modems, against efax and D-Modem's own T.30, over
SIP with T.38, phone to phone, through mobile codecs); without it those
are skipped. The lines run at twice a phone line's pace
(`DMODEM_LINE_SPEED=1` for a real one's).

Each test run's logs go to `build/test-runs/<date_time>/<module>-<task>/`,
one file for each test (`<class>/<test>.log`: what it printed, the line
tools' logs among it, and a failure's stack trace). A run where nothing
failed deletes its folder; the build names the one it kept.
`KERMIT_TRACE=1` and `TRANSFER_TRACE=1` add the transfer tests' bytes
each way.

Every function has a comment on the line just above it, or above its
annotations (Kotlin, Swift, shell), or a docstring (Python); C and C++ as
D-Modem's own rule has it. `tools/doc_check.py` lists any that don't
(and fails).

CI (`.github/workflows/android.yml`) runs all three, and that check; it needs a
`DMODEM_TOKEN` secret that can read the private D-Modem submodule.

`.github/workflows/d-modem-bump.yml` keeps the submodule on D-Modem's
main: daily (or by hand) it opens a pull request that moves it, with the
AT command reference in the app's assets (`tools/at_reference.py`), set to
merge once CI passes. It needs a `DMODEM_BUMP_TOKEN` secret (its header
says which permissions).

Signing comes from the environment: `ANDROID_KEYSTORE_FILE` (a PKCS12
keystore), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and
`ANDROID_KEY_PASSWORD` (the store's password if unset). Without them a
debug build takes the SDK's debug key and a release build is unsigned. CI
decodes `ANDROID_KEYSTORE_BASE64` into that file: the repository's secrets
hold the debug keystore, the protected `release` environment's (main and
`v*` tags) the release one, which CI's `release` job signs with.
