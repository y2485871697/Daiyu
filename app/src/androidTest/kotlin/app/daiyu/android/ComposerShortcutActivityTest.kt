package app.daiyu.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import app.daiyu.android.references.PromptReferences
import app.daiyu.android.ui.screens.chat.conversation.ComposerReferences

class ComposerShortcutActivityTest : WiredActivityTestCase() {
    private fun prepare(): Pair<String, String> {
        val source = fixture.database.create("default"); fixture.database.rename(source.id, "引用来源😀")
        val current = fixture.database.create("default")
        mainThread { activity.select(current) }
        awaitControl("shortcut editor ready") { !fixture.controller.dataBusy }
        return source.id.value to current.id.value
    }
    private fun type(text: String) {
        node("chat-draft").performSemanticsAction(SemanticsActions.SetText) { assertTrue(it(AnnotatedString(text))) }
    }
    fun testAtPickerSelectsMetadataOnlyAndReferenceChipSurvivesRecreation() {
        val (source, _) = prepare()
        type("请求 @")
        awaitUniqueTag("reference-picker-search")
        awaitUniqueTag("reference-picker-item:$source")
        node("reference-picker-item:$source").performClick()
        awaitUniqueTag("chat-reference:0")
        assertDraft("请求 ")
        val raw = fixture.database.conversations().first { it.draft.contains(PromptReferences.PREFIX) }.draft
        assertEquals(source, PromptReferences.conversations(listOf(raw)).single().id)
        assertEquals("请求 ", ComposerReferences(raw).text)
        assertFalse(raw.contains("会话全文"))
        activity = recreateActivity(activity)
        awaitUniqueTag("chat-reference:0")
        assertDraft("请求 ")
        node("chat-reference-remove:0").performClick()
        node("chat-reference-chips").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }
    fun testConversationSelectionPreservesSuffixAndTrailingBlankLines() {
        val (source, current) = prepare()
        type("前 @旧 后\n\n")
        node("chat-draft").performSemanticsAction(SemanticsActions.SetSelection) { assertTrue(it(4, 4, false)) }
        // Commit a real edit at the cursor without replacing the untouched suffix.
        node("chat-draft").performSemanticsAction(SemanticsActions.InsertTextAtCursor) { assertTrue(it(AnnotatedString("会话"))) }
        awaitUniqueTag("reference-picker-search")
        node("reference-picker-search").performTextReplacement("")
        awaitUniqueTag("reference-picker-item:$source")
        node("reference-picker-item:$source").performClick()
        val raw = fixture.database.conversations().first { it.id.value == current }.draft
        assertEquals("前  后\n\n", ComposerReferences(raw).text)
        assertEquals(source, PromptReferences.conversations(listOf(raw)).single().id)
        assertEquals(0, fixture.opens.get())
    }
    fun testPastedReferenceAndShortcutUseVisibleCoordinates() {
        val (source, current) = prepare()
        val file = "文件引用（未读取）：content://fixture/file"
        type("$file\n@")
        awaitUniqueTag("reference-picker-item:$source")
        node("reference-picker-item:$source").performClick()
        val raw = fixture.database.conversations().first { it.id.value == current }.draft
        val projection = ComposerReferences(raw)
        assertEquals("", projection.text)
        assertEquals(listOf("文件", "会话"), projection.references.map { it.kind })
        assertTrue(raw.contains(file))
        assertEquals(source, PromptReferences.conversations(listOf(raw)).single().id)
        assertEquals(0, fixture.opens.get())
    }
    fun testCancelLeavesExactDraftAndSlashPickerDoesNotSendOrReadSkills() {
        prepare(); type("@")
        awaitUniqueTag("reference-picker-done"); node("reference-picker-done").performClick()
        assertDraft("@")
        type("/")
        awaitUniqueTag("reference-picker-search")
        ui.onNodeWithText("选择 Skill").assertIsDisplayed()
        node("reference-picker-done").performClick()
        assertDraft("/"); assertEquals(0, fixture.opens.get())
    }
}
