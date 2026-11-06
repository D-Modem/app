# The modem's line

An Android app that is a dial-up modem: V.92/V.90/V.91, V.34 and below, and
class 1 fax, over SIP calls. No modem hardware: the modem is
[D-Modem](https://github.com/D-Modem/D-Modem)'s open modem core
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

Between two D-Modems, V.cvm (D-Modem's modem for speech codecs,
`+MS=VCVM`) does better: after V.8 it spends 15 to 20 s probing the
codec, then loads a mode for it, with LAPM over it. Through AMR-NB 12.2
the app's engines connect at about 1200 bit/s coded (750 to 1200 each
way, `CONNECT 1200/CVM/LAPM/V44`; D-Modem measured 175 to 350 through
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
