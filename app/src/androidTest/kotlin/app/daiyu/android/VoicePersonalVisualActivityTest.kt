package app.daiyu.android

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.assistants.assistantPreviewPalette
import java.util.concurrent.atomic.AtomicInteger

class VoicePersonalVisualActivityTest : WiredActivityTestCase() {
    private fun mountMenu(settings: Boolean, dark: Boolean = false, fontScale: Float = 1f,
        disabledSync: Boolean = false, selected: MutableList<String>, dismissed: AtomicInteger) {
        val visit = Any()
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                applyInterfaceScale = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    key(visit) {
                        var open by remember { mutableStateOf(true) }
                        val select: (String) -> Unit = { selected += it; open = false }
                        val dismiss: () -> Unit = { dismissed.incrementAndGet(); open = false }
                        if (open) {
                            if (settings) VoiceAccountSettingsSheet(select, dismiss,
                                isEnabled = { !disabledSync || it != "SYNC" })
                            else VoiceAddSheet(select, dismiss)
                        }
                    }
                }
            }
        } }
        awaitUniqueTag("chat-selection-sheet")
        awaitNodeWindowFocus(node("chat-selection-sheet"))
        ui.waitForIdle()
    }

    private fun awaitClosed() {
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeByFrame()
            ui.onAllNodesWithTag("chat-selection-sheet").fetchSemanticsNodes().isEmpty()
        }
    }

    fun testBothMenusUseCompactIconActionRowsInBothThemesAndLargeFonts() {
        val before = fixture.database.exportBackup()
        val opens = fixture.opens.get()
        for (settings in listOf(false, true)) for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f)) {
            val selected = mutableListOf<String>()
            val ids = if (settings) listOf("ACCOUNT", "SYNC") else listOf("CREATE", "IMPORT")
            mountMenu(settings, dark, fontScale, selected = selected, dismissed = AtomicInteger())
            val bounds = ids.map { id ->
                val row = node("voice-personal-action:$id").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                val icon = ui.onNodeWithTag("voice-personal-icon:$id", useUnmergedTree = true)
                    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val chevron = ui.onNodeWithTag("voice-personal-chevron:$id", useUnmergedTree = true)
                    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue("leading icon stays before trailing chevron", icon.right < chevron.left)
                row.fetchSemanticsNode().boundsInRoot
            }
            assertTrue("actions keep their original order", bounds[0].bottom <= bounds[1].top + 1f)
            val sheet = node("chat-selection-sheet").fetchSemanticsNode().boundsInRoot
            val viewport = node("preview-dialog-viewport").fetchSemanticsNode().boundsInRoot
            assertTrue("two actions wrap rather than fill the viewport", sheet.height < viewport.height * .75f)
            ui.onAllNodesWithContentDescription("关闭").assertCountEquals(0)
            node("voice-personal-action:${ids[1]}").performClick()
            awaitClosed()
            assertEquals(listOf(ids[1]), selected)
        }
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(opens, fixture.opens.get())
    }

    fun testDisabledSyncAndBackOrOutsideNeverDispatchAnAction() {
        val selected = mutableListOf<String>()
        val dismissed = AtomicInteger()
        mountMenu(settings = true, disabledSync = true, selected = selected, dismissed = dismissed)
        node("voice-personal-action:SYNC").assertIsNotEnabled().performTouchInput { click() }
        assertTrue(selected.isEmpty())
        node("voice-personal-action:ACCOUNT").assertIsEnabled()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitClosed()
        assertEquals(1, dismissed.get())
        mountMenu(settings = true, disabledSync = true, selected = selected, dismissed = dismissed)
        node("preview-dialog-viewport").performTouchInput { click(Offset(2f, 2f)) }
        awaitClosed()
        assertEquals(2, dismissed.get())
        assertTrue(selected.isEmpty())
        assertEquals(0, fixture.opens.get())
    }

    fun testAddButtonUsesDonePaletteForDefaultAndCustomAccentsWithoutChangingItsAction() {
        val clicks = AtomicInteger()
        for (dark in listOf(false, true)) for (accent in listOf(null, 0xFF005B4F.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())) {
            var expected = Color.Unspecified
            var expectedIcon = Color.Unspecified
            var density = 1f
            val visit = Any()
            mainThread { activity.setContent {
                key(visit) {
                    AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                        primaryAccentColor = accent), applyInterfaceScale = false) {
                        val colors = assistantPreviewPalette()
                        expected = colors.primary
                        expectedIcon = colors.onPrimary
                        density = LocalDensity.current.density
                        VoicePersonalScaffold("我的声音", onBack = {}, enabled = true,
                            onAdd = { clicks.incrementAndGet() }) { }
                    }
                }
            } }
            val add = node("voice-account-add").assertIsDisplayed().assertIsEnabled()
                .assertWidthIsAtLeast(56.dp).assertHeightIsAtLeast(56.dp)
            val pixels = captureScreenNode(add).toPixelMap()
            val fill = pixels[(12f * density).toInt(), pixels.height / 2]
            val foreground = pixels[pixels.width / 2, pixels.height / 2]
            for ((actual, target) in listOf(fill to expected, foreground to expectedIcon)) {
                assertEquals(target.red, actual.red, .025f)
                assertEquals(target.green, actual.green, .025f)
                assertEquals(target.blue, actual.blue, .025f)
            }
            val before = clicks.get()
            add.performClick()
            assertEquals(before + 1, clicks.get())
        }
        assertEquals(0, fixture.opens.get())
    }
}
