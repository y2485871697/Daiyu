"""Exact pet-only discovery and four-shard completeness, preserving full fallbacks."""
import json
from pathlib import Path
import tempfile
import unittest
from test_record_tool_tests_scope import recorder
import test_speech_platform_discovery as discovery


class PetPresentationSmokeScopeTest(unittest.TestCase):
    def test_selector_is_exact_and_non_smoke_modes_remain_full(self):
        classes = recorder.PET_PRESENTATION_PLATFORM_CLASSES
        for names in (classes, tuple(reversed(classes))):
            command, scope = recorder.command_for('python3', dict(SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=','.join(names)))
            self.assertEqual('pet-presentation-targeted', scope)
            self.assertNotIn('discover', command)
            self.assertEqual(list(recorder.PET_PRESENTATION_MODULES), command[4:-1])
        raw = ','.join(classes)
        for names in (raw + ',' + classes[0], ','.join(classes[:-1]), raw + ',app.daiyu.android.PlatformTest', raw + ',', ' ' + raw):
            self.assertEqual('full', recorder.command_for('python3', dict(SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=names))[1])
        for mode in ('compile', 'candidate', 'daily', 'release', ''):
            self.assertEqual('full', recorder.command_for('python3', dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=raw))[1])

    def fixture(self, root):
        (root / 'smoke').mkdir()
        paths = []
        for name in recorder.PET_PRESENTATION_PLATFORM_CLASSES:
            path = root / 'app/src/androidTest/kotlin' / (name.replace('.', '/') + '.kt')
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('package app.daiyu.android.pet\nclass ' + name.rsplit('.', 1)[1] + ' : TestCase() {\n fun testOne() {}\n fun testTwo() {}\n}\n')
            paths.append(path)
        (paths[0].parent / 'UnrelatedTest.kt').write_text('class OneTest : TestCase() {}\nclass TwoTest : TestCase() {}\n')
        return paths

    def test_only_six_sources_are_discovered_and_covered_once(self):
        helper = discovery.SpeechPlatformDiscoveryTest()
        classes = recorder.PET_PRESENTATION_PLATFORM_CLASSES
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.fixture(root)
            selected = []
            for shard in range(4):
                result = helper.select(root, shard=shard, classes=','.join(classes))
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = json.loads((root / 'smoke/instrumentation-shards.json').read_text())
                selection = json.loads((root / 'smoke/selection.json').read_text())
                self.assertEqual('targeted', inventory['discovery_scope'])
                self.assertEqual({name: 2 for name in classes}, inventory['all_classes'])
                self.assertEqual(len(selection['selected_classes']) * 2, selection['expected_tests'])
                selected.extend(selection['selected_classes'])
            self.assertEqual(sorted(classes), sorted(selected))

    def test_missing_ambiguous_and_empty_requested_file_fail_closed(self):
        helper = discovery.SpeechPlatformDiscoveryTest()
        raw = ','.join(recorder.PET_PRESENTATION_PLATFORM_CLASSES)
        for failure in ('missing', 'ambiguous', 'empty'):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory); path = self.fixture(root)[0]
                if failure == 'missing':
                    path.unlink()
                elif failure == 'ambiguous':
                    path.write_text(path.read_text() + 'class OtherTest : TestCase() {}\n')
                else:
                    path.write_text('package app.daiyu.android.pet\nclass PetOverlayHostTest : TestCase() {}\n')
                result = helper.select(root, classes=raw)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((root / 'smoke/selection.json').exists())
