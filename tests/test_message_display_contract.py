"""Display-only startup wiring; behavioral SQLite and Activity tests execute in cloud."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'

class MessageDisplayContract(unittest.TestCase):
    def test_pane_does_not_require_authoritative_history(self):
        host = (APP / 'MainActivity.kt').read_text()
        reader = host.split('internal var paneConversationReader:', 1)[1].split('private var paneSelectionJob', 1)[0]
        self.assertIn('controller.database.displayMessages(conversation.id, latestRunOnly = true)', reader)
        self.assertNotIn('database.messages(', reader)
        self.assertIn('private var displayWindow by mutableStateOf<', host)
        self.assertIn('displayWindow = prepared?.display', host)
        self.assertIn('shown = prepared?.messages.orEmpty().toMutableList()', host)
        self.assertIn('capturedDisplay !== displayWindow', host)
        self.assertIn('dataRevision = revision.data', host)
        self.assertIn('titleRevision = revision.title', host)
        self.assertIn('displayWindow?.let { window -> ChatTurnMapper.mapDisplay(window,', host)
        self.assertIn('if (displayWindow != null) return', host)

    def test_projection_cannot_supply_protocol_or_capabilities(self):
        dto = (APP / 'storage/MessageDisplayWindow.kt').read_text()
        self.assertNotIn('protocolItems', dto)
        mapper = (APP / 'ui/screens/chat/conversation/ChatTurnMapper.kt').read_text()
        display = mapper.split('internal fun mapDisplay(', 1)[1].split('private data class RenderMessage', 1)[0]
        self.assertNotIn('Message(', display.replace('RenderMessage(', ''))
        self.assertNotIn('MessageHistory.expand', display)
        self.assertIn('originals[Triple(', display)
        self.assertIn('owners[TurnOwner(', display)
        self.assertIn('supplements', display)

    def test_cache_invalidates_in_transaction_and_hit_never_reads_protocol(self):
        store = (APP / 'storage/MessageDisplayStore.kt').read_text()
        for event in ['BEFORE UPDATE ON messages', 'AFTER INSERT ON messages', 'BEFORE DELETE ON messages']:
            self.assertIn(event, store)
        self.assertIn('check(db.inTransaction())', store)
        self.assertIn('reader.skipValue()', store)
        miss = store.split('row.seq to (hit ?: run {', 1)[1]
        self.assertIn('if (hasCandidate(row.role, blocks))', miss)
        self.assertNotIn('retainCoordinates', store)
        self.assertIn('repairHistory(history, segments, projections)', miss)
        self.assertIn('"messages", "protocol"', miss)
        self.assertNotIn('"messages", "protocol"', store.split('row.seq to (hit ?: run {', 1)[0])
        self.assertIn('value.getInt("version") == 3', store)
        self.assertIn('value.getJSONArray("projections")', store)
        self.assertIn('coordinateBlocks = blocks', store)
        self.assertIn('coordinateBlocks = it.blocks', store)
        self.assertNotIn('block.put("text"', store)
        db = (APP / 'storage/ConversationDatabase.kt').read_text()
        self.assertIn('null, 32)', db)
        upgrade = db.split('override fun onUpgrade(', 1)[1]
        self.assertLess(upgrade.index('MessageDisplayStore.install(db)'), upgrade.index('if (oldVersion >= 13) return'))
        canonical = db.split('internal fun storedMessage(', 1)[1].split('internal fun videoInputs(', 1)[0]
        self.assertIn('requireNotNull(text("protocol"))', canonical)
        self.assertIn('MessageHistoryCodec.validate(message, decodeReuse?.historyReceipts)', canonical)
        self.assertIn('decodeColumns().also(::validateRead)', canonical)
        self.assertIn('decodeReuse.read(columns, ::decodeColumns, ::validateRead)', canonical)
        reuse = (APP / 'storage/CommitMessageDecodeReuse.kt').read_text()
        self.assertIn('validate(hit.message)', reuse)
        self.assertIn('snapshot(decode()).also(validate)', reuse)
        self.assertIn('SQLiteMessageVideoStore.readVerified(db, message)', canonical)

    def test_cloud_regressions_are_wired(self):
        check = (ROOT / '.github/workflows/chat-workflow-checks.yml').read_text()
        self.assertIn('--tests app.daiyu.android.storage.MessageDisplayStoreTest', check)
        self.assertIn('--tests app.daiyu.android.ui.screens.chat.conversation.StreamingHistoryRefreshTest', check)
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/RestoredMarkdownActivityTest.kt').read_text()
        self.assertIn('testDisplayPublishesRealBodyAndDraftWhileAuthorityReadIsBlocked', test)
        self.assertIn('assertEquals(scroll, state().scrollRequest!!.serial)', test)
        self.assertIn('assertEquals(keys, state().turns.map { it.listKey })', test)

if __name__ == '__main__':
    unittest.main()
