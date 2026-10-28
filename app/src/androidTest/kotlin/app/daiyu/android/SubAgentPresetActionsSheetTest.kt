package app.daiyu.android

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.delegation.ConversationSubAgentConfig
import app.daiyu.android.delegation.SubAgentPreset
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.SubAgentPresetActionsSheet
import app.daiyu.android.ui.components.subagent.conversationAgentColors
import app.daiyu.android.ui.screens.assistants.assistantPreviewPalette

class SubAgentPresetActionsSheetTest : WiredActivityTestCase() {
    private var open by mutableStateOf(false)
    private var dismisses = 0
    private var renames = 0
    private var deletes = 0
    private var motionWindow = IntSize.Zero
    private var largeScreen = false
    private val tag = "subagent-preset-actions-sheet"
    private var expectedBadgeBackground = Color.Unspecified
    private var expectedGlyph = Color.Unspecified

    private fun installClosed(appearance: AppearanceSettings = AppearanceSettings()) {
        mainThread {
            open = false
            dismisses = 0
            renames = 0
            deletes = 0
            activity.setContent {
                AgentAppTheme(appearance, applyInterfaceScale = false) {
                    val badgeBackground = conversationAgentColors().soft.compositeOver(MaterialTheme.colorScheme.surface)
                    val glyphColor = assistantPreviewPalette().primary
                    val window = LocalWindowInfo.current.containerSize
                    val density = LocalDensity.current.density
                    SideEffect {
                        expectedBadgeBackground = badgeBackground
                        expectedGlyph = glyphColor
                        motionWindow = window
                        largeScreen = window.width / density >= 840f && window.height / density >= 480f
                    }
                    if (open) SubAgentPresetActionsSheet(
                        SubAgentPreset("motion-fixture", "Motion fixture", ConversationSubAgentConfig(emptyList())),
                        onDismiss = { dismisses++; open = false },
                        onRename = { renames++; open = false },
                        onDelete = { deletes++; open = false },
                    )
                }
            }
        }
        // Establish a closed, non-editing window before freezing the animation clock.
        val root = focusedWindowRoot()
        dismissIme(root)
        awaitStableWindowFrame(root) {
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(root)
            val ime = androidx.core.view.WindowInsetsCompat.Type.ime()
            if (insets != null && !insets.isVisible(ime) && insets.getInsets(ime).bottom == 0 &&
                motionWindow.width > 0 && motionWindow.height > 0) root.height to motionWindow else null
        }
    }

    private data class Geometry(val bounds: Rect, val windowHeight: Float)

    private fun geometry(): Geometry {
        val target = node(tag).fetchSemanticsNode()
        return ui.runOnIdle {
            val coordinates = target.layoutInfo.coordinates
            var root = coordinates
            while (root.parentLayoutCoordinates != null) root = checkNotNull(root.parentLayoutCoordinates)
            Geometry(root.localBoundingBoxOf(coordinates, clipBounds = false), root.size.height.toFloat())
        }
    }

    private fun frame() {
        ui.mainClock.advanceTimeByFrame()
        ui.waitForIdle()
        instrumentation.waitForIdleSync()
    }

    private fun showSettled() {
        installClosed()
        ui.runOnIdle { open = true }
        ui.waitForIdle()
        node(tag).assertIsDisplayed()
        awaitNodeWindowFocus(node(tag))
    }

    fun testEachOpeningUsesOneEntryMotionThenRemainsSettled() {
        installClosed()
        ui.mainClock.autoAdvance = false
        try {
            repeat(2) {
                ui.runOnIdle { open = true }
                frame()
                val first = geometry()
                if (!largeScreen) {
                    assertTrue("Entry must start below the window: $first", first.bounds.top >= first.windowHeight - 1f)
                }
                ui.mainClock.advanceTimeBy(160)
                ui.waitForIdle()
                val entering = geometry()
                if (largeScreen) assertTrue("Entry must scale up", entering.bounds.width > first.bounds.width)
                else assertTrue("Entry must make progress", entering.bounds.top < first.bounds.top)
                ui.mainClock.advanceTimeBy(1000)
                ui.waitForIdle()
                val settled = geometry()
                assertTrue("Sheet must finish inside its window", settled.bounds.top >= 0f && settled.bounds.bottom <= settled.windowHeight + 1f)
                if (largeScreen) {
                    assertEquals(settled.bounds.width * .8f, first.bounds.width, 1f)
                    assertEquals(settled.bounds.height * .8f, first.bounds.height, 1f)
                    assertEquals(settled.bounds.center.x, first.bounds.center.x, 1f)
                    assertEquals(settled.bounds.center.y, first.bounds.center.y, 1f)
                } else {
                    assertEquals("Exactly one entry translation must be removed: first=$first entering=$entering settled=$settled host=$motionWindow", motionWindow.height.toFloat(),
                        first.bounds.top - settled.bounds.top, 1f)
                }
                repeat(8) {
                    frame()
                    val tail = geometry().bounds
                    assertEquals(settled.bounds.top, tail.top, 1f)
                    assertEquals(settled.bounds.bottom, tail.bottom, 1f)
                }
                ui.runOnIdle { open = false }
                frame()
                node(tag).assertDoesNotExist()
            }
            assertEquals(listOf(0, 0, 0), listOf(dismisses, renames, deletes))
        } finally { ui.mainClock.autoAdvance = true }
    }

