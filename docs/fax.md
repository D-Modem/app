# Fax


The app has class 1 fax (T.30, MH coding, V.27ter, V.29 and V.17 at up to
14400 bit/s). It sends PDFs and images, and it receives faxes into PDFs. Fax
operates on the SIP line (audio, or T.38 if the far end asks for it) and on
the line to another phone.

When the modem answers a call, the signal from the caller sets the type of
call: fax or data. This is the adaptive answer of D-Modem (`AT+FAA=1`). It is
enabled unless you disable it in the settings:

- If the far end sends the CNG of a fax machine, a V.8 fax call or T.38, the
  modem says FAX. Then the fax receiver of the app takes the call
  automatically.
- If the far end sends the handshake of a modem, the modem says DATA, and then
  its CONNECT, for the terminal and the bridges.

The app uses error correction mode (T.30 Annex A) if the other machine has it.
In this mode, the page goes in numbered HDLC frames, and the app sends again
the frames that noise on the line damages. We tested fax against efax and
against the T.30 machine of D-Modem (`t30_line` in native-test, on a clean
line and on a noisy line). We also tested it over SIP with T.38 between the
engine of the app and dmodemd (`sip_fax_line`).
