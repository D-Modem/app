# The terminal's fonts

| File | Font | Licence | Source |
|---|---|---|---|
| CascadiaCode.ttf | Cascadia Code (variable, wght 200-700) | OFL 1.1 | google/fonts `ofl/cascadiacode` |
| SauceCodeProNerdFontMono.ttf | Sauce Code Pro Nerd Font Mono, Regular | OFL 1.1 | ryanoasis/nerd-fonts `patched-fonts/SourceCodePro` |
| SourceCodePro.ttf | Source Code Pro (variable, wght 200-900) | OFL 1.1 | google/fonts `ofl/sourcecodepro` |
| RobotoMono.ttf | Roboto Mono (variable, wght 100-700) | OFL 1.1 | google/fonts `ofl/robotomono` |
| JetBrainsMono.ttf | JetBrains Mono (variable, wght 100-800) | OFL 1.1 | google/fonts `ofl/jetbrainsmono` |
| FiraCode.ttf | Fira Code (variable, wght 300-700) | OFL 1.1 | google/fonts `ofl/firacode` |
| OpenDyslexicMono.ttf | OpenDyslexic Mono, Regular | OFL 1.1 | antijingoist/opendyslexic `sources/opendyslexic-mono/OpenDyslexic-Mono.glyphs`, built with fontmake 3.12 (`-o ttf --feature-writer None`: the source's anchor `_` stops fontmake's mark feature writer; the glyphs are as they are) |
| Inconsolata.ttf | Inconsolata (variable, wght 200-900, wdth 50-200) | OFL 1.1 | google/fonts `ofl/inconsolata` |
| DroidSansMono.ttf | Droid Sans Mono | Apache 2.0 | AOSP `platform/frameworks/base/data/fonts` |

The retro terminal types (ANSI-BBS, RIPscrip, Amiga ANSI, the Commodores,
the Atari 8-bit and ST, Viewdata) draw their characters from their machines'
own character ROM fonts, in the shared code (`shared/.../terminal/RomFonts.kt`,
written by `tools/gen_rom_fonts.py`), not from a font file here. The glyphs
are SyncTERM's, from Synchronet's ciolib (`src/conio/allfonts.c`,
LGPL-2.0-or-later, copyright Rob Swindell and contributors;
`licenses/syncterm-fonts-LGPL-2.0.txt`): the IBM VGA's code page 437,
RIPterm's 8x8, the Commodore 64's and 128's, the Atari 8-bit's, the Amiga's
Topaz, Topaz Plus, MicroKnight, MicroKnight Plus, P0T-NOoDLE and mO'sOul,
Prestel's and the Atari ST's.

Which character each code is comes from Rebecca Bettencourt's Legacy
Computing mappings (`mappings/legacy` in kreativekorp/charset), in
`LegacyCharsets.kt` (written by `tools/gen_legacy_charsets.py`). That file
also has Unscii 2.1's `unscii-8` and `unscii-16` (public domain,
http://viznut.fi/unscii/), for the characters a machine's ROM hasn't got
(another model's, such as the Atari XL's international set).

The licence texts are in `licenses/`; the app shows them (Settings, Font licences).
