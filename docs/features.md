# The app, screen by screen


- **Home**: Home shows these items:
  - The state of the modem (off, ready, connecting, online with its rates
    down and up and the time of the call), and the one action for that
    state (configure SIP, start, hang up, stop).
  - The line (the SIP account, incoming calls, the network, fax).
  - Quick dial of the systems that you dialled last.
  - The phone (a voice call that you make or answer, and the messages of the
    answering machine: [voice.md](voice.md)).
  - The terminal and the directory.
  - The console of the modem (folded, with an AT command line).
- **An incoming call that rings** (SIP, the other phone, a mobile call): the
  app shows the incoming call notification of the phone (heads-up, over the
  lock screen, the ringtone). It also shows a popup over the app, with the
  name of the caller if the line gives it (the name of the other phone). The
  popup has these options:
  - Answer (ATA). The call of a fax machine goes to the fax receiver
    automatically. The call of a modem goes to the terminal.
  - Ignore. The call continues to ring, but the popup closes.
  - Answer as fax, if adaptive answer is disabled.
  - Answer as a voice call, or Take a message (the answering machine).

  The popup closes when the modem answers (here, a computer on a bridge, or
  S0) or when the caller stops the call.
- **Settings**: these items are in Settings:
  - The SIP account. You can also paste `user:password@host`, as the
    `SIP_LOGIN` of dmodemd.
  - The acceptance of incoming calls, T.38, and the log levels.
  - The AT commands that the app sends at the start (`ATX3` by default,
    because SIP has no dial tone).
  - The country, from the ITU list of 192 countries (the list of D-Modem,
    after E.180). The country sets the line settings of the modem and how the
    modem hears busy. It also sets the call progress tones that the app plays
    in its tones: one second of dial tone when you dial an entry, the ringing
    tone while the other phone rings, and the busy or congestion tone when a
    call fails. A button plays them. `tools/country_tones.py` makes the copy
    of the table in the shared Kotlin.
