package app.daiyu.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.UserBubbleMode
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.AppearanceColorEditor
import app.daiyu.android.ui.screens.settings.AppearanceColorSheet

class AppearanceColorPickerActivityTest : WiredActivityTestCase() {
    private var choiceDensity = 1f
    private fun capturePicker(target: SemanticsNodeInteraction): ImageBitmap =
        if (android.os.Build.VERSION.SDK_INT < 28) captureScreenNode(target)
        else target.captureToImage()

    private fun pixel(image: ImageBitmap, x: Float, y: Float) =
        image.toPixelMap()[(image.width * x).toInt(), (image.height * y).toInt()]

    private fun assertSameColor(expected: Color, actual: Color) {
        assertEquals(expected.red, actual.red, .015f)
        assertEquals(expected.green, actual.green, .015f)
        assertEquals(expected.blue, actual.blue, .015f)
    }

    private fun show(editor: AppearanceColorEditor, dark: Boolean, requests: MutableList<AppearanceSettings>, fontScale: Float? = null, customAccents: Boolean = true, primaryAccent: Int = 0xFF8E7C9E.toInt()) {
        val original = AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
            primaryAccentColor = if (customAccents) primaryAccent else null,
            secondaryAccentColor = if (customAccents) 0xFF56896C.toInt() else null)
        mainThread {
            var state by mutableStateOf(original)
            var visible by mutableStateOf(true)
            activity.setContent {
                AgentAppTheme(state, true) {
                    val density = LocalDensity.current
                    choiceDensity = density.density
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                            .testTag("exact-applied-surface")) {
                            if (visible) AppearanceColorSheet(editor, state,
                                onChange = { requests += it; state = it; true }, onDismiss = { visible = false })
                        }
                    }
                }
            }
        }
        awaitDialogEnter(node("assistant-sheet"))
    }

    fun testPaleAccentKeepsPreviewButtonTextAndSwitchThumbInsteadOfBlack() {
        for (dark in listOf(false, true)) {
            val requests = mutableListOf<AppearanceSettings>()
            show(AppearanceColorEditor.ACCENTS, dark, requests)
            val pad = node("appearance-color-pad:主强调色").scrollToVisible()
            // Touch near white but not at the edge: still a real selected color, not a default reset.
            pad.performTouchInput { click(Offset(width * .15f, height * .08f)) }
            val expected = if (dark) Color(0xFF261F30) else Color.White
            node("appearance-preview-primary-action").scrollToVisible().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get ->
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                assertTrue(get(layouts))
                assertEquals(expected, layouts.single().layoutInput.style.color)
            }
            val thumb = capturePicker(node("appearance-preview-switch-thumb").scrollToVisible())
            assertSameColor(Color.White, pixel(thumb, .5f, .5f))
            ui.runOnIdle { assertTrue(requests.isEmpty()) }
            node("appearance-color-confirm").performClick()
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("assistant-sheet", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            ui.runOnIdle { assertEquals(1, requests.size) }
            val savedAccent = Color(requests.single().primaryAccentColor!!)
            assertFalse(savedAccent == Color(0xFF8E7C9E))
            assertTrue(savedAccent.red > .7f && savedAccent.green > .7f && savedAccent.blue > .7f)
            show(AppearanceColorEditor.ACCENTS, dark, mutableListOf(), primaryAccent = requests.single().primaryAccentColor!!)
            node("appearance-preview-primary-action").scrollToVisible().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get ->
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                assertTrue(get(layouts))
                assertEquals(expected, layouts.single().layoutInput.style.color)
            }
            assertSameColor(Color.White, pixel(capturePicker(node("appearance-preview-switch-thumb").scrollToVisible()), .5f, .5f))
            node("appearance-preview-selection").scrollToVisible().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get ->
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                assertTrue(get(layouts))
                assertEquals(savedAccent, layouts.single().layoutInput.style.color)
            }
            androidx.test.espresso.Espresso.pressBack()
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("assistant-sheet", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        }
    }

    fun testBubbleOptionsStayCompactAndMatchPreviewSelection() {
        val labels = listOf("无气泡", "跟随主题", "自定义颜色")
        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f)) for (customAccents in listOf(false, true)) {
            val requests = mutableListOf<AppearanceSettings>()
            show(AppearanceColorEditor.BUBBLES, dark, requests, fontScale, customAccents)
            node("appearance-bubble-options").scrollToVisible()
            val rows = labels.map { node("settings-radio:$it") }
            val bounds = rows.map { it.fetchSemanticsNode().boundsInRoot }
            val group = node("appearance-bubble-options").fetchSemanticsNode().boundsInRoot
            bounds.forEach {
                assertTrue("48dp touch row", it.height >= 48f * choiceDensity - 1f)
                assertTrue("compact single-line row", it.height <= 50f * choiceDensity)
                assertEquals(group.left, it.left, 1f)
                assertEquals(group.right, it.right, 1f)
            }
            bounds.zipWithNext().forEach { (before, after) ->
                assertEquals(4f * choiceDensity, after.top - before.bottom, 1.5f)
            }
            for ((index, row) in rows.withIndex()) {
                row.scrollToVisible().performTouchInput { click(Offset(width - 2f, height / 2f)) }
                rows.forEachIndexed { other, item ->
                    if (other == index) item.assertIsSelected() else item.assertIsNotSelected()
                }
                ui.runOnIdle { assertTrue(requests.isEmpty()) }
                val selected = pixel(captureScreenNode(row), .5f, .85f)
                val preview = captureScreenNode(node("appearance-preview-selection").scrollToVisible())
                assertSameColor(pixel(preview, .5f, .85f), selected)
                if (!customAccents) assertSameColor(Color(if (dark) 0xFF28262F else 0xFFEFEDFB), selected)
            }
            node("appearance-color-confirm").performClick()
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("assistant-sheet", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            ui.runOnIdle {
                assertEquals(UserBubbleMode.CUSTOM, requests.single().userBubbleMode)
                assertEquals(if (customAccents) 0xFF8E7C9E.toInt() else null, requests.single().primaryAccentColor)
                assertEquals(if (customAccents) 0xFF56896C.toInt() else null, requests.single().secondaryAccentColor)
            }
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testBubbleOptionCancelAndDefaultDoNotSave() {
        for (dark in listOf(false, true)) {
            val requests = mutableListOf<AppearanceSettings>()
            show(AppearanceColorEditor.BUBBLES, dark, requests)
            node("settings-radio:无气泡").scrollToVisible().performClick().assertIsSelected()
            node("appearance-color-default").performClick()
            node("settings-radio:跟随主题").scrollToVisible().assertIsSelected()
            node("settings-radio:无气泡").performClick().assertIsSelected()
            androidx.test.espresso.Espresso.pressBack()
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("assistant-sheet", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            ui.runOnIdle { assertTrue(requests.isEmpty()) }
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testExactInterfaceDraftAndAppliedSurfaceInBothThemes() {
        for (dark in listOf(false, true)) for (fill in listOf(Color.Black, Color.White, Color(0xFF407FCE))) {
            val requests = mutableListOf<AppearanceSettings>()
            show(AppearanceColorEditor.STYLE, dark, requests)
            if (fill == Color(0xFF407FCE)) {
                node("appearance-swatch:界面色彩:${0xFF407FCE}").scrollToVisible().performClick()
            } else {
                val pad = node("appearance-color-pad:界面色彩").scrollToVisible()
                val labels = if (fill == Color.Black) listOf("减少明度") else listOf("减少饱和度", "增加明度")
                for (label in labels) repeat(25) {
                    val action = pad.fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }
                    ui.runOnIdle { assertTrue(action.action()) }
                }
            }
            val preview = capturePicker(node("appearance-draft-preview").scrollToVisible())
            assertSameColor(fill, pixel(preview, .5f, .02f))
            ui.runOnIdle { assertTrue(requests.isEmpty()) }
            node("appearance-color-confirm").performClick()
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("assistant-sheet", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            ui.runOnIdle { assertEquals(fill, Color(requests.single().paletteSeedColor!!)) }
            assertSameColor(fill, pixel(node("exact-applied-surface").captureToImage(), .5f, .5f))
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testPlaneKeepsHueAcrossDraggingAndBlackInBothThemes() {
        for (dark in listOf(false, true)) {
            val requests = mutableListOf<AppearanceSettings>()
            show(AppearanceColorEditor.STYLE, dark, requests)
            val hue = node("appearance-color-hue:界面色彩")
            hue.scrollToVisible().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(278.5f)) }
            val pad = node("appearance-color-pad:界面色彩").scrollToVisible()
            val before = capturePicker(pad)
            pad.performTouchInput {
                swipe(Offset(width * .7f, height * .2f), Offset(width * .08f, height * .98f), durationMillis = 350)
            }
            val dragged = capturePicker(pad)
            for ((x, y) in listOf(.91f to .11f, .62f to .3f, .91f to .71f)) {
                assertSameColor(pixel(before, x, y), pixel(dragged, x, y))
            }
            repeat(25) {
                val action = pad.fetchSemanticsNode().config[SemanticsActions.CustomActions]
                    .first { it.label == "减少明度" }
                ui.runOnIdle { assertTrue(action.action()) }
            }
            hue.scrollToVisible()
            assertEquals(278.5f, hue.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
            pad.scrollToVisible()
            val black = capturePicker(pad)
            assertSameColor(pixel(before, .91f, .11f), pixel(black, .91f, .11f))
            pad.performTouchInput { click(Offset(width * .75f, height * .25f)) }
            hue.scrollToVisible().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(150f)) }
            pad.scrollToVisible()
            val changedHue = capturePicker(pad)
            assertTrue(kotlin.math.abs(pixel(before, .91f, .11f).green - pixel(changedHue, .91f, .11f).green) > .3f)
            ui.runOnIdle { assertTrue(requests.isEmpty()) }
            androidx.test.espresso.Espresso.pressBack()
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("assistant-sheet", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testDragUpdatesSaturationAndValueAfterMidGestureRecomposition() {
        for (dark in listOf(false, true)) {
            val requests = mutableListOf<AppearanceSettings>()
            show(AppearanceColorEditor.STYLE, dark, requests)
            node("appearance-color-hue:界面色彩").scrollToVisible()
                .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(278.5f)) }
            val pad = node("appearance-color-pad:界面色彩").scrollToVisible()
            pad.performTouchInput {
                down(Offset(width * .7f, height * .2f))
                moveTo(Offset(width * .45f, height * .4f), delayMillis = 100)
            }
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
            pad.performTouchInput {
                moveTo(Offset(width * .08f, height * .98f), delayMillis = 200)
                up()
            }
            ui.runOnIdle { assertTrue(requests.isEmpty()) }
            node("appearance-color-confirm").performClick()
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("assistant-sheet", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            ui.runOnIdle {
                assertSameColor(Color.hsv(278.5f, .08f, .02f), Color(requests.single().paletteSeedColor!!))
            }
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testAccentDraftChangesPreviewButNotControlsOrRainbowTrack() {
        for (dark in listOf(false, true)) {
            val requests = mutableListOf<AppearanceSettings>()
            show(AppearanceColorEditor.ACCENTS, dark, requests)
            val confirm = node("appearance-color-confirm")
            val beforeConfirm = capturePicker(confirm)
            val preview = node("appearance-draft-preview").scrollToVisible()
            val beforePreview = capturePicker(preview)
            val track = node("appearance-color-hue-track:主强调色").scrollToVisible()
            val beforeTrack = capturePicker(track)
            node("appearance-swatch:主强调色:${0xFF407FCE}").scrollToVisible().performClick()
            val afterConfirm = capturePicker(confirm)
            for ((x, y) in listOf(.1f to .5f, .9f to .5f, .5f to .2f)) {
                assertSameColor(pixel(beforeConfirm, x, y), pixel(afterConfirm, x, y))
            }
            track.scrollToVisible()
            val afterTrack = capturePicker(track)
            for (x in listOf(.08f, .25f, .4f, .92f)) {
                assertSameColor(pixel(beforeTrack, x, .5f), pixel(afterTrack, x, .5f))
            }
            preview.scrollToVisible()
            val afterPreview = capturePicker(preview)
            val beforePixels = beforePreview.toPixelMap()
            val afterPixels = afterPreview.toPixelMap()
            var changed = 0
            for (y in 0 until beforePreview.height step 4) for (x in 0 until beforePreview.width step 4) {
                if (beforePixels[x, y] != afterPixels[x, y]) changed++
            }
            assertTrue("draft sample should update", changed > 40)
            ui.runOnIdle { assertTrue(requests.isEmpty()) }
            confirm.performClick()
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("assistant-sheet", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            ui.runOnIdle { assertEquals(0xFF407FCE.toInt(), requests.single().primaryAccentColor) }
        }
        assertEquals(0, fixture.opens.get())
    }
}
