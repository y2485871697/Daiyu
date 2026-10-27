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
        self.assertIn('原视频重新提交需要新的上传批准；原消息保留', store)

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

    def test_image_only_edit_has_explicit_submit_state(self):
        models = (UI / 'ChatScreenModels.kt').read_text()
        self.assertIn('val editingMessageCanSubmit: Boolean = false', models)
        self.assertIn('text.isNotBlank() || editingMessageCanSubmit || imageSendAvailable', models)
        composer = (UI / 'ChatComposer.kt').read_text()
        self.assertIn('state.text.isNotBlank() || state.editingMessageCanSubmit', composer)
        self.assertIn('val toolbarEnabled = state.enabled && !editing', composer)

if __name__ == '__main__':
    unittest.main()
