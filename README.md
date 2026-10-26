# D-Modem for Android

An Android app that is a dial-up modem: V.92/V.90/V.91, V.34 and below, and
class 1 fax, over SIP calls. No modem hardware: the modem is
[D-Modem-RE](https://github.com/Neo-Desktop/D-Modem-RE)'s open modem core
and DSP, built for Android with the NDK, and the telephone line is a SIP
account's G.711 call (Wi-Fi or mobile data).

Over SIP the phone is on the digital side of the network: toward an
analogue modem (a BBS on a real phone line) it is the V.90/V.92 digital
modem, up to 56000 bit/s downstream; toward another digital modem
(D-Modem, an ISP's modem bank) V.91, up to 62667 bit/s both ways, or V.34.
The SIP provider must carry G.711 (PCMU/PCMA) end to end to the telephone
network.

The phone's own mobile calls can be the line too, slowly, where Android
lets the app have a call's sound: Android 13 or later, the app privileged
(CALL_AUDIO_INTERCEPTION; on a rooted phone the app installs itself as a
Magisk module), and a phone whose audio supports it. Mobile calls carry
speech through codecs that wreck modem signals; native-test's
`codec_line` measured it, two modems back to back through each codec:

| Codec | Gets through |
|---|---|
| AMR-NB 12.2 kbit/s (a 3G/4G call, good signal) | V.21 (300 bit/s); V.22 and V.22bis connect but lose data about half the time; V.32 and up don't connect |
| AMR-NB 7.95, 5.9 kbit/s (a busy network) | V.21 only |
| AMR-NB 4.75 kbit/s, GSM full rate (2G) | nothing reliable |

Between two D-Modems, V.cvm (D-Modem-RE's modem for speech codecs,
`+MS=V_CVM`) does better: after V.8 it spends 15 to 20 s probing the
codec, then loads a mode for it, with LAPM over it. Through AMR-NB 12.2
the app's engines connect at about 1200 bit/s coded (750 to 1200 each
way, `CONNECT 1200/CVM/LAPM/V44`; D-Modem-RE measured 175 to 350 through
AMR 7.4). So the mobile-call line starts the modem in V.cvm, which offers
V.22 and V.21 to modems without it; V.21 (and 1200 and 2400 bit/s) are
settings for those. The dashboard says what the connection is ("V.cvm ·
LAPM · V.44").

Fax doesn't get through at all (V.cvm has no fax; V.27ter's training at 2400 bit/s comes
through AMR 12.2 with about one bit in sixteen wrong), so the app turns
fax down on that line.

Or no line at all: the modem starts on its own, no SIP account or
network needed, and answers AT commands in the terminal and on the serial
bridges; a dial gets NO DIALTONE, as a modem with no phone cable. Home
offers it beside setting up SIP ("Start without a line").

Two phones with the app can also be each other's line, over Wi-Fi or
Bluetooth, with no SIP account: the modems connect as over a perfect line.
An optional PIN, the same on both phones, keeps other callers out (a
challenge and response: the PIN never goes over the connection).

## Fax

Class 1 fax (T.30, MH coding, V.27ter, V.29 and V.17 up to 14400 bit/s),
sending PDFs and images and receiving into PDFs, on the SIP line (audio,
or T.38 when the far end asks for it) and the line to another phone.
A call answered is a fax or a data call by what the caller sends
(D-Modem-RE's adaptive answer, `AT+FAA=1`, on unless the settings turn
it off): a fax machine's CNG, a V.8 fax call or T.38 from the far end
and the modem says FAX, and the app's fax receiver takes the call by
itself; a modem's handshake and it says DATA, then its CONNECT, for the
terminal and the bridges.
Error correction mode (T.30 Annex A) when the other machine has it: the
page in numbered HDLC frames, those a noisy line spoils sent again. Tested
against efax, against D-Modem-RE's own T.30 machine (native-test's
`t30_line`, on a clean line and a noisy one), and over SIP with T.38
between the app's engine and dmodemd (`sip_fax_line`).

## A modem for a vintage computer: pico-serial

