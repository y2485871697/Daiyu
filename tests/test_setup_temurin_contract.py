"""The primary CI workflow uses a bounded, fail-closed Temurin retry."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
PIN = "actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6"


class SetupTemurinContractTests(unittest.TestCase):
    def test_composite_retries_three_times_and_fails_if_all_attempts_fail(self):
        action = (ROOT / ".github/actions/setup-temurin/action.yml").read_text(encoding="utf-8")
        self.assertEqual(action.count("uses: " + PIN), 3)
        self.assertIn("default: |\n      21\n      27", action)
        self.assertEqual(action.count("check-latest: false"), 3)
        self.assertIn("steps.temurin1.outcome != 'success'", action)
        self.assertIn("steps.temurin2.outcome != 'success'", action)
        self.assertIn("steps.temurin3.outcome != 'success'", action)
        self.assertIn("Temurin setup failed after three attempts", action)

    def test_build_workflow_uses_retry_action_at_each_java_setup_site(self):
        workflow = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
        self.assertEqual(workflow.count("uses: ./.github/actions/setup-temurin"), 5)
        self.assertNotIn(PIN, workflow)


if __name__ == "__main__":
    unittest.main()
