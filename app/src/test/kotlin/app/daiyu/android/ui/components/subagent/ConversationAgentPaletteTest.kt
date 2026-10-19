package app.daiyu.android.ui.components.subagent

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** Final preview CSS tokens; no preview tasks or balances are used as fixtures. */
class ConversationAgentPaletteTest {
    @Test fun collaborationUsesItsOwnMutedNeutralPaletteInLightMode() {
        val colors = ConversationAgentColors(false)
        assertEquals(Color(0xff36333f), colors.text)
        assertEquals(Color(0xff756980), colors.accent)
        assertEquals(Color(0xff938a9b), colors.muted)
        assertEquals(Color(0x2681738e), colors.line)
        assertEquals(Color(0x1281738e), colors.soft)
        assertEquals(listOf(Color(0xf0ffffff), Color(0xf0f8f4ff)), colors.gradient)
    }

    @Test fun darkCollaborationAndStatusKeepDistinctLineAndMutedTokens() {
        val colors = ConversationAgentColors(true)
        assertEquals(Color(0xffc6b7d5), colors.accent)
        assertEquals(Color(0xff9d92aa), colors.muted)
        assertEquals(Color(0x28c3b0d9), colors.line)
        assertEquals(Color(0xff2b283a), colors.taskLine)
        assertEquals(Color(0xff7a7786), colors.taskMuted)
        assertEquals(Color(0x26dccdff), colors.edge)
        assertEquals(listOf(Color(0xf22e283b), Color(0xf21b1825)), colors.gradient)
    }
}
