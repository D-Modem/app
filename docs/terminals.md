# Terminal types

The terminal (Android and iOS) can be each of the terminals below. Select one
in Settings, or give a directory entry its own type. The type sets these
items:

- The codes that the screen accepts.
- The answers to the identity requests of the remote.
- The `TERM` that SSH sends and the TERMINAL-TYPE that Telnet sends.
- The size of the screen, its character set and its glyphs.
- The key bar.

All the types are in `shared/src/commonMain/kotlin/org/dmodem/app/terminal/`.
There is a `TerminalProfile` subclass for each type (`TerminalProfile.kt`,
`Commodore.kt`, `Atari.kt`, `Videotex.kt`, `Minitel.kt`, `Tektronix.kt`).
Each subclass makes an interpreter (`VtInterpreter`, `Vt420Interpreter`,
`AnsiBbsInterpreter`, `ByteControlInterpreter` and its subclasses,
`TektronixInterpreter`). ReGIS is `Regis.kt` and sixel is `Sixel.kt`. The two
graphics terminals draw through `Raster.kt`.

| Type | Id | TERM / Telnet type | Screen | What it is |
|---|---|---|---|---|
| xterm | `xterm` | `xterm-256color` / `XTERM-256COLOR` | any | The default: a VT220 with 256 and 24-bit colour, sixel, the mouse, titles, and Tek mode |
| ANSI-BBS (PC) | `ansi-bbs` | `ansi` / `ANSI` | 80x25 | A PC's BBS terminal, as SyncTERM is: CP437 in a VGA font, iCE colours, ANSI music, doorway mode |
| RIPscrip | `rip` | `rip` / `ANSI` | 80x43 text over 640x350 graphics | RIPscrip 1.54 over ANSI-BBS: `!|` lines draw (lines, shapes, fills, text, the clipboard), mouse regions and buttons send their text when tapped, `ESC [ !` answers RIP's version |
| Avatar (AVT/0+) | `avatar` | `avatar` / `ANSI` | 80x25 | ANSI-BBS with FidoNet's Avatar codes: ^L, ^Y repeat, ^V's attributes, moves and AVT/0+'s area scrolls, clears, fills and pattern repeat |
| Amiga ANSI | `amiga` | `amiga` / `ANSI` | 80x25 | ANSI-BBS in Latin-1 in Topaz, with colours from the Amiga's 12-bit palette |
| DEC VT52 | `vt52` | `vt52` / `DEC-VT52` | 80x24 | The VT52's own escape codes and graphics |
| DEC VT100 | `vt100` | `vt100` / `DEC-VT100` | 80/132x24 | ANSI codes at level 1: no colour; double-size lines; VT52 mode |
| DEC VT102 | `vt102` | `vt102` / `DEC-VT102` | 80/132x24 | Adds character and line insert and delete, and a printer port |
| DEC VT220 | `vt220` | `vt220` / `DEC-VT220` | 80/132x24 | Level 2: G0-G3 character sets, 8-bit controls, soft fonts, user-defined keys, selective erase |
| DEC VT320 | `vt320` | `vt320` / `DEC-VT320` | 80/132x24 | Level 3: DEC Technical, the state reports, a status line |
| DEC VT340 | `vt340` | `vt340` / `DEC-VT340` | 80/132x24 | A VT320 with graphics: sixel and ReGIS in 16 shared colour registers, and Tek mode |
| DEC VT420 | `vt420` | `vt420` / `DEC-VT420` | 80/132x24+ | Level 4: left and right margins, rectangle operations, six pages |
| DEC VT510, VT520 | `vt510`, `vt520` | `vt510` / `DEC-VT510`, … | 80/132x24+ | Level 5: the display extent report, bell volumes |
| DEC VT525 | `vt525` | `vt525` / `DEC-VT525` | 80/132x24+ | A VT520 in colour, with its colour table |
| Commodore 64, 128 (40 and 80), Plus/4, VIC-20, PET (40 and 80) | `c64`, `c128`, `c128-80`, `plus4`, `vic20`, `pet`, `pet80` | `petscii` / `PETSCII` | 40x25 (VIC-20: 22x23; 80 for the C128's VDC and the 8032) | PETSCII as CCGMS and Novaterm do it, each in its machine's palette |
| Atari 8-bit | `atari`, `atari-intl` | `atascii` / `ATASCII` | 40x24 | ATASCII and the screen editor's controls; the XL's international set as a second type |
| Atari ST | `atari-st`, `atari-st-low` | `st52` / `VT52` | 80x25 in 4 colours, or 40x25 in 16 | TOS's VT52 with its colours (ESC b, ESC c), reverse, wrap, cursor save and on/off, more erases, line insert and delete, in the ST's character set |
| Viewdata (Prestel) | `viewdata` | `viewdata` / `VIEWDATA` | 40x24 | Teletext Level 1 attributes and mosaics; # sends |
| Minitel | `minitel` | `minitel` / `MINITEL` | 40x25 | Teletel videotex with the status row, the protocol commands, Mixte (80 columns) and Téléinformatique (a VT100) |
| Tektronix 4014 | `tek4014` | `tek4014` / `TEK4014` | 4096x3120 tube | Vectors, points, incremental plots and alpha text on a storage tube |

## Fonts

Each retro type draws with the character ROM font of its machine. The fonts
are the copies of SyncTERM (`RomFonts.kt`, written by
`tools/gen_rom_fonts.py`). The app reads them in the character set of the
machine (`MachineFonts.kt`):

| Types | Font |
|---|---|
| ANSI-BBS, Avatar | IBM VGA 8x16, code page 437 |
| RIPscrip | RIPterm's 8x8, code page 437 |
| Amiga ANSI | Topaz (Kickstart 1's), 8x8 |
| C64, Plus/4, VIC-20, PET | The C64's character ROM, both sets |
| C128 (40 and 80) | The C128's character ROM, both sets |
| Atari 8-bit | The Atari's character ROM (the XL's international letters from Unscii) |
| Atari ST | The ST's 8x8 (its colour modes') |
| Viewdata | Prestel's teletext characters, 8x16 |

