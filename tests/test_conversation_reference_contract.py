from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
A = ROOT / 'app/src/main/kotlin/app/daiyu/android'

class ConversationReferenceContractTest(unittest.TestCase):
    def test_host_initial_reference_grant_uses_real_user_not_compressed_summary(self):
        controller = (A / 'ChatController.kt').read_text()
        capture = controller.split('val referenceTexts =', 1)[1].split('val contextTools =', 1)[0]
        self.assertIn('user.blocks.filterIsInstance<MessageBlock.Text>()', capture)
        self.assertNotIn('preparedContext.messages', capture)
        self.assertIn('application.filesDir', capture)

    def test_reader_is_in_real_tool_registry_capabilities_and_close(self):
        controller = (A / 'ChatController.kt').read_text()
        self.assertIn('run, preparedContext.archiveId) + referenceReader.tools()', controller)
        self.assertIn('contextTools.map { it.definition.capability }', controller)
        self.assertIn('workspacePin = workspace, referenceReader = referenceReader', controller)
        catalog = (A / 'integration/tools/RunCatalogTools.kt').read_text()
        self.assertIn('deviceResources, skills, referenceReader)', catalog)

    def test_user_history_not_tool_or_summary_is_the_fallback_authority(self):
        reader = (A / 'references/ConversationReferenceReader.kt').read_text()
        self.assertIn('ContextService.read(database.readableDatabase, owner)', reader)
        self.assertIn('it.role == Message.USER_ROLE', reader)
        self.assertNotIn('contextService.prepare', reader)
        self.assertIn('require(id in allowed ||', reader)

    def test_default_workspace_folder_is_retained_after_run_close(self):
        reader = (A / 'references/ConversationReferenceReader.kt').read_text()
        self.assertIn('PrivateWorkspacePaths(filesDir).ensureRoot()', reader)
        self.assertIn('File(workspace, "会话工具索引")', reader)
        close = reader.split('override fun close()', 1)[1]
        self.assertNotIn('deleteRecursively', close)
        self.assertIn('closed = true', close)
        self.assertIn('sha256(file) == snapshot.hashes[key]', reader)

    def test_complete_history_and_tool_fields_are_exported_without_summary_truncation(self):
        source = (A / 'references/ReferenceTranscript.kt').read_text()
        self.assertIn('MessageHistory.expand(history)', source)
        self.assertIn('.put("arguments", block.invocation.arguments)', source)
        self.assertIn('.put("result", block.result ?: JSONObject.NULL)', source)
        self.assertIn('transcript.write(block.text)', source)
        self.assertNotIn('.take(', source)
        self.assertNotIn('protocolItems', source)
        self.assertIn('result_not_recorded', source)

    def test_composer_hint_includes_both_shortcuts(self):
        composer = (A / 'ui/screens/chat/conversation/ChatComposer.kt').read_text()
        self.assertIn('Text("给代鱼发消息，或使用 @ 和 / 引用", color = chrome.muted,', composer)

    def test_shortcuts_reach_host_and_existing_dialog(self):
        composer = (A / 'ui/screens/chat/conversation/ChatComposer.kt').read_text()
        self.assertIn('actions.onReferenceShortcut(it, next)', composer)
        self.assertIn('if (selection.collapsed)', composer)
        self.assertIn('ComposerShortcut.afterEdit(references.text, projected.text, selection.start,', composer)
        self.assertLess(composer.index('editingValue = TextFieldValue(projected.text, selection, composition)'),
                        composer.index('if (selection.collapsed)'))
        main = (A / 'MainActivity.kt').read_text()
        self.assertIn('openComposerShortcut(trigger, expected)', main)
        self.assertIn('chooseComposerShortcut(pick, it)', main)
        choose = main.split('private fun chooseComposerShortcut(', 1)[1].split('private class ChatReferencePick', 1)[0]
        self.assertIn('next = next + (if (next.isEmpty())', choose)
        self.assertNotIn('trimEnd', choose)
        self.assertLess(main.index('composerShortcutPick?.let { pick ->'), main.index('assistantSelectionOwner?.let { owner -> key(owner) {'))
        picker = (A / 'ui/screens/chat/conversation/ComposerShortcutPicker.kt').read_text()
        self.assertIn('AssistantPreviewDialog(true', picker)

    def test_explicit_skill_marker_keeps_existing_run_scope_and_on_demand_body(self):
        main = (A / 'MainActivity.kt').read_text()
        self.assertIn('"/skill:${choice.id} "', main)
        self.assertIn('it.id in enabled || it.skill.name in enabled', main)
        controller = (A / 'ChatController.kt').read_text()
        self.assertIn('selection = assistantContext.enabledSkillIds.toSet()', controller)
        scoped = (A / 'integration/tools/ScopedSkillTools.kt').read_text()
        self.assertIn('用户用/skill:<id>选择技能时先按索引读取对应SKILL.md', scoped)
        self.assertIn('scope.indexJson(authorization)', scoped)
