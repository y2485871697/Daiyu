package app.daiyu.android.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class McpJsonUnicodeTest {
    @Test fun unicodeEscapeRequiresExactlyFourAsciiHexDigitsBeforeConversion() {
        for (escape in listOf("+123", "-001", "12g4", "１２３４", "123", "")) {
            assertThrows(IllegalArgumentException::class.java) {
                McpJson.objectValue("{\"x\":\"\\u$escape\"}")
            }
        }
        // Keys use the same strict string parser, not only values.
        for (escape in listOf("+123", "-001")) {
            assertThrows(IllegalArgumentException::class.java) {
                McpJson.objectValue("{\"\\u$escape\":0}")
            }
        }
        assertEquals("\u0000\u00af\u00AF\u4e2d", McpJson.objectValue("""{"x":"\u0000\u00af\u00AF\u4E2d"}""").getString("x"))
        assertEquals("\uD83D\uDE00", McpJson.objectValue("""{"x":"\uD83D\uDE00"}""").getString("x"))
    }
}
