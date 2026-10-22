package app.daiyu.android.terminal

internal data class TerminalCellStyle(
    val foreground: Int? = null, val background: Int? = null,
    val bold: Boolean = false, val dim: Boolean = false, val inverse: Boolean = false,
)
internal data class TerminalGridCell(val text: String = " ", val style: TerminalCellStyle = TerminalCellStyle(),
    val continuation: Boolean = false)
internal data class TerminalGridFrame(val lines: List<List<TerminalGridCell>>, val cursorLine: Int,
    val cursorColumn: Int, val cursorVisible: Boolean, val alternate: Boolean)

/** Incremental VT screen; unknown escape sequences never become visible terminal text. */
internal class TerminalGridBuffer(val columns: Int, val rows: Int, private val historyLimit: Int = 500) {
    init { require(columns in 1..500 && rows in 1..500 && historyLimit >= 0) }
    private fun blank() = MutableList(columns) { TerminalGridCell() }
    private var screen = MutableList(rows) { blank() }
    private val history = ArrayDeque<List<TerminalGridCell>>()
    private var savedScreen: MutableList<MutableList<TerminalGridCell>>? = null
    private var row = 0
    private var column = 0
    private var savedRow = 0
    private var savedColumn = 0
    private var top = 0
    private var bottom = rows - 1
    private var visible = true
    private var wrap = false
    private var style = TerminalCellStyle()
    private var escape = 0
    private val sequence = StringBuilder()
    private var highSurrogate: Char? = null
    var applicationCursor = false
        private set

    fun frame(): TerminalGridFrame {
        val prefix = if (savedScreen == null) history.toList() else emptyList()
        return TerminalGridFrame(prefix + screen.map { it.toList() }, prefix.size + row, column, visible, savedScreen != null)
    }

    fun accept(text: String) {
        text.forEach { char ->
            when (escape) {
                1 -> when (char) {
                    '[' -> { escape = 2; sequence.clear() }
                    ']' -> { escape = 3; sequence.clear() }
                    'P', '^', '_' -> escape = 3
                    '7' -> { savedRow = row; savedColumn = column; escape = 0 }
                    '8' -> { row = savedRow; column = savedColumn; wrap = false; escape = 0 }
                    'D' -> { newline(); escape = 0 }
                    'M' -> { reverseIndex(); escape = 0 }
                    'E' -> { column = 0; newline(); escape = 0 }
                    'c' -> { reset(); escape = 0 }
                    '(', ')' -> escape = 5
                    else -> escape = 0
                }
                2 -> if (char.code in 0x40..0x7e) { csi(sequence.toString(), char); escape = 0 }
                    else if (sequence.length < 128) sequence.append(char) else escape = 0
                3 -> when (char) { '\u0007' -> escape = 0; '\u001b' -> escape = 4 }
                4 -> { escape = if (char == '\\') 0 else 3 }
                5 -> escape = 0
                else -> when (char) {
                    '\u001b' -> { highSurrogate = null; escape = 1 }
                    '\r' -> { column = 0; wrap = false }
                    '\n', '\u000b', '\u000c' -> newline()
                    '\b' -> { column = (column - 1).coerceAtLeast(0); wrap = false }
                    '\t' -> { column = ((column / 8 + 1) * 8).coerceAtMost(columns - 1); wrap = false }
                    else -> if (char.code >= 32 && char != '\u007f') {
                        val previous = highSurrogate
                        when {
                            char.isHighSurrogate() -> highSurrogate = char
                            char.isLowSurrogate() && previous != null -> { highSurrogate = null; put("$previous$char") }
                            else -> { highSurrogate = null; put(char.toString()) }
                        }
                    }
                }
            }
        }
    }

