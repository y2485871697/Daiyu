"""Compile-only Release must never silently schedule the full validation batch."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]

class CompileReleaseContract(unittest.TestCase):
    def setUp(self):
        self.workflow = (ROOT / '.github/workflows/build.yml').read_text()
        self.release = self.workflow.split('  release:\n', 1)[1].split('  synthetic:\n', 1)[0]
        self.smoke = self.workflow.split('  smoke:\n', 1)[1]

    def test_compile_is_the_explicit_default_and_reuses_release_build(self):
        self.assertIn('          - compile\n', self.workflow)
        self.assertIn('        default: compile\n', self.workflow)
        self.assertIn("inputs.mode == 'daily' || inputs.mode == 'compile'", self.release)
        self.assertIn('-PdaiyuProductionRelease=true :app:assembleRelease :app:assembleReleaseAndroidTest', self.release)
        self.assertIn('scripts/prepare_signing.py', self.release)
        self.assertIn('scripts/verify_release_package.py', self.release)
        self.assertIn('scripts/verify_license_bundle.py --apk', self.release)

    def test_compile_skips_full_tools_unit_and_lint(self):
        self.assertIn('if test "$SMOKE_MODE" != compile; then python3 scripts/acceptance/record_tool_tests.py --python python3; fi', self.release)
        self.assertIn("      - name: 单测与 Lint\n        if: inputs.mode != 'compile'", self.release)
        self.assertIn('python3 scripts/verify_boundaries.py', self.release)
        self.assertIn('if: always()', self.release)

    def test_compile_is_not_a_smoke_mode(self):
        guard = self.smoke.split('    runs-on:', 1)[0]
        self.assertIn("inputs.mode == 'release' || inputs.mode == 'daily'", guard)
        self.assertNotIn("inputs.mode == 'compile'", guard)
        self.assertNotIn('contains(', guard)

    def test_compile_artifact_records_the_actual_validation_scope(self):
        self.assertIn("'代鱼增量Release包'", self.release)
        self.assertIn('validation_scope="compile-only"', self.release)
        self.assertIn('full_regression_passed=False, unit_tests_run=False, instrumentation_run=False', self.release)
        self.assertIn('sha256sum *.apk tests/*.apk', self.release)

if __name__ == '__main__':
    unittest.main()
