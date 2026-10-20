package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.MiuixScaffoldPage
import app.daiyu.android.ui.screens.settings.*

class SettingsSubpageStyleActivityTest : WiredActivityTestCase() {
    private var dpSize = 1f

    private fun LazyListScope.form(events: MutableList<String>) {
        item("connection") {
            SettingsSectionTitle("连接配置")
            SettingsCard {
                SettingsRow("名称", summary = "本地配置", tag = "style-first", onClick = { events += "name" })
                SettingsRow("模型", tag = "style-second", onClick = { events += "model" })
            }
        }
        item("save") {
            SettingsCard { SettingsRow("保存", tag = "style-save", onClick = { events += "save" }) }
        }
    }

    private fun showForm(events: MutableList<String>, grouped: Boolean = true, dark: Boolean = false,
        scale: Float = 1f, fontScale: Float = 1f, pureBlack: Boolean = false) {
        mainThread {
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    key(grouped, dark, scale, fontScale, pureBlack) {
                        AgentAppTheme(AppearanceSettings(
                            themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                            interfaceScale = scale, pureBlackEnabled = pureBlack), true) {
                            dpSize = LocalDensity.current.density
                            Box(Modifier.requiredWidth(320.dp).fillMaxHeight().testTag("style-host")) {
                                if (grouped) SettingsScaffoldPage("表单", {}) { form(events) }
                                else MiuixScaffoldPage("表单", {}) { form(events) }
                            }
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
    }

    fun testGroupedCardsKeepInsetSpacingAndBorderlessRowsInBothThemesAndScales() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            val events = mutableListOf<String>()
            showForm(events, dark = dark, scale = scale)
            val host = node("style-host").fetchSemanticsNode().boundsInRoot
            val first = node("style-first").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val second = node("style-second").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val save = node("style-save").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(16f * dpSize, first.left - host.left, 1f)
            assertEquals(16f * dpSize, host.right - first.right, 1f)
            assertEquals(first.bottom, second.top, .1f)
            assertEquals(8f * dpSize, save.top - second.bottom, 2f)
            val pixels = ui.onRoot().captureToImage().toPixelMap()
            val x = (first.left + 4f * dpSize).toInt()
            val y = first.bottom.toInt()
            val expected = pixels[x, y - 4]
            for (sample in y - 3..y + 3) {
                val actual = pixels[x, sample]
                assertEquals(expected.red, actual.red, .02f)
                assertEquals(expected.green, actual.green, .02f)
                assertEquals(expected.blue, actual.blue, .02f)
            }
            node("style-first").performClick()
            node("style-second").performClick()
            node("style-save").performClick()
            ui.runOnIdle { assertEquals(listOf("name", "model", "save"), events) }
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testPureBlackAndLargeTextRemainReadableAndScrollable() {
        showForm(mutableListOf(), dark = true, fontScale = 1.3f, pureBlack = true)
        val first = reveal("style-first", "表单").fetchSemanticsNode().boundsInRoot
        val host = node("style-host").fetchSemanticsNode().boundsInRoot
        val pixels = ui.onRoot().captureToImage().toPixelMap()
        assertEquals(Color.Black, pixels[(host.left + 2f * dpSize).toInt(), first.center.y.toInt()])
        for (tag in listOf("style-first", "style-second", "style-save")) {
            val row = reveal(tag, "表单").assertHasClickAction().assertIsEnabled().fetchSemanticsNode().boundsInRoot
            assertTrue(row.height >= 48f * dpSize)
            assertTrue(row.left >= host.left && row.right <= host.right)
        }
    }

    fun testSettingsStyleDoesNotLeakIntoOtherConsumersOfSharedCards() {
        for (grouped in listOf(true, false, true)) {
            showForm(mutableListOf(), grouped = grouped)
            val host = node("style-host").fetchSemanticsNode().boundsInRoot
            val first = node("style-first").fetchSemanticsNode().boundsInRoot
            val second = node("style-second").fetchSemanticsNode().boundsInRoot
            val save = node("style-save").fetchSemanticsNode().boundsInRoot
            assertEquals((if (grouped) 16f else 20f) * dpSize, first.left - host.left, 1f)
            assertEquals((if (grouped) 8f else 0f) * dpSize, save.top - second.bottom, 2f)
        }
    }
}
