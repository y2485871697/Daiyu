"""Bounded eight-class IME regression with no full discovery fallback."""
import json
from pathlib import Path
import tempfile
import unittest

from test_record_tool_tests_scope import recorder
import test_speech_platform_discovery as discovery

CLASSES = (
    'app.daiyu.android.ui.navigation.PageImeCompositionActivityTest',
    'app.daiyu.android.ChatComposerImeActivityTest',
    'app.daiyu.android.ChatAttachmentSizingActivityTest',
    'app.daiyu.android.ui.screens.chat.conversation.ChatComposerMeasurePhaseTest',
    'app.daiyu.android.ui.screens.chat.conversation.ChatMessageListMeasurePhaseTest',
    'app.daiyu.android.ui.screens.chat.conversation.ChatResumeScrollTest',
    'app.daiyu.android.ui.screens.chat.conversation.ChatStreamingOcclusionTest',
    'app.daiyu.android.ManagedInputActivityTest',
)


class ChatImeSmokeScopeTest(unittest.TestCase):
    def test_only_exact_eight_class_scope_selects_bounded_contracts(self):
        self.assertEqual(CLASSES, recorder.IME_PLATFORM_CLASSES)
        self.assertTrue(recorder.IME_MODULES)
        self.assertEqual(8, len(CLASSES))
        self.assertIn('test_managed_input_contract', recorder.IME_MODULES)
        self.assertEqual(len(recorder.IME_MODULES), len(set(recorder.IME_MODULES)))
        for names in (CLASSES, tuple(reversed(CLASSES))):
            command, scope = recorder.command_for('python3', dict(SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=','.join(names)))
            self.assertEqual('ime-targeted', scope)
            self.assertEqual(['python3', '-B', '-m', 'unittest', *recorder.IME_MODULES, '-v'], command)
            self.assertNotIn('discover', command)
        raw = ','.join(CLASSES)
        invalid = [raw + ',', ' ' + raw, raw + ' ', raw + ',app.daiyu.android.OtherTest']
        invalid += [','.join(CLASSES[:i] + CLASSES[i + 1:]) for i in range(len(CLASSES))]
        invalid += [raw + ',' + name for name in CLASSES]
        for value in invalid:
            self.assertEqual('full', recorder.command_for('python3', dict(SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=value))[1])
        self.assertEqual('full', recorder.command_for('python3', dict(SMOKE_MODE='release', SMOKE_TEST_CLASSES=raw))[1])

    def test_source_reuse_only_ignores_a_root_queue_group_change(self):
        from test_smoke_boot import source
        before = b"name: test\nconcurrency:\n  group: old\n  cancel-in-progress: false\njobs:\n  run: safe\n"
        after = before.replace(b'group: old', b'group: new')
        self.assertTrue(source.concurrency_group_only_change(before, after))
        for invalid in (before, after.replace(b'run: safe', b'run: changed'),
                        after.replace(b'cancel-in-progress: false', b'cancel-in-progress: true'),
                        after + b'permissions: write-all\n', after.replace(b'name: test', b'name: other'),
                        after.replace(b'run: safe\n', b'run: safe\r\n'),
                        after.replace(b'group: new\n', b'group: new\r\n'),
                        after.replace(b'\n', b'\r\n')):
            self.assertFalse(source.concurrency_group_only_change(before, invalid))

    def test_four_shards_discover_only_the_eight_sources_and_cover_every_method(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = discovery.SpeechPlatformDiscoveryTest()
            helper.fixture(root)
            expected = {}
            for index, name in enumerate(CLASSES):
                package, _, short = name.rpartition('.')
                path = root / 'app/src/androidTest/kotlin' / (name.replace('.', '/') + '.kt')
                path.parent.mkdir(parents=True, exist_ok=True)
                expected[name] = index + 1
                path.write_text('package ' + package + '\nclass ' + short + ' : TestCase() {\n' +
                                ''.join(' fun testFrame' + str(i) + '() {}\n' for i in range(index + 1)) + '}\n')
            selected, count = [], 0
            for shard in range(4):
                result = helper.select(root, shard, classes=','.join(reversed(CLASSES)))
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = json.loads((root / 'smoke/instrumentation-shards.json').read_text())
                selection = json.loads((root / 'smoke/selection.json').read_text())
                self.assertEqual('targeted', inventory['discovery_scope'])
                self.assertEqual(expected, inventory['all_classes'])
                self.assertGreater(selection['expected_tests'], 0)
                self.assertEqual(sum(expected[name] for name in selection['selected_classes']), selection['expected_tests'])
                selected.extend(selection['selected_classes'])
                count += selection['expected_tests']
            self.assertCountEqual(CLASSES, selected)
            self.assertEqual(sum(expected.values()), count)


if __name__ == '__main__':
    unittest.main()
