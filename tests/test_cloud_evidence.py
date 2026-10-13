import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts/cooperation'))
from evidence import atomic_json, coverage, digest, verify_bundle


class CloudEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.state = Path(self.tmp.name)
        self.sha, self.config = 'a' * 40, 'b' * 64
        self.base = self.state / 'cloud/42'
        self.base.mkdir(parents=True)
        jobs = []
        for aid, platform, os_name in ((1, 'ubuntu-26.04', 'Linux'), (2, 'windows-latest', 'Windows')):
            folder = self.state / f'evidence/github-42-1-{platform}/unit'
            folder.mkdir(parents=True)
            (folder / 'check.log').write_text('BUILD SUCCESSFUL')
            with zipfile.ZipFile(folder / 'reports.zip', 'w') as z:
                z.writestr('TEST-demo.xml', '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase name="ok"/></testsuite>')
            record = dict(check_id=f'github-42-{os_name}', check_attempt=1, source_sha=self.sha,
                          config_id=self.config, scope='unit', task=':core-domain:test', exit_code=0,
                          result='passed', incremental_base_sha=self.sha, environment={'os': os_name},
                          command=['gradlew', ':core-domain:test'], apk_built=False, package_scan='passed',
                          log_sha256=digest(folder / 'check.log'), report_sha256=digest(folder / 'reports.zip'))
            atomic_json(folder / 'record.json', record)
            archive = self.base / f'{aid}.zip'
            with zipfile.ZipFile(archive, 'w') as z:
                for name in ('record.json', 'reports.zip', 'check.log'):
                    z.write(folder / name, 'unit/' + name)
            atomic_json(self.base / f'{aid}.json', dict(id=aid, name='代鱼检查报告-' + platform, digest='sha256:' + digest(archive)))
            atomic_json(folder / 'proof.json', dict(origin='github', run_id=42, attempt=1, artifact_id=aid,
                        artifact_sha256=digest(archive), job_id=aid,
                        files={n: digest(folder / n) for n in ('record.json', 'reports.zip', 'check.log')}))
            jobs.append(dict(id=aid, name='只检查 ' + platform, status='completed', conclusion='success', run_attempt=1))
        atomic_json(self.base / 'jobs.json', jobs)
        atomic_json(self.base / 'run.json', dict(id=42, run_attempt=1, head_sha=self.sha, status='completed', conclusion='success',
                    workflow_id=383377276, head_branch='main', repository={'full_name': 'y2485871697/Daiyu'}))
        atomic_json(self.base / 'complete.json', dict(source_sha=self.sha, attempt=1, scopes=['unit'], platforms=['ubuntu-26.04', 'windows-latest']))
        self.linux = self.state / 'evidence/github-42-1-ubuntu-26.04/unit'
        self.windows = self.state / 'evidence/github-42-1-windows-latest/unit'

    def tearDown(self):
        self.tmp.cleanup()

    def test_both_platforms_pass_and_restart(self):
        self.assertEqual(list(coverage(self.state, self.sha, self.config)), ['unit'])
        self.assertEqual(list(coverage(self.state, self.sha, self.config)), ['unit'])

    def test_missing_windows_bundle_rejects_matrix(self):
        (self.windows / 'record.json').unlink()
        self.assertEqual(coverage(self.state, self.sha, self.config), {})

    def test_corrupt_windows_archive_rejects_linux(self):
        (self.base / '2.zip').write_bytes(b'tampered')
        self.assertEqual(coverage(self.state, self.sha, self.config), {})

    def test_missing_completion_rejects(self):
        (self.base / 'complete.json').unlink()
        self.assertEqual(coverage(self.state, self.sha, self.config), {})

    def test_unknown_origin_rejects(self):
        path = self.linux / 'proof.json'
        proof = json.loads(path.read_text(encoding='utf-8')); proof['origin'] = 'untrusted'
        atomic_json(path, proof)
        with self.assertRaisesRegex(ValueError, 'unsupported_evidence_origin'):
            verify_bundle(self.linux, self.sha, self.config, 'unit')
        self.assertEqual(coverage(self.state, self.sha, self.config), {})

    def test_job_platform_mismatch_rejects(self):
        path = self.base / 'jobs.json'
        jobs = json.loads(path.read_text(encoding='utf-8')); jobs[1]['name'] = '只检查 ubuntu-26.04'
        atomic_json(path, jobs)
        self.assertEqual(coverage(self.state, self.sha, self.config), {})

    def test_wrong_attempt_rejects(self):
        path = self.base / 'run.json'
        run = json.loads(path.read_text(encoding='utf-8')); run['run_attempt'] = 2
        atomic_json(path, run)
        self.assertEqual(coverage(self.state, self.sha, self.config), {})

    def test_peer_cannot_change_origin(self):
        path = self.windows / 'proof.json'
        proof = json.loads(path.read_text(encoding='utf-8')); proof['origin'] = 'windows-pc'
        atomic_json(path, proof)
        self.assertEqual(coverage(self.state, self.sha, self.config), {})