- **Terminal**: a VT100/VT220/xterm terminal on the modem (256 and 24-bit
  colours, the alternate screen, bracketed paste, DEC line drawing). It also
  accepts the codes that BBSes expect: CP437, the cursor save/restore and
  clear-home of ANSI.SYS, bold as bright, and iCE colours. It draws box
  drawing and block characters to fill their cells, thus ANSI art has no gaps.

  A terminal type (in Settings, and in each directory entry) sets the type of
  the terminal: xterm, ANSI-BBS (the terminal of a PC, in CP437, as SyncTERM
  is), RIPscrip, Avatar, Amiga ANSI, DEC VT52, VT100, VT102, VT220, VT320,
  VT340, VT420, VT510, VT520 or VT525, a Commodore, an Atari 8-bit or ST,
  Viewdata (Prestel), a Minitel or a Tektronix 4014. Each type answers the
  device attribute requests as that terminal did. Each type knows only its
  own sequences (a VT100 has no colours, a VT220 has no xterm titles or
  mouse). Each type gives SSH its `TERM` and Telnet its TERMINAL-TYPE.

  The DEC terminals have these items: their double-width and double-height
  lines, 80/132 column switching (the screen fits again to the width), the
  sequences and graphics of the VT52 (and the VT52 mode of a VT100), the UK
  character set, the keyboard LEDs and parameter reports, and a printer port
  (from the VT102). The app saves the output of the printer port to a text
  file.

  The VT220 and VT320 add these items:
  - G0-G3 with their locking and single shifts.
  - The national replacement sets, DEC Supplemental and (VT320) DEC Technical
    and Latin-1 as 96-character sets.
  - 8-bit replies (S8C1T).
  - Protected characters with selective erase (DECSCA, DECSED, DECSEL).
  - The conformance level (DECSCL).
  - The printer, keyboard and user-defined key status reports.
  - The cursor and tab stop reports (DECCIR, DECTABSR).
  - The preferred supplemental set.
  - User-defined keys (DECUDK: Shift with F6-F20).
  - Soft characters (DECDLD: the glyphs of the host, drawn from its bitmaps).

  A VT320 also has its status line below the screen. The line shows the
  position of the cursor, or a line that the host writes (DECSSDT, DECSASD).

  The VT420 adds these items:
  - Left and right margins (DECLRMM, DECSLRM).
  - The rectangular area operations (copy, erase, selective erase, fill,
    attribute change and reverse, as a rectangle or a stream, and the
    checksum).
  - Column insert and delete, and back and forward index.
  - Six pages (NP, PP, PPA, PPR, PPB).
  - The lines and columns that the host requests (DECSNLS, DECSLPP, DECSCPP).
  - The keypad and backarrow modes.

  The VT5xx add the display extent report. The VT525 adds colour, with its
  colour table (DECAC, DECRQTSR, DECRSTS, in RGB or the HLS of DEC).

  The VT340 has the graphics: sixel images and ReGIS (`ESC P p`) in 16 colour
  registers that they share. ReGIS draws on an 800x480 screen over the text,
  with these items:
  - Its positions (absolute, relative, the position stack, pixel vectors).
  - Vectors, circles, arcs and curves, and filled polygons.
  - Text in sizes and directions.
  - The writing controls (overlay, replace, complement, erase, the colour,
    patterns, negative, shading).
  - The screen controls (erase, background, address mapping, the colour map
    in HLS or letters).
  - The position and alphabet reports, loadable alphabets and macrographs.

  The Tektronix 4014 is its storage tube as a separate type. It has vectors,
  points, incremental plots and alpha text in four sizes, the line styles,
  the report of the beam position, and ESC FF to clear. xterm and the VT340
  also have it as Tek mode (`ESC [ ? 38
  h`, on the alternate screen until `ESC ETX`).

  The key bar gets the DEC keypad (PF1-PF4, digits, minus, comma, period,
  Enter, in numeric or application mode). On a VT220 or VT320, it also gets
  the F6-F20, Find and Select of the LK201, and a page of the user-defined
  keys. A hardware numpad sends the keys of the keypad.

  ANSI-BBS is the 80x25 of the PC, drawn in the 8x16 code page 437 of the IBM
  VGA. It has these SyncTERM additions:
  - Font selection (`ESC [ 0 ; n SP D`, with the SyncTERM font numbers: the
    fonts of the C64, C128, Atari, Amiga, Prestel and ST).
  - ANSI music (`ESC [ |`, and a bare `ESC [ M` if a PLAY string follows it).
    The app plays it as a PC speaker does.
  - iCE colours when the BBS requests them (`ESC [ ? 33 h`).
  - The line speed that the BBS requests for ANSI animations
    (`ESC [ 0 ; n * r`).
  - Doorway mode for DOS doors (`ESC [ = 255 h`: the keys as the scan codes
    of a PC).

  Avatar is ANSI-BBS with the compact codes of FidoNet (AVT/0 and AVT/0+:
  ^L, the repeat of ^Y, the PC attributes of ^V, cursor moves, insert mode,
  area scrolls, clears and fills, and pattern repeats).

  RIPscrip (1.54) is ANSI-BBS in which the lines that start with `!|` draw on
  a 640x350 EGA screen over an 80x43 text window. It draws lines in their
  styles and thicknesses, rectangles, bars, circles, ellipses, arcs, pie
  slices, Béziers, polygons, flood fills in the patterns of BGI or of the
  host, text, the palette, the viewport and the text window, and the
  clipboard. It has mouse regions and buttons that send their text when you
  tap them. It answers `ESC [ !` with its version.

  Amiga ANSI is ANSI-BBS in Latin-1 (the set of the Amiga), in Topaz (the
  8x8 font of the Amiga), in colours from the palette of the Amiga.

  The Commodore types (C64, C128 in 40 or 80 columns, Plus/4, VIC-20, PET in
  40 or 80 columns) use PETSCII as CCGMS and Novaterm do. The screen becomes
  the screen of the machine (40x25, or 22x23 for the VIC-20) in its palette.
  The app draws it in square cells from the 8x8 character ROM of the machine
  (the ROM of the C64 or of the C128). It has the two character sets, the
  colour and cursor codes, reverse, and the ESC sequences, line feed,
  underline and flash of the C128. The app changes typed and pasted text into
  PETSCII. CTRL and C= with a digit set colours, and C= with a letter types
  its graphic. The key bar has RUN/STOP, CLR/HOME, INST/DEL, RVS, F1-F8 and a
  page of colour keys. The app saves a capture as a `.seq` file, and "Plain
  text" sends it back without change.

  The Atari 8-bit types (ATASCII, with the graphics set or the international
  set of the XL) give the 40x24 of the screen editor in its blues. They have
  inverse video, cursor keys that wrap round the screen, CLEAR, EOL, insert
  and delete of characters and lines, tabs, the buzzer, and ESC to show the
  character of a control. CTRL with a letter types its graphic. The INV key
  of the key bar types the next character inverse.

  The Atari ST types (80 columns in the four colours of medium resolution,
  or 40 columns in the sixteen colours of low resolution) are the VT52 of TOS
  with its additions. These are the pens (ESC b, ESC c), reverse, wrap, the
  saved and hidden cursor, erases to the start of the screen and of the line,
  line insert and delete, and the character set of the ST.

  Viewdata is the 40x24 teletext of Prestel. Its attributes use a cell and
  change the remaining part of the row (alpha and mosaic colours, flash,
  conceal, contiguous or separated mosaics, hold, new and black background).
  The app draws double height as single height. The app draws the 2x3
  mosaics from its own bitmaps. The cursor wraps round the screen. # (and
  also Enter) is the key that sends.

  The Minitel is the 40x25 of Teletel with its status row. It has these
  items:
  - US addressing, text and mosaics (SO/SI), and the accents and symbols of
    SS2.
  - The ESC colours and attributes (the background and separation change at
    the next space or mosaic).
  - REP and its CSI commands.
  - The protocol commands (it answers as a Minitel 2).
  - Scroll mode or page mode.
  - The 80-column Mixte mode, and the Téléinformatique mode as a VT100.
  - A key bar of Envoi, Retour, Suite, Répétition, Correction, Sommaire,
    Guide, Annulation and Connexion/Fin.

  Viewdata and the Minitel save captures as `.vdt`.

  The terminal also has these items:
  - Colour themes (VGA, xterm, Solarized, Dracula, Monokai, Gruvbox, Nord,
    green and amber phosphor).
  - Fonts (Cascadia Code, Sauce Code Pro Nerd Font, Source Code Pro, Roboto
    Mono, JetBrains Mono, Fira Code, OpenDyslexic Mono, Inconsolata, Droid
    Sans Mono or the font of the system). Real bold weights from the variable
    fonts, and optional ligatures (`app/src/main/assets/fonts`).
  - 80/132/40 fixed columns, or pinch to zoom.
  - Scrollback with fling.
  - Long-press selection with copy, paste, share and open link.
  - An extra keys bar (Esc, sticky Ctrl/Alt, Tab, arrows with auto-repeat,
    Home/End/PgUp/PgDn, F1-F12).
  - Volume keys as font size or Ctrl/Alt.
  - Bell as vibration or flash.
  - Full screen and snippets.
  - Session capture to a file (shared to other apps).
  - Hang up (+++ with its guard times, ATH).
  - Start PPP. When a call is connected (you dialled and logged in
    manually), the PPP of the app takes the call. The traffic of the phone
    then goes through it, as with the dial of the internet screen
    (`PppTakeoverTest`).

  The terminal sends and receives files by ZMODEM, YMODEM, XMODEM(-1K) and
  Kermit. It also sends and receives Intel HEX or Motorola S-records for
  EPROM programmers, monitors and boot loaders:
  - When it sends a file as records, it uses S1/S2/S3 by the size of the
    image, and Intel extended addresses above 64 KB. If the file already
    contains records, it sends the file without change.
  - When it receives records, it checks their checksums and writes the
    binary image that they hold (gaps are 0xFF).

  Plain text is also available. The app sends the bytes of a file without
  change, as if you typed them (to an editor, a BBS, `cat > file`). It saves
  the received data without change until the line is silent for 10 s.
