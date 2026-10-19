import json
import os
from pathlib import Path
import re
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import candidate_package as candidate


class CandidatePackageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'tests').mkdir()
        (self.root / ('candidate-' + 'a' * 8 + '.apk')).write_bytes(b'app')
        (self.root / 'tests/platform-test.apk').write_bytes(b'test')
        self.env = dict(GITHUB_SHA='a' * 40, GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='1',
                        SMOKE_TEST_CLASSES='app.daiyu.android.FirstTest')
        self.value = candidate.record(self.root, self.env)
        (self.root / 'candidate.json').write_text(json.dumps(self.value))

    def test_exact_identity_and_hashes(self):
        candidate.verify(self.root, self.env)
        self.assertIs(self.value['formal_acceptance'], False)
        self.assertIs(self.value['production_delivery'], False)
        for key in ('GITHUB_SHA', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'SMOKE_TEST_CLASSES'):
            changed = dict(self.env)
            changed[key] = 'b' * 40 if key == 'GITHUB_SHA' else 'app.daiyu.android.OtherTest' if key == 'SMOKE_TEST_CLASSES' else '2'
            with self.subTest(key=key), self.assertRaises(ValueError):
                candidate.verify(self.root, changed)
        (self.root / 'tests/platform-test.apk').write_bytes(b'changed')
        with self.assertRaises(ValueError):
            candidate.verify(self.root, self.env)

    def test_formal_record_and_extra_apk_rejected(self):
        for name in ('构建记录.json', 'extra.apk', 'tests/extra.apk'):
            path = self.root / name
            path.write_bytes(b'x')
            with self.assertRaises(ValueError):
                candidate.verify(self.root, self.env)
            path.unlink()

    def test_create_verifies_both_certificates_before_publication(self):
        output = 'Number of signers: 1\nSigner #1 certificate SHA-256 digest: ' + candidate.EXPECTED_CERT + '\nVerified using v2 scheme (APK Signature Scheme v2): true\n'
        with patch('candidate_package.subprocess.run') as run:
            run.return_value.stdout = output
            candidate.create(self.root, self.env, 'apksigner')
            self.assertEqual(run.call_count, 2)
        candidate.verify(self.root, self.env)
        (self.root / 'candidate.json').unlink()
        with patch('candidate_package.subprocess.run') as run:
            run.return_value.stdout = 'wrong certificate'
            with self.assertRaises(ValueError):
                candidate.create(self.root, self.env, 'apksigner')
        self.assertFalse((self.root / 'candidate.json').exists())

    def test_workflow_isolates_candidate_and_keeps_formal_matrix(self):
        workflow = (ROOT / '.github/workflows/build.yml').read_text()
        body = workflow.split('  candidate:\n', 1)[1].split('  smoke:\n', 1)[0]
        self.assertIn('定向候选编译（非正式交付）', body)
        self.assertIn(':app:assembleDebug :app:assembleDebugAndroidTest', body)
        self.assertIn('--verify-revision-only', body)
        self.assertIn('test -z "$SOURCE_RUN_ID"', body)
        self.assertIn('test -z "$SOURCE_SHA"', body)
        self.assertIn('test "$SOURCE_VARIANT" = debug', body)
        self.assertNotIn('代鱼开发包', body)
        self.assertNotIn('代鱼正式候选包', body)
        self.assertNotIn('assembleRelease', body)
        self.assertNotIn('record_tool_tests.py', body)
        smoke = workflow.split('  smoke:\n', 1)[1]
        arrays = [json.loads(x) for x in re.findall(r"'(\[[^']*\])'", smoke)]
        self.assertEqual([len(v) for v in arrays[:3]], [1, 1, 2])
        self.assertEqual({(v['api'], v['shard']) for v in arrays[-1]},
                         {(api, shard) for api in ('26', '37.2') for shard in range(4)})
        self.assertNotIn('gradlew', smoke)
        self.assertIn("inputs.mode == 'candidate' && needs.candidate.result == 'success'", smoke)
        self.assertIn('candidate_package.py verify --directory delivery', smoke)


if __name__ == '__main__':
    unittest.main()
