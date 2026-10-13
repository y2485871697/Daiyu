import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts/cooperation'))
from evidence import atomic_json, coverage, digest, verify_bundle, validate_record
from cooperate import cloud_fill, locked, run_scope


class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.state = Path(self.tmp.name)
        self.folder = self.state / 'evidence/pc-1/unit'
        self.folder.mkdir(parents=True)
        (self.folder / 'check.log').write_text('BUILD SUCCESSFUL')
        with zipfile.ZipFile(self.folder / 'reports.zip', 'w') as z:
            z.writestr('TEST-demo.xml', '<testsuite tests="2" errors="0" failures="0" skipped="0"/>')
        self.record = dict(check_id='pc-demo', check_attempt=2, source_sha='a'*40,
            config_id='b'*64, scope='unit', task=':core-domain:test', exit_code=0, result='passed',
            incremental_base_sha='a'*40, environment={'jdk':'27'}, command=['gradlew',':core-domain:test'],
            log_sha256=digest(self.folder / 'check.log'), report_sha256=digest(self.folder / 'reports.zip'))
        self.save()

    def save(self):
        atomic_json(self.folder/'status.json',dict(source_sha='a'*40,config_id='b'*64,check_attempt=2,result='passed',exit_code=0,package_scan='passed'))
        atomic_json(self.folder / 'record.json', self.record)
        atomic_json(self.folder / 'proof.json', dict(origin='windows-pc', files={n:digest(self.folder/n)
                    for n in ('record.json','reports.zip','check.log')}))

    def tearDown(self):
        self.tmp.cleanup()

    def test_pass_and_restart_rehash(self):
        self.assertEqual(verify_bundle(self.folder,'a'*40,'b'*64,'unit')['tests_passed'],2)
        self.assertEqual(list(coverage(self.state,'a'*40,'b'*64)),['unit'])

    def test_sha_mismatch(self):
        self.assertEqual(coverage(self.state,'c'*40,'b'*64),{})

    def test_config_mismatch(self):
        self.assertEqual(coverage(self.state,'a'*40,'c'*64),{})

    def test_tamper(self):
        (self.folder/'check.log').write_text('tampered')
        with self.assertRaisesRegex(ValueError,'hash_mismatch'):
            verify_bundle(self.folder,'a'*40,'b'*64,'unit')

    def test_failed_record_not_missing(self):
        self.record.update(exit_code=1,result='failed');self.save()
        with self.assertRaisesRegex(ValueError,'known_check_failure'):
            coverage(self.state,'a'*40,'b'*64)

    def test_missing_artifact(self):
        (self.folder/'reports.zip').unlink()
        self.assertEqual(coverage(self.state,'a'*40,'b'*64),{})

    def test_check_task_mismatch(self):
        self.record['task']=':app:assembleDebug';self.save()
        with self.assertRaises(ValueError):verify_bundle(self.folder,'a'*40,'b'*64,'unit')

    def test_unknown_dispatch_never_replays(self):
        atomic_json(self.state/'cloud-dispatch.json',dict(source_sha='a'*40,scope='lint',token='unknown'))
        with patch('cooperate.cloud_candidates',return_value=[]),patch('cooperate.subprocess.run') as run:
            with self.assertRaisesRegex(RuntimeError,'unknown_do_not_repeat'):
                cloud_fill(self.state,'a'*40,'b'*64)
            run.assert_not_called()

    def test_lock_refuses_parallel_coordinator(self):
        with locked(self.state):
            with self.assertRaises(BlockingIOError):
                with locked(self.state):pass

    def test_run_mode_identification(self):
        self.assertEqual(run_scope({'display_title':'代鱼检查 lint token'}),'lint')
        self.assertIsNone(run_scope({'display_title':'代鱼构建'}))


if __name__ == '__main__':unittest.main()
