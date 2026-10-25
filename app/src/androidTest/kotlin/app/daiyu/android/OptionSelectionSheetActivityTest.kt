package app.daiyu.android

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.OptionSelectionItem
import app.daiyu.android.ui.components.OptionSelectionSheet
import java.util.concurrent.atomic.AtomicInteger

class OptionSelectionSheetActivityTest : WiredActivityTestCase() {
    private fun mount(
        options: Int = 2,
        selected: Int = 0,
        disabled: Int = -1,
        mode: AppearanceThemeMode = AppearanceThemeMode.LIGHT,
        fontScale: Float = 1f,
        selections: AtomicInteger,
        dismissed: AtomicInteger = AtomicInteger(),
        onSelect: (Int) -> Unit = {},
    ) {
        val visit = Any()
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(themeMode = mode), applyInterfaceScale = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    key(visit) {
                        var open by remember { mutableStateOf(true) }
                        if (open) OptionSelectionSheet("选择接口", onDismiss = { open = false; dismissed.incrementAndGet() }) {
                            repeat(options) { index ->
                                OptionSelectionItem("接口 $index", selected = index == selected,
                                    enabled = index != disabled, modifier = Modifier.testTag("choice:$index")) {
                                    selections.incrementAndGet(); onSelect(index); open = false
                                }
                            }
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

    fun testShortChoicesWrapAndChooseExactlyOnceInBothThemesAndLargeFonts() {
        val before = fixture.database.exportBackup()
        val opens = fixture.opens.get()
        for (mode in listOf(AppearanceThemeMode.LIGHT, AppearanceThemeMode.DARK)) {
            for (fontScale in listOf(1f, 1.3f)) {
                val selections = AtomicInteger()
                var selected = -1
                mount(mode = mode, fontScale = fontScale, selections = selections, onSelect = { selected = it })
                node("choice:0").assertIsSelected().assertHeightIsAtLeast(48.dp)
                node("choice:1").assertIsNotSelected().assertHeightIsAtLeast(48.dp)
                ui.onAllNodesWithContentDescription("关闭").assertCountEquals(0)
                val sheet = node("chat-selection-sheet").fetchSemanticsNode().boundsInRoot
                val viewport = node("preview-dialog-viewport").fetchSemanticsNode().boundsInRoot
                assertTrue("two choices should wrap rather than fill the viewport", sheet.height < viewport.height * .75f)
                assertTrue(sheet.top > viewport.top)
                node("choice:1").performClick()
                awaitClosed()
                assertEquals(1, selections.get())
                assertEquals(1, selected)
            }
        }
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(opens, fixture.opens.get())
    }

    fun testDisabledChoiceAndBackOrOutsideNeverSelectAnOption() {
        val selections = AtomicInteger()
        val dismissed = AtomicInteger()
        mount(disabled = 1, selections = selections, dismissed = dismissed)
        node("choice:1").assertIsNotEnabled().performTouchInput { click() }
        assertEquals(0, selections.get())
        node("choice:0").assertIsSelected()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitClosed()
        assertEquals(1, dismissed.get())
        assertEquals(0, selections.get())
        mount(selections = selections, dismissed = dismissed)
        node("preview-dialog-viewport").performTouchInput { click(Offset(2f, 2f)) }
        awaitClosed()
        assertEquals(2, dismissed.get())
        assertEquals(0, selections.get())
        assertEquals(0, fixture.opens.get())
    }

    fun testLongChoicesScrollToTheLastOptionWithoutChangingSelectionOnScroll() {
        val selections = AtomicInteger()
        var selected = -1
        mount(options = 40, mode = AppearanceThemeMode.DARK, fontScale = 1.3f,
            selections = selections, onSelect = { selected = it })
        node("choice:39").performScrollTo().assertIsDisplayed().assertIsNotSelected()
        assertEquals(0, selections.get())
        node("choice:39").performClick()
        awaitClosed()
        assertEquals(1, selections.get())
        assertEquals(39, selected)
        assertEquals(0, fixture.opens.get())
    }
}
