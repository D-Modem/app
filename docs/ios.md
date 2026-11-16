# The iOS app


The native modem also builds for iOS and macOS. Swift calls the C API of the
engine, `app/src/main/cpp/dmodem_engine.h`, in place of the JNI of Android.
The build puts D-Modem, libre and Mbed TLS into one XCFramework:

    ios/build-native.sh        # macOS with Xcode, CMake, Ninja
    swift test --package-path ios/DModemSmoke

The two apps share the Kotlin in `shared/`. This is Kotlin Multiplatform: the
JVM for Android, the framework `DModemShared` for iOS, and Linux native for
its tests. The shared code has these items, on a small common I/O layer
(`shared/.../io`):

- The emulator of the terminal.
- The file transfers (X/Y/ZMODEM, Kermit, Intel HEX and S-records, plain text).
- PPP (its client and server, the chat scripts).
- Fax (T.4, T.30, class 1, the PDF).
- Voice calls and the answering machine (V.253, μ-law, WAV).
- The line to another phone (PeerLine).

Each app keeps these items as its own: the UI, the modem service, sockets and
Bluetooth, and the VPN and files of Android. The tests of the shared code run
on the JVM and natively: `./gradlew :shared:allTests`.

The iOS app (`ios/DModem`, SwiftUI, iOS 16) is the modem on a SIP account.
It can also be the modem on the line to another phone with the app (Android
or iOS) on the same Wi-Fi. This line is the shared PeerLine on POSIX sockets
(with its PIN). The app offers and finds the line with Bonjour
(`_dmodem._tcp`, as the NSD of Android). The app has these screens:

- Home: the state of the modem, the rates and the CONNECT text, start,
  hang up, stop, an AT command line, the log.
- The terminal (the shared emulator drawn in SwiftUI, CP437 and the VGA
  colours, Esc/Ctrl/Tab/arrows) and its file transfers (ZMODEM, YMODEM,
  XMODEM, Kermit, Intel HEX, S-records, plain text). A `sz` or `kermit -s` on
  the remote starts a transfer automatically.
- Phone: voice calls through the modem (dial, answer, the keypad). The
  answering machine, disabled until you enable it, with its greeting and
  messages ([voice.md](voice.md)).
- Fax: send PDFs, images and text. Answer a call that rings as a fax,
  manually or for each call. The received faxes as PDFs.
- Internet: dial-up to an ISP (with its login script if it has one, and PPP).
  The traffic of the phone goes through it as a VPN. The `DModemTunnel` packet
  tunnel extension sends its packets to the PPP of the app on the loopback.
- Settings: the SIP account or the other phone, the commands of the modem.

The calls of the modem are phone calls of the phone (CallKit). An incoming
call rings as a phone call does. You answer it or refuse it on that screen.
A call that you dial shows as a call, and you hang it up from the call screen.
The passwords and the PIN are in the Keychain. Received files and faxes are
in the Files app (On My iPhone › D-Modem). The modem continues to operate in
the background on the audio session (its speaker, or silent). Calls ring
while the app runs. There is no push service, thus a call cannot wake the
app. The VPN of dial-up needs the Network Extensions capability on the team
of the app.

The screens run the jobs of the shared Kotlin (`shared/.../jobs`: a fax call,
a voice call, a transfer, dial-up). Each job gets the DTE of the modem while
it runs. The jobs have tests on the JVM and natively. The modem without the
views is the `ios/DModemKit` Swift package. It has the engine from Swift, its
DTE and the job that has the DTE, the settings and their secrets, and the
session. Its tests also run on Linux, against the host build:

    ./gradlew :shared:assembleDModemSharedReleaseXCFramework
    ios/build-native.sh
    cd ios/DModem && xcodegen generate && open DModem.xcodeproj

    DMODEM_HOST_BUILD=$PWD/build-host swift test --package-path ios/DModemKit   # Linux or macOS

The `portable.h` of D-Modem has the Apple methods where Linux has its own
methods (close-on-exec sockets, SIGPIPE, a monotonic sleep, the hang-up of a
peer). The Linux build also tests them with `-DDMODEM_PORTABLE_FALLBACKS`.
CI (`.github/workflows/apple.yml`) builds the XCFramework, runs the Swift
tests on macOS and builds the app for the simulator. The Linux job runs the
tests of DModemKit on the host engine.
