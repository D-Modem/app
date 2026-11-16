<!-- Made by tools/settings_ref.py from the apps' ModemSettings: change the code (or the script), not this page. -->

# Settings reference

This page lists each setting that the apps keep, from their code. [The app, screen by screen](features.md) shows where you set each one. [The modem's line](lines.md) tells what the lines are.

## Android

`ModemSettings` ([`ModemSettings.kt`](../app/src/main/java/org/dmodem/app/android/settings/ModemSettings.kt)). The app keeps each setting in its private DataStore `modem`, under the key in the table.

| setting | key | kind | default | what it is |
|---|---|---|---|---|
| `user` | `user` | text | (empty) | The SIP account: user, password, registrar or proxy (host[:port]). |
| `password` | `password` | text | (empty) | As above. |
| `domain` | `domain` | text | (empty) | As above. |
| `anonymous` | `sip_anonymous` | on/off | `false` | SIP with no account (anonymous): nothing registered, calls dialled to a SIP address as it is (sip:user@host, or user@host) from "Anonymous" &lt;sip:anonymous@anonymous.invalid&gt;, and calls that come straight to the phone's address taken; the account's fields are kept but not used. |
| `sipPort` | `sip_port` | number | `5060` | The local SIP port. |
| `listen` | `listen` | on/off | `false` | Register, and answer calls (RING on the DTE, ATA or ATS0=n). |
| `t38` | `t38` | on/off | `true` | Fax calls may go over to T.38. |
| `country` | `country` | text | (empty) | The modem's country by name (empty: USA). |
| `initCommands` | `init_commands` | text | `ATX3` | AT commands sent to the modem each time it starts (empty: none). |
| `logLevel` | `log_level` | number | `0` | The modem's log level (0 errors .. 5), SIP's (0 none .. 4 the SIP messages). |
| `sipLogLevel` | `sip_log_level` | number | `1` | As above. |
| `logTimestamp` | `log_timestamp` | text | `[HH:mm:ss.SSS]` | The log's time stamp before each line, a date pattern (SimpleDateFormat's; empty: none). |
| `line` | `line` | text | `sip` | The modem's line: a SIP account, or another phone with the app over Wi-Fi or Bluetooth. |
| `peerAddress` | `peer_address` | text | (empty) | The other phone to call: host[:port] on Wi-Fi, a paired phone's address on Bluetooth. |
| `peerName` | `peer_name` | text | (empty) | The paired phone's name, to show. |
| `peerPort` | `peer_port` | number | `5070` | The TCP port phones call each other on. |
| `peerPin` | `peer_pin` | text | (empty) | The PIN between the two phones (empty: none): callers must know this phone's, ours answers the other's. |
| `speaker` | `speaker` | on/off | `true` | The modem's speaker on the phone's: the dialling and the handshake heard (as ATM and ATL have it). |
| `callTones` | `callTones` | on/off | `true` | The country's call progress tones (dial, ringing, busy, congestion) where the line has none of its own. |
| `adaptiveAnswer` | `adaptiveAnswer` | on/off | `true` | Calls answered as fax or data by what they turn out to be (D-Modem's adaptive answer, AT+FAA=1): a fax machine's to the fax receiver, a modem's to the terminal. |
| `callerId` | `callerId` | on/off | `true` | Caller ID on (AT+VCID=1): a ringing call's number and name in the terminal, its popup and notification. |
| `cellSpeed` | `cell_speed` | text | `VCVM` | The modulation on mobile calls (+MS): VCVM (V.cvm, made for speech codecs: about 1200 bit/s through AMR 12.2, to another D-Modem; V.22 or V.21 to other modems), V21 (300 bit/s), V22, V22B. |
| `btModemAddress` | `bt_modem_address` | text | (empty) | The Bluetooth modem's (LINE_BT_MODEM): the paired device's address, its name to show. |
| `btModemName` | `bt_modem_name` | text | (empty) | As above. |
| `serialModemBaud` | `serial_modem_baud` | number | `115200` | The modem on a USB serial adapter's (LINE_SERIAL_MODEM): the adapter's speed, and RTS/CTS flow control (8N1). |
| `serialModemRtsCts` | `serial_modem_rtscts` | on/off | `true` | As above. |
| `recordLine` | `record_line` | on/off | `false` | Each call on a USB modem's line recorded (LineRecordings), for a bug report. |
| `usbTxGain` | `usb_tx_gain` | number | `0` | A USB modem's line: the modem's sound to it louder or softer, dB (-20 to 20; D-Modem's DMODEM_USB_TX_GAIN). |
| `usbTxAuto` | `usb_tx_auto` | on/off | `true` | A USB modem's line: its transmit level set for each call from the remote's answer tone (DMODEM_USB_TX_GAIN=auto), not [usbTxGain]. |

`line` is one of these values: `bluetooth`, `bt_modem`, `cell`, `none`, `serial_modem`, `sip`, `usb`, `wifi`.

## iOS

`ModemSettings` ([`ModemSettings.swift`](../ios/DModemKit/Sources/DModemKit/ModemSettings.swift)). The app keeps each setting as JSON, under the name of the setting. But the app keeps the secrets in the Keychain, under the name in the table.

| setting | Keychain | kind | default | what it is |
|---|---|---|---|---|
| `user` |  | text | (empty) | The SIP account. |
| `password` | `sip-password` | text | (empty) | As above. |
| `domain` |  | text | (empty) | The registrar's or proxy's host[:port]. |
| `anonymous` |  | on/off | `false` | SIP with no account (anonymous): nothing registered, calls dialled to a SIP address as it is (sip:user@host, or user@host) from "Anonymous" &lt;sip:anonymous@anonymous.invalid&gt;, calls that come straight to the phone's address taken; the account's fields kept, not used. |
| `sipPort` |  | number | `5060` | The local SIP port. |
| `listen` |  | on/off | `false` | Register and take calls (on Wi-Fi: take the other phone's). |
| `t38` |  | on/off | `true` | Fax calls may go over to T.38. |
| `country` |  | text | (empty) | The modem's country (empty: USA). |
| `initCommands` |  | text | `ATX3` | AT commands sent each time it starts (SIP has no dial tone: ATX3). |
| `logLevel` |  | number | `0` | The modem's log level (0 errors .. 5), SIP's (0 none .. 4 the SIP messages). |
| `sipLogLevel` |  | number | `1` | As above. |
| `speaker` |  | on/off | `true` | The modem's speaker on the phone's: the dialling and the handshake heard. |
| `line` |  | choice | `sip` | The modem's line: a SIP account, another phone with the app on Wi-Fi, or none. |
| `peerAddress` |  | text | (empty) | The other phone to call (host[:port]), and the PIN between the two. |
| `peerPort` |  | number | `5070` | As above. |
| `peerPin` | `peer-pin` | text | (empty) | As above. |
| `faxIdent` |  | text | (empty) | Fax: the identity sent (CSI/TSI), error correction, the fastest rate, fine resolution, ringing calls answered as faxes. |
| `faxEcm` |  | on/off | `true` | As above. |
| `faxMaxBps` |  | number | `14400` | As above. |
| `faxFine` |  | on/off | `true` | As above. |
| `faxAutoAnswer` |  | on/off | `false` | As above. |
| `voicemail` |  | on/off | `false` | Voice calls: the answering machine takes calls nobody answers (off until turned on), after this many rings, a message this long at most (s). |
| `voicemailRings` |  | number | `4` | As above. |
| `voicemailMaxSeconds` |  | number | `120` | As above. |
| `ispNumber` |  | text | (empty) | Dial-up internet: the ISP's number, login, its login script (chat, if any), the modem's commands first. |
| `ispUser` |  | text | (empty) | As above. |
| `ispPassword` | `isp-password` | text | (empty) | As above. |
| `ispScript` |  | text | (empty) | As above. |
| `ispInit` |  | text | (empty) | As above. |

`line` is one of these values: `sip`, `wifi`, `none`.
