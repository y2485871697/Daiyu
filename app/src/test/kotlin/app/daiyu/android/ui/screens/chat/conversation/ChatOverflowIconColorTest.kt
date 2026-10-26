package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatOverflowIconColorTest {
    private val accent = Color(0xFF8872B7)
    private val actions = listOf(
        ChatMenuAction.NEW_CHAT, ChatMenuAction.SEARCH, ChatMenuAction.INTERACTIVE_MODE,
        ChatMenuAction.OPEN_TERMINAL, ChatMenuAction.OPEN_BROWSER, ChatMenuAction.OPEN_WORKSPACE,
        ChatMenuAction.SUB_AGENT, ChatMenuAction.CONTEXT_STATS,
        ChatMenuAction.COMPRESS_CONTEXT, ChatMenuAction.AUTO_COMPRESS,
    )

    @Test
    fun lightThemeUsesDistinctSoftFeatureColors() {
        assertEquals(listOf(
            Color(0xFF5F85B4), Color(0xFF747CB3), Color(0xFFAD728E),
            Color(0xFF568E90), Color(0xFF4A819C), Color(0xFF9D7845),
            accent, Color(0xFF567E62), Color(0xFFA46D49), Color(0xFF47857A),
        ), actions.map { chatOverflowIconColor(it, false, accent) })
    }

    @Test
    fun darkThemeUsesBrighterFeatureColors() {
        val darkAccent = Color(0xFFC7B7E5)
        assertEquals(listOf(
            Color(0xFF9ABCEB), Color(0xFFB2BBEF), Color(0xFFDFA9C1),
            Color(0xFF80B3B1), Color(0xFF8CC7DF), Color(0xFFC5A778),
            darkAccent, Color(0xFF9BC8AA), Color(0xFFDBAE8A), Color(0xFF8ACBC1),
        ), actions.map { chatOverflowIconColor(it, true, darkAccent) })
    }

    @Test
    fun palettesDoNotCollapseBackToThreeColorsOrCarryStateAlpha() {
        for (dark in listOf(false, true)) {
            val colors = actions.map { chatOverflowIconColor(it, dark, accent) }
            assertEquals(10, colors.toSet().size)
            colors.forEach { assertEquals(1f, it.alpha, 0f) }
        }
    }

    @Test
    fun subAgentAndOtherActionsKeepTheSuppliedThemeAccent() {
        for (dark in listOf(false, true)) {
            for (customAccent in listOf(Color(0xFF734B97), Color(0xFFAAC9A0).copy(alpha = .37f))) {
                for (action in listOf(ChatMenuAction.SUB_AGENT, ChatMenuAction.SETTINGS, ChatMenuAction.VOICE_INPUT)) {
                    assertEquals(customAccent, chatOverflowIconColor(action, dark, customAccent))
                }
            }
        }
    }

    @Test
    fun enabledIconsRemainVisibleAgainstNominalMenuSurfaceColors() {
        val lightSurfaces = listOf(Color.White, Color(0xFFFAF7FF))
        val darkSurfaces = listOf(Color(0xFF2C2739), Color(0xFF1B1924))
        for ((dark, surfaces) in listOf(false to lightSurfaces, true to darkSurfaces)) {
            for (action in actions) {
                for (surface in surfaces) {
                    val icon = chatOverflowIconColor(action, dark, accent).copy(alpha = .9f).compositeOver(surface)
                    val light = maxOf(icon.luminance(), surface.luminance())
                    val darkLuminance = minOf(icon.luminance(), surface.luminance())
                    assertTrue("$action dark=$dark surface=$surface", (light + .05f) / (darkLuminance + .05f) >= 3f)
                }
            }
        }
    }
}
