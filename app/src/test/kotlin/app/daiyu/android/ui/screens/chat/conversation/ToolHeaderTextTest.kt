package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.em
import org.junit.Assert.*
import org.junit.Test

class ToolHeaderTextTest {
    @Test fun bothSeparatorCharactersUseTheSameInlineBulletWithoutChangingText() {
        for (text in listOf("Android • user", "执行命令 · Android • root", "读取输出 · Debian • root（PRoot）")) {
            val styled = toolHeaderText(text, true)
            assertEquals(text, styled.text)
            assertTrue(styled.spanStyles.isEmpty())
            val positions = text.indices.filter { text[it] == '·' || text[it] == '•' }
            val annotations = styled.getStringAnnotations(0, styled.length)
            assertEquals(positions, annotations.map { it.start })
            annotations.forEach { range ->
                assertEquals(range.start + 1, range.end)
                assertEquals(ToolHeaderSeparatorId, range.item)
            }
        }
    }

    @Test fun visibleBulletIsUniformAndCenteredWithoutEnlargingTheFontBox() {
        assertEquals(.5.em, ToolHeaderSeparatorPlaceholder.width)
        assertEquals(ToolHeaderSeparatorPlaceholder.width, ToolHeaderSeparatorPlaceholder.height)
        assertEquals(PlaceholderVerticalAlign.Center, ToolHeaderSeparatorPlaceholder.placeholderVerticalAlign)
    }

    @Test fun disabledStylingLeavesOtherToolHeadersUnchanged() {
        val text = "执行其他工具 · example • value"
        val styled = toolHeaderText(text, false)
        assertEquals(text, styled.text)
        assertTrue(styled.spanStyles.isEmpty())
        assertTrue(styled.getStringAnnotations(0, styled.length).isEmpty())
    }

    @Test fun headersWithoutSeparatorsHaveNoSpansOrPlaceholders() {
        for (text in listOf("", "终端命令", "多环境终端", "工具链", "a.b: value")) {
            val styled = toolHeaderText(text, true)
            assertEquals(text, styled.text)
            assertTrue(styled.spanStyles.isEmpty())
            assertTrue(styled.getStringAnnotations(0, styled.length).isEmpty())
        }
    }
}
