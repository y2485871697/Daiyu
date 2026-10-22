package app.daiyu.android.terminal

import org.junit.Assert.*
import org.junit.Test

class TerminalGridBufferTest {
    private fun text(buffer: TerminalGridBuffer) = buffer.frame().lines.map { line ->
        line.filterNot { it.continuation }.joinToString("") { it.text }.trimEnd()
    }
    @Test fun carriageReturnErasesAndMovesTheActualScreenInsteadOfAppendingEscapes() {
        val grid = TerminalGridBuffer(12, 3)
        grid.accept("progress 10%\r\u001b[2Kdone")
        assertEquals("done", text(grid)[0])
        grid.accept("\u001b[2J\u001b[Hnext")
        assertEquals(listOf("next", "", ""), text(grid))
    }
    @Test fun splitSequencesColorsAndTruecolorAreRetainedAcrossUpdates() {
        val grid = TerminalGridBuffer(12, 2)
        grid.accept("\u001b[38;2;1;")
        grid.accept("2;3mA\u001b[48;5;196mB")
        assertEquals(0x010203, grid.frame().lines[0][0].style.foreground)
        assertEquals(0xff0000, grid.frame().lines[0][1].style.background)
    }
    @Test fun alternateScreenRestoresMainScreenAndHidesItsHistory() {
        val grid = TerminalGridBuffer(10, 2)
        grid.accept("main\u001b[?1049happ\u001b[?25l")
        assertTrue(grid.frame().alternate)
        assertEquals("app", text(grid)[0])
        assertFalse(grid.frame().cursorVisible)
        grid.accept("\u001b[?1049l")
        assertFalse(grid.frame().alternate)
        assertEquals("main", text(grid)[0])
    }
    @Test fun wideCharactersAndSurrogatesKeepCursorCells() {
        val grid = TerminalGridBuffer(10, 2)
        grid.accept("中\ud83d"); grid.accept("\ude00A")
        assertEquals("中😀A", text(grid)[0])
        assertTrue(grid.frame().lines[0][1].continuation)
        assertTrue(grid.frame().lines[0][3].continuation)
        assertEquals(5, grid.frame().cursorColumn)
    }
    @Test fun historyIsBoundedAndApplicationCursorModeTracksTheTerminal() {
        val grid = TerminalGridBuffer(10, 2, 3)
        repeat(20) { grid.accept("$it\r\n") }
        assertEquals(5, grid.frame().lines.size)
        grid.accept("\u001b[?1h"); assertTrue(grid.applicationCursor)
        grid.accept("\u001b[?1l"); assertFalse(grid.applicationCursor)
    }
    @Test fun oscTitlesDoNotAppearAsOutputEvenWhenSplit() {
        val grid = TerminalGridBuffer(10, 2)
        grid.accept("\u001b]0;private"); grid.accept(" title\u001b\\ok")
        assertEquals("ok", text(grid)[0])
    }
}
