// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

/**
 * The code extension a VT220 and later have (ISO 2022 as DEC used it):
 * four sets, G0 to G3, designated into place; GL (0x21..0x7e) and GR
 * (0xa1..0xfe) each showing one of them by a locking shift; and the
 * single shifts SS2 and SS3, which take one character from G2 or G3.
 * A VT100 has only G0 and G1 and SO/SI, which this does as well.
 */
class CharsetShifting {
    /** G0 to G3 */
    val g = arrayOf(GSet.ASCII, GSet.ASCII, GSet.LATIN1, GSet.LATIN1)
    /** the G set GL shows (0 to 3) */
    var gl = 0
        private set
    /** the G set GR shows (1 to 3) */
    var gr = 2
        private set
    /** the G set the next character comes from (SS2 2, SS3 3), or -1 */
    var single = -1
        private set
    /** the user-preferred supplemental set (DECAUPSS), what ESC ( < designates */
    var preferred = GSet.LATIN1

    /** the soft set the host loaded (DECDLD), what a G set holding [GSet.SOFT] shows */
    val soft = SoftFont()

    /** what DECSC keeps */
    class Saved(val g: Array<GSet>, val gl: Int, val gr: Int)

    /** the state for DECSC */
    fun save() = Saved(g.copyOf(), gl, gr)

    /** what [save] kept back (null: the state at the start) */
    fun restore(s: Saved?) {
        if (s == null) {
            reset()
            return
        }
        s.g.copyInto(g)
        gl = s.gl
        gr = s.gr
        single = -1
    }

    /** G0 and G1 ASCII, G2 and G3 the preferred supplemental set, G0 in GL and G2 in GR */
    fun reset() {
        g[0] = GSet.ASCII
        g[1] = GSet.ASCII
        g[2] = preferred
        g[3] = preferred
        gl = 0
        gr = 2
        single = -1
    }

    /** a locking shift: GL to G[n] (SI 0, SO 1, LS2, LS3) */
    fun lockGl(n: Int) {
        gl = n
    }

    /** a locking shift: GR to G[n] (LS1R, LS2R, LS3R) */
    fun lockGr(n: Int) {
        gr = n
    }

    /** a single shift: the next graphic character from G[n] (SS2, SS3) */
    fun singleShift(n: Int) {
        single = n
    }

    /** G[slot] given [set] */
    fun designate(slot: Int, set: GSet) {
        g[slot] = set
    }

    /**
     * a graphic code point as the sets show it: 0x20..0x7f through GL's
     * set, 0xa0..0xff through GR's when [eightBit] (the line's octets are
     * the code points, as Latin-1 decodes them); a single shift's set
     * taking either for one character
     */
    fun map(cp: Int, eightBit: Boolean): Int {
        val s = single
        single = -1
        return when {
            cp in 0x20..0x7f -> show(g[if (s >= 0) s else gl], cp)
            eightBit && cp in 0xa0..0xff -> show(g[if (s >= 0) s else gr], cp - 0x80)
            else -> cp
        }
    }

    /** [cp] (0x20..0x7f) as [set] has it, the soft set's from [soft] */
    private fun show(set: GSet, cp: Int): Int = if (set == GSet.SOFT) soft.map(cp) else set.map(cp)

    /** the designator G[n] was given (the soft set's its own; one with none, ASCII's) */
    fun designator(n: Int): String = (if (g[n] == GSet.SOFT) soft.designator else g[n].designator) ?: "B"

    /** whether G[n] holds a 96-character set */
    fun is96(n: Int): Boolean = if (g[n] == GSet.SOFT) soft.is96 else g[n].is96
}
