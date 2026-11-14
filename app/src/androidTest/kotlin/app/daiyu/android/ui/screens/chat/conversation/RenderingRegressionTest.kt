package app.daiyu.android.ui.screens.chat.conversation

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import app.daiyu.android.DiagnosticExportTestProvider
import app.daiyu.android.MarkdownBlock
import app.daiyu.android.MarkdownParser
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import java.util.UUID
import org.junit.Assert.*
import app.daiyu.android.WiredActivityTestCase

class RenderingRegressionTest : WiredActivityTestCase() {
    private val context get() = instrumentation.targetContext
    @Composable private fun Theme(content: @Composable () -> Unit) {
        AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false, content = content)
    }
    fun testNarrowToolbarKeepsFourActionsReachable() {
        var direction by mutableStateOf(LayoutDirection.Ltr)
        val code = MarkdownBlock.Code((1..80).joinToString("\n") { "line $it" }, "long-language-label")
        show { CompositionLocalProvider(LocalLayoutDirection provides direction) {
            key(direction) { Box(Modifier.width(100.dp)) { MarkdownCodeBlock(code, Int.MAX_VALUE, true) } }
        } }
        for (layout in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            ui.runOnIdle { direction = layout }
            ui.waitForIdle()
            val range = ui.onNodeWithTag("markdown-code-actions", true).fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
            assertTrue(range.maxValue() > 0f)
            for (name in listOf("download", "expand", "unlock", "copy")) {
                ui.onNodeWithTag("markdown-code-$name", true).performScrollTo().assertIsDisplayed()
                    .assertWidthIsEqualTo(36.dp).assertHeightIsEqualTo(36.dp).assertHasClickAction()
            }
        }
    }
    fun testTableAlignmentRemainsPhysicalUnderRtl() {
        var direction by mutableStateOf(LayoutDirection.Ltr)
        val source = "يسار | وسط | يمين\n:--- | :---: | ---:\na | b | c"
        val blocks = MarkdownParser.parse(source)
        show { CompositionLocalProvider(LocalLayoutDirection provides direction) {
            ParsedMessageMarkdown(source, blocks, Modifier.width(360.dp))
        } }
        for (layout in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            ui.runOnIdle { direction = layout }
            for ((label, expected) in listOf("يسار" to TextAlign.Left, "وسط" to TextAlign.Center, "يمين" to TextAlign.Right)) {
                val results = mutableListOf<TextLayoutResult>()
                ui.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                    assertTrue(it(results))
                }
                assertEquals(expected, results.single().layoutInput.style.textAlign)
            }
        }
    }
    fun testExportRestoresClickSnapshotAndCancellationCleansUp() {
        val registry = PickerRegistry()
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        var savedState by mutableStateOf(SaveableStateRegistry(null) { true })
        var mounted by mutableStateOf(true)
        fun restore() {
            val values = ui.runOnIdle { savedState.performSave() }
            mainThread { mounted = false }
            mainThread {
                savedState = SaveableStateRegistry(values) { true }
                mounted = true
            }
        }
        val before = staged().map { it.name }.toSet()
        val original = "  代鱼😀\r\n".repeat(80_000) + "TRAILING  \n"
        var block by mutableStateOf(MarkdownBlock.Code(original, "txt", closed = false))
        val info = instrumentation.context.packageManager.getProviderInfo(
            ComponentName(instrumentation.context, DiagnosticExportTestProvider::class.java), 0)
        val target = Uri.parse("content://${info.authority}/${UUID.randomUUID()}")
        fun pending() = staged().filterNot { it.name in before }
        try {
            show { if (mounted) {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner, LocalSaveableStateRegistry provides savedState) {
                    Box(Modifier.width(300.dp)) { MarkdownCodeBlock(block, 1, false) }
                }
            } }
            ui.onNodeWithTag("markdown-code-download", true).performClick()
            ui.waitUntil(10_000) { registry.launches == 1 }
            val snapshot = pending().single()
            assertEquals(original, snapshot.readText(Charsets.UTF_8))
            ui.runOnIdle { block = block.copy(text = "newer text") }
            restore()
            ui.onNodeWithTag("markdown-code-download", true).assertIsNotEnabled()
            assertTrue(snapshot.isFile)
            ui.runOnIdle { registry.complete(target) }
            ui.waitUntil(10_000) { !snapshot.exists() }
            assertArrayEquals(original.toByteArray(Charsets.UTF_8), context.contentResolver.openInputStream(target)!!.use { it.readBytes() })
            ui.onNodeWithTag("markdown-code-download", true).assertIsEnabled().performClick()
            ui.waitUntil(10_000) { registry.launches == 2 }
            val cancelled = pending().single()
            restore()
            ui.runOnIdle { registry.complete(null) }
            ui.waitUntil(5_000) { !cancelled.exists() }
            ui.onNodeWithTag("markdown-code-download", true).assertIsEnabled()
        } finally {
            context.contentResolver.delete(target, null, null)
            pending().forEach { it.delete() }
        }
    }
    private fun show(content: @Composable () -> Unit) = mainThread {
        activity.setContent { Theme(content) }
    }
    private fun staged() = context.cacheDir.listFiles().orEmpty().filter {
        it.name.startsWith("markdown-code-download-") && it.name.endsWith(".txt")
    }
    private class PickerRegistry : ActivityResultRegistry() {
        @Volatile var launches = 0
        private var code = 0
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            code = requestCode
            launches++
        }
        fun complete(uri: Uri?) {
            dispatchResult(code, if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                if (uri == null) null else Intent().setData(uri))
        }
    }
}
