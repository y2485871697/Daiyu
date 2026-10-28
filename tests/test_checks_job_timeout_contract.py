"""The full checks job must also budget report upload and Gradle post-cache cleanup."""
from pathlib import Path
import re
import unittest


class ChecksJobTimeoutContract(unittest.TestCase):
    def test_full_checks_leave_room_for_upload_and_cache_cleanup(self):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/build.yml').read_text()
        checks = workflow.split('  checks:\n', 1)[1].split('\n  build:\n', 1)[0]
        limits = re.findall(r'^    timeout-minutes: (\d+)$', checks, re.M)
        self.assertEqual(1, len(limits))
        self.assertGreaterEqual(int(limits[0]), 40)
        self.assertIn('os: [ubuntu-26.04, windows-latest]', checks)
        self.assertIn('fail-fast: false', checks)
        self.assertIn('python scripts/cooperation/cloud_checks.py --scope "$CHECK_SCOPE"', checks)
        upload = checks.split('- name: 上传检查报告', 1)[1]
        self.assertIn('if: always()', upload)
        self.assertIn('if-no-files-found: error', upload)