- **Dial**: the dialing directory. Each entry has the number of the system
  (or the SIP user), the AT commands that are necessary before ATD, its
  terminal type, its character set and its backspace. One tap starts the
  modem if it is off, and dials.
- **Remote logins**: a server that the app connects to through the internet,
  not through the modem, in a separate terminal session. On Android, a
  directory entry connects by SSH, Telnet or rlogin. rlogin sends the user
  name as the two names, and the size of the window if the server asks for
  it. On iOS, the network button of the Terminal tab opens the Remote screen:
  SSH, Telnet or rlogin. For SSH, the app keeps the key of the server the
  first time that you trust it, and uses a password or the private key of the
  app. Telnet and rlogin come from the shared Kotlin (`org.dmodem.app.net`).
- **SSH** is the same client on all platforms. The GUI also uses it on Linux,
  Windows and macOS. It is libdmodemssh (`native-ssh`), on libssh 0.11 and
  its own Mbed TLS. Both are built from their release tarballs, pinned by
  SHA-256. Android loads it through JNI (`NativeSsh.kt`). iOS loads it as the
  `DModemSSHNative` XCFramework (`ios/DModemSSH`). It has these items:
  - Ed25519, ECDSA and RSA keys, for the host and the user.
  - Password, keyboard-interactive and key logins. The key can be in the
    format of OpenSSH, PEM or the .ppk of PuTTY. The engine reads the .ppk
    itself (`native-ssh/src/ppk.c`).
  - The keys of an agent. On the desktops, this is the socket of an
    ssh-agent, or on Windows, Pageant or the agent of OpenSSH
    (`native-ssh/src/agent.c`).
  - On Android and iOS, the keys of the app. The app can make them, or
    import them in the format of OpenSSH, PEM or the .ppk of PuTTY. iOS
    keeps one key, from Files. It changes a .ppk into the OpenSSH format
    with its passphrase, and shows its public key to copy.
  - On Android, port forwards (-L, -R, -D: SOCKS 4/4a/5) and agent
    forwarding (-A). If the entry says so, the app asks the user to approve
    each signature.

  Its tests run against a real sshd: `native-ssh/test`. The JVM tests of the
  app also run on a host build of the JNI library
  (`./gradlew :app:buildHostSsh`, which the unit tests run).
