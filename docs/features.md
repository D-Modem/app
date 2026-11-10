# The app, screen by screen


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
  the country, from the ITU's list of 192 (D-Modem's, after E.180):
  the modem's line settings and how it hears busy, and the call progress
  tones the app plays in its tones (a second of dial tone as an entry is
  dialled, the ringing tone while the other phone rings, the busy or
  congestion tone when a call can't be made), with a button to hear them.
  `tools/country_tones.py` makes the shared Kotlin's copy of the table.
- **Terminal**: a VT100/VT220/xterm terminal on the modem (256 and 24-bit
  colours, the alternate screen, bracketed paste, DEC line drawing) that
  also speaks what BBSes expect: CP437, ANSI.SYS's cursor save/restore and
  clear-home, bold as bright, iCE colours, and box drawing and block
  characters drawn to fill their cells so ANSI art has no gaps. A
  terminal type (Settings, and each directory entry's own) picks what it
  is: xterm, ANSI-BBS (a PC's, in CP437), DEC VT100, VT102 or VT220. Each
  answers the device attribute requests as that terminal did, knows only
  its own sequences (a VT100 has no colours, a VT220 no xterm titles or
  mouse), and gives SSH its `TERM` and Telnet its TERMINAL-TYPE. Colour
  themes (VGA, xterm, Solarized, Dracula, Monokai, Gruvbox, Nord, green
  and amber phosphor), fonts (Cascadia Code, Sauce Code Pro Nerd Font,
  Source Code Pro, Roboto Mono, JetBrains Mono, Fira Code, OpenDyslexic
  Mono, Inconsolata, Droid Sans Mono or the system's; real bold weights
  from the variable fonts, optional ligatures; `app/src/main/assets/fonts`), 80/132/40 fixed columns or pinch to zoom, scrollback
  with fling, long-press selection with copy, paste, share and open link,
  an extra keys bar (Esc, sticky Ctrl/Alt, Tab, arrows with auto-repeat,
  Home/End/PgUp/PgDn, F1-F12), volume keys as font size or Ctrl/Alt,
  bell as vibration or flash, full screen, snippets, session capture to a
  file (shared to other apps), hang up (+++ with its guard times, ATH),
  and Start PPP: once a call is up (dialled and logged in by hand), the
  app's PPP takes it, the phone's traffic through it as the internet's
  own dial has it (`PppTakeoverTest`).
  Files sent and received by ZMODEM, YMODEM, XMODEM(-1K) and Kermit, and
  as Intel HEX or Motorola S-records for EPROM programmers, monitors and
  boot loaders: a file sent as records (a file of them already, as it is:
  S1/S2/S3 by the image's size, Intel's extended addresses past 64 KB),
  records received with their checksums checked into the binary image
  they hold (gaps 0xFF). Plain text too: a file's bytes sent verbatim,
  as if typed (to an editor, a BBS, `cat > file`), and what comes saved
  as it is until the line is quiet for 10 s.
- **Dial**: the dialing directory: each system's number (or SIP user), the
  AT commands it needs before ATD, its terminal type, character set and backspace; one
  tap starts the modem if it is off and dials.
- **Log**: the modem's and SIP's log (Copy for all of it).
- **AT commands** (Settings, AT commands, and the terminal's menu): the
  AT command reference, D-Modem's manual (`docs/at_commands.pdf`, the
  A5 edition) in the app (`app/src/main/assets/docs`), its pages shown
  in the app with a zoom, or opened in the phone's PDF app for its search
  and its contents' links.
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
  A USB serial adapter plugged into the phone is `/dev/ttyUSB0` there (a
  pty of the app's too: Android lets no app's programs at a USB device):
  the app opens the adapter when a program opens the port and closes it
  (DTR down) when the program does; the speed, stop bits, RTS/CTS and B0's
  hang-up follow what the program sets (`stty`, minicom, pppd); a pty keeps
  no character size or parity, so it is 8 data bits, no parity.
  `SerialTtyTest`.
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
