package app.daiyu.android.terminal

import org.junit.Assert.*
import org.junit.Test

class TerminalBlockProtocolTest {
    @Test fun splitMarkerKeepsCommandOutputExitStatusAndDirectorySeparate() {
        val parser = TerminalBlockProtocol("test")
        val events = mutableListOf<TerminalBlockProtocol.Event>()
        parser.accept("shell setup echo\u001b]daiyu-test;rea", events::add)
        assertTrue(events.isEmpty())
        parser.accept("dy;/workspace\u0007hello\r\n\u001b]daiyu-test;done;command;7;/workspace/a;b\u0007", events::add)
        assertEquals(TerminalBlockProtocol.Event.Ready("/workspace"), events[0])
        assertEquals(TerminalBlockProtocol.Event.Output("hello\r\n"), events[1])
        assertEquals(TerminalBlockProtocol.Event.Finished("command", 7, "/workspace/a;b"), events[2])
    }
    @Test fun everyPossibleChunkBoundaryProducesExactlyOneCompletion() {
        val raw = "\u001b]daiyu-test;ready;/workspace\u0007payload\u001b]daiyu-test;done;id;0;/workspace\u0007"
        for (boundary in 0..raw.length) {
            val events = mutableListOf<TerminalBlockProtocol.Event>()
            val parser = TerminalBlockProtocol("test")
            parser.accept(raw.take(boundary), events::add); parser.accept(raw.drop(boundary), events::add)
            assertEquals(1, events.count { it is TerminalBlockProtocol.Event.Finished })
            assertEquals("payload", events.filterIsInstance<TerminalBlockProtocol.Event.Output>().joinToString("") { it.text })
        }
    }
    @Test fun commandsStayInCurrentShellAndAreQuotedWithoutExecutingTheirTextAsFraming() {
        val parser = TerminalBlockProtocol("test")
        val command = parser.command("id", "export X='a b'; cd /workspace; printf '%s' \"\$X\"")
        assertTrue(command.startsWith("eval '"))
        assertTrue(command.contains("'\\''a b'\\''"))
        assertTrue(command.endsWith("\r"))
        assertTrue(command.contains("\"\$?\" \"\$PWD\""))
        assertFalse(command.contains("sh -c"))
        assertTrue(parser.setup().contains("stty -echo -icanon"))
        assertTrue(parser.setup().contains("exec 9>&1"))
        assertTrue(command.endsWith(" >&9\r"))
    }
}
