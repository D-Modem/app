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

## Layout

- `external/d-modem-re`: D-Modem-RE (git submodule): the modem core, the
  data pumps, the data link layer, the SIP user agent (on libre) and
  `libdmodem_sip`, the modem on a SIP line as a library.
- `app/src/main/cpp`: the native library, `libdmodem_jni.so`:
  `engine.cpp` runs the modem in a thread of its own with its DTE on a
  socket pair whose other end is the app's; `jni.cpp` binds it to
  `com.neodesktop.dmodem.NativeModem`.
- `native-test`: the engine on the build host, against a dmodemd over
  loopback SIP (dialling out, data both ways, restarting, a call coming
  in, the app closing its end).

## Building

    git submodule update --init

The native library builds with CMake 3.22+ and the NDK (r29); D-Modem-RE's
CMake fetches libre and Mbed TLS while configuring and builds them with the
NDK's toolchain for each ABI:

    cmake -S app/src/main/cpp -B build-arm64 -G Ninja \
      -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26
    cmake --build build-arm64 --target dmodem_jni

The host test needs OpenSSL's headers (libssl-dev):

    cmake -S native-test -B build-host -G Ninja
    cmake --build build-host
    ctest --test-dir build-host --output-on-failure

## License

GNU Affero General Public License version 3 only ([COPYING](COPYING)), as
D-Modem-RE (see its LICENSING.md for the parts it fetches: libre is
BSD-3-Clause, Mbed TLS Apache-2.0).
