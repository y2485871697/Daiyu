"""Source ownership guard, supplementary to Runner/dispatcher execution tests."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class RunnerOwnerContractTest(unittest.TestCase):
    def test_only_runner_issues_live_execution_owner_in_production(self):
        callers = []
        for part in ("core-domain/src/main", "app/src/main", "components/openminis-browser/adapter/src/main"):
            for path in (ROOT / part).rglob("*.kt"):
                if "RunExecutionOwner.issue(" in path.read_text():
                    callers.append(path.relative_to(ROOT).as_posix())
        self.assertEqual(["core-domain/src/main/kotlin/app/daiyu/android/domain/ChatRunner.kt"], callers)
        token = (ROOT / "core-domain/src/main/kotlin/app/daiyu/android/domain/runtime/RunExecutionOwner.kt").read_text()
        self.assertIn("class RunExecutionOwner private constructor(", token)
        self.assertNotIn("data class RunExecutionOwner", token)
        self.assertIn("internal fun issue(", token)
