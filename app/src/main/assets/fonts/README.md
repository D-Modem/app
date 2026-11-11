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

The Commodore terminal types draw their characters from 8x8 bitmaps in the
shared code (`shared/.../terminal/LegacyCharsets.kt`, written by
`tools/gen_legacy_charsets.py`), not from a font file here: the glyphs are
Unscii 2.1's `unscii-8` (public domain, http://viznut.fi/unscii/), and
which character each code is comes from Rebecca Bettencourt's Legacy
Computing mappings (`mappings/legacy` in kreativekorp/charset). No
Commodore ROM font is shipped.

The licence texts are in `licenses/`; the app shows them (Settings, Font licences).
