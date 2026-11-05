# Fax


Class 1 fax (T.30, MH coding, V.27ter, V.29 and V.17 up to 14400 bit/s),
sending PDFs and images and receiving into PDFs, on the SIP line (audio,
or T.38 when the far end asks for it) and the line to another phone.
A call answered is a fax or a data call by what the caller sends
(D-Modem's adaptive answer, `AT+FAA=1`, on unless the settings turn
it off): a fax machine's CNG, a V.8 fax call or T.38 from the far end
and the modem says FAX, and the app's fax receiver takes the call by
itself; a modem's handshake and it says DATA, then its CONNECT, for the
terminal and the bridges.
Error correction mode (T.30 Annex A) when the other machine has it: the
page in numbered HDLC frames, those a noisy line spoils sent again. Tested
against efax, against D-Modem's own T.30 machine (native-test's
`t30_line`, on a clean line and a noisy one), and over SIP with T.38
between the app's engine and dmodemd (`sip_fax_line`).
