"""Source wiring guards; Android tests own composer geometry and actual callbacks."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
UI = SRC / 'ui/screens/chat/conversation'

class MessageComposerEditContract(unittest.TestCase):
    def test_editing_is_projected_without_replacing_the_persisted_draft(self):
        main = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('ComposerUi(text = inlineMessageEditor?.text ?: draft', main)
        self.assertIn('selectionStart = inlineMessageEditor?.selectionStart ?: selectionStart', main)
        edit = main.split('else if (editor != null) {', 1)[1].split('} else {', 1)[0]
        self.assertIn('editor.copy(text = next', edit)
        self.assertNotIn('database.draft', edit)
        self.assertIn('inlineMessageEditor?.visit === renderedInlineVisit', main)

    def test_editing_cannot_open_the_normal_draft_shortcut_picker(self):
        main = (SRC / 'MainActivity.kt').read_text()
        available = main.split('private fun composerShortcutAvailable()', 1)[1].split('private fun shortcutPickCurrent', 1)[0]
        self.assertIn('inlineMessageEditor == null', available)
        callback = main.split('onReferenceShortcut =', 1)[1].split('onCycleGptSpeed =', 1)[0]
        self.assertIn('inlineMessageEditor == null', callback)
        entry = main.split('is MessageActionResult.EditDraft ->', 1)[1].split('is MessageActionResult.Branch', 1)[0]
        self.assertIn('composerShortcutPick = null', entry)

    def test_send_routes_to_original_revision_before_normal_media_send(self):
        main = (SRC / 'MainActivity.kt').read_text().split('onSend = send@{ text ->', 1)[1].split('onSupplement =', 1)[0]
        self.assertLess(main.index('messageCallbacks.onSaveEdit'), main.index('sendImageDraft'))
        self.assertLess(main.index('return@send'), main.index('text == draft'))
        self.assertIn('text == editor.text && editor.canSubmit', main)
        store = (SRC / 'storage/MessageActionStore.kt').read_text()
        self.assertIn('addAll(selected.blocks.filterNot { it is MessageBlock.Text })', store)
        self.assertIn('重新提交原视频需要新的上传批准', store)

    def test_message_owned_attachments_are_borrowed_not_imported(self):
        main = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('editor.images, editor.videos, imageHost.historyLoader', main)
        media = (UI / 'ComposerMessageEdit.kt').read_text()
        self.assertIn('MessageImage(item.image, index + 1, loader, compact = true)', media)
        self.assertIn('videos.forEachIndexed', media)
        for forbidden in ('discard', 'import(', 'remove(', 'VideoChatHost', 'ImageAttachmentStore'):
            self.assertNotIn(forbidden, media)
        store = (SRC / 'storage/MessageActionStore.kt').read_text().split('fun originalUserEdit(', 1)[1].split('fun branch(', 1)[0]
        self.assertIn('authorize(db, target)', store)
        self.assertIn('it.owner.messageId == target.messageId.value', store)
        self.assertIn('SQLiteMessageVideoStore.readVerified(db, owner)', store)

    def test_original_bubble_is_not_replaced_by_a_second_editor(self):
        self.assertFalse((UI / 'InlineMessageEdit.kt').exists())
        self.assertNotIn('InlineMessageEdit(actions)', (UI / 'ChatMessageList.kt').read_text())
        actions = (UI / 'ChatScreen.kt').read_text()
        self.assertNotIn('onMessageEditText', actions)
        self.assertNotIn('onSaveMessageEdit', actions)

    def test_cancel_is_between_equal_side_slots_and_above_divider(self):
        composer = (UI / 'ChatComposer.kt').read_text()
        head = composer.split('.testTag("chat-composer-header")', 1)[1].split('.testTag("chat-composer-divider")', 1)[0]
        self.assertIn('Modifier.weight(1f).testTag("chat-model")', head)
        self.assertIn('if (editing) ComposerCancelEdit(state.enabled, actions.onCancelMessageEdit)', head)
        self.assertIn('Modifier.weight(1f).wrapContentWidth(Alignment.End)', head)
        self.assertNotIn('.offset(', head)
        button = (UI / 'ComposerMessageEdit.kt').read_text().split('internal fun ComposerCancelEdit', 1)[1].split('/**', 1)[0]
        for expected in ('widthIn(min = 72.dp)', 'heightIn(min = 40.dp)', 'Alignment.Center', 'colors.secondaryContainer', 'colors.onSecondary'):
            self.assertIn(expected, button)
        self.assertNotIn('.height(', button)
        self.assertNotIn('max =', button)

    def test_cancel_background_is_compact_without_reducing_the_touch_slot(self):
        button = (UI / 'ComposerMessageEdit.kt').read_text().split('internal fun ComposerCancelEdit', 1)[1].split('/**', 1)[0]
        touch, background = button.split('        Box(', 1)
        for expected in ('heightIn(min = 40.dp)', '.testTag("message-edit-cancel")',
                         '.conversationClickable(enabled = enabled', 'onClick = onCancel',
                         '.padding(vertical = 3.dp)'):
            self.assertIn(expected, touch)
        self.assertNotIn('.background(', touch)
        self.assertNotIn('.clip(', touch)
        for expected in ('heightIn(min = 26.dp)', '.testTag("message-edit-cancel-background")',
                         '.background(colors.secondaryContainer)', 'horizontal = 8.dp, vertical = 3.dp'):
            self.assertIn(expected, background)
        self.assertNotIn('.conversationClickable(', background)
        self.assertNotIn('fillMaxHeight', background)
        self.assertNotIn('.height(', background)

    def test_image_only_edit_has_explicit_submit_state(self):
        models = (UI / 'ChatScreenModels.kt').read_text()
        self.assertIn('val editingMessageCanSubmit: Boolean = false', models)
        self.assertIn('text.isNotBlank() || editingMessageCanSubmit || imageSendAvailable', models)
        composer = (UI / 'ChatComposer.kt').read_text()
        self.assertIn('state.text.isNotBlank() || state.editingMessageCanSubmit', composer)
        self.assertIn('val toolbarEnabled = state.enabled && !editing', composer)

    def test_edit_model_and_attachment_controls_are_not_disabled_with_the_toolbar(self):
        composer = (UI / 'ChatComposer.kt').read_text()
        self.assertIn('ComposerChip(state.modelLabel.ifBlank { "模型" }, state.enabled, actions.onModelClick,', composer)
        self.assertIn('val attachmentEnabled = state.enabled', composer)
        self.assertIn('.testTag("chat-attach"), enabled = attachmentEnabled', composer)
        self.assertIn('onLongClick = if (editing) null', composer)

    def test_added_images_use_an_independent_draft_and_reach_revision_save(self):
        main = (SRC / 'MainActivity.kt').read_text()
        host = (SRC / 'MessageEditImageDraft.kt').read_text()
        self.assertIn('MessageEditImageDraft(target.conversationId.value', main)
        self.assertIn('messageCallbacks.onSaveEditImages(editor.key, text, editor.additions.ready)', main)
        self.assertIn('message-edit-${UUID.randomUUID()}', host)
        self.assertIn('source.import(owner, uri, signal)', host)
        self.assertIn('source.store.clear(owner)', host)
        stop = main.split('override fun onStop()', 1)[1].split('override fun onDestroy()', 1)[0]
        self.assertIn('suspendMessageActionEditor()', stop)
        self.assertNotIn('invalidateMessageActionEditor()', stop)
        suspend = main.split('private fun suspendMessageActionEditor()', 1)[1].split('private fun ', 1)[0]
        self.assertIn('messageActionHost.invalidate()', suspend)
        self.assertNotIn('clearInlineMessageEditor()', suspend)
        self.assertNotIn('additions.close()', suspend)
        self.assertIn('request.firstPickerHop = false; request.pickerAway = preserve', main)
        self.assertIn('request.visit === inlineMessageEditor?.visit', main)
        self.assertIn('request.editVisit === inlineMessageEditor?.visit', main)
        self.assertIn('editor.copy(text = next, selectionStart = next.length', main)
        store = (SRC / 'storage/MessageActionStore.kt').read_text()
        self.assertIn('appendEditImages(edited, addedImages)', store)
        self.assertIn('val id = MessageId(java.util.UUID.randomUUID().toString())', store)
        self.assertIn('images.restore(owner, refs, contents)', store)
        self.assertIn('original.size + added.size <= limits.maxImages', store)
        self.assertIn('imageInputSupported = true, isCancelled = cancelled', store)
        controller = (SRC / 'ChatController.kt').read_text()
        self.assertIn('if (action == MessageAction.SAVE_EDIT) editImages else emptyList()', controller)
        self.assertIn('actionRevision = revision', controller)

    def test_unknown_save_keeps_temporary_attachments_and_blocks_resubmit(self):
        main = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('!uncertain && !additions.blocked', main)
        self.assertIn('inlineMessageEditor = editor.copy(uncertain = true)', main)
        uncertain = main.split('private fun retainUncertainMessageEdit()', 1)[1].split('private fun ', 1)[0]
        self.assertIn('dataRevision = controller.dataRevision', uncertain)
        self.assertNotIn('clearInlineMessageEditor()', uncertain)
        self.assertEqual(main.count('retainUncertainMessageEdit()'), 3)
        self.assertIn('if (outcome.outcome == RegenerationSendOutcome.ACCEPTED)', main)
        self.assertIn('it.uncertain || it.additions.blocked', main)


    def test_picker_return_settles_after_the_activity_is_resumed(self):
        main = (SRC / 'MainActivity.kt').read_text()
        resumed = main.split('override fun onPostResume()', 1)[1].split('override fun ', 1)[0]
        self.assertIn('window.decorView.post', resumed)
        self.assertIn('settleEditImages()', resumed)

    def test_pending_file_reference_blocks_edit_save_until_it_settles(self):
        main = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('it.live && it.editVisit === editor.visit', main)
        self.assertIn('editor.canSubmit && !editReferencePending(editor)', main)
        self.assertIn('it.canSubmit && !editReferencePending(it)', main)
        settle = main.split('private fun settleChatReference()', 1)[1].split('private fun ', 1)[0]
        self.assertIn('if (request.editVisit != null) publishChat(rowsChanged = false)', settle)
        self.assertIn('if (request.editVisit != null && activityUsable()) publishChat(rowsChanged = false)', settle)

    def test_replacing_editor_closes_its_temporary_image_owner(self):
        main = (SRC / 'MainActivity.kt').read_text()
        edit = main.split('is MessageActionResult.EditDraft ->', 1)[1].split('is MessageActionResult.Branch', 1)[0]
        self.assertLess(edit.index('clearInlineMessageEditor()'), edit.index('inlineMessageEditor = InlineMessageEditor'))

if __name__ == '__main__':
    unittest.main()
