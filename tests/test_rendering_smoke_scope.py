"""Rendering-only cloud inventory and tool-receipt routing."""
import json
from pathlib import Path
import tempfile
import unittest
from test_record_tool_tests_scope import recorder
from test_speech_platform_discovery import SpeechPlatformDiscoveryTest

CLASSES = (
    'app.daiyu.android.ui.screens.chat.conversation.RenderingRegressionTest',
    'app.daiyu.android.ui.screens.chat.conversation.MessageMarkdownTest',
    'app.daiyu.android.ui.screens.chat.conversation.MarkdownTableStyleTest',
    'app.daiyu.android.ui.screens.chat.conversation.ReportedReasoningMarkdownTest',
    'app.daiyu.android.ui.screens.chat.conversation.ReasoningWorkflowTest',
    'app.daiyu.android.RestoredMarkdownActivityTest',
)
class RenderingSmokeScopeTests(unittest.TestCase):
    def test_recorder_requires_exact_scope_and_accepts_reverse_order(self):
        self.assertEqual(CLASSES, recorder.RENDERING_PLATFORM_CLASSES)
        for names in (CLASSES, tuple(reversed(CLASSES))):
            cmd, scope = recorder.command_for('python3', dict(SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=','.join(names)))
            self.assertEqual('rendering-targeted', scope)
            self.assertNotIn('discover', cmd)
        for names in (CLASSES[:-1], CLASSES + CLASSES[:1], CLASSES + ('unknown.Test',)):
            self.assertEqual('full', recorder.command_for('python3', dict(SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=','.join(names)))[1])

    def test_inventory_reads_only_the_exact_rendering_sources(self):
        helper = SpeechPlatformDiscoveryTest()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper.fixture(root)
            for name in CLASSES:
                path = root / 'app/src/androidTest/kotlin' / (name.replace('.', '/') + '.kt')
                path.parent.mkdir(parents=True, exist_ok=True)
                package, _, short = name.rpartition('.')
                path.write_text(f'package {package}\nclass {short} : TestCase() {{\n fun testOne() {{}}\n}}\n')
            chosen = []
            for shard in range(4):
                result = helper.select(root, shard, classes=','.join(reversed(CLASSES)))
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = json.loads((root / 'smoke/instrumentation-shards.json').read_text())
                selection = json.loads((root / 'smoke/selection.json').read_text())
                self.assertEqual('targeted', inventory['discovery_scope'])
                self.assertEqual(set(CLASSES), set(inventory['all_classes']))
                chosen.extend(selection['selected_classes'])
            self.assertCountEqual(CLASSES, chosen)
            path.unlink()
            result = helper.select(root, classes=','.join(CLASSES))
            self.assertNotEqual(0, result.returncode)
            self.assertIn('Unknown instrumentation test class', result.stderr)
