// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.terminal

/** A terminal's colours: the 16 ANSI colours, the default foreground and background, the cursor. */
class TermTheme(
    val id: String,
    val name: String,
    val fg: Int,
    val bg: Int,
    val cursor: Int,
    /** black red green yellow blue magenta cyan white, then their bright ones (0xRRGGBB) */
    ansi: IntArray,
) {
    /** the 256 colours (ARGB): these 16, xterm's 6x6x6 cube and its 24 greys */
    val palette = IntArray(256)

    init {
        require(ansi.size == 16)
        for (i in 0 until 16) palette[i] = OPAQUE or ansi[i]
        val levels = intArrayOf(0, 95, 135, 175, 215, 255)
        for (i in 0 until 216) {
            val r = levels[i / 36]
            val g = levels[(i / 6) % 6]
            val b = levels[i % 6]
            palette[16 + i] = OPAQUE or (r shl 16) or (g shl 8) or b
        }
        for (i in 0 until 24) {
            val v = 8 + i * 10
            palette[232 + i] = OPAQUE or (v shl 16) or (v shl 8) or v
        }
    }

    val fgArgb: Int get() = OPAQUE or fg
    val bgArgb: Int get() = OPAQUE or bg
    val cursorArgb: Int get() = OPAQUE or cursor

    /** a cell's colour (Style's encoding) as ARGB */
    fun argb(color: Int, isFg: Boolean): Int = when {
        color == Style.DEFAULT_FG -> fgArgb
        color == Style.DEFAULT_BG -> bgArgb
        color and Style.RGB != 0 -> OPAQUE or (color and 0xffffff)
        color in 0..255 -> palette[color]
        else -> if (isFg) fgArgb else bgArgb
    }

    companion object {
        private const val OPAQUE = 0xff000000.toInt()

        /** the ints as an array */
        private fun ints(vararg v: Int) = v

        val VGA = TermTheme(
            "vga", "VGA (DOS, BBS)", 0xaaaaaa, 0x000000, 0xaaaaaa,
            ints(
                0x000000, 0xaa0000, 0x00aa00, 0xaa5500, 0x0000aa, 0xaa00aa, 0x00aaaa, 0xaaaaaa,
                0x555555, 0xff5555, 0x55ff55, 0xffff55, 0x5555ff, 0xff55ff, 0x55ffff, 0xffffff,
            ),
        )
        val XTERM = TermTheme(
            "xterm", "xterm", 0xe5e5e5, 0x000000, 0xe5e5e5,
            ints(
                0x000000, 0xcd0000, 0x00cd00, 0xcdcd00, 0x0000ee, 0xcd00cd, 0x00cdcd, 0xe5e5e5,
                0x7f7f7f, 0xff0000, 0x00ff00, 0xffff00, 0x5c5cff, 0xff00ff, 0x00ffff, 0xffffff,
            ),
        )
        val SOLARIZED_DARK = TermTheme(
            "solarized-dark", "Solarized Dark", 0x839496, 0x002b36, 0x93a1a1,
            ints(
                0x073642, 0xdc322f, 0x859900, 0xb58900, 0x268bd2, 0xd33682, 0x2aa198, 0xeee8d5,
                0x002b36, 0xcb4b16, 0x586e75, 0x657b83, 0x839496, 0x6c71c4, 0x93a1a1, 0xfdf6e3,
            ),
        )
        val SOLARIZED_LIGHT = TermTheme(
            "solarized-light", "Solarized Light", 0x657b83, 0xfdf6e3, 0x586e75,
            ints(
                0x073642, 0xdc322f, 0x859900, 0xb58900, 0x268bd2, 0xd33682, 0x2aa198, 0xeee8d5,
                0x002b36, 0xcb4b16, 0x586e75, 0x657b83, 0x839496, 0x6c71c4, 0x93a1a1, 0xfdf6e3,
            ),
        )
        val DRACULA = TermTheme(
            "dracula", "Dracula", 0xf8f8f2, 0x282a36, 0xf8f8f2,
            ints(
                0x21222c, 0xff5555, 0x50fa7b, 0xf1fa8c, 0xbd93f9, 0xff79c6, 0x8be9fd, 0xf8f8f2,
                0x6272a4, 0xff6e6e, 0x69ff94, 0xffffa5, 0xd6acff, 0xff92df, 0xa4ffff, 0xffffff,
            ),
        )
        val MONOKAI = TermTheme(
            "monokai", "Monokai", 0xf8f8f2, 0x272822, 0xf8f8f0,
            ints(
                0x272822, 0xf92672, 0xa6e22e, 0xf4bf75, 0x66d9ef, 0xae81ff, 0xa1efe4, 0xf8f8f2,
                0x75715e, 0xf92672, 0xa6e22e, 0xf4bf75, 0x66d9ef, 0xae81ff, 0xa1efe4, 0xf9f8f5,
            ),
        )
        val GRUVBOX = TermTheme(
            "gruvbox-dark", "Gruvbox Dark", 0xebdbb2, 0x282828, 0xebdbb2,
            ints(
                0x282828, 0xcc241d, 0x98971a, 0xd79921, 0x458588, 0xb16286, 0x689d6a, 0xa89984,
                0x928374, 0xfb4934, 0xb8bb26, 0xfabd2f, 0x83a598, 0xd3869b, 0x8ec07c, 0xebdbb2,
            ),
        )
        val NORD = TermTheme(
            "nord", "Nord", 0xd8dee9, 0x2e3440, 0xd8dee9,
            ints(
                0x3b4252, 0xbf616a, 0xa3be8c, 0xebcb8b, 0x81a1c1, 0xb48ead, 0x88c0d0, 0xe5e9f0,
                0x4c566a, 0xbf616a, 0xa3be8c, 0xebcb8b, 0x81a1c1, 0xb48ead, 0x8fbcbb, 0xeceff4,
            ),
        )
        val TANGO_LIGHT = TermTheme(
            "tango-light", "Tango Light", 0x2e3436, 0xffffff, 0x2e3436,
            ints(
                0x2e3436, 0xcc0000, 0x4e9a06, 0xc4a000, 0x3465a4, 0x75507b, 0x06989a, 0xd3d7cf,
                0x555753, 0xef2929, 0x8ae234, 0xfce94f, 0x729fcf, 0xad7fa8, 0x34e2e2, 0xeeeeec,
            ),
        )
        val GREEN = TermTheme(
            "green", "Green phosphor", 0x33ff33, 0x000000, 0x33ff33,
            ints(
                0x001a00, 0x1f991f, 0x33ff33, 0x2bd92b, 0x1a801a, 0x26b326, 0x2ecc2e, 0x33ff33,
                0x0d400d, 0x33ff33, 0x66ff66, 0x66ff66, 0x33cc33, 0x4dff4d, 0x80ff80, 0xb3ffb3,
            ),
        )
        val AMBER = TermTheme(
            "amber", "Amber", 0xffb000, 0x000000, 0xffb000,
            ints(
                0x1a1200, 0x996a00, 0xffb000, 0xd99600, 0x805800, 0xb37b00, 0xcc8c00, 0xffb000,
                0x403000, 0xffb000, 0xffc433, 0xffc433, 0xcc8c00, 0xffbe1a, 0xffd166, 0xffe0a3,
            ),
        )

        val ALL = listOf(VGA, XTERM, SOLARIZED_DARK, SOLARIZED_LIGHT, DRACULA, MONOKAI, GRUVBOX, NORD, TANGO_LIGHT, GREEN, AMBER)

        /** the theme with [id], VGA if none has it */
        fun byId(id: String): TermTheme = ALL.firstOrNull { it.id == id } ?: VGA
    }
}
