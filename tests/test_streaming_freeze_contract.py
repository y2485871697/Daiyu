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

if __name__ == '__main__':
    unittest.main()
