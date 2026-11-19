"""Keep file-text fixes behind exact grants, bounded reads and an exact targeted CI scope."""
from pathlib import Path
import importlib.util
import json
import re
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
CLASSES = ('app.daiyu.android.platform.DocumentTextReadTest', 'app.daiyu.android.platform.PlatformPermissionBoundaryTest')


class DocumentTextReadContract(unittest.TestCase):
    def test_generic_mime_fallback_reuses_existing_reader_and_grant_fence(self):
        source = (ROOT / 'app/src/main/kotlin/app/daiyu/android/platform/AndroidPlatformReads.kt').read_text()
        self.assertIn('!OpenedFileStore(app).owns(uri) &&', source)
        self.assertIn('app.checkUriPermission(uri', source)
        self.assertIn('DocumentReadPolicy.validUriEncoding(raw)', source)
        self.assertIn('DocumentReadPolicy.supports(mime, name)', source)
        self.assertIn('OpenableColumns.DISPLAY_NAME', source)
        self.assertIn('DocumentReadPolicy.binaryHeader(header, count)', source)
        self.assertIn('java.io.SequenceInputStream(prefix, input)', source)
        self.assertIn('DocumentTextWindow.read(textInput', source)
        self.assertNotIn('takePersistableUriPermission', source)
        self.assertNotIn('readBytes()', source)

    def test_exact_scope_preserves_unknown_and_duplicate_fallbacks(self):
        spec = importlib.util.spec_from_file_location('file_text_scope', ROOT / 'scripts/acceptance/record_tool_tests.py')
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        for classes in (CLASSES, CLASSES[::-1]):
            command, scope = module.command_for('python3', {'SMOKE_MODE': 'smoke', 'SMOKE_TEST_CLASSES': ','.join(classes)})
            self.assertEqual('file-text-targeted', scope)
            self.assertIn('test_document_text_read_contract', command)
            self.assertNotIn('discover', command)
        for classes in (CLASSES[:1], CLASSES + CLASSES[:1], CLASSES + ('app.daiyu.android.UnknownTest',)):
            self.assertNotEqual('file-text-targeted', module.command_for('python3', {'SMOKE_MODE': 'smoke', 'SMOKE_TEST_CLASSES': ','.join(classes)})[1])
        self.assertNotEqual('file-text-targeted', module.command_for('python3', {'SMOKE_MODE': 'candidate', 'SMOKE_TEST_CLASSES': ','.join(CLASSES)})[1])

    def test_backend_regressions_cover_actual_observed_failures(self):
        source = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/platform/DocumentTextReadTest.kt').read_text()
        for token in ('application/octet-stream', '%EF%BC%89', 'unsupported_document_format',
                      'binary_document', 'invalid_utf8', 'uri_read_grant_required', 'private_result_consent_required',
                      'next_offset_bytes', 'sliceStart', 'MockContentResolver', 'PlatformReadTool.FILE_TEXT'):
            self.assertIn(token, source)
        smoke = (ROOT / 'scripts/smoke_boot.sh').read_text()
        self.assertIn('file_text_sources if file_text_only else', smoke)
        self.assertIn('discovery_scope="targeted" if file_text_only or', smoke)

    def test_two_reader_classes_allow_four_shards_without_unrelated_discovery(self):
        from test_speech_platform_discovery import SpeechPlatformDiscoveryTest
        expected = {}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'smoke').mkdir()
            for name in CLASSES:
                relative = Path('app/src/androidTest/kotlin') / (name.replace('.', '/') + '.kt')
                source = (ROOT / relative).read_text()
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(source)
                expected[name] = len(re.findall(r'^\s*fun test\w+\(', source, re.M))
            sibling = root / 'app/src/androidTest/kotlin/UnrelatedTest.kt'
            sibling.write_text('package fixture\nclass BrokenTest : TestCase() {}\nclass OtherTest : TestCase() {}\n')
            executed = 0
            for shard in range(4):
                result = SpeechPlatformDiscoveryTest().select(root, shard=shard, classes=','.join(CLASSES))
                self.assertEqual(0, result.returncode, result.stderr)
                selection = json.loads((root / 'smoke/selection.json').read_text())
                inventory = json.loads((root / 'smoke/instrumentation-shards.json').read_text())
                self.assertEqual('targeted', inventory['discovery_scope'])
                self.assertEqual(expected, inventory['all_classes'])
                executed += selection['expected_tests']
            self.assertEqual(sum(expected.values()), executed)