A BBS can change the font of the ANSI-BBS types with the SyncTERM sequence
`ESC [ 0 ; n SP D`. The app draws the font at the height of the profile, and
reads it as the octets of the session, as SyncTERM does. These fonts are
available:

| n | Font |
|---|---|
| 0 | IBM VGA (code page 437) |
| 26 | Code page 437, thin |
| 32, 33 | Commodore 64, upper and lower case |
| 34, 35 | Commodore 128, upper and lower case |
| 36 | Atari 8-bit |
| 37 | P0T-NOoDLE (Amiga) |
| 38 | mO'sOul (Amiga) |
| 39 | MicroKnight Plus (Amiga) |
| 40 | Topaz Plus (Amiga) |
| 41 | MicroKnight (Amiga) |
| 42 | Topaz (Amiga) |
| 43 | Prestel |
| 44 | Atari ST (its mono mode's 8x16) |

The number of the profile font, RIS or another terminal type sets the font of
the profile again. The other code page fonts (1-31) are not available, and
the app ignores them.

On a VT510 and later, the host can change the size of the screen (DECSNLS,
DECSLPP, DECSCPP). xterm and ANSI-BBS fit the view.

## Graphics

Sixel images are cells of an image that scroll with the text. But ReGIS and
the Tektronix draw a picture over the full screen. The picture has the size
of the screen in pixels. Only the cells that it drew in show it. If the size
of the screen changes, the picture starts again, as on a storage tube.

- **Sixel**: xterm, ANSI-BBS and the VT340. On the VT340, the images
  share the 16 colour registers of ReGIS.
- **ReGIS** (`ESC P p … ESC \`, VT340 only):
  - The screen is 800x480. Positions can be absolute or relative, and
    use the position stack and pixel vectors.
  - Commands: P, V, C (circles, arcs, closed and open curves), F, T, W,
    S, R, L and macrographs (@).
  - The app runs each command when it is complete. Thus a drawing that comes
    in many reads shows while it comes. `ESC P 1 p` starts again from
    zero, and `ESC P p` continues from the end of the last drawing.
- **Tektronix 4014**: its own terminal type, and Tek mode on xterm and
  the VT340.
  - `ESC [ ? 38 h` changes to the alternate screen, and `ESC ETX` goes
    back to the text.

## Tests

Each sequence has unit tests in `shared/src/commonTest`. Run them with
`./gradlew :shared:jvmTest :shared:linuxX64Test`.

`ProfileRenderTest` in `app/src/test` draws a screen on each terminal
type through the real Android view. It keeps each screen as
`app/build/profiles/<id>.png`. Run it with
`./gradlew :app:testDebugUnitTest --tests '*ProfileRenderTest*'`. After a
change to the drawing code, look at the pictures.

### vttest

To run vttest, do these steps:

1. Open the Linux shell of the app.
2. Install vttest (`apk add vttest`, or the equivalent command of the distro).
3. Set the terminal type in Settings.
4. Run `vttest`.

The table gives the expected results, menu by menu, from the unit tests and
the code. We did not compare these results with a vttest run on a device
yet. When you do this, update the table.

| vttest menu | Profiles | Expect |
|---|---|---|
| 1 Cursor movements | all DEC, xterm | passes |
| 2 Screen features (132 columns, scrolling regions, origin mode) | all DEC, xterm | passes. The app draws smooth scrolling (DECSCLM) as jump scrolling |
| 3 Character sets | VT100 and later | passes. NRCS needs a VT220 or later |
| 4 Double-sized characters | VT100 and later | passes |
| 5 Keyboard | all | you cannot examine the LEDs and auto-repeat on a phone. Use the key bar for the keypad and function keys |
| 6 Terminal reports | all | passes. The answerback message is empty |
| 7 VT52 mode | VT100 and later | passes |
| 8 VT102 features | VT102 and later | passes |
| 9 Known bugs | all | n/a |
| 10 Reset and self-test | all | the self-test (DECTST) does nothing |
| 11 Non-VT100 terminals | by level | VT220: passes. VT320: passes. VT420: passes except the locator (no mouse reports on a DEC type). VT520: passes except the keyboard's own settings (the app accepts and ignores bell volumes and key click). xterm: its own menus |

## What is not done

**ReGIS**
- The app draws text upright in all directions. The string moves in the
  direction that the command gives, but the glyphs do not turn.
- The app accepts and ignores italics (T(I)), and the hard copy and
  scrolling of S.
- The app does not answer R(M) (macrograph storage).
- If a colour register changes after the app draws, the app does not change
  the colour of the items that it drew before.

**RIPscrip**
- The app does not load icons (1I, 1W) and scenes (1R) from the files of the host.
- The app does not expand text variables ($…$).
- The app draws the stroke fonts with the bitmap font of the PC at their size.
- Vertical text goes down, but its glyphs do not turn.

**Tektronix**
- No graphic input (GIN) cross-hair.
- No write-through or defocused modes.

**Viewdata and Minitel**
- The app draws double height and double size as single size.
- The Minitel draws its text in the font of the app, not in the font of its own ROM.

**Fonts**
- The app does not keep fonts 1-3 of `ESC [ Ps1 ; n SP D` (SyncTERM uses
  them for bright and blinking text). Only font 0 changes.
- The DEC types, the Tektronix and the Minitel use the font of the app. Their
  ROM fonts are not available.
- The VIC-20, Plus/4 and PET draw with the ROM of the C64, which is almost
  the same as their ROM.

**DEC**
- No locator (DECELR), and no answerback message.

**iOS**
- The code that draws pictures and images is written, but we did not
  compile it in Xcode yet (`imageRuns` and `imagePixels` of the bridge).
