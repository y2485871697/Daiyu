package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.screens.assistants.AssistantPreviewSection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.daiyu.android.assistants.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.assistants.AssistantEditScreen
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AssistantEditVisualTest : WiredActivityTestCase() {
    private val density = AtomicReference<Density>()
    private val saved = AtomicInteger()
    private val closed = AtomicInteger()
    private val writes = AtomicInteger()
    private val memory = AtomicReference(AssistantMemorySnapshot("Original memory", "base"))
    private lateinit var store: AssistantStore
    private lateinit var editorAssets: AssistantAssets

    private fun show(scale: Float = 1f, dark: Boolean = false, prompt: String = "Original prompt") {
        saved.set(0)
        closed.set(0)
        writes.set(0)
        memory.set(AssistantMemorySnapshot("Original memory", "base"))
        val profile = AssistantProfile("editor-visual", "Visual editor", prompt,
            memoryEnabled = false, enabledSkillIds = emptyList())
        store = object : AssistantStore {
            override val assistants = MutableStateFlow(listOf(profile))
            override val selectedId = MutableStateFlow(profile.id)
            override fun save(profile: AssistantProfile): AssistantProfile = error("Unexpected creation")
            @Synchronized
            override fun compareAndSet(expected: AssistantProfile, updated: AssistantProfile): AssistantProfile {
                check(assistants.value.single() == expected)
                assistants.value = listOf(updated)
                writes.incrementAndGet()
                return updated
            }
            override fun delete(id: String) { error("Unexpected deletion") }
            override fun select(id: String) { error("Unexpected selection") }
        }
        editorAssets = object : AssistantAssets by FileAssistantAssets(instrumentation.targetContext) {
            override fun memory(id: String): AssistantMemorySnapshot = memory.get()
            override fun saveMemory(id: String, content: String, revision: String): AssistantMemorySnapshot {
                check(memory.get().revision == revision)
                return AssistantMemorySnapshot(content, "saved").also { memory.set(it) }
            }
        }
        mainThread {
            activity.setContent {
                val platform = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(platform.density, .9f)) {
                    AgentAppTheme(AppearanceSettings(interfaceScale = scale,
                        themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                        applyInterfaceScale = true) {
                        density.set(LocalDensity.current)
                        AssistantEditScreen(profile.id, { closed.incrementAndGet() }, store, editorAssets,
                            installedSkills = listOf(AssistantSkill("visual-skill", "Visual skill", "Skill description")),
                            onSaved = { saved.incrementAndGet() })
                    }
                }
            }
        }
        ui.waitUntil(5_000) {
            ui.onAllNodes(hasTestTag("assistant-edit-memory") and hasText("Original memory"),
                useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
    }

    private fun text(id: Int) = instrumentation.targetContext.getString(id)
    private fun tagged(tag: String) = ui.onNodeWithTag(tag, useUnmergedTree = true)
    private fun dp(px: Float) = px / density.get().density
    private fun assertType(text: String, size: TextUnit, lineHeight: TextUnit) {
        val results = mutableListOf<TextLayoutResult>()
        ui.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        val input = results.single().layoutInput
        assertEquals(size, input.style.fontSize)
        assertEquals(lineHeight, input.style.lineHeight)
        assertEquals(.9f, input.density.fontScale, .001f)
    }

    private fun setField(tag: String, value: String) {
        tagged(tag).performScrollTo().performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(value)) }
        tagged(tag).assertTextEquals(value)
    }

    fun testEditorMatchesPreviewTypographyAndKeepsFooterOutsideScrollingContent() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            show(scale, dark)
            ui.onNodeWithContentDescription("关闭").assertDoesNotExist()
            ui.onNodeWithContentDescription("返回").assertHasClickAction()
            tagged("assistant-edit-name").performScrollTo()
            assertType(text(R.string.assistant_editor_title), 18.sp, 26.sp)
            assertType(text(R.string.assistant_editor_name), 13.sp, 20.sp)
            assertType("Visual editor", 14.sp, 21.sp)
            tagged("assistant-edit-description").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
            tagged("assistant-edit-avatar").assertHasClickAction()
            tagged("assistant-edit-save").assertIsNotEnabled()
            val footer = tagged("assistant-sheet-footer").fetchSemanticsNode().boundsInRoot
            val save = tagged("assistant-edit-save").fetchSemanticsNode().boundsInRoot
            val cancel = tagged("assistant-edit-cancel").fetchSemanticsNode().boundsInRoot
            assertEquals(44f, dp(save.height), .6f)
            assertEquals(8f, dp(save.left - cancel.right), .6f)
            assertEquals(dp(cancel.width), dp(save.width), .6f)
            tagged("assistant-skill-visual-skill").performScrollTo().assertIsOff()
            assertType(text(R.string.assistant_skills_caption), 12.sp, 18.sp)
            assertType("Visual skill", 13.sp, 20.sp)
            assertType("Skill description", 11.sp, 16.sp)
            tagged("assistant-memory-switch").performScrollTo().assertIsOff()
            val toggle = tagged("assistant-memory-switch").fetchSemanticsNode().boundsInRoot
            assertEquals(36f, dp(toggle.width), .6f)
            assertEquals(22f, dp(toggle.height), .6f)
            tagged("assistant-edit-memory").performScrollTo().assertIsDisplayed()
            assertType(text(R.string.assistant_memory_content), 13.sp, 20.sp)
            assertEquals(footer, tagged("assistant-sheet-footer").fetchSemanticsNode().boundsInRoot)
            assertEquals(0, writes.get())
            assertEquals(0, saved.get())
            tagged("assistant-edit-cancel").performClick()
            assertEquals(1, closed.get())
            assertEquals(0, writes.get())
        }
    }

    fun testTogglesStayInDraftUntilRevisionCheckedSave() {
        show()
        tagged("assistant-skill-visual-skill").performScrollTo().performClick()
        tagged("assistant-edit-save").assertIsEnabled()
        assertEquals(0, writes.get())
        assertTrue(store.list().single().enabledSkillIds.isEmpty())
        tagged("assistant-memory-switch").performScrollTo().performClick()
        tagged("assistant-edit-save").assertIsEnabled()
        assertEquals(0, saved.get())
        assertFalse(store.list().single().memoryEnabled)
        tagged("assistant-memory-switch").assertIsOn()
        setField("assistant-edit-memory", "Updated memory")
        setField("assistant-edit-prompt", "Updated prompt")
        setField("assistant-edit-name", "Updated name")
        setField("assistant-edit-description", "能帮忙整理资料")
        tagged("assistant-edit-save").assertIsEnabled().performClick()
        ui.waitUntil(5_000) { closed.get() == 1 }
        assertEquals(1, writes.get())
        assertEquals(1, saved.get())
        assertTrue(store.list().single().memoryEnabled)
        assertEquals(listOf("visual-skill"), store.list().single().enabledSkillIds)
        assertEquals("Updated name", store.list().single().name)
        assertEquals("能帮忙整理资料", store.list().single().description)
        assertEquals("Updated prompt", store.list().single().prompt)
        assertEquals(AssistantMemorySnapshot("Updated memory", "saved"), memory.get())
        assertEquals("editor-visual", store.selectedId.value)
    }

    fun testToggleOnlySaveAndRevertingToOriginalClearsDirtyState() {
        for (tag in listOf("assistant-skill-visual-skill", "assistant-memory-switch")) {
            show()
            tagged(tag).performScrollTo().performClick()
            tagged("assistant-edit-save").assertIsEnabled()
            tagged(tag).performClick()
            tagged("assistant-edit-save").assertIsNotEnabled()
            assertEquals(0, writes.get())
            tagged(tag).performClick()
            tagged("assistant-edit-save").assertIsEnabled().performClick()
            ui.waitUntil(5_000) { closed.get() == 1 }
            assertEquals(1, writes.get())
            assertEquals(1, saved.get())
            val profile = store.list().single()
            assertEquals(tag == "assistant-memory-switch", profile.memoryEnabled)
            assertEquals(if (tag == "assistant-skill-visual-skill") listOf("visual-skill") else emptyList<String>(),
                profile.enabledSkillIds)
        }
    }

    fun testPromptAndMemoryDragsStayInsideFieldIncludingEdgesAndFling() {
        show(prompt = (1..80).joinToString("\n") { "Prompt line $it" })
        val longMemory = (1..80).joinToString("\n") { "Memory line $it" }
        setField("assistant-edit-memory", longMemory)
        for (tag in listOf("assistant-edit-prompt", "assistant-edit-memory")) {
            val field = tagged(tag).performScrollTo()
            val pageRange = tagged("assistant-editor").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
            val beforePage = pageRange.value()
            val before = field.captureToImage().toPixelMap()
            field.performTouchInput { swipe(Offset(centerX, height * .75f), Offset(centerX, height * .25f), 350) }
            ui.waitForIdle()
            assertEquals(beforePage, pageRange.value(), 1f)
            val after = field.captureToImage().toPixelMap()
            var changed = 0
            for (y in 0 until minOf(before.height, after.height)) for (x in 0 until minOf(before.width, after.width)) {
                if (before[x, y] != after[x, y]) changed++
            }
            assertTrue("Text must scroll inside $tag", changed > before.width)
            repeat(5) {
                field.performTouchInput { swipeDown(durationMillis = 100) }
                ui.waitForIdle()
                assertEquals(beforePage, pageRange.value(), 1f)
            }
        }
        // A new gesture outside a field must restore ordinary page scrolling.
        tagged("assistant-skills").performScrollTo()
        val range = tagged("assistant-editor").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        val before = range.value()
        tagged("assistant-skills").performTouchInput { swipeDown(durationMillis = 350) }
        ui.waitForIdle()
        assertTrue(range.value() < before)
        assertEquals(0, writes.get())
        assertEquals(0, saved.get())
    }

    fun testDescriptionOnlyEditSavesAndReopensWithoutBecomingSystemPrompt() {
        show()
        setField("assistant-edit-description", "擅长整理资料")
        tagged("assistant-edit-save").assertIsEnabled()
        assertEquals("", store.list().single().description)
        tagged("assistant-edit-save").performClick()
        ui.waitUntil(5_000) { closed.get() == 1 }
        val profile = store.list().single()
        assertEquals("擅长整理资料", profile.description)
        assertEquals("Original prompt", profile.prompt)
        mainThread { activity.setContent { Box(Modifier) {} } }
        ui.waitForIdle()
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    AssistantEditScreen(profile.id, {}, store, editorAssets)
                }
            }
        }
        tagged("assistant-edit-description").performScrollTo().assertTextEquals("擅长整理资料")
        tagged("assistant-edit-save").assertIsNotEnabled()
        assertEquals(1, writes.get())
    }

    fun testSkillsLegendPaddingIsTransparentInBothThemes() {
        val background = Color(0xFF304050)
        for (dark in listOf(false, true)) {
            mainThread {
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                        applyInterfaceScale = false) {
                        Box(Modifier.fillMaxWidth().background(background).padding(24.dp)) {
                            AssistantPreviewSection("Skills") {
                                Box(Modifier.fillMaxWidth().padding(24.dp))
                            }
                        }
                    }
                }
            }
            val pixels = tagged("assistant-section-legend:Skills").captureToImage().toPixelMap()
            // The legend's horizontal padding must show its real parent, not a white mask.
            for (x in listOf(1, pixels.width - 2)) {
                val pixel = pixels[x, 1]
                assertEquals(background.red, pixel.red, .01f)
                assertEquals(background.green, pixel.green, .01f)
                assertEquals(background.blue, pixel.blue, .01f)
            }
        }
    }

    fun testCancelDiscardsUnsavedFields() {
        show()
        tagged("assistant-skill-visual-skill").performScrollTo().performClick()
        tagged("assistant-memory-switch").performScrollTo().performClick()
        setField("assistant-edit-name", "Unsaved name")
        setField("assistant-edit-description", "Unsaved description")
        setField("assistant-edit-prompt", "Unsaved prompt")
        setField("assistant-edit-memory", "Unsaved memory")
        tagged("assistant-edit-cancel").performClick()
        assertEquals(1, closed.get())
        assertEquals(0, writes.get())
        assertFalse(store.list().single().memoryEnabled)
        assertTrue(store.list().single().enabledSkillIds.isEmpty())
        assertEquals("Visual editor", store.list().single().name)
        assertEquals("", store.list().single().description)
        assertEquals("Original prompt", store.list().single().prompt)
        assertEquals(AssistantMemorySnapshot("Original memory", "base"), memory.get())
    }
}
