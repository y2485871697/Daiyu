"""Transient archive-download failures are retried without weakening the pin."""
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "prepare_speech_runtime", ROOT / "scripts/prepare-speech-runtime.py")
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class Response:
    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self, _limit):
        return b"verified-by-caller"


class SpeechRuntimeDownloadTests(unittest.TestCase):
    def test_transient_504_retries_and_returns_response_bytes(self):
        unavailable = urllib.error.HTTPError(
            prepare.URL, 504, "Gateway Time-out", None, io.BytesIO())
        with patch.object(prepare.urllib.request, "urlopen",
                          side_effect=[unavailable, Response()]) as fetch, \
                patch.object(prepare.time, "sleep") as sleep:
            self.assertEqual(prepare.download_archive(), b"verified-by-caller")
        self.assertEqual(fetch.call_count, 2)
        sleep.assert_called_once_with(1)

    def test_non_transient_http_error_is_not_retried(self):
        missing = urllib.error.HTTPError(
            prepare.URL, 404, "Not Found", None, io.BytesIO())
        with patch.object(prepare.urllib.request, "urlopen", side_effect=missing) as fetch, \
                patch.object(prepare.time, "sleep") as sleep:
            with self.assertRaises(urllib.error.HTTPError):
                prepare.download_archive()
        fetch.assert_called_once()
        sleep.assert_not_called()

    def test_transient_http_errors_stop_after_the_bounded_attempt_count(self):
        errors = [urllib.error.HTTPError(
            prepare.URL, 504, "Gateway Time-out", None, io.BytesIO())
            for _ in range(prepare.DOWNLOAD_ATTEMPTS)]
        with patch.object(prepare.urllib.request, "urlopen", side_effect=errors) as fetch, \
                patch.object(prepare.time, "sleep") as sleep:
            with self.assertRaises(urllib.error.HTTPError):
                prepare.download_archive()
        self.assertEqual(fetch.call_count, prepare.DOWNLOAD_ATTEMPTS)
        self.assertEqual(sleep.call_count, prepare.DOWNLOAD_ATTEMPTS - 1)

    def test_downloaded_archive_still_requires_the_exact_checksum(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(prepare, "download_archive", return_value=b"not-the-pinned-tar"):
            with self.assertRaisesRegex(RuntimeError, "SHA-256 mismatch"):
                prepare.prepare(Path(directory))
        self.assertEqual(list(Path(directory).rglob("*")), [])


if __name__ == "__main__":
    unittest.main()
