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

    def test_metadata_reservation_promotes_only_at_execution_and_keeps_unstarted_cleanup(self):
        source = (TERMINAL / "LinuxEnvironmentStore.kt").read_text()
        acquire = source[source.index("private fun acquireSelected"):source.index("private fun selectionPathIdentityUnavailable")]
        self.assertIn("rootfsAccess?.reserve(installation, identities)", acquire)
        self.assertNotIn("rootfsAccess?.acquire(", acquire)
        claim = source[source.index("internal fun claimExecution()"):source.index("internal class BackupHandle")]
        self.assertLess(claim.index("rootAccess?.claimExecution()"), claim.index("runnerPins.claim("))
        for token in ("unstartedRootExecution = rootExecution", "closeUnstartedClaim()", "failure.addSuppressed(cleanup)"):
            self.assertIn(token, claim)

    def test_retained_shared_admission_never_weakens_exclusive_management(self):
        pins = (TERMINAL / "LinuxRunnerGenerationPins.kt").read_text()
        for token in ('allowRetained: Boolean = false', 'retained.forEach(::validateRetained)',
                      'pin[0] != "2"', 'pin[5] != "shared"', 'record[1] != currentBoot()',
                      'record[3] != hash(bytes)', 'fileKey(file.toPath())?.let(::encodedKey) != record[4]',
                      'check(pinFiles().size < MAX_PINS)', 'guard.unsettled.add(id)'):
            self.assertIn(token, pins)
        self.assertIn('if (requireEmpty) "exclusive" else "shared"', pins)
        store = (TERMINAL / "LinuxEnvironmentStore.kt").read_text()
        for start, end in (("fun select(", "fun clearSelection("),
                           ("fun clearSelection(", "fun status("),
                           ("internal fun acquireRootfsBackup(", "private fun validate(")):
            section = store[store.index(start):store.index(end)]
            self.assertIn('runnerPins.admitted(requireEmpty = true)', section)
            self.assertNotIn('allowRetained = true', section)
        common = (TERMINAL / "LinuxRootfsBackupAccess.kt").read_text()
        self.assertIn('requireEmpty = export, allowRetained = !export', common)
        self.assertIn('requireNoLegacyPins()', common)
        for token in ('inspectTrustedSelection', 'acquireSelected', 'claimExecution'):
            self.assertIn(token, store)
        self.assertEqual(4, store.count('allowRetained = true'))

    def test_upgrade_observation_is_background_only_and_boot_identity_is_android_wired(self):
        source = (TERMINAL / "LinuxBootRecoveryReceiver.kt").read_text()
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        self.assertIn('android:name=".terminal.LinuxBootRecoveryReceiver" android:exported="false"', manifest)
        self.assertIn("android.intent.action.MY_PACKAGE_REPLACED", manifest)
        for token in ("Intent.ACTION_MY_PACKAGE_REPLACED", "goAsync()", "pending.finish()", "observeExistingLinuxPins"):
            self.assertIn(token, source)
        for token in ('"linux-rootfs-access-v1"', '"linux-selection"', '"workspace-generations"', "stores.forEach(::observe)"):
            self.assertIn(token, source)
        self.assertNotIn('if (!File(context.filesDir, "linux-rootfs-access-v1").isDirectory) return', source)
        for token in ("startActivity(", "ProcessBuilder", "claimExecution("):
            self.assertNotIn(token, source)
        for filename in ("LinuxSettingsApplication.kt", "TerminalEnvironmentHost.kt"):
            self.assertIn("androidLinuxBootIdentity(context)", (TERMINAL / filename).read_text())
        self.assertIn("Settings.Global.BOOT_COUNT", (TERMINAL / "TerminalEnvironmentHost.kt").read_text())

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
