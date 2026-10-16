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
network: the phone's own cellular calls go through speech codecs
(AMR, EVS) that no modem faster than V.22bis survives.

## The app

- **Modem** tab: start and stop the modem, the call's state and rates, and
  a command line to the modem with what it answered.
- **Settings**: the SIP account (or paste `user:password@host`, as
  dmodemd's `SIP_LOGIN`), taking incoming calls, T.38, the AT commands
  sent at start (`ATX3` by default: SIP has no dial tone), the log levels.
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
- **Dial**: the dialing directory: each system's number (or SIP user), the
  AT commands it needs before ATD, its character set and backspace; one
  tap starts the modem if it is off and dials.
- **Log**: the modem's and SIP's log.

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
  socket pair whose other end is the app's; `jni.cpp` binds it to
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
  in, the app closing its end).

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

The native library alone, without Gradle:

    cmake -S app/src/main/cpp -B build-arm64 -G Ninja \
      -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26
    cmake --build build-arm64 --target dmodem_jni

The host test needs OpenSSL's headers (libssl-dev):

    cmake -S native-test -B build-host -G Ninja
    cmake --build build-host
    ctest --test-dir build-host --output-on-failure

CI (`.github/workflows/android.yml`) runs all three; it needs a
`DMODEM_RE_TOKEN` secret that can read the private D-Modem-RE submodule.

## License

GNU Affero General Public License version 3 only ([COPYING](COPYING)), as
D-Modem-RE (see its LICENSING.md for the parts it fetches: libre is
BSD-3-Clause, Mbed TLS Apache-2.0).