    private fun put(text: String) {
        val cp = text.codePointAt(0)
        val kind = Character.getType(cp)
        if (kind == Character.NON_SPACING_MARK.toInt() || kind == Character.COMBINING_SPACING_MARK.toInt() || cp == 0xfe0f) {
            val target = if (wrap) column else column - 1
            if (target >= 0) {
                val index = if (screen[row][target].continuation) target - 1 else target
                if (index >= 0) screen[row][index] = screen[row][index].copy(text = screen[row][index].text + text)
            }
            return
        }
        val width = if (cp in 0x1100..0x115f || cp in 0x2e80..0xa4cf || cp in 0xac00..0xd7af ||
            cp in 0xf900..0xfaff || cp in 0xfe10..0xfe6f || cp in 0xff01..0xff60 || cp in 0x1f000..0x1ffff) 2 else 1
        if (wrap || (width == 2 && column == columns - 1)) { column = 0; newline() }
        eraseCell(row, column)
        screen[row][column] = TerminalGridCell(text, style)
        if (width == 2 && column + 1 < columns) { eraseCell(row, column + 1); screen[row][column + 1] = TerminalGridCell("", style, true) }
        if (column + width >= columns) { column = columns - 1; wrap = true } else column += width
    }

    private fun eraseCell(y: Int, x: Int) {
        if (screen[y][x].continuation && x > 0) screen[y][x - 1] = TerminalGridCell()
        if (x + 1 < columns && screen[y][x + 1].continuation) screen[y][x + 1] = TerminalGridCell()
        screen[y][x] = TerminalGridCell(style = style)
    }
    private fun newline() { wrap = false; if (row == bottom) scrollUp(1) else row = (row + 1).coerceAtMost(rows - 1) }
    private fun reverseIndex() { wrap = false; if (row == top) { screen.removeAt(bottom); screen.add(top, blank()) } else row = (row - 1).coerceAtLeast(0) }
    private fun scrollUp(count: Int) = repeat(count.coerceIn(1, bottom - top + 1)) {
        val removed = screen.removeAt(top)
        if (top == 0 && bottom == rows - 1 && savedScreen == null) {
            history.addLast(removed.toList()); while (history.size > historyLimit) history.removeFirst()
        }
        screen.add(bottom, blank())
    }
    private fun csi(raw: String, command: Char) {
        val private = raw.startsWith('?')
        val numbers = raw.removePrefix("?").split(';').map { it.toIntOrNull() ?: 0 }
        fun n(index: Int = 0, fallback: Int = 1) = (numbers.getOrElse(index) { 0 }.takeIf { it != 0 } ?: fallback).coerceIn(1, 500)
        if (private && command in "hl") {
            numbers.forEach { mode -> when (mode) {
                1 -> applicationCursor = command == 'h'
                25 -> visible = command == 'h'
                1048 -> if (command == 'h') { savedRow = row; savedColumn = column } else { row = savedRow; column = savedColumn }
                47, 1047, 1049 -> if (command == 'h' && savedScreen == null) {
                    savedScreen = screen; savedRow = row; savedColumn = column
                    screen = MutableList(rows) { blank() }; row = 0; column = 0; top = 0; bottom = rows - 1
                } else if (command == 'l' && savedScreen != null) {
                    screen = checkNotNull(savedScreen); savedScreen = null; row = savedRow; column = savedColumn; top = 0; bottom = rows - 1
                }
            } }
            wrap = false; return
        }
        when (command) {
            'A' -> row = (row - n()).coerceAtLeast(0)
            'B', 'e' -> row = (row + n()).coerceAtMost(rows - 1)
            'C', 'a' -> column = (column + n()).coerceAtMost(columns - 1)
            'D' -> column = (column - n()).coerceAtLeast(0)
            'E' -> { row = (row + n()).coerceAtMost(rows - 1); column = 0 }
            'F' -> { row = (row - n()).coerceAtLeast(0); column = 0 }
            'G', '`' -> column = (n() - 1).coerceIn(0, columns - 1)
            'd' -> row = (n() - 1).coerceIn(0, rows - 1)
            'H', 'f' -> { row = (n() - 1).coerceIn(0, rows - 1); column = (n(1) - 1).coerceIn(0, columns - 1) }
            'J' -> when (numbers[0]) {
                0 -> { for (x in column until columns) eraseCell(row, x); for (y in row + 1 until rows) screen[y] = blank() }
                1 -> { for (y in 0 until row) screen[y] = blank(); for (x in 0..column) eraseCell(row, x) }
                2 -> screen = MutableList(rows) { blank() }
                3 -> history.clear()
            }
            'K' -> when (numbers[0]) { 0 -> for (x in column until columns) eraseCell(row, x); 1 -> for (x in 0..column) eraseCell(row, x); 2 -> screen[row] = blank() }
            'X' -> repeat(n().coerceAtMost(columns - column)) { eraseCell(row, column + it) }
            '@' -> repeat(n().coerceAtMost(columns - column)) { screen[row].add(column, TerminalGridCell(style = style)); screen[row].removeAt(columns) }
            'P' -> repeat(n().coerceAtMost(columns - column)) { screen[row].removeAt(column); screen[row].add(TerminalGridCell(style = style)) }
            'L' -> if (row in top..bottom) repeat(n().coerceAtMost(bottom - row + 1)) { screen.removeAt(bottom); screen.add(row, blank()) }
            'M' -> if (row in top..bottom) repeat(n().coerceAtMost(bottom - row + 1)) { screen.removeAt(row); screen.add(bottom, blank()) }
            'S' -> scrollUp(n())
            'T' -> repeat(n().coerceIn(1, bottom - top + 1)) { screen.removeAt(bottom); screen.add(top, blank()) }
            'r' -> { val start = n() - 1; val end = n(1, rows) - 1; if (start in 0 until rows && end in start + 1 until rows) { top = start; bottom = end; row = 0; column = 0 } }
            's' -> { savedRow = row; savedColumn = column }
            'u' -> { row = savedRow; column = savedColumn }
            'm' -> sgr(numbers)
        }
        if (command != 'm') wrap = false
    }
    private fun sgr(values: List<Int>) {
        var index = 0
        while (index < values.size) {
            val value = values[index++]
            when (value) {
                0 -> style = TerminalCellStyle()
                1 -> style = style.copy(bold = true)
                2 -> style = style.copy(dim = true)
                7 -> style = style.copy(inverse = true)
                22 -> style = style.copy(bold = false, dim = false)
                27 -> style = style.copy(inverse = false)
                39 -> style = style.copy(foreground = null)
                49 -> style = style.copy(background = null)
                in 30..37 -> style = style.copy(foreground = palette(value - 30))
                in 40..47 -> style = style.copy(background = palette(value - 40))
                in 90..97 -> style = style.copy(foreground = palette(value - 90 + 8))
                in 100..107 -> style = style.copy(background = palette(value - 100 + 8))
                38, 48 -> {
                    var color: Int? = null
                    if (values.getOrNull(index) == 5 && values.getOrNull(index + 1) != null) { color = palette(values[index + 1].coerceIn(0, 255)); index += 2 }
                    else if (values.getOrNull(index) == 2 && values.getOrNull(index + 3) != null) {
                        color = (values[index + 1].coerceIn(0, 255) shl 16) or (values[index + 2].coerceIn(0, 255) shl 8) or values[index + 3].coerceIn(0, 255); index += 4
                    }
                    if (color != null) style = if (value == 38) style.copy(foreground = color) else style.copy(background = color)
                }
            }
        }
    }
    private fun reset() { screen = MutableList(rows) { blank() }; history.clear(); savedScreen = null; row = 0; column = 0; top = 0; bottom = rows - 1; visible = true; wrap = false; style = TerminalCellStyle(); applicationCursor = false }
    private fun palette(index: Int): Int {
        val basic = intArrayOf(0x000000, 0xaa0000, 0x00aa00, 0xaa5500, 0x0000aa, 0xaa00aa, 0x00aaaa, 0xaaaaaa,
            0x555555, 0xff5555, 0x55ff55, 0xffff55, 0x5555ff, 0xff55ff, 0x55ffff, 0xffffff)
        if (index < 16) return basic[index]
        if (index >= 232) return (8 + (index - 232) * 10) * 0x010101
        val value = index - 16
        fun channel(n: Int) = if (n == 0) 0 else 55 + n * 40
        return (channel(value / 36) shl 16) or (channel(value / 6 % 6) shl 8) or channel(value % 6)
    }
}
