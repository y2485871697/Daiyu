"""Offline revision guard tests: Git/Gradle are mocked, no dispatch or credentials."""
import os
from pathlib import Path
import re
import subprocess
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts/cooperation'))
import cloud_checks

SHA = 'a' * 40
OTHER_SHA = 'b' * 40


class CloudRevisionTests(unittest.TestCase):
    def test_matching_target_returns_exact_checkout(self):
        with patch.dict(os.environ, {'GITHUB_SHA': SHA, 'EXPECTED_SHA': SHA}, clear=True), \
             patch('cloud_checks.subprocess.check_output', return_value=SHA + '\n') as git:
            self.assertEqual(cloud_checks.verify_revision(), SHA)
        git.assert_called_once_with(['git', 'rev-parse', 'HEAD'], text=True)

    def test_empty_or_unset_manual_target_still_verifies_actual_identity(self):
        for target in ({}, {'EXPECTED_SHA': ''}):
            with self.subTest(target=target), \
                 patch.dict(os.environ, {'GITHUB_SHA': SHA, **target}, clear=True), \
                 patch('cloud_checks.subprocess.check_output', return_value=SHA) as git:
                self.assertEqual(cloud_checks.verify_revision(), SHA)
                git.assert_called_once()

    def test_invalid_target_rejected_without_git(self):
        for target in ('a' * 39, 'a' * 41, 'g' * 40, 'a' * 64,
                       'main', ' ' + SHA, SHA + ' ', SHA + '\n', ' ', '$(echo invalid)'):
            with self.subTest(target=target), \
                 patch.dict(os.environ, {'GITHUB_SHA': SHA, 'EXPECTED_SHA': target}, clear=True), \
                 patch('cloud_checks.subprocess.check_output') as git:
                with self.assertRaisesRegex(ValueError, '^invalid_expected_sha$'):
                    cloud_checks.verify_revision()
                git.assert_not_called()

    def test_full_but_different_target_rejected(self):
        with patch.dict(os.environ, {'GITHUB_SHA': SHA, 'EXPECTED_SHA': OTHER_SHA}, clear=True), \
             patch('cloud_checks.subprocess.check_output', return_value=SHA):
            with self.assertRaisesRegex(ValueError, '^checkout_sha_mismatch$'):
                cloud_checks.verify_revision()

    def test_checkout_mismatch_rejected_even_with_empty_target(self):
        # Matching either the event or checkout is not enough: all must agree.
        for target in ('', SHA, OTHER_SHA):
            with self.subTest(target=target), \
                 patch.dict(os.environ, {'GITHUB_SHA': SHA, 'EXPECTED_SHA': target}, clear=True), \
                 patch('cloud_checks.subprocess.check_output', return_value=OTHER_SHA):
                with self.assertRaisesRegex(ValueError, '^checkout_sha_mismatch$'):
                    cloud_checks.verify_revision()

    def test_missing_or_invalid_event_sha_rejected_for_manual_build(self):
        for event in ({}, {'GITHUB_SHA': ''}, {'GITHUB_SHA': 'main'}, {'GITHUB_SHA': 'a' * 39}):
            with self.subTest(event=event), patch.dict(os.environ, event, clear=True), \
                 patch('cloud_checks.subprocess.check_output') as git:
                with self.assertRaisesRegex(ValueError, '^invalid_github_sha$'):
                    cloud_checks.verify_revision()
                git.assert_not_called()

    def test_invalid_checkout_rejected(self):
        for head in ('', 'main', 'g' * 40):
            with self.subTest(head=head), \
                 patch.dict(os.environ, {'GITHUB_SHA': SHA}, clear=True), \
                 patch('cloud_checks.subprocess.check_output', return_value=head):
                with self.assertRaisesRegex(ValueError, '^invalid_checkout_sha$'):
                    cloud_checks.verify_revision()

    def test_git_failure_is_not_success(self):
        with patch.dict(os.environ, {'GITHUB_SHA': SHA}, clear=True), \
             patch('cloud_checks.subprocess.check_output',
                   side_effect=subprocess.CalledProcessError(128, ['git', 'rev-parse', 'HEAD'])):
            with self.assertRaises(subprocess.CalledProcessError):
                cloud_checks.verify_revision()

    def test_revision_only_cli_does_not_run_checks(self):
        with patch.dict(os.environ, {'GITHUB_SHA': SHA, 'EXPECTED_SHA': SHA}, clear=True), \
             patch('cloud_checks.subprocess.check_output', return_value=SHA) as git, \
             patch('cloud_checks.run') as checks:
            self.assertEqual(cloud_checks.main(['--verify-revision-only']), 0)
            git.assert_called_once()
            checks.assert_not_called()

    def test_default_cli_preserves_checks_scope_and_exit_code(self):
        with patch('cloud_checks.run', return_value=7) as checks:
            self.assertEqual(cloud_checks.main(['--scope', 'lint']), 7)
            checks.assert_called_once_with('lint')

    def test_revision_only_cli_propagates_rejection(self):
        with patch.dict(os.environ, {'GITHUB_SHA': SHA, 'EXPECTED_SHA': OTHER_SHA}, clear=True), \
             patch('cloud_checks.subprocess.check_output', return_value=SHA), \
             patch('cloud_checks.run') as checks:
            with self.assertRaisesRegex(ValueError, '^checkout_sha_mismatch$'):
                cloud_checks.main(['--verify-revision-only'])
            checks.assert_not_called()

    def test_checks_reuse_guard_before_any_work(self):
        with patch.dict(os.environ, {'GITHUB_SHA': SHA, 'EXPECTED_SHA': 'invalid'}, clear=True), \
             patch('cloud_checks.configuration') as configuration, \
             patch('cloud_checks.Path.mkdir') as mkdir, \
             patch('cloud_checks.subprocess.check_output') as git, \
             patch('cloud_checks.subprocess.run') as gradle:
            with self.assertRaisesRegex(ValueError, '^invalid_expected_sha$'):
                cloud_checks.run('unit')
            configuration.assert_not_called()
            mkdir.assert_not_called()
            git.assert_not_called()
            gradle.assert_not_called()


