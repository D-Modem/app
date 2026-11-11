# Terminal types

The terminal (Android and iOS) can be any of the terminals below. Pick one
in Settings, or give a directory entry its own. The choice sets what the
screen understands, how it answers the remote's identity requests, what
SSH sends as `TERM` and Telnet as TERMINAL-TYPE, the screen's size, its
character set and glyphs, and the key bar.

All of them live in `shared/src/commonMain/kotlin/org/dmodem/app/terminal/`:
a `TerminalProfile` subclass for each type (`TerminalProfile.kt`,
`Commodore.kt`, `Atari.kt`, `Videotex.kt`, `Minitel.kt`, `Tektronix.kt`)
and the interpreter it makes (`VtInterpreter`, `Vt420Interpreter`,
`AnsiBbsInterpreter`, `ByteControlInterpreter` and its subclasses,
`TektronixInterpreter`). ReGIS is `Regis.kt`, sixel `Sixel.kt`, and both
graphics terminals draw through `Raster.kt`.

| Type | Id | TERM / Telnet type | Screen | What it is |
|---|---|---|---|---|
| xterm | `xterm` | `xterm-256color` / `XTERM-256COLOR` | any | The default: a VT220 with 256 and 24-bit colour, sixel, the mouse, titles, and Tek mode |
| ANSI-BBS (PC) | `ansi-bbs` | `ansi` / `ANSI` | 80x25 | A PC's BBS terminal, as SyncTERM is: CP437 in a VGA font, iCE colours, ANSI music, doorway mode |
| Avatar (AVT/0+) | `avatar` | `avatar` / `ANSI` | 80x25 | ANSI-BBS with FidoNet's Avatar codes: ^L, ^Y repeat, ^V's attributes, moves and AVT/0+'s area scrolls, clears, fills and pattern repeat |
| Amiga ANSI | `amiga` | `amiga` / `ANSI` | 80x25 | ANSI-BBS in Latin-1 with 8x8 glyphs (Topaz's size, Unscii's drawing) and colours from the Amiga's 12-bit palette |
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

A VT510's and later screen size can change at the host's request
(DECSNLS, DECSLPP, DECSCPP). xterm and ANSI-BBS fit the view.

## Graphics

Sixel images are cells of an image that scroll with the text. ReGIS and
the Tektronix draw a picture over the whole screen instead. It is kept
the screen's size in pixels, and only the cells it has drawn in show it.
Resizing the screen starts the picture again, as on a storage tube.

- **Sixel**: xterm, ANSI-BBS and the VT340. On the VT340 the images
  share ReGIS's 16 colour registers.
- **ReGIS** (`ESC P p … ESC \`, VT340 only):
  - The screen is 800x480. Positions can be absolute or relative, and
    use the position stack and pixel vectors.
  - Commands: P, V, C (circles, arcs, closed and open curves), F, T, W,
    S, R, L and macrographs (@).
  - Commands are run as they become whole, so a drawing sent over many
    reads shows as it comes. `ESC P 1 p` starts afresh, and `ESC P p`
    carries on from where the last one left off.
- **Tektronix 4014**: its own terminal type, and Tek mode on xterm and
  the VT340.
  - `ESC [ ? 38 h` switches to the alternate screen, and `ESC ETX` goes
    back to the text.

## Testing

Each sequence has unit tests in `shared/src/commonTest`. Run them with
`./gradlew :shared:jvmTest :shared:linuxX64Test`.

`ProfileRenderTest` in `app/src/test` draws a screen on every terminal
type through the real Android view and keeps it as
`app/build/profiles/<id>.png`. Run it with
`./gradlew :app:testDebugUnitTest --tests '*ProfileRenderTest*'` and look
at the pictures after a change to drawing.

### vttest

To run vttest, open the app's Linux shell and install it
(`apk add vttest`, or the distro's equivalent). Set the terminal type in
Settings, then run `vttest`.

What to expect, menu by menu, from the unit tests and the code. These
haven't yet been checked against a vttest run on a device: update the
table when they are.

| vttest menu | Profiles | Expect |
|---|---|---|
| 1 Cursor movements | all DEC, xterm | passes |
| 2 Screen features (132 columns, scrolling regions, origin mode) | all DEC, xterm | passes. Smooth scrolling (DECSCLM) is drawn as jump scrolling |
| 3 Character sets | VT100 and later | passes. NRCS needs a VT220 or later |
| 4 Double-sized characters | VT100 and later | passes |
| 5 Keyboard | all | the LEDs and auto-repeat can't be checked on a phone. Use the key bar for the keypad and function keys |
| 6 Terminal reports | all | passes. The answerback message is empty |
| 7 VT52 mode | VT100 and later | passes |
| 8 VT102 features | VT102 and later | passes |
| 9 Known bugs | all | n/a |
| 10 Reset and self-test | all | the self-test (DECTST) does nothing |
| 11 Non-VT100 terminals | by level | VT220: passes. VT320: passes. VT420: passes except the locator (no mouse reports on a DEC type). VT520: passes except the keyboard's own settings (bell volumes and key click are accepted and ignored). xterm: its own menus |

## What isn't done

**ReGIS**
- Text is drawn upright in any direction: the string moves the way it's
  told, but the glyphs don't turn.
- Italics (T(I)) and the hard copy and scrolling of S are accepted and
  ignored.
- R(M) (macrograph storage) isn't answered.
- A colour register changed after drawing doesn't recolour what's
  already drawn.

**Tektronix**
- No graphic input (GIN) cross-hair.
- No write-through or defocused modes.

**Viewdata and Minitel**
- Double height and double size are drawn single.

**DEC**
- No locator (DECELR), and no answerback message.

**iOS**
- The picture and image drawing is built but not yet compiled in Xcode
  (the bridge's `imageRuns` and `imagePixels`).
