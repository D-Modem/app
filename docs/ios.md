# The iOS app


The native modem builds for iOS and macOS too: the engine's C API,
`app/src/main/cpp/dmodem_engine.h` (what Swift calls in place of
Android's JNI), with D-Modem, libre and Mbed TLS as one XCFramework:

    ios/build-native.sh        # macOS with Xcode, CMake, Ninja
    swift test --package-path ios/DModemSmoke

The Kotlin the two apps share is `shared/` (Kotlin Multiplatform: the
JVM for Android, iOS's framework `DModemShared`; Linux native for its
tests): the terminal's emulator, the file transfers (X/Y/ZMODEM, Kermit, Intel HEX and S-records, plain text),
PPP (its client and server, the chat scripts), fax (T.4, T.30, class 1,
the PDF) and the line to another phone (PeerLine), on a small common I/O
layer (`shared/.../io`). What stays each app's own: the UI, the modem
service, sockets and Bluetooth, Android's VPN and files. Its tests run on
the JVM and natively: `./gradlew :shared:allTests`.

The iOS app (`ios/DModem`, SwiftUI, iOS 16) is the modem on a SIP
account, or on the line to another phone with the app (Android or iOS)
on the same Wi-Fi: the shared PeerLine on POSIX sockets (its PIN too),
offered and found with Bonjour (`_dmodem._tcp`, as Android's NSD). It has:

- Home: the modem's state, the rates and what the CONNECT was, start,
  hang up, stop, an AT command line, the log
- the terminal (the shared emulator drawn in SwiftUI, CP437 and the VGA
  colours, Esc/Ctrl/Tab/arrows) and its file transfers (ZMODEM, YMODEM,
  XMODEM, Kermit, Intel HEX, S-records, plain text; a remote's `sz` or `kermit -s`
  starts one by itself)
- Fax: PDFs, images and text sent; a ringing call answered as a fax, by
  hand or each one; the received faxes as PDFs
- Internet: dial-up to an ISP (its login script if it has one, PPP), the
  phone's traffic through it as a VPN (the `DModemTunnel` packet tunnel
  extension, its packets to the app's PPP on the loopback)
- Settings: the SIP account or the other phone, the modem's commands

The modem's calls are the phone's own (CallKit): one ringing in rings
as a phone call does, answered or turned down there; one dialled shows
as a call, hung up from the call screen. The passwords and the PIN are
in the Keychain. Received files and faxes are in the Files app (On My
iPhone › D-Modem). The modem keeps going in the background on the audio
session (its speaker, or quiet); calls ring in while the app runs (with
no push service, a call can't wake it). Dial-up's VPN needs the Network
Extensions capability on the app's team.

What the screens run is the shared Kotlin's jobs (`shared/.../jobs`: a
fax call, a transfer, dial-up, each lent the modem's DTE while it lasts),
tested on the JVM and natively. The modem apart from the views is the
`ios/DModemKit` Swift package (the engine from Swift, its DTE and who
it's lent to, the settings and their secrets, the session), whose tests
run on Linux too, against the host build:

    ./gradlew :shared:assembleDModemSharedReleaseXCFramework
    ios/build-native.sh
    cd ios/DModem && xcodegen generate && open DModem.xcodeproj

    DMODEM_HOST_BUILD=$PWD/build-host swift test --package-path ios/DModemKit   # Linux or macOS

D-Modem's `portable.h` has Apple's ways where Linux has its own
(close-on-exec sockets, SIGPIPE, a monotonic sleep, a peer's hang-up);
the Linux build tests them too with `-DDMODEM_PORTABLE_FALLBACKS`. CI
(`.github/workflows/apple.yml`) builds the XCFramework, runs the Swift
tests on macOS and builds the app for the simulator; the Linux job runs
DModemKit's tests on the host engine.
