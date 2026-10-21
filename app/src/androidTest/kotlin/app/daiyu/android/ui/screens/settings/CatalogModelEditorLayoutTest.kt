package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.model.catalog.CatalogModelEdit
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.app.DaiyuPalette
import app.daiyu.android.ui.components.subagent.ConversationAgentButton
import app.daiyu.android.ui.screens.assistants.assistantPreviewPalette
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import java.io.File

class CatalogModelEditorLayoutTest : WiredActivityTestCase() {
    private var visible by mutableStateOf(false)
    private var enabled by mutableStateOf(true)
    private var saved: CatalogModelEdit? = null
    private var saves = 0
    private var dismissals = 0
    private var deletions = 0

    private fun show(dark: Boolean = false, scale: Float = 1f, fontScale: Float = 1f,
        canDelete: Boolean = true, isNew: Boolean = false) {
        mainThread {
            visible = false
            enabled = true
            activity.setContent {
                val platform = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(platform.density, fontScale)) {
                    AgentAppTheme(AppearanceSettings(interfaceScale = scale,
                        themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), true) {
                        val accent = assistantPreviewPalette()
                        Column {
                            ConversationAgentButton("", primary = true, primaryColor = accent.primary,
                                onPrimaryColor = accent.onPrimary,
                                modifier = Modifier.size(160.dp, 44.dp).testTag("collaboration-accent-reference"), onClick = {})
                            TextButton("", onClick = {}, colors = ButtonDefaults.textButtonColorsPrimary(),
                                modifier = Modifier.size(160.dp, 44.dp).testTag("global-accent-reference"))
                        }
                        if (visible) CatalogModelEditorDialog(
                            CatalogModelEdit("layout-model", "布局模型", "8192", true, true),
                            isNew = isNew, enabled = enabled, canDelete = canDelete,
                            onSave = { saved = it; saves++ }, onDelete = { deletions++ },
                            onDismiss = { dismissals++; visible = false })
                    }
                }
            }
        }
    }

    private fun open() {
        mainThread { visible = true }
        awaitUniqueTag("catalog-model-editor")
    }
    private fun revealEditor(tag: String): SemanticsNodeInteraction {
        node("catalog-model-editor").scrollToDescendant(hasTestTag(tag))
        return node(tag).assertIsDisplayed()
    }
    private fun assertFill(tag: String, expected: Color) {
        val pixels = captureScreenNode(node(tag)).toPixelMap()
        for (x in listOf(pixels.width / 6, pixels.width * 5 / 6)) {
            val actual = pixels[x, pixels.height / 2]
            assertEquals("$tag red", expected.red, actual.red, .025f)
            assertEquals("$tag green", expected.green, actual.green, .025f)
            assertEquals("$tag blue", expected.blue, actual.blue, .025f)
        }
    }
    private fun capture(name: String) {
        val directory = File(checkNotNull(activity.getExternalFilesDir(null)), "settings-ui")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use {
            check(captureScreenNode(node("catalog-model-dialog")).asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    fun testFourButtonsAndFieldsShareContentEdgesWithFixedFooter() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            show(dark, scale, fontScale = 1.3f)
            open()
            val footer = node("catalog-model-actions").fetchSemanticsNode().boundsInRoot
            val cancel = node("dialog-cancel").fetchSemanticsNode().boundsInRoot
            val save = node("dialog-confirm").fetchSemanticsNode().boundsInRoot
            assertEquals(cancel.width, save.width, 1f)
            assertEquals(cancel.top, save.top, 1f)
            assertEquals(cancel.bottom, save.bottom, 1f)
            assertTrue(save.left > cancel.right)
            for (tag in listOf("settings-field:展示名称", "settings-field:Model ID",
                "settings-field:上下文窗口（tokens）", "catalog-model-capabilities",
                "catalog-model-auto", "local-model-delete:layout-model")) {
                val bounds = revealEditor(tag).fetchSemanticsNode().boundsInRoot
                assertEquals("$tag left", footer.left, bounds.left, 1f)
                assertEquals("$tag right", footer.right, bounds.right, 1f)
                assertTrue("$tag above footer", bounds.bottom <= footer.top + 1f)
                assertEquals(footer, node("catalog-model-actions").fetchSemanticsNode().boundsInRoot)
            }
            capture("catalog-model-${if (dark) "dark" else "light"}-$scale")
            node("dialog-cancel").performClick()
        }
    }

    fun testAccentMatchesCollaborationWithoutChangingGlobalTheme() {
        for (dark in listOf(false, true)) {
            show(dark)
            val primary = if (dark) Color(0xFFB6A4C7) else Color(0xFF8E7C9E)
            val palette = if (dark) DaiyuPalette.dark else DaiyuPalette.light
            assertFill("collaboration-accent-reference", primary)
            assertFill("global-accent-reference", palette.primary)
            open()
            assertFill("dialog-confirm", primary)
            assertFill("dialog-cancel", palette.secondaryVariant)
            revealEditor("catalog-model-auto")
            assertFill("catalog-model-auto", primary)
            revealEditor("local-model-delete:layout-model")
            assertFill("local-model-delete:layout-model", palette.error)
            node("dialog-cancel").performClick()
            assertFill("global-accent-reference", palette.primary)
        }
    }

    fun testFullWidthActionsRetainValidationResetAndDisabledGuards() {
        show(canDelete = false)
        open()
        node("settings-field:Model ID").assertIsNotEnabled()
        revealEditor("settings-field:上下文窗口（tokens）").performTextReplacement("invalid")
        node("dialog-confirm").assertIsNotEnabled()
        revealEditor("catalog-model-auto").performTouchInput { click(Offset(width - 12f, center.y)) }
        revealEditor("settings-field:上下文窗口（tokens）").assertEditableText("")
        assertNull(saved)
        node("dialog-confirm").performClick()
        assertEquals(1, saves)
        assertEquals(CatalogModelEdit("layout-model", "布局模型"), saved)
        revealEditor("local-model-delete:layout-model").assertIsNotEnabled()
        revealEditor("settings-field:上下文窗口（tokens）").performTextReplacement("16384")
        mainThread { enabled = false }
        for (tag in listOf("catalog-model-auto", "local-model-delete:layout-model"))
            revealEditor(tag).assertIsNotEnabled().performTouchInput { click(center) }
        for (tag in listOf("dialog-confirm", "dialog-cancel"))
            node(tag).assertIsNotEnabled().performTouchInput { click(center) }
        assertEquals(1, saves)
        assertEquals(0, deletions)
        assertEquals(0, dismissals)
        revealEditor("settings-field:上下文窗口（tokens）").assertEditableText("16384")
        mainThread { enabled = true }
        node("dialog-cancel").performClick()
        show(canDelete = true)
        open()
        revealEditor("local-model-delete:layout-model").performTouchInput { click(Offset(width - 12f, center.y)) }
        assertEquals(1, deletions)
        node("dialog-cancel").performClick()
        show(isNew = true)
        open()
        node("settings-field:Model ID").assertIsEnabled()
        node("catalog-model-auto").assertDoesNotExist()
        node("local-model-delete:layout-model").assertDoesNotExist()
        node("dialog-cancel").performClick()
        assertEquals(0, fixture.opens.get())
    }
}
