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
the Atari 8-bit and ST, Viewdata) do not use a font file from this folder.
They draw their characters from the character ROM fonts of their machines,
in the shared code (`shared/.../terminal/RomFonts.kt`, written by
`tools/gen_rom_fonts.py`). The glyphs come from SyncTERM, from the ciolib of
Synchronet (`src/conio/allfonts.c`, LGPL-2.0-or-later, copyright Rob Swindell
and contributors; `licenses/syncterm-fonts-LGPL-2.0.txt`). These are the
fonts: the code page 437 of the IBM VGA, the 8x8 of RIPterm, the fonts of the
Commodore 64 and 128, the font of the Atari 8-bit, the Topaz, Topaz Plus,
MicroKnight, MicroKnight Plus, P0T-NOoDLE and mO'sOul of the Amiga, and the
fonts of Prestel and the Atari ST.

The character for each code comes from the Legacy Computing mappings of
Rebecca Bettencourt (`mappings/legacy` in kreativekorp/charset), in
`LegacyCharsets.kt` (written by `tools/gen_legacy_charsets.py`). That file
also has `unscii-8` and `unscii-16` of Unscii 2.1 (public domain,
http://viznut.fi/unscii/). These give the characters that the ROM of a
machine does not have (the characters of another model, for example the
international set of the Atari XL).

The licence texts are in `licenses/`. The app shows them (Settings, Font licences).
