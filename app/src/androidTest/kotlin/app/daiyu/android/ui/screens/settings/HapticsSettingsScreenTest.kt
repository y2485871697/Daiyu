package app.daiyu.android.ui.screens.settings

import android.content.Context
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.domain.haptics.HapticDelivery
import app.daiyu.android.domain.haptics.HapticIntensity
import app.daiyu.android.domain.haptics.HapticPort
import app.daiyu.android.domain.haptics.HapticRequest
import app.daiyu.android.haptics.HapticsRuntime
import app.daiyu.android.haptics.LocalHapticSettingsStore
import app.daiyu.android.ui.app.AgentAppTheme

class HapticsSettingsScreenTest : WiredActivityTestCase() {
    private class RecordingPort : HapticPort {
        val requests = mutableListOf<HapticRequest>()
        override fun play(request: HapticRequest): HapticDelivery {
            requests += request
            return HapticDelivery.PLAYED
        }
        override fun cancel() = Unit
    }

    private class WriteControlledPreferences(private val actual: SharedPreferences) : SharedPreferences by actual {
        var rejectWrites = false
        override fun edit(): SharedPreferences.Editor {
            val editor = actual.edit()
            return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) {
                    proxy, method, args ->
                if (method.name == "commit" && rejectWrites) false
                else method.invoke(editor, *(args ?: emptyArray())).let { if (it === editor) proxy else it }
            } as SharedPreferences.Editor
        }
    }

    private class Page(val preferences: WriteControlledPreferences, val store: LocalHapticSettingsStore,
                       val port: RecordingPort) {
        val current = mutableStateOf(true)
        var pixelsPerDp = 1f
    }

    private fun withPage(dark: Boolean = false, scale: Float = 1f,
                         direction: LayoutDirection = LayoutDirection.Ltr, test: (Page) -> Unit) {
        val name = "haptics-ui-${System.nanoTime()}"
        val preferences = instrumentation.targetContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        val controlled = WriteControlledPreferences(preferences)
        val page = Page(controlled, LocalHapticSettingsStore(controlled), RecordingPort())
        var cleanup: (() -> Unit)? = null
        try {
            mainThread {
                val runtime = HapticsRuntime(page.store) { page.port }
                val session = runtime.attach(activity.window.decorView, "haptics-settings-test", resumed = true)
                cleanup = { session.close(); runtime.close() }
                activity.setContent {
                    val platform = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(platform.density, 1.3f),
                        LocalLayoutDirection provides direction) {
                        AgentAppTheme(AppearanceSettings(interfaceScale = scale,
                            themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), true) {
                            page.pixelsPerDp = LocalDensity.current.density
                            HapticsSettingsScreen(runtime, session, onBack = {}, ownerCurrent = { page.current.value })
                        }
                    }
                }
            }
            test(page)
            assertEquals(0, fixture.opens.get())
        } finally {
            mainThread {
                activity.setContent { Box {} }
                cleanup?.invoke()
                check(preferences.edit().clear().commit())
            }
            instrumentation.targetContext.deleteSharedPreferences(name)
        }
    }

    private fun slot(title: String) = ui.onNodeWithTag("haptics-switch-slot:$title", useUnmergedTree = true)

    fun testPageSwitchSlotsAlignInBothThemesScalesAndDirections() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f))
            for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) withPage(dark, scale, direction) { page ->
                val bounds = listOf("触感反馈", "消息生成震动", "后台震动").map {
                    slot(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                }
                bounds.forEach {
                    assertEquals(48f * page.pixelsPerDp, it.width, 1f)
                    assertEquals(48f * page.pixelsPerDp, it.height, 1f)
                    assertEquals(bounds.first().center.x, it.center.x, 1f)
                }
                ui.onNodeWithContentDescription("展开触感反馈", useUnmergedTree = true).assertIsDisplayed()
                ui.onNodeWithContentDescription("展开后台震动", useUnmergedTree = true).assertIsDisplayed()
            }
    }

    fun testSwitchAndExpansionActionsStayIndependent() = withPage { page ->
        slot("触感反馈").performTouchInput { click(center) }
        assertFalse(page.store.read().touch)
        ui.onNodeWithTag("haptics-intensity").assertDoesNotExist()
        slot("触感反馈").performTouchInput { click(center) }
        assertTrue(page.store.read().touch)
        node("settings-switch:后台震动").performClick()
        node("settings-switch:后台推理过程震动").assertIsDisplayed()
        ui.onNodeWithContentDescription("收起后台震动", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(page.store.read().background)
        node("settings-switch:后台震动").performClick()
        node("settings-switch:后台推理过程震动").assertDoesNotExist()
        slot("后台震动").performTouchInput { click(center) }
        assertFalse(page.store.read().background)
        node("settings-switch:后台推理过程震动").assertDoesNotExist()
    }


    fun testFailedBackgroundSaveKeepsTheConfirmedExpansionState() = withPage { page ->
        node("settings-switch:后台震动").performClick()
        node("settings-switch:后台推理过程震动").assertIsDisplayed()
        mainThread { page.preferences.rejectWrites = true }
        slot("后台震动").performTouchInput { click(center) }
        assertTrue(page.store.read().background)
        node("settings-switch:后台推理过程震动").assertIsDisplayed()
        ui.onNodeWithContentDescription("收起后台震动", useUnmergedTree = true).assertIsDisplayed()
        ui.onNodeWithText("设置写入失败，未保存").assertIsDisplayed()
        mainThread { page.preferences.rejectWrites = false }
        slot("后台震动").performTouchInput { click(center) }
        assertFalse(page.store.read().background)
        mainThread { page.preferences.rejectWrites = true }
        node("settings-switch:后台震动").performClick()
        assertFalse(page.store.read().background)
        node("settings-switch:后台推理过程震动").assertDoesNotExist()
        mainThread { page.preferences.rejectWrites = false }
        node("settings-switch:后台震动").performClick()
        assertTrue(page.store.read().background)
        node("settings-switch:后台推理过程震动").assertIsDisplayed()
    }

    fun testNativeSliderSavesFourLevelsOnceAndRejectsRevokedOwner() = withPage { page ->
        slot("触感反馈").performTouchInput { click(center) }
        assertFalse(page.store.read().touch)
        node("settings-switch:触感反馈").performClick()
        val slider = ui.onNodeWithTag("haptics-intensity")
        slider.assertIsDisplayed()
        ui.onNodeWithText("震动幅度").assertIsDisplayed()
        ui.onNodeWithText("关闭上级开关不会覆盖已保存的下级选项。后台震动不会保持应用进程存活。").assertDoesNotExist()
        listOf(HapticIntensity.LOW, HapticIntensity.MEDIUM, HapticIntensity.HIGH, HapticIntensity.DEFAULT)
            .forEachIndexed { index, intensity ->
                slider.performSemanticsAction(SemanticsActions.SetProgress) { it(intensity.ordinal.toFloat()) }
                awaitVisualIdle()
                assertEquals(intensity, page.store.read().intensity)
                assertEquals(index + 1, page.port.requests.count { it.preview })
                assertEquals(intensity, page.port.requests.last().intensity)
                slider.performSemanticsAction(SemanticsActions.SetProgress) { it(intensity.ordinal.toFloat()) }
                assertEquals(index + 1, page.port.requests.count { it.preview })
            }
        val retainedProgress = slider.fetchSemanticsNode().config[SemanticsActions.SetProgress].action!!
        mainThread { page.current.value = false }
        val before = page.port.requests.size
        mainThread { retainedProgress(HapticIntensity.HIGH.ordinal.toFloat()) }
        assertEquals(HapticIntensity.DEFAULT, page.store.read().intensity)
        assertEquals(before, page.port.requests.size)
    }
}
