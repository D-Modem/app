<img src=".github/banner.png" alt="D-Modem/app: A dial-up modem in your pocket" width="100%">

# D-Modem app

A dial-up modem in your pocket: the D-Modem app for Android and iOS.

The app is a V.92/V.90/V.91, V.34 and class 1 fax modem. Its telephone
line is the G.711 call of a SIP account, through Wi-Fi or mobile data. The
modem is the open modem core and DSP of
[D-Modem](https://github.com/D-Modem/D-Modem), built for the phone. You do
not need modem hardware. Toward a BBS on a real phone line, the app is the
digital modem, at up to 56000 bit/s downstream. Toward another digital
modem, it uses V.91 at up to 62667 bit/s in the two directions.

- A terminal, a dialling directory and the AT console of the modem
- Class 1 fax at up to 14400 bit/s: the app sends PDFs and images, and receives faxes as PDFs. T.38 is also available
- Voice calls through the modem on the speaker and microphone of the phone, and an answering machine that keeps messages on the phone (disabled until you enable it)
- The external modem of a vintage computer, on a USB serial adapter, [pico-serial](https://github.com/D-Modem/hardware), Bluetooth serial or TCP
- Real phone lines through USB modems (for example, the pico-serial Line stick), a Hayes modem on a serial adapter, or a Bluetooth modem
- Two phones as the line for each other, through Wi-Fi or Bluetooth, without a SIP account
- The mobile calls of the phone as a slow line, where Android permits it

## Quick start

Android: open the project in Android Studio, or do these commands:

    git submodule update --init
    ./gradlew assembleDebug

iOS: refer to [docs/ios.md](docs/ios.md). The full procedures, with Docker,
are in [docs/building.md](docs/building.md).

## Documentation

The documentation is also a website, [d-modem.github.io/app](https://d-modem.github.io/app/). It has reference pages that are made from the code.

[docs/](docs/README.md) gives information about the lines of the modem, fax,
voice calls, adapters and phone lines, each screen of the app, the build for
Android and iOS, and the source layout.

## The D-Modem suite

| Repository | What it is |
| --- | --- |
| [D-Modem](https://github.com/D-Modem/D-Modem) | The open V.92 softmodem: dmodemd, libdmodem and the Asterisk, FreeSWITCH and ARI modules |
| [app](https://github.com/D-Modem/app) | The D-Modem app for Android and iOS: a dial-up modem on a SIP call |
| [hardware](https://github.com/D-Modem/hardware) | pico-serial: RP2040 serial adapters and phone-line USB sticks |
| [menu](https://github.com/D-Modem/menu) | modemmenu: the login and the menu that dial-up callers get to |
| [bbs](https://github.com/D-Modem/bbs) | A bulletin board for telnet, SSH, TN3270 and modem callers |
| [go3270](https://github.com/D-Modem/go3270) | A Go library for 3270 terminal servers, used by bbs |
| [brand](https://github.com/D-Modem/brand) | Logos, colours, type and the naming guide |

## Licence

GNU Affero General Public License version 3 only ([COPYING](COPYING)), or a
commercial licence from the D-Modem Authors ([COMMERCIAL-LICENSE.md](COMMERCIAL-LICENSE.md)), as
D-Modem (see its LICENSING.md for the parts it fetches: libre is
BSD-3-Clause, Mbed TLS Apache-2.0). Contributions come with a contributor agreement ([CONTRIBUTING.md](CONTRIBUTING.md)).
