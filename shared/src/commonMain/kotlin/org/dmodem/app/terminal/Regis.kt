// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.terminal

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ReGIS, DEC's graphics language (a VT340's): what comes in DCS p … ST,
 * read as it comes, each command run once the next has begun (or the
 * string ended). Its commands: P (position), V (vectors), C (circles,
 * arcs and curves), F (fill), T (text), W (writing controls), S (screen
 * controls, the colour map among them), R (reports), L (loadable
 * alphabets) and @ (macrographs). Drawn on an 800x480 screen (the
 * address mapping S(A) changes what the coordinates mean) over the text,
 * in the 16 colour [palette] registers it shares with the sixels.
 */
class Regis(private val t: TerminalEmulator, private val palette: IntArray) {
    private val layer = PictureLayer(t, WIDTH, HEIGHT)
    private val raster get() = layer.raster
    /** what's come and not yet run */
    private val pending = StringBuilder()

    // the position, in the user's coordinates
    private var x = 0
    private var y = 0

    /** a position put on the stack: P(B) or V(B) (bounded: V(E) closes the figure), or (S) */
    private class Mark(val x: Int, val y: Int, val bounded: Boolean)
    private val stack = ArrayList<Mark>()

    /** the writing controls: the mode, the colour register, the pattern, the pixel vector multiplier, shading */
    private data class Writing(
        var mode: Raster.Mode = Raster.Mode.OVERLAY,
        var color: Int = 7,
        var pattern: Int = 0xff,
        var negate: Boolean = false,
        var multiplier: Int = 1,
        var shading: Boolean = false,
        var shadeY: Int = 0,
    )
    private var writing = Writing()

    /** the text controls: a character's cell (in pixels of the 800x480 screen), the way the string goes, the alphabet */
    private data class TextStyle(var cellW: Int = 10, var cellH: Int = 20, var dx: Int = 1, var dy: Int = 0, var alphabet: Int = 0)
    private var text = TextStyle()
    private var savedText: TextStyle? = null

    /** the background's colour register (S(I)) */
    private var background = 0

    // the address mapping (S(A)): the user's coordinates at the screen's top left and bottom right
    private var ax1 = 0
    private var ay1 = 0
    private var ax2 = WIDTH - 1
    private var ay2 = HEIGHT - 1

    private val macros = HashMap<Char, String>()
    /** the loaded alphabets (1-3): each character's rows */
    private val alphabets = HashMap<Int, MutableMap<Int, IntArray>>()
    private val alphabetNames = HashMap<Int, String>()
    private val fonts = HashMap<Int, BitmapFont>()
    /** the alphabet L loads into */
    private var loading = 1

    /** a curve's points (C(B) closed, C(S) open), in the screen's coordinates, until C(E) */
    private var curve: MutableList<Pair<Int, Int>>? = null
    private var curveClosed = false
    /** F's polygon: the points its commands went through, in the screen's coordinates */
    private var filling: MutableList<Pair<Int, Int>>? = null

    /** a DCS p began: [p1] 1 or 3 starts afresh (the position, the writing and text controls as at the start) */
    fun begin(p1: Int) {
        pending.setLength(0)
        if (p1 == 1 || p1 == 3) restart()
    }

    /** one code point of the string: kept until its command is whole */
    fun feed(cp: Int) {
        if (pending.length < MAX_PENDING) pending.appendCp(cp)
    }

    /** the commands that are whole run, then the picture put on the screen */
    fun flush() {
        runPending(final = false)
        layer.flush()
    }

    /** the string ended: what's left of it run */
    fun end() {
        runPending(final = true)
    }

    /** the string cancelled: what hadn't run dropped */
    fun abort() {
        pending.setLength(0)
    }

    /** RIS: the picture gone, the macrographs and alphabets forgotten, the controls as at the start */
    fun reset() {
        pending.setLength(0)
        macros.clear()
        alphabets.clear()
        alphabetNames.clear()
        fonts.clear()
        background = 0
        restart()
        layer.reset()
    }