    private fun assertClosed(dismiss: Int, rename: Int, delete: Int) {
        ui.mainClock.advanceTimeBy(1000)
        ui.waitForIdle()
        node(tag).assertDoesNotExist()
        ui.runOnIdle {
            assertFalse(open)
            assertEquals(listOf(dismiss, rename, delete), listOf(dismisses, renames, deletes))
        }
        ui.mainClock.advanceTimeBy(1000)
        ui.waitForIdle()
        assertEquals(listOf(dismiss, rename, delete), listOf(dismisses, renames, deletes))
    }

    fun testBackgroundDismissesExactlyOnce() {
        showSettled()
        val bounds = geometry().bounds
        assertTrue(bounds.top > 8f)
        ui.onNode(isRoot() and hasAnyDescendant(hasTestTag(tag)), useUnmergedTree = true)
            .performTouchInput { click(Offset(center.x, bounds.top / 2f)) }
        assertClosed(1, 0, 0)
    }

    fun testBackDismissesExactlyOnce() {
        showSettled()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        assertClosed(1, 0, 0)
    }

    fun testDragDownStillDismissesExactlyOnce() {
        showSettled()
        val bounds = geometry().bounds
        ui.onNode(isRoot() and hasAnyDescendant(hasTestTag(tag)), useUnmergedTree = true)
            .performTouchInput {
                swipe(Offset(bounds.center.x, bounds.top + 8f), Offset(bounds.center.x, height.toFloat() - 4f), 120L)
            }
        assertClosed(1, 0, 0)
    }

    fun testRenameDispatchesOnlyRenameOnce() {
        showSettled()
        ui.onNodeWithText("重命名组").performClick()
        assertClosed(0, 1, 0)
    }

    fun testDeleteDispatchesOnlyDeleteOnce() {
        showSettled()
        ui.onNodeWithText("删除组").performClick()
        assertClosed(0, 0, 1)
    }
    fun testHeaderIconIsCompactAndActionsKeepTheirTouchHeight() {
        showSettled()
        node("subagent-preset-badge").assertWidthIsEqualTo(40.dp).assertHeightIsEqualTo(40.dp)
        ui.onNodeWithTag("subagent-preset-glyph", useUnmergedTree = true)
            .assertWidthIsEqualTo(24.dp).assertHeightIsEqualTo(24.dp)
        listOf("重命名组", "删除组").forEach { label ->
            ui.onNodeWithText(label).assertHasClickAction().assertHeightIsAtLeast(64.dp)
        }
        assertEquals(listOf(0, 0, 0), listOf(dismisses, renames, deletes))
    }

    fun testHeaderUsesReferenceAccentColorsInLightDarkAndCustomThemes() {
        for (dark in listOf(false, true)) for (custom in listOf(false, true)) {
            installClosed(AppearanceSettings(
                themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                paletteSeedColor = if (custom) 0xffe8e1d5.toInt() else null,
                primaryAccentColor = if (custom) 0xff356b52.toInt() else null,
                secondaryAccentColor = if (custom) 0xff98612c.toInt() else null,
            ))
            ui.runOnIdle { open = true }
            ui.waitForIdle()
            val badge = node("subagent-preset-badge").assertIsDisplayed()
            awaitNodeWindowFocus(badge)
            instrumentation.uiAutomation.waitForIdle(100, 5_000)
            val density = badge.fetchSemanticsNode().layoutInfo.density.density
            val pixels = captureScreenNode(badge).toPixelMap()
            val actual = pixels[pixels.width / 2, (3f * density).toInt()]
            assertEquals("Badge background red", expectedBadgeBackground.red, actual.red, .01f)
            assertEquals("Badge background green", expectedBadgeBackground.green, actual.green, .01f)
            assertEquals("Badge background blue", expectedBadgeBackground.blue, actual.blue, .01f)
            val glyph = captureScreenNode(ui.onNodeWithTag("subagent-preset-glyph", useUnmergedTree = true)).toPixelMap()
            val hasPrimaryPixel = (0 until glyph.width).any { x -> (0 until glyph.height).any { y ->
                val color = glyph[x, y]
                kotlin.math.abs(expectedGlyph.red - color.red) < .01f &&
                    kotlin.math.abs(expectedGlyph.green - color.green) < .01f &&
                    kotlin.math.abs(expectedGlyph.blue - color.blue) < .01f
            } }
            assertTrue("Group glyph uses the reference primary accent", hasPrimaryPixel)
            assertEquals(listOf(0, 0, 0), listOf(dismisses, renames, deletes))
            ui.runOnIdle { open = false }
            ui.waitForIdle()
            node(tag).assertDoesNotExist()
        }
    }

}
