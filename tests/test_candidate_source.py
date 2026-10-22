import hashlib
import io
import json
import os
import re
from pathlib import Path
import sys
import tempfile
import shutil
import unittest
import zipfile
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import candidate_package as candidate
import verify_smoke_source as source


class CandidateSourceTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / 'tests').mkdir()
        (self.root / 'candidate-aaaaaaaa.apk').write_bytes(b'app')
        (self.root / 'tests/platform-test.apk').write_bytes(b'test')
        classes = 'app.daiyu.android.FirstTest'
        self.env = dict(SOURCE_SHA='a' * 40, SOURCE_RUN_ID='123', SOURCE_VARIANT='candidate',
                        SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=classes)
        value = candidate.record(self.root, dict(GITHUB_SHA='a' * 40, GITHUB_RUN_ID='123',
                                 GITHUB_RUN_ATTEMPT='1', SMOKE_TEST_CLASSES=classes))
        (self.root / 'candidate.json').write_text(json.dumps(value))
        self.run = dict(id=123, run_attempt=1, head_sha='a' * 40, head_branch='main',
                        workflow_id=383377276, status='completed', conclusion='failure')
        self.jobs = [dict(name='定向候选编译（非正式交付）', conclusion='success')]
        self.current = (ROOT / '.github/workflows/build.yml').read_text()
        self.previous = source.candidate_workflow_inputs(self.current)

    def verify(self, **overrides):
        values = dict(env=self.env, root=self.root, source_run=self.run, jobs=self.jobs,
                      changed=['.github/workflows/build.yml', 'scripts/verify_smoke_source.py',
                               'tests/test_candidate_source.py', 'docs/重构清单.md', '更新说明.md'],
                      source_workflow=self.previous, current_workflow=self.current)
        values.update(overrides)
        return source.verify_candidate(**values)

    def test_source_identity_and_original_candidate_bytes(self):
        self.verify()
        self.verify(source_run=dict(self.run, conclusion='success'))
        for key, value in [('run_attempt', 2), ('run_attempt', True), ('status', 'in_progress'),
                           ('conclusion', 'cancelled'), ('conclusion', None), ('conclusion', 'timed_out'),
                           ('conclusion', 'skipped'), ('conclusion', 'neutral'),
                           ('conclusion', 'action_required'), ('head_sha', 'b' * 40),
                           ('head_branch', 'other'), ('workflow_id', 1), ('id', 124)]:
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                self.verify(source_run=dict(self.run, **{key: value}))
        with self.assertRaises(ValueError):
            self.verify(jobs=[dict(self.jobs[0], conclusion='failure')])
        for key, value in [('SOURCE_SHA', 'b' * 40), ('SOURCE_RUN_ID', '124'),
                           ('SMOKE_MODE', 'release'), ('SMOKE_TEST_CLASSES', ''),
                           ('SMOKE_TEST_CLASSES', 'app.daiyu.android.OtherTest')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.verify(env=dict(self.env, **{key: value}))
        for name in ['candidate-aaaaaaaa.apk', 'tests/platform-test.apk']:
            path = self.root / name
            original = path.read_bytes()
            path.write_bytes(b'changed')
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.verify()
            path.write_bytes(original)

    def test_workflow_only_permits_reviewed_infrastructure_diff(self):
        for old, new in [(':app:assembleRelease', ':app:assembleDebug'),
                         ('-gpu swiftshader', '-gpu host'),
                         ('"hw.lcd.density":"160"', '"hw.lcd.density":"525"'),
                         ('timeout-minutes: 160', 'timeout-minutes: 170'),
                         ('needs.release.result == \'success\'', 'true')]:
            self.assertIn(old, self.current)
            with self.subTest(old=old), self.assertRaises(ValueError):
                self.verify(current_workflow=self.current.replace(old, new))
        for path in ['app/src/main/Test.kt', 'app/src/androidTest/Test.kt', 'app/build.gradle.kts',
                     'core-domain/src/Test.kt', 'scripts/prepare_signing.py',
                     'tests/../app/build.gradle.kts', 'scripts/new-build-input.py']:
            with self.subTest(path=path), self.assertRaises(ValueError):
                self.verify(changed=[path])
        for name in ['构建记录.json', 'extra.apk', 'tests/extra.apk']:
            target = self.root / name
            target.write_bytes(b'x')
            with self.assertRaises(ValueError):
                self.verify()
            target.unlink()

    def archive(self):
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w') as zipped:
            for path in self.root.rglob('*'):
                if path.is_file():
                    zipped.writestr(path.relative_to(self.root).as_posix(), path.read_bytes())
        return output.getvalue()

    def test_archive_digest_file_set_and_extracted_bytes_are_bound(self):
        archive = self.archive()
        artifact = dict(digest='sha256:' + hashlib.sha256(archive).hexdigest())
        source.verify_candidate_archive(self.root, artifact, archive)
        with self.assertRaises(ValueError):
            source.verify_candidate_archive(self.root, dict(digest='sha256:' + '0' * 64), archive)
        (self.root / 'tests/platform-test.apk').write_bytes(b'tampered')
        with self.assertRaises(ValueError):
            source.verify_candidate_archive(self.root, artifact, archive)
        (self.root / 'tests/platform-test.apk').write_bytes(b'test')
        source.verify_candidate_archive(self.root, artifact, archive)
        (self.root / 'extra.txt').write_bytes(b'extra')
        with self.assertRaises(ValueError):
            source.verify_candidate_archive(self.root, artifact, archive)

    def main_fixture(self, artifacts=None, archive_override=None):
        archive = self.archive()
        artifact = dict(id=456, name='代鱼定向候选包-filtered', expired=False,
                        workflow_run=dict(id=123, head_sha='a' * 40),
                        digest='sha256:' + hashlib.sha256(archive).hexdigest(),
                        archive_download_url='https://api.github.com/repos/owner/repo/actions/artifacts/456/zip')
        metadata = artifacts(artifact) if artifacts else dict(total_count=1, artifacts=[artifact])
        parent = self.root / 'main-fixture'
        parent.mkdir()
        delivery = parent / 'delivery'
        delivery.mkdir()
        for path in self.root.glob('*'):
            if path.is_file():
                shutil.copy(path, delivery / path.name)
        shutil.copytree(self.root / 'tests', delivery / 'tests')
        (parent / '.github/workflows').mkdir(parents=True)
        (parent / '.github/workflows/build.yml').write_text(self.current)
        env = dict(self.env, GITHUB_SHA='b' * 40, GITHUB_RUN_ID='789', GITHUB_RUN_ATTEMPT='1',
                   GITHUB_REPOSITORY='owner/repo', GH_TOKEN='fixture-not-a-secret')
        responses = [self.run, dict(jobs=self.jobs), metadata]
        def open_url(request, timeout):
            if request.full_url.endswith('/zip'):
                self.assertNotIn('Authorization', request.headers)
                self.assertIn('Authorization', request.unredirected_hdrs)
                return io.BytesIO(archive if archive_override is None else archive_override)
            return io.BytesIO(json.dumps(responses.pop(0)).encode())
        previous = os.getcwd()
        try:
            os.chdir(parent)
            with patch.dict(os.environ, env, clear=True), \
                    patch.object(source.urllib.request, 'urlopen', side_effect=open_url), \
                    patch.object(source.subprocess, 'run') as run_git, \
                    patch.object(source.subprocess, 'check_output', side_effect=[
                        '.github/workflows/build.yml\0scripts/verify_smoke_source.py\0', self.previous]):
                source.main()
                run_git.assert_called_once_with(['git', 'merge-base', '--is-ancestor', 'a' * 40, 'b' * 40], check=True)
            return json.loads((parent / 'smoke/source.json').read_text())
        finally:
            if sys.exc_info()[0] is not None:
                self.assertFalse((parent / 'smoke/source.json').exists())
            os.chdir(previous)

    def test_authorization_only_reaches_initial_api_not_redirect_storage(self):
        request = source.urllib.request.Request('https://api.github.com/repos/owner/repo/actions/artifacts/456/zip',
                                               headers={'Accept': 'application/vnd.github+json'})
        request.add_unredirected_header('Authorization', 'Bearer fixture-not-a-secret')
        redirected = source.urllib.request.HTTPRedirectHandler().redirect_request(
            request, None, 302, 'Found', {}, 'https://results-receiver.actions.githubusercontent.com/fixture')
        self.assertTrue(request.has_header('Authorization'))
        self.assertFalse(redirected.has_header('Authorization'))
        self.assertEqual(redirected.get_header('Accept'), 'application/vnd.github+json')

    def test_main_preserves_build_and_check_identity_and_raw_artifact_digest(self):
        receipt = self.main_fixture()
        self.assertEqual(receipt['build_sha'], 'a' * 40)
        self.assertEqual(receipt['check_sha'], 'b' * 40)
        self.assertEqual(receipt['build_attempt'], 1)
        self.assertIs(receipt['formal_acceptance'], False)
        self.assertEqual(receipt['source_artifact']['id'], 456)

    def test_main_rejects_wrong_artifact_run_before_archive_fetch(self):
        with self.assertRaisesRegex(ValueError, '归属不符'):
            self.main_fixture(artifacts=lambda item: dict(total_count=1, artifacts=[
                dict(item, workflow_run=dict(id=124, head_sha='a' * 40))]))

    def test_main_rejects_wrong_artifact_sha(self):
        with self.assertRaisesRegex(ValueError, '归属不符'):
            self.main_fixture(artifacts=lambda item: dict(total_count=1, artifacts=[
                dict(item, workflow_run=dict(id=123, head_sha='b' * 40))]))

    def test_main_rejects_expired_artifact(self):
        with self.assertRaisesRegex(ValueError, '不唯一'):
            self.main_fixture(artifacts=lambda item: dict(total_count=1, artifacts=[dict(item, expired=True)]))

    def test_main_rejects_incomplete_artifact_page(self):
        with self.assertRaisesRegex(ValueError, '分页不完整'):
            self.main_fixture(artifacts=lambda item: dict(total_count=2, artifacts=[item]))

    def test_main_rejects_wrong_artifact_digest(self):
        with self.assertRaisesRegex(ValueError, '归档摘要不符'):
            self.main_fixture(artifacts=lambda item: dict(total_count=1, artifacts=[
                dict(item, digest='sha256:' + '0' * 64)]))

    def test_main_rejects_ambiguous_artifact(self):
        with self.assertRaisesRegex(ValueError, '不唯一'):
            self.main_fixture(artifacts=lambda item: dict(total_count=2, artifacts=[item, dict(item, id=457)]))

    def test_actual_matrix_selection_for_all_modes_and_requested_apis(self):
        smoke = self.current.split('  smoke:\n', 1)[1]
        expression = re.search(r'include: \$\{\{ fromJSON\((.*?)\) \}\}', smoke).group(1)
        for mode in ['build', 'release', 'candidate', 'smoke']:
            for variant in ['debug', 'release', 'candidate']:
                for api in ['all', '26', '37.2']:
                    value = expression
                    for key, item in [('mode', mode), ('source_variant', variant), ('smoke_api', api)]:
                        value = value.replace('inputs.' + key, repr(item))
                    matrix = json.loads(eval(value.replace('&&', ' and ').replace('||', ' or '),
                                             {'__builtins__': {}}, {}))
                    single = mode == 'candidate' or (mode == 'smoke' and variant == 'candidate')
                    apis = [api] if api != 'all' and mode in ['candidate', 'smoke'] else ['26', '37.2']
                    shards = [0] if single else range(4)
                    self.assertEqual({(item['api'], item['shard']) for item in matrix},
                                     {(a, shard) for a in apis for shard in shards}, (mode, variant, api))
                    for item in matrix:
                        self.assertEqual(item['target'], 'default' if item['api'] == '26' else 'google_apis_ps16k')
                        self.assertEqual(item['ram'], 4096 if item['api'] == '26' else 6144)

    def test_workflow_wires_candidate_download_and_single_shard_without_rebuild(self):
        smoke = self.current.split('  smoke:\n', 1)[1]
        condition = "(inputs.mode == 'candidate' || (inputs.mode == 'smoke' && inputs.source_variant == 'candidate'))"
        self.assertIn(condition + " && '代鱼定向候选包-filtered'", smoke)
        self.assertIn('SMOKE_SHARD_COUNT: ${{ ' + condition + ' && 1 || 4 }}', smoke)
        self.assertNotIn('gradlew', smoke)
        self.assertIn('target: ${{ matrix.target }}', smoke)
        self.assertIn('options: [debug, release, candidate]', self.current)
        with self.assertRaises(ValueError):
            source.verify(self.env, {}, self.run, self.jobs, [])


if __name__ == '__main__':
    unittest.main()
