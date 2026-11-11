#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Neo-Desktop
"""
Writes shared/.../terminal/LegacyCharsets.kt: the 8-bit home computers'
character sets as Unicode (Rebecca Bettencourt's Legacy Computing
mappings) and an 8x8 glyph for each character they use (Unscii's, public
domain), so the app draws them as those machines did without shipping
their ROM fonts.

  python3 tools/gen_legacy_charsets.py UNSCII_8_HEX UNSCII_16_HEX MAPPINGS_DIR

UNSCII_8_HEX and UNSCII_16_HEX are unscii-8.hex and unscii-16.hex
(http://viznut.fi/unscii/); MAPPINGS_DIR is mappings/legacy of
https://github.com/kreativekorp/charset. The 8x16 ones are a PC's: code
page 437 as a VGA showed it, for the ANSI-BBS profile.
"""
import os
import sys

# (the Kotlin name, the mapping file, what it is)
SETS = [
    ("C64_UPPER", "c64ipri", "Commodore 64/128 and Plus/4, upper case and graphics"),
    ("C64_LOWER", "c64ialt", "Commodore 64/128 and Plus/4, lower and upper case"),
    ("VIC_UPPER", "cvicipri", "VIC-20, upper case and graphics"),
    ("VIC_LOWER", "cvicialt", "VIC-20, lower and upper case"),
    ("PET_UPPER", "cpetipri", "PET, upper case and graphics"),
    ("PET_LOWER", "cpetialt", "PET, lower and upper case"),
    ("ATARI_GRAPHICS", "atari8ig", "Atari 8-bit ATASCII, the graphics set (0x80-0xff: 0x00-0x7f reversed)"),
    ("ATARI_INTERNATIONAL", "atari8ii", "Atari 8-bit ATASCII, the international set (0x80-0xff: 0x00-0x7f reversed)"),
]

# (a CHR$() mapping without its control codes' glyphs, and the screen-code one they come from)
VIDEO = {"atari8ig": "atari8vg", "atari8ii": "atari8vi"}


# glyphs Unscii hasn't got, drawn here (the Atari's ESC and CLEAR symbols: arrows to the upper left)
EXTRA = {
    0x241B: ["........", ".######.", ".##.....", ".#.#....", ".#..#...", ".#...#..", ".#....#.", "........"],
    0x1F8B0: ["........", ".####...", ".##.....", ".#.#....", ".#..#...", ".....#..", "......#.", "........"],
}


# code page 437's 0x20..0xff (as Charsets.kt's Cp437 has them)
CP437 = [chr(c) for c in range(0x20, 0x7F)] + list(
    "ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ¢£¥₧ƒáíóúñÑªº¿⌐¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐"
    "└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀αßΓπΣσµτΦΘΩδ∞φε∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■\u00a0")

# 8x16 glyphs Unscii hasn't got, drawn here: the reversed not sign and the bullet operator
EXTRA_16 = {
    0x2310: ["........"] * 7 + ["..#####.", "..##....", "..##....", "..##...."] + ["........"] * 5,
    0x2219: ["........"] * 7 + ["...##...", "...##..."] + ["........"] * 7,
}


def atari_screen_code(a):
    """an ATASCII code (0x00..0x7f) as its screen code"""
    if a < 0x20:
        return a + 0x40
    if a < 0x60:
        return a - 0x20
    return a

OUT = "shared/src/commonMain/kotlin/org/dmodem/app/terminal/LegacyCharsets.kt"


def mapping(path):
    """a mapping file's codes and code points (the first of a sequence)"""
    m = {}
    for line in open(path, encoding="utf-8"):
        if not line.startswith("0x"):
            continue
        f = line.split("\t")
        # (a reversed character's: drawn as its plain one, reversed)
        if f[1].startswith("<"):
            continue
        cp = f[1].split("+")[0].strip()
        m[int(f[0], 16)] = int(cp, 16)
    return m


def glyphs(path, height):
    """a .hex font's 8-pixel-wide glyphs [height] rows high, by code point"""
    g = {}
    for line in open(path):
        cp, bits = line.strip().split(":")
        if len(bits) == 2 * height:
            g[int(cp, 16)] = bits.upper()
    return g