    /** the position, the stack, the writing and text controls and the address mapping as at the start */
    private fun restart() {
        x = 0
        y = 0
        stack.clear()
        writing = Writing()
        text = TextStyle()
        savedText = null
        ax1 = 0
        ay1 = 0
        ax2 = WIDTH - 1
        ay2 = HEIGHT - 1
        curve = null
        filling = null
        loading = 1
    }

    /** [pending]'s whole commands run (all of them, if [final]) and taken off it */
    private fun runPending(final: Boolean) {
        if (pending.isEmpty()) return
        val s = pending.toString()
        val used = run(s, final, 0)
        pending.setLength(0)
        if (used < s.length) pending.append(s, used, s.length)
    }

    /**
     * the commands in [s] run, each once the next has begun (or all, if
     * [final]); how much of [s] was used (the rest an unfinished command)
     */
    private fun run(s: String, final: Boolean, depth: Int): Int {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '@') {
                val used = macrograph(s, i, final, depth)
                if (used < 0) return i
                i = used
                continue
            }
            if (c.uppercaseChar() !in COMMANDS) {
                i++
                continue
            }
            val e = commandEnd(s, i)
            if (e < 0 && !final) return i
            val end = if (e < 0) s.length else e
            command(upperOutsideQuotes(s.substring(i, end)), depth)
            i = end
        }
        return i
    }

    /**
     * the macrograph at [i] (@:X … @; defined, @. all cleared, @X run);
     * where it ends, or -1 if it isn't whole yet
     */
    private fun macrograph(s: String, i: Int, final: Boolean, depth: Int): Int {
        if (i + 1 >= s.length) return if (final) s.length else -1
        val c = s[i + 1]
        return when (c) {
            ':' -> {
                if (i + 2 >= s.length) return if (final) s.length else -1
                val close = s.indexOf("@;", i + 3)
                if (close < 0) return if (final) s.length else -1
                macros[s[i + 2].uppercaseChar()] = s.substring(i + 3, close)
                close + 2
            }
            '.' -> {
                macros.clear()
                i + 2
            }
            else -> {
                if (depth < MAX_DEPTH) macros[c.uppercaseChar()]?.let { run(it, true, depth + 1) }
                i + 2
            }
        }
    }

    /** where the command at [start] ends: the next command (outside quotes, brackets and parentheses), or -1 if none has begun */
    private fun commandEnd(s: String, start: Int): Int {
        val hex = s[start].uppercaseChar() == 'L'
        var quote = 0.toChar()
        var parens = 0
        var brackets = 0
        for (j in start + 1 until s.length) {
            val c = s[j]
            when {
                quote != 0.toChar() -> if (c == quote) quote = 0.toChar()
                c == '\'' || c == '"' -> quote = c
                c == '(' -> parens++
                c == ')' -> if (parens > 0) parens--
                c == '[' -> brackets++
                c == ']' -> if (brackets > 0) brackets--
                parens > 0 || brackets > 0 -> {}
                c == ';' || c == '@' -> return j
                // (L's rows are hex: their letters aren't commands)
                hex && c.uppercaseChar() in 'A'..'F' -> {}
                c.uppercaseChar() in COMMANDS -> return j
            }
        }
        return -1
    }

    /** [s] in capitals but for its quoted strings */
    private fun upperOutsideQuotes(s: String): String {
        val b = StringBuilder(s.length)
        var quote = 0.toChar()
        for (c in s) {
            when {
                quote != 0.toChar() -> { if (c == quote) quote = 0.toChar(); b.append(c) }
                c == '\'' || c == '"' -> { quote = c; b.append(c) }
                else -> b.append(c.uppercaseChar())
            }
        }
        return b.toString()
    }

    /** one command (its letter first, in capitals), its writing controls of the moment (a W option of its own) undone after */
    private fun command(s: String, depth: Int) {
        val c = Cursor(s, 1)
        val saved = writing.copy()
        var temporary = false
        /** a W option of the command's own: the writing controls for it alone */
        fun temp(o: Opt) {
            o.sub?.let { applyWriting(it, writing) }
            temporary = true
        }
        when (s[0]) {
            'P' -> position(c, ::temp)
            'V' -> vector(c, ::temp)
            'C' -> circle(c, ::temp)
            'F' -> fill(c, depth)
            'T' -> text(c, ::temp)
            'W' -> c.each(onGroup = { opts -> applyWriting(opts, writing) })
            'S' -> c.each(onGroup = ::screen)
            'R' -> c.each(onGroup = ::report)
            'L' -> load(c)
        }
        if (temporary) writing = saved
    }

    /** P: to each position, the stack's (B, S, E), a pixel vector's step */
    private fun position(c: Cursor, temp: (Opt) -> Unit) {
        c.each(
            onPosition = { p -> resolve(p).let { (nx, ny) -> x = nx; y = ny } },
            onGroup = { opts ->
                for (o in opts) when (o.letter) {
                    'B', 'S' -> stack.add(Mark(x, y, o.letter == 'B'))
                    'E' -> stack.removeLastOrNull()?.let { x = it.x; y = it.y }
                    'W' -> temp(o)
                }
            },
            onDigit = { d -> step(d).let { (nx, ny) -> x = nx; y = ny } },
        )
    }

    /** V: a line to each position ([] a dot), the stack's (B begun, E back, closing a bounded figure), a pixel vector's line */
    private fun vector(c: Cursor, temp: (Opt) -> Unit) {
        c.each(
            onPosition = { p ->
                if (p.empty) dot() else resolve(p).let { (nx, ny) -> lineTo(nx, ny) }
            },
            onGroup = { opts ->
                for (o in opts) when (o.letter) {
                    'B', 'S' -> stack.add(Mark(x, y, o.letter == 'B'))
                    'E' -> stack.removeLastOrNull()?.let { m -> if (m.bounded) lineTo(m.x, m.y) else { x = m.x; y = m.y } }
                    'W' -> temp(o)
                }
            },
            onDigit = { d -> step(d).let { (nx, ny) -> lineTo(nx, ny) } },
        )
    }

    /**
     * C: a circle round the position through each one given (C: round the
     * one given, through the position), an arc of A degrees, or a curve
     * through the positions from B or S to E
     */
    private fun circle(c: Cursor, temp: (Opt) -> Unit) {
        var center = false
        var arc: Int? = null
        c.each(
            onPosition = { p ->
                val (px, py) = resolve(p)
                val cv = curve
                if (cv != null) {
                    cv.add(dx(px) to dy(py))
                    x = px
                    y = py
                } else {
                    round(px, py, center, arc)
                }
            },
            onGroup = { opts ->
                for (o in opts) when (o.letter) {
                    'C' -> center = true
                    'A' -> arc = o.num ?: 360
                    'B', 'S' -> {
                        curve = mutableListOf(dx(x) to dy(y))
                        curveClosed = o.letter == 'B'
                    }
                    'E' -> curve?.let { pts -> curve = null; spline(pts, curveClosed) }
                    'W' -> temp(o)
                }
            },
            onDigit = { d -> step(d).let { (nx, ny) -> x = nx; y = ny; curve?.add(dx(nx) to dy(ny)) } },
        )
    }

    /** a circle or arc: round the position through ([px], [py]), or ([center]) round that through the position */
    private fun round(px: Int, py: Int, center: Boolean, arc: Int?) {
        val (cx, cy) = if (center) dx(px) to dy(py) else dx(x) to dy(y)
        val (ex, ey) = if (center) dx(x) to dy(y) else dx(px) to dy(py)
        val r = sqrt(((ex - cx) * (ex - cx) + (ey - cy) * (ey - cy)).toDouble())
        val start = if (arc == null) 0.0 else atan2(-(ey - cy).toDouble(), (ex - cx).toDouble()) * 180 / PI
        val sweep = (arc ?: 360).toDouble()
        val fill = filling
        if (fill != null) {
            val n = 48
            for (i in 0..n) {
                val a = (start + sweep * i / n) * PI / 180
                fill.add((cx + r * cos(a)).roundToInt() to (cy - r * sin(a)).roundToInt())
            }
        } else {
            setUp()
            raster.arc(cx, cy, r.roundToInt(), start, sweep)
        }
        // (an arc round a given centre leaves the position at its end)
        if (center && arc != null) {
            val a = (start + sweep) * PI / 180
            val (ux, uy) = user((cx + r * cos(a)).roundToInt(), (cy - r * sin(a)).roundToInt())
            x = ux
            y = uy
        }
    }

    /** a Catmull-Rom curve through [pts] (screen coordinates), round to the first if [closed] */
    private fun spline(pts: List<Pair<Int, Int>>, closed: Boolean) {
        if (pts.size < 2) return
        val n = pts.size
        /** the [i]th point (wrapping round a closed curve, held at the ends of an open one) */
        fun at(i: Int): Pair<Int, Int> = if (closed) pts[((i % n) + n) % n] else pts[i.coerceIn(0, n - 1)]
        val spans = if (closed) n else n - 1
        var last = pts[0]
        for (s in 0 until spans) {
            val p0 = at(s - 1)
            val p1 = at(s)
            val p2 = at(s + 1)
            val p3 = at(s + 2)
            for (k in 1..STEPS) {
                val u = k.toDouble() / STEPS
                /** one coordinate along the span */
                fun cr(a: Int, b: Int, c: Int, d: Int): Int =
                    (0.5 * (2 * b + (-a + c) * u + (2 * a - 5 * b + 4 * c - d) * u * u + (-a + 3 * b - 3 * c + d) * u * u * u)).roundToInt()
                val next = cr(p0.first, p1.first, p2.first, p3.first) to cr(p0.second, p1.second, p2.second, p3.second)
                screenLine(last.first, last.second, next.first, next.second)
                last = next
            }
        }
    }

    /** F(…): the commands in its parentheses run for the outline of a polygon, then the polygon filled */
    private fun fill(c: Cursor, depth: Int) {
        val inner = c.parenthesised() ?: return
        val outer = filling
        val pts = mutableListOf(dx(x) to dy(y))
        filling = pts
        run(inner, true, depth + 1)
        filling = outer
        if (outer != null) {
            outer.addAll(pts)
        } else {
            setUp()
            raster.fill(pts)
        }
    }

    /** T: strings drawn from the position, the text controls (S, M, H, U, D, A, B, E) set, [x,y] moving it */
    private fun text(c: Cursor, temp: (Opt) -> Unit) {
        c.each(
            onPosition = { p -> resolve(p).let { (nx, ny) -> x = nx; y = ny } },
            onGroup = { opts ->
                for (o in opts) when (o.letter) {
                    'S' -> o.positions.firstOrNull()?.let { p ->
                        text.cellW = (p.x?.value ?: text.cellW).coerceIn(1, WIDTH)
                        text.cellH = (p.y?.value ?: text.cellH).coerceIn(1, HEIGHT)
                    } ?: o.num?.let { n ->
                        val k = n.coerceIn(0, 16)
                        text.cellW = if (k == 0) 10 else 10 * k
                        text.cellH = if (k == 0) 10 else 20 * k
                    }
                    'U', 'M' -> o.positions.firstOrNull()?.let { p ->
                        val unit = if (o.letter == 'M') 10 else 1
                        p.x?.let { text.cellW = (it.value * unit).coerceIn(1, WIDTH) }
                        p.y?.let { text.cellH = (it.value * unit).coerceIn(1, HEIGHT) }
                    }
                    'H' -> o.num?.let { text.cellH = (10 * it).coerceIn(1, HEIGHT) }
                    'D' -> o.num?.let { a ->
                        val r = a * PI / 180
                        text.dx = cos(r).roundToInt()
                        text.dy = -sin(r).roundToInt()
                    }
                    'A' -> o.num?.let { text.alphabet = it.coerceIn(0, 3) }
                    'B' -> savedText = text.copy()
                    'E' -> savedText?.let { text = it.copy() }
                    'W' -> temp(o)
                }
            },
            onString = ::string,
        )
    }

    /** [s] drawn at the position, a cell a character the way the string goes (CR back to its start, LF down a cell) */
    private fun string(s: String) {
        setUp()
        var px = dx(x)
        var py = dy(y)
        val startX = px
        val font = text.alphabet.takeIf { it > 0 }?.let(::font)
        for (ch in s) {
            when (ch) {
                '\r' -> px = startX
                '\n' -> py += text.cellH
                else -> {
                    val cp = ch.code
                    val f = if (font != null && font.has(cp)) font else LegacyCharsets.VGA
                    if (filling == null && cp != ' '.code) raster.text(px, py, ch.toString(), f, text.cellW, text.cellH)
                    px += text.dx * text.cellW
                    py += text.dy * text.cellH
                }
            }
        }
        user(px, py).let { (ux, uy) -> x = ux; y = uy }
    }

    /** alphabet [n]'s loaded characters as a font (8 pixels wide, or as wide as the widest row) */
    private fun font(n: Int): BitmapFont? = fonts[n] ?: alphabets[n]?.takeIf { it.isNotEmpty() }?.let { glyphs ->
        val codes = glyphs.keys.sorted().toIntArray()
        val height = glyphs.values.maxOf { it.size }.coerceAtLeast(1)
        val widest = glyphs.values.maxOf { r -> r.maxOrNull() ?: 0 }
        var width = 8
        while (width < 24 && widest ushr width != 0) width += 8
        val rows = IntArray(codes.size * height)
        for ((i, c) in codes.withIndex()) glyphs.getValue(c).copyInto(rows, i * height)
        BitmapFont(height, codes, rows, width).also { fonts[n] = it }
    }

    /** L: the alphabet to load (A, its name a string), then each character as a string and its rows in hex */
    private fun load(c: Cursor) {
        c.each(
            onGroup = { opts ->
                for (o in opts) if (o.letter == 'A') {
                    o.num?.let { loading = it.coerceIn(1, 3) }
                    o.str?.let { alphabetNames[loading] = it }
                }
            },
            onString = { s ->
                if (s.isNotEmpty()) {
                    val rows = c.hexRows()
                    alphabets.getOrPut(loading) { HashMap() }[s[0].code] = rows.take(MAX_ROWS).toIntArray()
                    fonts.remove(loading)
                }
            },
        )
    }

    /** S's options: E erased, I the background, A the address mapping, M the colour map; the others (cursor, time, hard copy, scrolling) taken */
    private fun screen(opts: List<Opt>) {
        for (o in opts) when (o.letter) {
            'E' -> {
                setUp()
                raster.clear(backgroundColor())
            }
            'I' -> background = colorIndex(o) ?: background
            'A' -> {
                o.positions.getOrNull(0)?.let { p -> p.x?.let { ax1 = it.value }; p.y?.let { ay1 = it.value } }
                o.positions.getOrNull(1)?.let { p -> p.x?.let { ax2 = it.value }; p.y?.let { ay2 = it.value } }
            }
            'M' -> {
                val n = o.num ?: continue
                o.sub?.let(::colorOf)?.let { if (n in 0 until 16) palette[n] = it }
            }
        }
    }

    /** R's options: P the position ([x,y]), E the last error (none), L the alphabet loading */
    private fun report(opts: List<Opt>) {
        for (o in opts) when (o.letter) {
            'P' -> t.reply("[$x,$y]\r")
            'E' -> t.reply("\"0,0\"\r")
            'L' -> t.reply("A$loading\"${alphabetNames[loading] ?: ""}\"\r")
        }
    }

    /** W's options into [w]: the mode (V, R, C, E), the colour (I), the pattern (P), negative (N), shading (S), the multiplier (M) */
    private fun applyWriting(opts: List<Opt>, w: Writing) {
        for (o in opts) when (o.letter) {
            'V' -> w.mode = Raster.Mode.OVERLAY
            'R' -> w.mode = Raster.Mode.REPLACE
            'C' -> w.mode = Raster.Mode.COMPLEMENT
            'E' -> w.mode = Raster.Mode.ERASE
            'I' -> colorIndex(o)?.let { w.color = it }
            'P' -> {
                val d = o.digits
                if (d != null && d.length > 1 && d.all { it == '0' || it == '1' }) {
                    var bits = 0
                    for (k in 0 until 8) bits = (bits shl 1) or (d[k % d.length] - '0')
                    w.pattern = bits
                } else {
                    o.num?.let { w.pattern = PATTERNS[it.coerceIn(0, 9)] }
                }
            }
            'N' -> w.negate = (o.num ?: 1) != 0
            'S' -> {
                w.shading = o.str != null || (o.num ?: 1) != 0
                w.shadeY = o.positions.firstOrNull()?.let { resolve(it).second } ?: y
            }
            'M' -> o.num?.let { w.multiplier = it.coerceIn(1, 256) }
        }
    }

    /** an I option's colour register: a number, or the register nearest the colour in its parentheses */
    private fun colorIndex(o: Opt): Int? {
        o.num?.let { return it.coerceIn(0, 15) }
        val c = o.sub?.let(::colorOf) ?: return null
        /** the squared distance between two colours */
        fun dist(a: Int, b: Int): Int {
            val dr = ((a shr 16) and 0xff) - ((b shr 16) and 0xff)
            val dg = ((a shr 8) and 0xff) - ((b shr 8) and 0xff)
            val db = (a and 0xff) - (b and 0xff)
            return dr * dr + dg * dg + db * db
        }
        return (0 until 16).minByOrNull { dist(palette[it], c) }
    }

    /** a colour as ReGIS writes one: a letter (D B R G M C Y W), or H, L and S (L alone a grey) */
    private fun colorOf(opts: List<Opt>): Int? {
        var h: Int? = null
        var l: Int? = null
        var s: Int? = null
        for (o in opts) {
            when (o.letter) {
                'H' -> h = o.num ?: 0
                'L' -> l = o.num ?: 0
                'S' -> s = o.num ?: 0
                else -> LETTERS[o.letter]?.let { if (o.num == null) return it }
            }
        }
        if (h == null && l == null && s == null) return null
        return Sixel.hls(h ?: 0, l ?: 50, s ?: if (h == null) 0 else 100)
    }

    /** the raster set to draw as the writing controls say */
    private fun setUp() {
        val r = raster
        r.mode = writing.mode
        r.color = palette[writing.color and 15] or (0xff shl 24)
        r.background = backgroundColor()
        r.pattern = if (writing.negate) writing.pattern.inv() and 0xff else writing.pattern
    }

    /** the background's colour (transparent where it's black, so the text shows through) */
    private fun backgroundColor(): Int {
        val c = palette[background and 15]
        return if (c and 0xffffff == 0) 0 else c or (0xff shl 24)
    }

    /** a dot at the position */
    private fun dot() {
        filling?.let { it.add(dx(x) to dy(y)); return }
        setUp()
        raster.point(dx(x), dy(y))
    }

    /** a line from the position to ([nx], [ny]), the position then there */
    private fun lineTo(nx: Int, ny: Int) {
        screenLine(dx(x), dy(y), dx(nx), dy(ny))
        x = nx
        y = ny
    }

    /** a line between two points of the screen (or, filling, the second added to the polygon), shaded to the shading line if that's on */
    private fun screenLine(x0: Int, y0: Int, x1: Int, y1: Int) {
        filling?.let { it.add(x1 to y1); return }
        setUp()
        if (writing.shading) {
            val sy = dy(writing.shadeY)
            raster.fill(listOf(x0 to y0, x1 to y1, x1 to sy, x0 to sy))
        }
        raster.line(x0, y0, x1, y1)
    }

    /** where pixel vector [d] (0 right, counterclockwise to 7) goes from the position */
    private fun step(d: Int): Pair<Int, Int> {
        val m = writing.multiplier
        val (sx, sy) = DIRECTIONS[d and 7]
        // (a step is a screen pixel's, in the user's units)
        val ux = sx * m * (ax2 - ax1) / (WIDTH - 1)
        val uy = sy * m * (ay2 - ay1) / (HEIGHT - 1)
        return (x + if (ux == 0) sx * m else ux) to (y + if (uy == 0) sy * m else uy)
    }

    /** a position from the position: each part given absolute, or relative (signed), or left */
    private fun resolve(p: Pos): Pair<Int, Int> {
        /** one part of it */
        fun part(v: Part?, base: Int) = when {
            v == null -> base
            v.relative -> base + v.value
            else -> v.value
        }
        return part(p.x, x) to part(p.y, y)
    }

    /** a user's x as the screen's */
    private fun dx(ux: Int): Int = map(ux, ax1, ax2, WIDTH)

    /** a user's y as the screen's */
    private fun dy(uy: Int): Int = map(uy, ay1, ay2, HEIGHT)

    /** [v] from [a]..[b] onto 0..[size] - 1 */
    private fun map(v: Int, a: Int, b: Int, size: Int): Int {
        val span = if (b == a) 1 else b - a
        return ((v - a).toLong() * (size - 1) / span).toInt()
    }

    /** a point of the screen as the user's coordinates */
    private fun user(sx: Int, sy: Int): Pair<Int, Int> =
        (ax1 + (sx.toLong() * (ax2 - ax1) / (WIDTH - 1)).toInt()) to (ay1 + (sy.toLong() * (ay2 - ay1) / (HEIGHT - 1)).toInt())

    /** a coordinate in a position: its value, and whether it's from the position (signed) */
    private class Part(val value: Int, val relative: Boolean)

    /** a position as written, [x,y]: either part left out; [] (empty) the position itself */
    private class Pos(val x: Part?, val y: Part?, val empty: Boolean)

    /** an option: its letter, then what follows it (a number, positions, options in parentheses, a string) */
    private class Opt(val letter: Char) {
        var num: Int? = null
        /** the number's digits as written (a pattern's bits) */
        var digits: String? = null
        val positions = ArrayList<Pos>()
        var sub: List<Opt>? = null
        var str: String? = null
    }

    /** a command's text read from [i] on */
    private class Cursor(val s: String, var i: Int) {
        /** the character at the cursor (NUL at the end) */
        fun peek(): Char = if (i < s.length) s[i] else 0.toChar()

        /** blanks and commas skipped */
        fun skip() {
            while (i < s.length && (s[i] <= ' ' || s[i] == ',')) i++
        }

        /**
         * each of the command's arguments in turn to its handler: positions,
         * option groups, strings, pixel vector digits (anything else skipped)
         */
        fun each(
            onPosition: (Pos) -> Unit = {},
            onGroup: (List<Opt>) -> Unit = {},
            onString: (String) -> Unit = {},
            onDigit: (Int) -> Unit = {},
        ) {
            while (true) {
                skip()
                when (val c = peek()) {
                    0.toChar() -> return
                    '[' -> onPosition(position())
                    '(' -> onGroup(group())
                    '\'', '"' -> onString(string())
                    in '0'..'7' -> { i++; onDigit(c - '0') }
                    else -> i++
                }
            }
        }

        /** a number: a sign, digits, a fraction (dropped); null if there's none */
        fun number(): Int? {
            val start = i
            var neg = false
            if (peek() == '+' || peek() == '-') { neg = peek() == '-'; i++ }
            val d = digits()
            if (d.isEmpty()) { i = start; return null }
            if (peek() == '.') { i++; digits() }
            val v = d.takeLast(9).toInt()
            return if (neg) -v else v
        }

        /** the digits at the cursor */
        fun digits(): String {
            val start = i
            while (peek() in '0'..'9') i++
            return s.substring(start, i)
        }

        /** [x,y] */
        fun position(): Pos {
            i++
            /** one part, up to a comma or the bracket */
            fun part(): Part? {
                while (peek() == ' ') i++
                val c = peek()
                val relative = c == '+' || c == '-'
                val v = number()
                while (i < s.length && s[i] != ',' && s[i] != ']') i++
                return v?.let { Part(it, relative) }
            }
            val px = part()
            var py: Part? = null
            val hadComma = peek() == ','
            if (hadComma) { i++; py = part() }
            if (peek() == ']') i++
            return Pos(px, py, px == null && py == null && !hadComma)
        }

        /** a quoted string (a doubled quote one of it) */
        fun string(): String {
            val q = s[i++]
            val b = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                if (c == q) {
                    if (peek() == q) { b.append(q); i++ } else break
                } else {
                    b.append(c)
                }
            }
            return b.toString()
        }

        /** (…): options, each a letter and what follows it (a bare number another of the last letter's) */
        fun group(): List<Opt> {
            i++
            val opts = ArrayList<Opt>()
            var last: Opt? = null
            while (i < s.length) {
                skip()
                val c = peek()
                when {
                    c == ')' -> { i++; break }
                    c.isLetter() -> {
                        i++
                        last = Opt(c).also { opts.add(it); values(it) }
                    }
                    (c.isDigit() || c == '+' || c == '-') && last != null -> {
                        last = Opt(last.letter).also { opts.add(it); values(it) }
                    }
                    c == '[' && last != null -> last.positions.add(position())
                    c == '(' && last != null -> last.sub = group()
                    c == '\'' || c == '"' -> { val str = string(); last?.str = str }
                    else -> i++
                }
            }
            return opts
        }

        /** what follows an option's letter: a number, positions, options, a string */
        private fun values(o: Opt) {
            while (peek() == ' ') i++
            val c = peek()
            if (c.isDigit() || c == '+' || c == '-') {
                val start = i
                o.num = number()
                o.digits = s.substring(start, i).trimStart('+', '-')
            }
            while (true) {
                while (peek() == ' ') i++
                when (peek()) {
                    '[' -> o.positions.add(position())
                    '(' -> o.sub = group()
                    '\'', '"' -> o.str = string()
                    else -> return
                }
            }
        }

        /** the text inside the parentheses at the cursor (balanced, quotes kept), or null if none */
        fun parenthesised(): String? {
            skip()
            if (peek() != '(') return null
            val start = ++i
            var depth = 1
            var quote = 0.toChar()
            while (i < s.length) {
                val c = s[i++]
                when {
                    quote != 0.toChar() -> if (c == quote) quote = 0.toChar()
                    c == '\'' || c == '"' -> quote = c
                    c == '(' -> depth++
                    c == ')' -> if (--depth == 0) return s.substring(start, i - 1)
                }
            }
            return s.substring(start)
        }

        /** a loaded character's rows: hex numbers, commas between, up to the next string */
        fun hexRows(): List<Int> {
            val rows = ArrayList<Int>()
            while (true) {
                skip()
                val start = i
                while (i < s.length && s[i].uppercaseChar() in HEX) i++
                if (i == start) return rows
                rows.add(s.substring(start, i).takeLast(6).toInt(16))
            }
        }
    }

    companion object {
        /** the VT340's screen, in pixels */
        const val WIDTH = 800
        const val HEIGHT = 480
        /** the command letters */
        private const val COMMANDS = "PVCFTWSRL"
        private const val HEX = "0123456789ABCDEF"
        /** how much of an unfinished command is kept */
        private const val MAX_PENDING = 1 shl 20
        /** how deep macrographs and fills may nest */
        private const val MAX_DEPTH = 8
        /** a loaded character's rows at most */
        private const val MAX_ROWS = 32
        /** a curve's lines between each two of its points */
        private const val STEPS = 8
        /** the standard patterns, 0-9 */
        private val PATTERNS = intArrayOf(0x00, 0xff, 0xf0, 0xe4, 0xaa, 0xea, 0x88, 0x84, 0xc8, 0xee)
        /** the pixel vectors' steps: 0 right, counterclockwise (y down) */
        private val DIRECTIONS = listOf(1 to 0, 1 to -1, 0 to -1, -1 to -1, -1 to 0, -1 to 1, 0 to 1, 1 to 1)
        /** ReGIS's colour letters */
        private val LETTERS = mapOf(
            'D' to 0xff000000.toInt(), 'B' to 0xff0000ff.toInt(), 'R' to 0xffff0000.toInt(), 'G' to 0xff00ff00.toInt(),
            'M' to 0xffff00ff.toInt(), 'C' to 0xff00ffff.toInt(), 'Y' to 0xffffff00.toInt(), 'W' to 0xffffffff.toInt(),
        )
    }
}
