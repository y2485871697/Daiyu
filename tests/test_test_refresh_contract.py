"""New instrumentation may reuse an unchanged production app, never rebuild it."""
from pathlib import Path
import hashlib
import io
import json
import tempfile
import zipfile
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from verify_test_refresh import validate_changes, validate_graph, verify_archive


class TestRefreshContract(unittest.TestCase):
    def test_only_reviewed_validation_paths_are_allowed(self):
        validate_changes(['app/src/androidTest/kotlin/app/daiyu/android/ConversationPaneSwitchActivityTest.kt',
                          'app/src/test/kotlin/ExampleTest.kt', 'tests/example.py',
                          'docs/重构清单.md', '.github/workflows/test-refresh-regression.yml'])

    def test_chat_checks_metadata_does_not_allow_other_workflows(self):
        validate_changes(['.github/workflows/chat-workflow-checks.yml'])
        for name in ('.github/workflows/build.yml', '.github/workflows/unknown-checks.yml',
                     '.github/workflows/chat-workflow-checks.yml.bak', '.github/actions/example/action.yml'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                validate_changes([name])
        with self.assertRaises(ValueError):
            validate_changes(['.github/workflows/chat-workflow-checks.yml', '.github/workflows/build.yml'])

    def test_application_build_and_ambiguous_paths_fail_closed(self):
        for name in ('app/src/main/Example.kt', 'app/build.gradle.kts', 'settings.gradle.kts',
                     'scripts/prepare-speech-runtime.py', '.github/workflows/build.yml',
                     'app/src/androidTest/../main/Example.kt', 'tests//a', 'tests\\a', '/tests/a'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                validate_changes([name])

    def test_task_graph_and_artifact_boundaries(self):
        workflow = (ROOT / '.github/workflows/test-refresh-regression.yml').read_text()
        self.assertIn(':app:assembleReleaseAndroidTest', workflow)
        self.assertNotIn(':app:assembleRelease ', workflow)
        self.assertIn('test_refresh_only.init.gradle', workflow)
        self.assertIn('verify_test_refresh.py source', workflow)
        self.assertIn('verify_test_refresh.py bind', workflow)
        self.assertIn('verify_test_refresh.py check', workflow)
        self.assertIn('source-delivery', workflow)
        guard = (ROOT / 'scripts/cooperation/test_refresh_only.init.gradle').read_text()
        self.assertIn("!t.name.toLowerCase().contains('androidtest')", guard)
        self.assertIn('install|uninstall|connected|managedDevice', guard)

    def test_only_androidtest_packaging_is_allowed(self):
        validate_graph([':app:compileReleaseKotlin', ':app:packageReleaseResources',
                        ':app:packageReleaseAndroidTest', ':app:assembleReleaseAndroidTest'])
        for task in (':app:assembleRelease', ':app:packageRelease', ':app:bundleRelease',
                     ':app:signRelease', ':app:installReleaseAndroidTest', ':app:connectedReleaseAndroidTest'):
            with self.subTest(task=task), self.assertRaises(ValueError):
                validate_graph([':app:assembleReleaseAndroidTest', task])
        with self.assertRaises(ValueError):
            validate_graph([':app:compileReleaseAndroidTestKotlin'])

    def test_archive_binds_old_application_without_rejecting_new_instrumentation(self):
        app = '代鱼-0.1.0-12345678.apk'
        contents = {app: b'application', 'tests/platform-test.apk': b'old test'}
        record = {'sha256': {name: hashlib.sha256(data).hexdigest() for name, data in contents.items()}}
        record_bytes = json.dumps(record).encode()
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, 'w') as package:
            package.writestr('构建记录.json', record_bytes)
            for name, data in contents.items():
                package.writestr(name, data)
        archive = buffer.getvalue()
        artifact = {'id': 123, 'digest': 'sha256:' + hashlib.sha256(archive).hexdigest()}
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            record_path = directory / '应用原始构建记录.json'
            record_path.write_bytes(record_bytes)
            (directory / app).write_bytes(contents[app])
            (directory / 'tests').mkdir()
            (directory / 'tests/platform-test.apk').write_bytes(b'new test')
            verify_archive(artifact, directory, record_path, record, archive, local_apks={app})
            with self.assertRaises(ValueError):
                verify_archive(artifact, directory, record_path, record, archive)
            (directory / app).write_bytes(b'changed app')
            with self.assertRaises(ValueError):
                verify_archive(artifact, directory, record_path, record, archive, local_apks={app})
            (directory / app).unlink()
            with self.assertRaises(ValueError):
                verify_archive(artifact, directory, record_path, record, archive, local_apks={app})