def drawn(rows):
    """rows of . and # as hex"""
    return "".join("%02X" % int(r.replace(".", "0").replace("#", "1"), 2) for r in rows)


def font_kotlin(w, name, what, height, font, cps):
    """a BitmapFont of [font]'s glyphs for code points [cps] (in order)"""
    w("    /** %s */" % what)
    w("    val %s: BitmapFont = BitmapFont.parse(" % name)
    w("        %d," % height)
    per = 4 if height == 8 else 2
    for i in range(0, len(cps), per):
        w("        \"" + "".join("%X:%s;" % (cp, font[cp]) for cp in cps[i:i + per]) + "\" +")
    w("            \"\",")
    w("    )")


def main():
    """the tables and glyphs read, LegacyCharsets.kt written"""
    hexfile, hex16, mapdir = sys.argv[1], sys.argv[2], sys.argv[3]
    font = glyphs(hexfile, 8)
    for cp, rows in EXTRA.items():
        font.setdefault(cp, drawn(rows))
    vga = glyphs(hex16, 16)
    for cp, rows in EXTRA_16.items():
        vga.setdefault(cp, drawn(rows))
    pc = [ord(c) for c in CP437]
    gone = [cp for cp in pc if cp not in vga]
    if gone:
        print("no 8x16 glyph for: " + " ".join("U+%04X" % cp for cp in gone), file=sys.stderr)
    tables = []
    used = set(range(0x20, 0x7F))
    for name, f, what in SETS:
        m = mapping(os.path.join(mapdir, f + ".txt"))
        if f in VIDEO:
            v = mapping(os.path.join(mapdir, VIDEO[f] + ".txt"))
            for a in range(0x80):
                if a not in m:
                    m[a] = v[atari_screen_code(a)]
            m = {a: m[a & 0x7F] for a in range(256)}
        t = [m.get(c, 0) for c in range(256)]
        tables.append((name, f, what, t))
        used.update(cp for cp in t if cp)
    # (Latin-1's letters and signs: an Amiga's set, drawn as Topaz is, 8x8)
    used.update(range(0xA1, 0x100))
    missing = sorted(cp for cp in used if cp not in font)
    if missing:
        print("no glyph for: " + " ".join("U+%04X" % cp for cp in missing), file=sys.stderr)
    out = []
    w = out.append
    w("// SPDX-License-Identifier: AGPL-3.0-only")
    w("// Copyright (C) 2026 Neo-Desktop")
    w("// Generated by tools/gen_legacy_charsets.py: don't edit by hand.")
    w("package org.dmodem.app.terminal")
    w("")
    w("/**")
    w(" * The 8-bit home computers' character sets, each code (0x00..0xff) as its")
    w(" * Unicode code point (0: not a character), from Rebecca Bettencourt's")
    w(" * Legacy Computing mappings; and [GLYPHS], an 8x8 glyph for every one of")
    w(" * them (and Latin-1's, an Amiga's), and [VGA], code page 437 in 8x16 as a PC's VGA showed it; both")
    w(" * from Unscii (public domain; the few it hasn't drawn in the generator).")
    w(" */")
    w("internal object LegacyCharsets {")
    for name, f, what, t in tables:
        w("    /** %s (%s.txt) */" % (what, f))
        w("    val %s: IntArray = intArrayOf(" % name)
        for i in range(0, 256, 8):
            w("        " + ", ".join("0x%X" % cp for cp in t[i:i + 8]) + ",")
        w("    )")
        w("")
    have = sorted(cp for cp in used if cp in font)
    font_kotlin(w, "GLYPHS", "the sets' 8x8 glyphs: each code point (hex) and its rows (top first, high bit leftmost)", 8, font, have)
    w("")
    font_kotlin(w, "VGA", "code page 437's 8x16 glyphs, as [GLYPHS]", 16, vga, sorted(cp for cp in set(pc) if cp in vga))
    w("}")
    open(OUT, "w").write("\n".join(out) + "\n")
    print("%d sets, %d glyphs, %d missing" % (len(tables), len(have), len(missing)))


if __name__ == "__main__":
    main()
