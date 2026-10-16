"""Offline preparation contracts: in-memory fixture archives only, never download real inputs."""
import hashlib
import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('speech_runtime', ROOT / 'scripts/prepare-speech-runtime.py')
RUNTIME = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNTIME)


class OfflineSpeechRuntimeTest(unittest.TestCase):
    def archive(self, omit=None, duplicate=None):
        target = io.BytesIO()
        with tarfile.open(fileobj=target, mode='w:bz2') as tar:
            for abi in RUNTIME.ABIS:
                for library in RUNTIME.LIBS:
                    name = f'./jniLibs/{abi}/{library}'
                    if name == omit:
                        continue
                    payload = b'\x7fELF' + abi.encode() + library.encode()
                    info = tarfile.TarInfo(name)
                    info.size = len(payload)
                    tar.addfile(info, io.BytesIO(payload))
                    if name == duplicate:
                        tar.addfile(info, io.BytesIO(payload))
            # Archive may contain documentation, unrelated files or traversal paths;
            # none may become APK inputs. No extractall is used.
            info = tarfile.TarInfo('../../not-allowed')
            info.size = 4
            tar.addfile(info, io.BytesIO(b'evil'))
        return target.getvalue()

    def test_pinned_version_tar_hash_and_exact_eight_allowlisted_members(self):
        self.assertEqual('7fbdb6f8bb1f8ec092467ed61d9ec85c01f060d125ccf632ef9d7ec5816d4a65', RUNTIME.SHA256)
        self.assertIn('/v2.1.15/sherpa-ncnn-v2.1.15-android.tar.bz2', RUNTIME.URL)
        self.assertEqual({'arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86'}, set(RUNTIME.ABIS))
        data = self.archive()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'fixture.tar.bz2'
            source.write_bytes(data)
            with patch.object(RUNTIME, 'SHA256', hashlib.sha256(data).hexdigest()):
                with patch.object(RUNTIME.urllib.request, 'urlopen', side_effect=AssertionError('unexpected network')):
                    RUNTIME.prepare(root / 'output', source)
            files = {str(item.relative_to(root / 'output')).replace('\\', '/')
                     for item in (root / 'output').rglob('*') if item.is_file()}
            self.assertEqual({f'{abi}/{name}' for abi in RUNTIME.ABIS for name in RUNTIME.LIBS}, files)
            self.assertFalse((root / 'not-allowed').exists())

    def test_corrupt_archive_is_rejected_before_any_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'corrupt.tar.bz2'
            source.write_bytes(b'bad archive')
            with self.assertRaisesRegex(RuntimeError, 'SHA-256'):
                RUNTIME.prepare(root / 'output', source)
            self.assertFalse((root / 'output').exists())

    def test_missing_or_duplicate_library_never_publishes_partial_output(self):
        name = './jniLibs/arm64-v8a/libsherpa-ncnn-jni.so'
        for data in (self.archive(omit=name), self.archive(duplicate=name)):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                source = root / 'fixture.tar.bz2'
                source.write_bytes(data)
                with patch.object(RUNTIME, 'SHA256', hashlib.sha256(data).hexdigest()):
                    with self.assertRaises(RuntimeError):
                        RUNTIME.prepare(root / 'output', source)
                self.assertFalse((root / 'output').exists())

    def test_build_and_checks_prepare_before_checks_and_smoke_never_prepares(self):
        workflow = (ROOT / '.github/workflows/build.yml').read_text(encoding='utf-8')
        checks = workflow.split('  checks:', 1)[1].split('  build:', 1)[0]
        build = workflow.split('  build:', 1)[1].split('  smoke:', 1)[0]
        smoke = workflow.split('  smoke:', 1)[1]
        self.assertLess(checks.index(':app:prepareOfflineSpeechRuntime'), checks.index('cloud_checks.py'))
        self.assertLess(build.index(':app:prepareOfflineSpeechRuntime'), build.index(':app:testDebugUnitTest'))
        self.assertNotIn('prepareOfflineSpeechRuntime', smoke)
        self.assertNotIn('prepare-speech-runtime.py', smoke)


if __name__ == '__main__':
    unittest.main()
