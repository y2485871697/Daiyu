import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts/cooperation'))
from evidence import SCOPES, UNIT_REPORTS, atomic_json, coverage, digest, verify_bundle


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
                for module in UNIT_REPORTS:
                    z.writestr(module + '/TEST-demo.xml', '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase name="ok"/></testsuite>')
            record = dict(check_id=f'github-42-{os_name}', check_attempt=1, source_sha=self.sha,
                          config_id=self.config, scope='unit', task=SCOPES['unit'], exit_code=0,
                          result='passed', incremental_base_sha=self.sha, environment={'os': os_name},
                          command=['gradlew', *SCOPES['unit'].split()], apk_built=False, package_scan='passed',
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
                    workflow_id=397560064, head_branch='main', repository={'full_name': 'y2485871697/Daiyu'}))
        atomic_json(self.base / 'complete.json', dict(source_sha=self.sha, attempt=1, scopes=['unit'], platforms=['ubuntu-26.04', 'windows-latest']))
        self.linux = self.state / 'evidence/github-42-1-ubuntu-26.04/unit'
        self.windows = self.state / 'evidence/github-42-1-windows-latest/unit'

    def tearDown(self):
        self.tmp.cleanup()

    def replace_reports(self, folder, *, skip=False, omit=False):
        from evidence import verify_xml
        from test_windows_unit_skips import CLASS, METHOD
        import xml.etree.ElementTree as ET
        aid = 1 if folder == self.linux else 2
        with zipfile.ZipFile(folder / 'reports.zip', 'w') as z:
            for index, module in enumerate(UNIT_REPORTS):
                suite = ET.Element('testsuite', tests='1', failures='0', errors='0', skipped='0')
                ET.SubElement(suite, 'testcase', classname='Portable', name='ok')
                if index == 1 and not omit:
                    suite.set('tests', '2')
                    case = ET.SubElement(suite, 'testcase', classname=CLASS, name=METHOD)
                    if skip:
                        suite.set('skipped', '1')
                        ET.SubElement(case, 'skipped', message='POSIX unavailable')
                z.writestr(module + '/TEST-demo.xml', ET.tostring(suite))
        record = json.loads((folder / 'record.json').read_text())
        record['report_sha256'] = digest(folder / 'reports.zip')
        if not (skip and folder == self.linux):
            record['report_summary'] = verify_xml(folder / 'reports.zip', 'unit', platform=record['environment']['os'])
        atomic_json(folder / 'record.json', record)
        archive = self.base / f'{aid}.zip'
        with zipfile.ZipFile(archive, 'w') as z:
            for name in ('record.json', 'reports.zip', 'check.log'):
                z.write(folder / name, 'unit/' + name)
        artifact = json.loads((self.base / f'{aid}.json').read_text())
        artifact['digest'] = 'sha256:' + digest(archive)
        atomic_json(self.base / f'{aid}.json', artifact)
        proof = json.loads((folder / 'proof.json').read_text())
        proof['artifact_sha256'] = digest(archive)
        proof['files'] = {n: digest(folder / n) for n in ('record.json', 'reports.zip', 'check.log')}
        atomic_json(folder / 'proof.json', proof)

    def test_windows_skips_require_linux_execution_from_either_matrix_member(self):
        self.replace_reports(self.windows, skip=True)
        self.replace_reports(self.linux)
        for folder in (self.windows, self.linux):
            verify_bundle(folder, self.sha, self.config, 'unit')
        self.assertEqual(list(coverage(self.state, self.sha, self.config)), ['unit'])
        self.replace_reports(self.linux, omit=True)
        for folder in (self.windows, self.linux):
            with self.assertRaisesRegex(ValueError, 'windows_skip_without_linux_execution'):
                verify_bundle(folder, self.sha, self.config, 'unit')
        self.assertEqual(coverage(self.state, self.sha, self.config), {})

    def test_linux_skip_cannot_back_windows_skip(self):
        self.replace_reports(self.windows, skip=True)
        self.replace_reports(self.linux, skip=True)
        self.assertEqual(coverage(self.state, self.sha, self.config), {})

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