The phone can be a computer's external modem on a serial cable: on a
USB serial adapter (FTDI, CP210x, CH34x, PL2303 and CDC-ACM chips, under
the chip makers' IDs and the 1100 or so adapter makers' own that Linux's
drivers know: tools/usb-serial-ids; and Exar's XR21V141x and XR21B14xx),
Bluetooth serial, or TCP. [pico-serial](https://github.com/Neo-Desktop/pico-serial)
is a USB-C to DB25 adapter made for it: plugged into the computer's
serial port it is the modem there. The app finds its control interface
and uses its lines and settings (its firmware/PROTOCOL.md):

- the modem's DCD, DSR and RI each their own: a call ringing in raises RI
  on the computer, as a real modem's ring does;
- the computer's DTR read from the board, so dropping it hangs up;
- the board's role (automatic, modem or computer port), where its speed
  comes from (learned from the computer's typing, as a Hayes modem does,
  or the app's), RTS/CTS flow control, and what it has carried, in the
  serial port settings.

Any other CDC-ACM adapter's line notifications (`SERIAL_STATE`) are read
too, so the computer dropping DTR hangs up there as well.

## A real phone line: USB modems

The modem's line can also be a telephone line, through a USB modem that
carries the modem's sound (D-Modem-RE's `usb_line.cpp`, as
`dmodemd --usb` drives them): a Smart Link SmartUSB56 (USB 0483:7554,
with caller ID, its ST7554 or ST75951 DAA), or a
[pico-serial](https://github.com/Neo-Desktop/pico-serial) Line stick
(1209:0002, a Si3056 DAA: caller ID on hook, and the country's line
settings in its registers), or an Agere USB 2.0 soft modem (047E:2892,
047E:2828: 8000 Hz, caller ID, on its ROM's own isochronous stream,
nothing downloaded into it; a USB 2.0 port). The phone runs the modem; plugged into it
(USB OTG) the stick is its line, with no SIP account or network. Settings,
Line, "A phone line, through a USB modem"; Android asks once whether
D-Modem may use it, and offers the app when one is plugged in.

An app can't list the USB devices itself: Android opens the one the user
allowed and gives its descriptor. libusb is built for Android in the app
(`app/src/main/cpp/usb/libusb.cmake`, D-Modem-RE's pinned release) and
`usb_line.cpp` built with its three calls that look for the device
renamed to `usb/usb_android.cpp`'s, which start libusb without its device
discovery and give it that descriptor, wrapped, as the only device on the
bus. Tested on the build host on D-Modem-RE's fake pico-serial Line
sticks and Agere modems, and simulated exchange (`native-test/usb_line_test.py`, its `agere` run on the ROM's isochronous stream: dialling,
caller ID, answering, CONNECT 33600, data each way, hanging up, unplugged);
not yet on a phone with a real stick. The phone's speaker isn't fed on
this line.

## The app

- **Home**: what the modem is doing (off, ready, connecting, online with
  its rates down and up and the call's time) and the one thing to do about
  it (set up SIP, start, hang up, stop); the line (the SIP account,
  incoming calls, the network, fax); quick dial of the systems last
  dialled; the terminal and the directory; the modem's console (folded,
  with an AT command line).
- **A call ringing in** (SIP, the other phone, a mobile call): the
  phone's incoming call notification (heads-up, over the lock screen,
  the ringtone) and a popup over the app, with who's calling when the
  line says (the other phone's name): Answer (ATA: a fax machine's call
  goes to the fax receiver by itself, a modem's to the terminal), or
  Ignore (it rings on, out of sight); Answer as fax too when adaptive
  answer is off. Gone when the modem
  answers (here, a computer on a bridge, S0) or the caller gives up.
- **Settings**: the SIP account (or paste `user:password@host`, as
  dmodemd's `SIP_LOGIN`), taking incoming calls, T.38, the AT commands
  sent at start (`ATX3` by default: SIP has no dial tone), the log levels;
  the country, from the ITU's list of 192 (D-Modem-RE's, after E.180):
  the modem's line settings and how it hears busy, and the call progress
  tones the app plays in its tones (a second of dial tone as an entry is
  dialled, the ringing tone while the other phone rings, the busy or
  congestion tone when a call can't be made), with a button to hear them.
  `tools/country_tones.py` makes the shared Kotlin's copy of the table.
- **Terminal**: a VT100/VT220/xterm terminal on the modem (256 and 24-bit
  colours, the alternate screen, bracketed paste, DEC line drawing) that
  also speaks what BBSes expect: CP437, ANSI.SYS's cursor save/restore and
  clear-home, bold as bright, iCE colours, and box drawing and block
  characters drawn to fill their cells so ANSI art has no gaps. Colour
  themes (VGA, xterm, Solarized, Dracula, Monokai, Gruvbox, Nord, green
  and amber phosphor), fonts (Cascadia Code, Sauce Code Pro Nerd Font,
  Source Code Pro, Roboto Mono, JetBrains Mono, Fira Code, OpenDyslexic
  Mono, Inconsolata, Droid Sans Mono or the system's; real bold weights
  from the variable fonts, optional ligatures; `app/src/main/assets/fonts`), 80/132/40 fixed columns or pinch to zoom, scrollback
  with fling, long-press selection with copy, paste, share and open link,
  an extra keys bar (Esc, sticky Ctrl/Alt, Tab, arrows with auto-repeat,
  Home/End/PgUp/PgDn, F1-F12), volume keys as font size or Ctrl/Alt,
  bell as vibration or flash, full screen, snippets, session capture to a
  file (shared to other apps), hang up (+++ with its guard times, ATH).
  Files sent and received by ZMODEM, YMODEM, XMODEM(-1K) and Kermit, and
  as Intel HEX or Motorola S-records for EPROM programmers, monitors and
  boot loaders: a file sent as records (a file of them already, as it is:
  S1/S2/S3 by the image's size, Intel's extended addresses past 64 KB),
  records received with their checksums checked into the binary image
  they hold (gaps 0xFF). Plain text too: a file's bytes sent verbatim,
  as if typed (to an editor, a BBS, `cat > file`), and what comes saved
  as it is until the line is quiet for 10 s.
- **Dial**: the dialing directory: each system's number (or SIP user), the
  AT commands it needs before ATD, its character set and backspace; one
  tap starts the modem if it is off and dials.
- **Log**: the modem's and SIP's log (Copy for all of it).
- **Back**: from any other tab back to Home; on Home the app goes to the
  background (the modem keeps running), as the Home button would.
- **Local Linux** (Settings, Linux): distributions installed and run
  under proot, as root in /root, the phone's storage at /sdcard, and the
  modem as a serial port at `/dev/ttyDM0` (a pty of the app's, bound in):
  `minicom -D /dev/ttyDM0`, pppd, efax and the like work as on a real
  modem. The program that opens it has the modem as a serial bridge's
  client has it (one at a time, with the TCP, Bluetooth and USB bridges);
  closing it drops DTR, so the call hangs up as a serial cable's end
  would. `ModemTtyTest`, and `LinuxSystemTest` in a real Alpine under
  proot: ATI from the distro, the modem's answer back.
- **Dial-in** (Settings, Dial-up internet): the phone as an ISP for whoever
  calls the modem, on SIP or any other line. A call that rings is answered
  (unless the fax settings answer calls already); once it connects, the
  modem's DTE is taken and a PPP server on the call gives the caller
  10.64.64.2, DNS at 10.64.64.1 and the phone's network through the same
  NAT the serial bridges' internet host uses. When the caller's PPP ends,
  or none starts, the app hangs up; when the call drops, PPP stops. Home
  shows the caller online, with what has gone each way and a Hang up
  button. Tested over two of D-Modem's modems back to back
  (`DialInLineTest`: answer, CONNECT 33600, the address, DNS and a ping
  through the phone, the hang-up).

The modem runs in a foreground service (`ModemService`) with the CPU and
Wi-Fi kept awake for the call's real-time audio. The SIP user agent binds
to the addresses the phone has when it starts, so the service starts the
modem again when the default network changes (after the call, if one is
in progress). The DNS servers come from the active network: Android has
no `/etc/resolv.conf` for libre to read.

## Layout

- `external/d-modem-re`: D-Modem-RE (git submodule): the modem core, the
  data pumps, the data link layer, the SIP user agent (on libre) and
  `libdmodem_sip`, the modem on a SIP line as a library.
- `app/src/main/cpp`: the native library, `libdmodem_jni.so`:
  `engine.cpp` runs the modem in a thread of its own with its DTE on a
  socket pair whose other end is the app's, on SIP, a peer line
  (`peer_modem.cpp`) or a USB modem (`usb/`); `jni.cpp` binds it to
  `com.neodesktop.dmodem.NativeModem`.
- `app/src/main/java/com/neodesktop/dmodem`: `modem/` the service, the
  state the UI observes and `DteHub`, the modem's DTE shared by the
  console, the terminal and the bridges; `settings/`; `ui/` (Compose).
- `app/src/main/java/com/neodesktop/dmodem/terminal`: the terminal:
  `TerminalEmulator` (pure Kotlin, unit tested), `TerminalView` (drawing
  and input), `TerminalSession` (the emulator on the modem's DTE),
  `BoxDrawing`, `Themes`, the directory and snippets.
- `native-test`: the engine on the build host, against a dmodemd over
  loopback SIP (dialling out, data both ways, restarting, a call coming
  in, the app closing its end), and on fake USB modems (`usb_line_test.py`).

## Building

    git submodule update --init
    ./gradlew assembleDebug testDebugUnitTest

You need JDK 17 or later and the Android SDK with platform 37, NDK
29.0.14206865 and CMake 3.31.6 (`sdkmanager "platforms;android-37.0"
"ndk;29.0.14206865" "cmake;3.31.6"`). D-Modem-RE's CMake fetches libre and
Mbed TLS (git) while configuring and builds them with the NDK's toolchain
for each ABI. `./gradlew connectedDebugAndroidTest` runs the instrumented
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

The host test needs OpenSSL's headers (libssl-dev):

    cmake -S native-test -B build-host -G Ninja
    cmake --build build-host
    ctest --test-dir build-host --output-on-failure

With `build-host` built, the unit tests also make calls on its test
lines (fax between modems, against efax and D-Modem-RE's own T.30, over
SIP with T.38, phone to phone, through mobile codecs); without it those
are skipped. The lines run at twice a phone line's pace
(`DMODEM_LINE_SPEED=1` for a real one's).

Each test run's logs go to `build/test-runs/<date_time>/<module>-<task>/`,
one file for each test (`<class>/<test>.log`: what it printed, the line
tools' logs among it, and a failure's stack trace). A run where nothing
failed deletes its folder; the build names the one it kept.
`KERMIT_TRACE=1` and `TRANSFER_TRACE=1` add the transfer tests' bytes
each way.

CI (`.github/workflows/android.yml`) runs all three; it needs a
`DMODEM_RE_TOKEN` secret that can read the private D-Modem-RE submodule.

## Apple's systems: the iOS app

The native modem builds for iOS and macOS too: the engine's C API,
`app/src/main/cpp/dmodem_engine.h` (what Swift calls in place of
Android's JNI), with D-Modem-RE, libre and Mbed TLS as one XCFramework:

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

D-Modem-RE's `portable.h` has Apple's ways where Linux has its own
(close-on-exec sockets, SIGPIPE, a monotonic sleep, a peer's hang-up);
the Linux build tests them too with `-DDMODEM_PORTABLE_FALLBACKS`. CI
(`.github/workflows/apple.yml`) builds the XCFramework, runs the Swift
tests on macOS and builds the app for the simulator; the Linux job runs
DModemKit's tests on the host engine.

## License

GNU Affero General Public License version 3 only ([COPYING](COPYING)), as
D-Modem-RE (see its LICENSING.md for the parts it fetches: libre is
BSD-3-Clause, Mbed TLS Apache-2.0).
