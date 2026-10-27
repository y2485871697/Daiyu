package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import org.junit.Assert.*
import org.junit.Test

class ToolHeaderTextTest {
    @Test fun onlySeparatorCharactersBecomeBoldAndLargerWithoutChangingText() {
        for (text in listOf("Android • user", "执行命令 · Android • root", "读取输出 · Debian • root（PRoot）")) {
            val styled = toolHeaderText(text, true)
            assertEquals(text, styled.text)
            val positions = text.indices.filter { text[it] == '·' || text[it] == '•' }
            assertEquals(positions, styled.spanStyles.map { it.start })
            styled.spanStyles.forEach { range ->
                assertEquals(range.start + 1, range.end)
                assertEquals(FontWeight.Bold, range.item.fontWeight)
                assertEquals(1.4.em, range.item.fontSize)
                assertEquals(Color.Unspecified, range.item.color)
            }
        }
    }

    @Test fun disabledStylingLeavesOtherToolHeadersUnchanged() {
        val text = "执行其他工具 · example • value"
        val styled = toolHeaderText(text, false)
        assertEquals(text, styled.text)
        assertTrue(styled.spanStyles.isEmpty())
    }

    @Test fun headersWithoutSeparatorsHaveNoSpansOrPlaceholders() {
        for (text in listOf("", "终端命令", "多环境终端", "工具链", "a.b: value")) {
            val styled = toolHeaderText(text, true)
            assertEquals(text, styled.text)
            assertTrue(styled.spanStyles.isEmpty())
        }
    }
}