class BuildRevisionWorkflowTests(unittest.TestCase):
    def test_guard_is_first_after_checkout_and_cannot_be_skipped_on_failure(self):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/build.yml').read_text(encoding='utf-8')
        build = workflow.split('  build:', 1)[1].split('  smoke:', 1)[0]
        steps = re.split(r'(?m)^      - ', build)[1:]
        self.assertTrue(steps[0].startswith('name: 限定定向过滤仅用于smoke补检'))
        self.assertIn('shell: bash', steps[0])
        self.assertIn('run: test -z "$SMOKE_TEST_CLASSES" || test "$SMOKE_MODE" = smoke', steps[0])
        self.assertNotIn('if:', steps[0])
        self.assertNotIn('continue-on-error:', steps[0])
        steps = steps[1:]
        self.assertTrue(steps[0].startswith('uses: actions/checkout@'))
        self.assertIn('persist-credentials: false', steps[0])
        self.assertIn('EXPECTED_SHA: ${{ inputs.expected_sha }}', steps[1])
        self.assertIn('run: python3 scripts/cooperation/cloud_checks.py --verify-revision-only', steps[1])
        self.assertNotIn('if:', steps[1])
        self.assertNotIn('continue-on-error:', steps[1])
        self.assertTrue(steps[2].startswith('uses: actions/setup-java@'))

    def test_build_run_name_includes_dispatch_check_id(self):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/build.yml').read_text(encoding='utf-8')
        run_name = next(line for line in workflow.splitlines() if line.startswith('run-name:'))
        self.assertTrue(run_name.endswith("|| format('代鱼构建 {0}', inputs.check_id) }}"))
        self.assertIn("format('代鱼双API补检 {0}{1}', inputs.check_id, inputs.smoke_classes != '' && '-filtered' || '')", run_name)
        self.assertIn("format('代鱼检查 {0} {1}', inputs.check_scope, inputs.check_id)", run_name)


class SmokeRevisionWorkflowTests(unittest.TestCase):
    def test_guard_is_first_after_checkout_for_both_smoke_entry_modes(self):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/build.yml').read_text(encoding='utf-8')
        smoke = workflow.split('  smoke:\n', 1)[1]
        steps = re.split(r'(?m)^      - ', smoke)[1:]
        self.assertTrue(steps[0].startswith('name: 限定定向过滤仅用于smoke或candidate'))
        self.assertIn('shell: bash', steps[0])
        self.assertIn('test -z "$SMOKE_TEST_CLASSES" || test "$SMOKE_MODE" = smoke', steps[0])
        self.assertIn('if test "$SMOKE_MODE" = candidate; then', steps[0])
        self.assertIn('test -n "$SMOKE_TEST_CLASSES"', steps[0])
        self.assertNotIn('if:', steps[0])
        self.assertNotIn('continue-on-error:', steps[0])
        steps = steps[1:]
        self.assertTrue(steps[0].startswith('uses: actions/checkout@'))
        self.assertIn('persist-credentials: false', steps[0])
        self.assertIn('fetch-depth: 0', steps[0])
        self.assertNotRegex(steps[0], r'(?m)^\s+ref:')
        self.assertIn('EXPECTED_SHA: ${{ inputs.expected_sha }}', steps[1])
        self.assertIn('run: python3 scripts/cooperation/cloud_checks.py --verify-revision-only', steps[1])
        self.assertNotIn('if:', steps[1])
        self.assertNotIn('continue-on-error:', steps[1])
        self.assertIn('run: python3 scripts/acceptance/record_tool_tests.py --python python3', steps[2])

    def test_guard_preserves_existing_apk_reuse_without_rebuild_or_extra_download(self):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/build.yml').read_text(encoding='utf-8')
        smoke = workflow.split('  smoke:\n', 1)[1]
        self.assertEqual(smoke.count('uses: actions/download-artifact@'), 1)
        self.assertIn("run-id: ${{ inputs.mode == 'smoke' && inputs.source_run_id || github.run_id }}", smoke)
        self.assertIn('SOURCE_RUN_ID: ${{ inputs.source_run_id }}', smoke)
        self.assertIn('SOURCE_SHA: ${{ inputs.source_sha }}', smoke)
        self.assertNotIn('gradlew', smoke)
        self.assertNotIn('assemble', smoke)
        anchors = ('run: python3 scripts/cooperation/cloud_checks.py --verify-revision-only',
                   'uses: actions/download-artifact@', 'run: python3 scripts/verify_smoke_source.py',
                   'run: cd delivery && sha256sum -c SHA256SUMS', 'uses: ReactiveCircus/android-emulator-runner@')
        positions = [smoke.index(anchor) for anchor in anchors]
        self.assertEqual(positions, sorted(positions))


if __name__ == '__main__':
    unittest.main()