- **Log**: the log of the modem and of SIP (Copy copies all of it).
- **AT commands** (Settings, AT commands, and the menu of the terminal): the
  AT command reference. This is the manual of D-Modem
  (`docs/at_commands.pdf`, the A5 edition) in the app
  (`app/src/main/assets/docs`). The app shows its pages with a zoom. You can
  also open it in the PDF app of the phone, for its search and the links of
  its contents.
- **Back**: from all other tabs, Back goes to Home. On Home, the app goes to
  the background (the modem continues to run), as the Home button does.
- **Local Linux** (Settings, Linux): the app installs and runs distributions
  under proot. You are root in /root, the storage of the phone is at
  /sdcard, and the modem is a serial port at `/dev/ttyDM0` (a pty of the
  app, bound in). `minicom -D /dev/ttyDM0`, pppd, efax and similar programs
  operate as on a real modem. The program that opens the port has the modem
  as the client of a serial bridge has it (one at a time, with the TCP,
  Bluetooth and USB bridges). When the program closes the port, the app
  drops DTR, thus the call hangs up as at the end of a serial cable.
  `ModemTtyTest`, and `LinuxSystemTest` in a real Alpine under proot (ATI
  from the distro, and the answer of the modem).

  A USB serial adapter that you connect to the phone is `/dev/ttyUSB0` in
  the Linux. This is also a pty of the app, because Android does not let
  the programs of an app access a USB device. The app opens the adapter
  when a program opens the port. It closes the adapter (DTR down) when the
  program closes the port. The speed, stop bits, RTS/CTS and the hang-up of
  B0 follow the settings of the program (`stty`, minicom, pppd). A pty
  keeps no character size or parity, thus the port uses 8 data bits and no
  parity. `SerialTtyTest`.
- **Dial-in** (Settings, Dial-up internet): the phone is an ISP for each
  caller of the modem, on SIP or on all other lines. The app answers a call
  that rings (unless the fax settings already answer calls). When the call
  connects, the app takes the DTE of the modem. A PPP server on the call
  gives the caller 10.64.64.2 and DNS at 10.64.64.1. It also gives the
  network of the phone, through the same NAT that the internet host of the
  serial bridges uses. When the PPP of the caller ends, or if it does not
  start, the app hangs up. When the call drops, PPP stops. Home shows the
  caller online, with the data in each direction and a Hang up button. We
  tested this over two modems of D-Modem back to back (`DialInLineTest`:
  answer, CONNECT 33600, the address, DNS and a ping through the phone, the
  hang-up).

The modem runs in a foreground service (`ModemService`). The service keeps
the CPU and Wi-Fi awake for the real-time audio of the call. The SIP user
agent binds to the addresses that the phone has when it starts. Thus, when
the default network changes, the service starts the modem again (after the
call, if a call continues). The DNS servers come from the active network,
because Android has no `/etc/resolv.conf` for libre to read.
