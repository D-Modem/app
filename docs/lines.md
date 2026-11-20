# The modem's line

The app is a dial-up modem for Android: V.92/V.90/V.91, V.34 and lower, and
class 1 fax, over SIP calls. It does not use modem hardware. The modem is the
open modem core and DSP of [D-Modem](https://github.com/D-Modem/D-Modem),
built for Android with the NDK. The telephone line is the G.711 call of a SIP
account (Wi-Fi or mobile data).

Over SIP, the phone is on the digital side of the network. Toward an
analogue modem (a BBS on a real phone line), the phone is the V.90/V.92
digital modem, at up to 56000 bit/s downstream. Toward another digital modem
(D-Modem, or the modem bank of an ISP), it uses V.91 at up to 62667 bit/s in
the two directions, or V.34. The SIP provider must carry G.711 (PCMU/PCMA)
end to end to the telephone network.

The mobile calls of the phone can also be the line, at a low speed. This is
possible only if Android lets the app get the sound of a call. These
conditions are necessary:

- Android 13 or later.
- The app is privileged (CALL_AUDIO_INTERCEPTION). On a rooted phone, the app
  installs itself as a Magisk module.
- The audio of the phone supports it.

Mobile calls carry speech through codecs that cause bad damage to modem
signals. We measured this with `codec_line` in native-test: two modems back
to back through each codec.

| Codec | Gets through |
|---|---|
| AMR-NB 12.2 kbit/s (a 3G/4G call, good signal) | V.21 (300 bit/s). V.22 and V.22bis connect, but lose data about half the time. V.32 and higher do not connect |
| AMR-NB 7.95, 5.9 kbit/s (a busy network) | V.21 only |
| AMR-NB 4.75 kbit/s, GSM full rate (2G) | Nothing reliable |

Between two D-Modems, V.cvm (the modem of D-Modem for speech codecs,
`+MS=VCVM`) gives better results. After V.8, it probes the codec for 15 to
20 s. Then it loads a mode for that codec, with LAPM over it. Through AMR-NB
12.2, the engines of the app connect at about 1200 bit/s coded (750 to 1200
in each direction, `CONNECT 1200/CVM/LAPM/V44`). D-Modem measured 175 to 350
through AMR 7.4. Thus, on the mobile-call line, the modem starts in V.cvm.
V.cvm offers V.22 and V.21 to modems that do not have V.cvm. V.21 (and 1200
and 2400 bit/s) are settings for those modems. The dashboard shows the type
of connection ("V.cvm · LAPM · V.44").

Fax does not get through at all. V.cvm has no fax. The training of V.27ter at
2400 bit/s gets through AMR 12.2, but about one bit in sixteen is wrong. Thus
the app refuses fax on that line.

You can also use no line. The modem starts by itself, without a SIP account
or a network. It answers AT commands in the terminal and on the serial
bridges. A dial gets NO DIALTONE, as a modem without a phone cable. Home
shows this option next to the SIP configuration ("Start without a line").

Two phones with the app can also be the line for each other, through Wi-Fi or
Bluetooth, without a SIP account. The modems connect as over a perfect line.
An optional PIN, the same on the two phones, prevents access by other
callers. The PIN uses a challenge and a response, thus the PIN never goes
over the connection.
