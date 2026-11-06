<img src=".github/banner.png" alt="D-Modem/app: A dial-up modem in your pocket" width="100%">

# D-Modem app

A dial-up modem in your pocket: the D-Modem app for Android and iOS.

The app is a V.92/V.90/V.91, V.34 and class 1 fax modem whose telephone
line is a SIP account's G.711 call, over Wi-Fi or mobile data. The modem
is [D-Modem](https://github.com/D-Modem/D-Modem)'s open modem core and
DSP, built for the phone; no modem hardware is needed. Toward a BBS on a
real phone line it is the digital modem, up to 56000 bit/s downstream;
toward another digital modem, V.91 at up to 62667 bit/s both ways.

- A terminal, a dialling directory and the modem's AT console
- Class 1 fax up to 14400 bit/s: PDFs and images sent, faxes received as PDFs, T.38 too
- A vintage computer's external modem, on a USB serial adapter, [pico-serial](https://github.com/D-Modem/hardware), Bluetooth serial or TCP
- Real phone lines through USB modems (the pico-serial Line stick among them), a Hayes modem on a serial adapter, or a Bluetooth modem
- Two phones as each other's line over Wi-Fi or Bluetooth, no SIP account needed
- The phone's own mobile calls as a slow line, where Android allows it

## Quick start

Android: open the project in Android Studio, or

    git submodule update --init
    ./gradlew assembleDebug

iOS: see [docs/ios.md](docs/ios.md). The full steps, Docker included, are
in [docs/building.md](docs/building.md).

## Documentation

[docs/](docs/README.md) covers the modem's lines, fax, adapters and
phone lines, every screen of the app, building for Android and iOS, and
the source layout.

## The D-Modem suite

| Repository | What it is |
| --- | --- |
| [D-Modem](https://github.com/D-Modem/D-Modem) | The open V.92 softmodem: dmodemd, libdmodem and the Asterisk, FreeSWITCH and ARI modules |
| [app](https://github.com/D-Modem/app) | The D-Modem app for Android and iOS: a dial-up modem on a SIP call |
| [hardware](https://github.com/D-Modem/hardware) | pico-serial: RP2040 serial adapters and phone-line USB sticks |
| [menu](https://github.com/D-Modem/menu) | modemmenu: the login and menu dial-up callers reach |
| [bbs](https://github.com/D-Modem/bbs) | A bulletin board for telnet, SSH, TN3270 and modem callers |
| [go3270](https://github.com/D-Modem/go3270) | A Go library for 3270 terminal servers, used by bbs |
| [brand](https://github.com/D-Modem/brand) | Logos, colours, type and the naming guide |

## Licence

GNU Affero General Public License version 3 only ([COPYING](COPYING)), as
D-Modem (see its LICENSING.md for the parts it fetches: libre is
BSD-3-Clause, Mbed TLS Apache-2.0).
