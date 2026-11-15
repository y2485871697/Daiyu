"""Exact pet model subset, without selecting unrelated device suites."""
import json
from pathlib import Path
import tempfile
import unittest
from test_record_tool_tests_scope import recorder
import test_speech_platform_discovery as discovery

class PetModelSmokeScopeTest(unittest.TestCase):
    def test_exact_selector_and_non_smoke_fallbacks(self):
        classes = recorder.PET_MODEL_PLATFORM_CLASSES
        for names in (classes, tuple(reversed(classes))):
            command, scope = recorder.command_for('python3', dict(SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=','.join(names)))
            self.assertEqual('pet-model-targeted', scope)
            self.assertEqual(list(recorder.PET_MODEL_MODULES), command[4:-1])
        raw = ','.join(classes)
        for value in (raw + ',', raw + ',' + classes[0], ','.join(classes[:-1]), ' ' + raw):
            self.assertEqual('full', recorder.command_for('python3', dict(SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=value))[1])
        for mode in ('compile', 'candidate', 'daily', 'release', ''):
            self.assertEqual('full', recorder.command_for('python3', dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=raw))[1])

    def fixture(self, root):
        (root / 'smoke').mkdir()
        paths = []
        for name in recorder.PET_MODEL_PLATFORM_CLASSES:
            path = root / 'app/src/androidTest/kotlin' / (name.replace('.', '/') + '.kt')
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('package ' + name.rsplit('.', 1)[0] + '\nclass ' + name.rsplit('.', 1)[1] + ' : TestCase() {\n fun testOne() {}\n fun testTwo() {}\n}\n')
            paths.append(path)
        (paths[0].parent / 'UnrelatedTest.kt').write_text('class OneTest : TestCase() {}\nclass TwoTest : TestCase() {}\n')
        return paths

    def test_all_requested_sources_are_selected_once_across_four_shards(self):
        helper = discovery.SpeechPlatformDiscoveryTest()
        classes = recorder.PET_MODEL_PLATFORM_CLASSES
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.fixture(root); selected = []
            for shard in range(4):
                result = helper.select(root, shard=shard, classes=','.join(classes))
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = json.loads((root / 'smoke/instrumentation-shards.json').read_text())
                self.assertEqual('targeted', inventory['discovery_scope'])
                self.assertEqual({name: 2 for name in classes}, inventory['all_classes'])
                selected.extend(json.loads((root / 'smoke/selection.json').read_text())['selected_classes'])
            self.assertEqual(sorted(classes), sorted(selected))

    def test_missing_ambiguous_or_empty_target_fails_closed(self):
        helper = discovery.SpeechPlatformDiscoveryTest()
        for issue in ('missing', 'ambiguous', 'empty'):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory); path = self.fixture(root)[0]
                if issue == 'missing': path.unlink()
                elif issue == 'ambiguous': path.write_text(path.read_text() + 'class OtherTest : TestCase() {}\n')
                else: path.write_text('class AndroidPetStoreTest : TestCase() {}\n')
                result = helper.select(root, classes=','.join(recorder.PET_MODEL_PLATFORM_CLASSES))
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((root / 'smoke/selection.json').exists())
