from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
TERMINAL = ROOT / "app/src/main/kotlin/app/daiyu/android/terminal"


class LinuxStatusPinContractTest(unittest.TestCase):
    def test_trusted_status_validates_without_acquiring_a_lease(self):
        source = (TERMINAL / "TerminalEnvironmentHost.kt").read_text()
        source = source[source.index("internal class TerminalTrustedLinuxSelection("):]
        status = source[source.index("fun status():"):source.index("fun select(")]
        self.assertIn("store.inspectTrustedSelection(trusted)", status)
        self.assertNotIn("withSelection(", status)
        self.assertNotIn("store.acquire(", status)

    def test_inspection_checks_both_pin_domains_without_claiming(self):
        source = (TERMINAL / "LinuxEnvironmentStore.kt").read_text()
        inspect = source[source.index("internal fun inspectTrustedSelection"):source.index("/** A lease freezes")]
        self.assertIn("runnerPins.admitted", inspect)
        self.assertIn("load() == expected", inspect)
        self.assertIn("validate(expected)", inspect)
        self.assertIn("rootfsAccess?.inspectAdmission()", inspect)
        common = (TERMINAL / "LinuxRootfsBackupAccess.kt").read_text()
        inspect += common[common.index("@Synchronized fun inspectAdmission"):common.index("@Synchronized fun acquire")]
        self.assertNotIn(".claim(", inspect)
        self.assertNotIn(".acquire(", inspect)
        self.assertNotIn(".delete(", inspect)

    def test_model_binding_uses_actual_attempt_not_a_false_unconfigured_label(self):
        source = (ROOT / "app/src/main/kotlin/app/daiyu/android/ChatController.kt").read_text()
        factory = source[source.index("commandFactory = if (RunCommandTools.CAPABILITY"):]
        factory = factory[:factory.index("authorizationFactory =")]
        self.assertIn("host?.linuxStateOnWorker()", factory)
        self.assertIn("CommandLinuxBinding.freeze(commandLinuxState, reference)", factory)
        self.assertIn("linuxState = linuxBinding.state", factory)
        command = (ROOT / "app/src/main/kotlin/app/daiyu/android/integration/tools/RunCommandTools.kt").read_text()
        self.assertNotIn("未配置", command)
        self.assertIn("linux_runner_generation_unknown", command)
        self.assertIn("linux_workspace_generation_unavailable", command)
        self.assertIn("catch (cancelled: java.util.concurrent.CancellationException)", command)


if __name__ == "__main__":
    unittest.main()
