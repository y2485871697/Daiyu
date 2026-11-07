"""Source wiring for the no-SQL label and nonblocking runtime snapshot paths."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'

class StreamingFreezeContract(unittest.TestCase):
    def test_streaming_label_uses_the_loaded_binding_and_assistant_projection(self):
        source = (APP / 'MainActivity.kt').read_text()
        label = source.split('    private fun currentAssistantLabel()', 1)[1].split('    private fun savedModelLabel()', 1)[0]
        self.assertIn('val conversation = selected', label)
        self.assertIn('if (conversation == null) assistantStore.selectedId.value else conversation.assistantId', label)
        self.assertIn('assistantStore.assistants.value.firstOrNull', label)
        self.assertNotIn('database', label)
        self.assertNotIn('assistantStore.read(', label)

    def test_context_snapshot_does_not_take_the_checkpoint_monitor(self):
        source = (APP / 'storage/SQLiteRuntimeStore.kt').read_text()
        self.assertIn('@Volatile private var contextUsageBinding:', source)
        self.assertIn('@Volatile private var closed = false', source)
        self.assertNotIn('@Synchronized internal fun contextUsageModel(', source)
        getter = source.split('    internal fun contextUsageModel(', 1)[1].split('    private val transactions', 1)[0]
        self.assertIn('available()', getter)
        self.assertIn('val binding = contextUsageBinding', getter)
        self.assertNotIn('synchronized', getter)
        self.assertNotIn('database', getter)
        controller = (APP / 'ChatController.kt').read_text()
        scope = controller.split('    internal fun cloudContextUsage(', 1)[1].split('    private fun idleAvailable()', 1)[0]
        self.assertIn('active?.conversationId == conversation', scope)

    def test_body_projection_does_not_wait_for_durable_receipts(self):
        source = (APP / 'MainActivity.kt').read_text()
        remap = source.split('        val remapTurns = ', 1)[1].split('        chatState = ', 1)[0]
        self.assertNotIn('receiptsReady', remap)
        self.assertIn('rowsChanged || workflowBoundaries != oldBoundaries', remap)
        self.assertIn('toolReceiptSnapshot?.takeIf { receiptsReady }?.projectionReader', source)
        self.assertIn('if (receiptsReady) questionCards = buildMap', source)
        self.assertIn('question.copy(binding = null, canAnswer = false)', source)
        selection = source.split('    private fun select(conversation: Conversation?, prepared:', 1)[1].split('    private fun compressionSendBlocked', 1)[0]
        self.assertIn('questionCards = emptyMap()', selection)

    def test_streaming_size_checks_count_without_full_payload_byte_arrays(self):
        codec = (APP / 'storage/RunCheckpointCodec.kt').read_text()
        archive = (APP / 'context/ContextStorage.kt').read_text()
        store = (APP / 'storage/SQLiteRuntimeStore.kt').read_text()
        self.assertIn('checkEncodedSize(bytes: Long, running: Boolean)', codec)
        self.assertIn('checkEncodedSize(Utf8Payload.byteCount(it, Long.MAX_VALUE)', codec)
        self.assertIn('require(Utf8Payload.byteCount(text, Long.MAX_VALUE) <= MAX_BYTES)', codec)
        self.assertIn('Utf8Payload.byteCount(value, Long.MAX_VALUE) <= MAX_FIELD_BYTES', archive)
        self.assertIn('RunCheckpointCodec.encodeUtf8(result, checkpointJsonWriter, saved.owner)', store)
        self.assertIn('val limit = MAX_BYTES - if (value.message.status == RunStatus.RUNNING) RECOVERY_RESERVE else 0', codec)
        self.assertIn('writer.writeUtf8(CheckpointStringPool.pack(root, writer, limit), limit)', codec)
        pool = (APP / 'storage/CheckpointStringPool.kt').read_text()
        self.assertIn('writer.measureUtf8(root, limit)', pool)
        self.assertIn('CheckpointJsonWriter().measureUtf8(root, limit)', pool)
        writer = (APP / 'storage/CheckpointJsonWriter.kt').read_text()
        encode = writer.split('fun writeJsonUtf8(root: Any, maxBytes: Int)', 1)[1].split('fun measureUtf8', 1)[0]
        self.assertLess(encode.index('val size = measureUtf8(root, maxBytes)'),
                        encode.index('val result = ByteArray(size)'))
        measure = writer.split('fun measureUtf8(root: Any, maxBytes: Int)', 1)[1].split('private fun appendUtf8', 1)[0]
        self.assertIn('require(length <= maxBytes - size)', measure)
        self.assertIn('appendUtf8(root, countOnly = true)', measure)
        self.assertLess(measure.index('require(length <= maxBytes - size)'), measure.index('size += length'))
        self.assertIn('return size', measure)
        self.assertNotIn('encoded.toByteArray(Charsets.UTF_8).size', store)
        self.assertNotIn('it.toByteArray(Charsets.UTF_8).size', codec)
        self.assertNotIn('value.toByteArray(Charsets.UTF_8).size.toLong()', archive)
        # Decode keeps its array budget and malformed UTF-16 normalization without a full stringify.
        self.assertIn('ArchiveCodec.decodeCheckpointArray(value.getJSONArray(key))', codec)
        decoder = archive.split('internal fun decodeCheckpointArray(value: JSONArray)', 1)[1].split('private fun buildArray', 1)[0]
        self.assertIn('measureUtf8(value, MAX_ARCHIVE_BYTES.toInt())', decoder)
        self.assertIn('if (hasUnpairedSurrogate(value)) decode(archiveBytes(value)) else decodeArray(value)', decoder)
        self.assertNotIn('value.toString()', decoder)

if __name__ == '__main__':
    unittest.main()
