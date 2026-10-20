package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
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

    private fun show(scale: Float = 1f, dark: Boolean = false) {
        saved.set(0)
        closed.set(0)
        writes.set(0)
        memory.set(AssistantMemorySnapshot("Original memory", "base"))
        val profile = AssistantProfile("editor-visual", "Visual editor", "Original prompt",
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
        val assets = object : AssistantAssets by FileAssistantAssets(instrumentation.targetContext) {
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
                        AssistantEditScreen(profile.id, { closed.incrementAndGet() }, store, assets,
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
            tagged("assistant-edit-name").performScrollTo()
            assertType(text(R.string.assistant_editor_title), 16.sp, 22.4.sp)
            assertType(text(R.string.assistant_editor_name), 10.sp, 16.sp)
            assertType("Visual editor", 11.sp, 18.15.sp)
            tagged("assistant-edit-description").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
            tagged("assistant-edit-avatar").assertHasClickAction()
            tagged("assistant-edit-save").assertIsNotEnabled()
            val footer = tagged("assistant-sheet-footer").fetchSemanticsNode().boundsInRoot
            val save = tagged("assistant-edit-save").fetchSemanticsNode().boundsInRoot
            val cancel = tagged("assistant-edit-cancel").fetchSemanticsNode().boundsInRoot
            assertEquals(33f, dp(save.height), .6f)
            assertEquals(8f, dp(save.left - cancel.right), .6f)
            assertEquals(dp(cancel.width), dp(save.width), .6f)
            tagged("assistant-skill-visual-skill").performScrollTo().assertIsOff()
            assertType(text(R.string.assistant_skills_caption), 9.sp, 14.4.sp)
            assertType("Visual skill", 10.sp, 16.sp)
            assertType("Skill description", 8.sp, 12.8.sp)
            tagged("assistant-memory-switch").performScrollTo().assertIsOff()
            val toggle = tagged("assistant-memory-switch").fetchSemanticsNode().boundsInRoot
            assertEquals(29f, dp(toggle.width), .6f)
            assertEquals(18f, dp(toggle.height), .6f)
            tagged("assistant-edit-memory").performScrollTo().assertIsDisplayed()
            assertType(text(R.string.assistant_memory_content), 10.sp, 16.sp)
            assertEquals(footer, tagged("assistant-sheet-footer").fetchSemanticsNode().boundsInRoot)
            assertEquals(0, writes.get())
            assertEquals(0, saved.get())
            tagged("assistant-edit-cancel").performClick()
            assertEquals(1, closed.get())
            assertEquals(0, writes.get())
        }
    }

    fun testEditorKeepsExistingToggleAndRevisionCheckedSaveCallbacks() {
        show()
        tagged("assistant-skill-visual-skill").performScrollTo().performClick()
        ui.waitUntil(5_000) { saved.get() == 1 }
        assertEquals(listOf("visual-skill"), store.list().single().enabledSkillIds)
        tagged("assistant-memory-switch").performScrollTo().performClick()
        ui.waitUntil(5_000) { saved.get() == 2 }
        assertTrue(store.list().single().memoryEnabled)
        tagged("assistant-memory-switch").assertIsOn()
        setField("assistant-edit-memory", "Updated memory")
        setField("assistant-edit-prompt", "Updated prompt")
        setField("assistant-edit-name", "Updated name")
        tagged("assistant-edit-save").assertIsEnabled().performClick()
        ui.waitUntil(5_000) { closed.get() == 1 }
        assertEquals(3, writes.get())
        assertEquals(3, saved.get())
        assertEquals("Updated name", store.list().single().name)
        assertEquals("Updated prompt", store.list().single().prompt)
        assertEquals(AssistantMemorySnapshot("Updated memory", "saved"), memory.get())
        assertEquals("editor-visual", store.selectedId.value)
    }

    fun testCancelDiscardsUnsavedFields() {
        show()
        setField("assistant-edit-name", "Unsaved name")
        setField("assistant-edit-prompt", "Unsaved prompt")
        setField("assistant-edit-memory", "Unsaved memory")
        tagged("assistant-edit-cancel").performClick()
        assertEquals(1, closed.get())
        assertEquals(0, writes.get())
        assertEquals("Visual editor", store.list().single().name)
        assertEquals("Original prompt", store.list().single().prompt)
        assertEquals(AssistantMemorySnapshot("Original memory", "base"), memory.get())
    }
}
