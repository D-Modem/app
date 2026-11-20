# Building the Android app


    git submodule update --init
    ./gradlew assembleDebug testDebugUnitTest

These items are necessary: JDK 17 or later, and the Android SDK with platform
37, NDK 29.0.14206865 and CMake 3.31.6 (`sdkmanager "platforms;android-37.0"
"ndk;29.0.14206865" "cmake;3.31.6"`).

The CMake of D-Modem fetches libre and Mbed TLS (git) when it configures the
build. It builds them with the toolchain of the NDK for each ABI. The SSH
client (`native-ssh`) fetches the release tarballs of libssh and of its own
Mbed TLS (pinned by SHA-256) in the same way. The unit tests also build that
SSH client for the build host (`buildHostSsh`: CMake, a C compiler and the
JNI headers of the JDK). `./gradlew connectedDebugAndroidTest` runs the
instrumented tests on a device or emulator (the native library loads and
answers AT commands).

You can also build in Docker, with the same toolchain as CI. First, check out
the submodules, because the build takes them from the tree. This command puts
the debug APK into `out/`:

    docker build --target apk --output out .

`docker build -t dmodem-android .` makes an image with the toolchain and the
source, to run Gradle in. The `Dockerfile` has the other options (a release
build, the unit tests, the CA of a proxy).

To build only the native library, without Gradle:

    cmake -S app/src/main/cpp -B build-arm64 -G Ninja \
      -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26
    cmake --build build-arm64 --target dmodem_jni

The host test needs the headers of OpenSSL (libssl-dev), and GoogleTest
(libgtest-dev). If GoogleTest is not installed, CMake fetches it:

    cmake -S native-test -B build-host -G Ninja
    cmake --build build-host
    ctest --test-dir build-host --output-on-failure

Each engine test is a separate ctest test. To run only the engine tests, use
`ctest -R engine.`. To run one test directly, use
`build-host/engine_test --gtest_filter=*Ringing`. Add `ENGINE_TEST_LOG=1` to
see the log of the engine while the test runs.

If `build-host` is built, the unit tests also make calls on its test lines
(fax between modems, against efax and the T.30 of D-Modem, over SIP with
T.38, phone to phone, through mobile codecs). If it is not built, the unit
tests skip those calls. The lines run at two times the rate of a phone line.
Set `DMODEM_LINE_SPEED=1` for the rate of a real line.

The logs of each test run go to `build/test-runs/<date_time>/<module>-<task>/`,
with one file for each test (`<class>/<test>.log`). The file has the output
of the test, with the logs of the line tools and the stack trace of a
failure. If no test fails, the run deletes its folder. If the build keeps a
folder, it gives its name. `KERMIT_TRACE=1` and `TRANSFER_TRACE=1` add the
bytes of the transfer tests in each direction.

Each function has a comment on the line immediately above it, or above its
annotations (Kotlin, Swift, shell), or a docstring (Python). C and C++ follow
the rule of D-Modem. `tools/doc_check.py` lists each function without a
comment (and fails).

CI (`.github/workflows/android.yml`) runs all three, and that check. It needs
a `DMODEM_TOKEN` secret that can read the private D-Modem submodule.

`.github/workflows/d-modem-bump.yml` keeps the submodule on the main of
D-Modem. Each day (or when you start it manually), it opens a pull request
that moves the submodule, with the AT command reference in the assets of the
app (`tools/at_reference.py`). The pull request is set to merge when CI
passes. The workflow needs a `DMODEM_BUMP_TOKEN` secret (its header gives
the necessary permissions).

The signing keys come from the environment: `ANDROID_KEYSTORE_FILE` (a PKCS12
keystore), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and
`ANDROID_KEY_PASSWORD` (if it is not set, the app uses the password of the
store). Without them, a debug build uses the debug key of the SDK, and a
release build is unsigned. CI decodes `ANDROID_KEYSTORE_BASE64` into that
file. The secrets of the repository hold the debug keystore. The secrets of
the protected `release` environment (main and `v*` tags) hold the release
keystore. The `release` job of CI signs with it.
