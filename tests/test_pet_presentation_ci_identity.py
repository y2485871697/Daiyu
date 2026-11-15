"""Frozen pet checks retain the real source and workflow identities."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class PetPresentationCiIdentityTest(unittest.TestCase):
    def setUp(self):
        self.workflow = (ROOT / '.github/workflows/pet-presentation-checks.yml').read_text()

    def test_checkout_and_ancestor_guard_precede_tools_and_gradle(self):
        source = self.workflow.split('      - name: 固定检查接线源码', 1)[0]
        for fragment in ('ref: ${{ inputs.expected_sha }}', 'fetch-depth: 0',
                         'persist-credentials: false', '[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]]',
                         'test "$(git rev-parse HEAD)" = "$EXPECTED_SHA"',
                         'git merge-base --is-ancestor "$EXPECTED_SHA" "$GITHUB_SHA"'):
            self.assertIn(fragment, source)
        self.assertNotIn('continue-on-error', self.workflow)
        self.assertLess(self.workflow.index('核对准确源码'), self.workflow.index('setup-temurin'))

    def test_legacy_guard_override_is_subprocess_only(self):
        self.assertIn('GITHUB_SHA="$EXPECTED_SHA" python3 scripts/cooperation/cloud_checks.py --verify-revision-only', self.workflow)
        self.assertNotIn('GITHUB_SHA:', self.workflow)
        self.assertNotIn('GITHUB_ENV', self.workflow)

    def test_current_ci_contracts_run_from_separate_workflow_checkout(self):
        tools = self.workflow.split('      - name: 固定检查接线源码', 1)[1]
        for fragment in ('ref: ${{ github.sha }}', 'path: .pet-validation-tools',
                         'working-directory: .pet-validation-tools',
                         'PYTHONPATH=tests python3 -B -m unittest test_pet_presentation_ci_identity -v'):
            self.assertIn(fragment, tools)

    def test_receipt_records_checkout_and_workflow_separately(self):
        for fragment in ("subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()",
                         "assert source_sha == os.environ['EXPECTED_SHA']",
                         "source_sha=source_sha, workflow_sha=os.environ['GITHUB_SHA']",
                         "check_id=os.environ['CHECK_ID']", "run_id=os.environ['GITHUB_RUN_ID']",
                         "attempt=int(os.environ['GITHUB_RUN_ATTEMPT'])", 'instrumentation_run=False'):
            self.assertIn(fragment, self.workflow)
        self.assertEqual(3, self.workflow.count('checks_only.init.gradle'))
        self.assertNotIn('assemble', self.workflow)
        self.assertNotIn('discover', self.workflow)
