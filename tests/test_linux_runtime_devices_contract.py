"""Fixed runtime device mappings accompany files-only rootfs extraction."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
TERMINAL = ROOT / "app/src/main/kotlin/app/daiyu/android/terminal"


class LinuxRuntimeDevicesContractTest(unittest.TestCase):
    def test_proot_terminal_and_install_probe_bind_only_fixed_devices(self):
        store = (TERMINAL / "LinuxEnvironmentStore.kt").read_text()
        installer = (TERMINAL / "install/RootlessLinuxInstaller.kt").read_text()
        self.assertIn('"-w", guestCwd, "-b", "/dev:/dev") + binds', store)
        self.assertIn('"-w", "/", "-b", "/dev:/dev", "/bin/sh")', installer)
        self.assertIn('sharedMounts = mounts.toMap()', store)
        self.assertIn('guestWorkingDirectory = "/", sharedMounts = emptyMap()', installer)
        self.assertIn('"linux_proot_device_mount_conflict"', store)
        self.assertIn('"linux_workspace_device_mount_conflict"', store)

    def test_chroot_devices_are_pinned_and_mounted_after_private_namespace(self):
        header = (TERMINAL / "native/daiyu_root_scope.h").read_text()
        helper = header.split('static int root_mount_dev(int rootfd) {', 1)[1].split(
            'static int root_identity_number', 1)[0]
        self.assertIn('root_open_directory("/dev")', helper)
        self.assertIn('openat(rootfd,"dev",O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW)', helper)
        self.assertIn('mount(from,to,NULL,MS_BIND|MS_REC,NULL)', helper)
        self.assertIn('close(sourcefd); errno=saved;', helper)
        self.assertNotIn('mknod', helper)
        native = (TERMINAL / "native/daiyu_pty.c").read_text()
        self.assertLess(native.index('root_enter()<0'), native.index('root_mount_dev(rootfd)<0'))
        self.assertLess(native.index('root_mount_dev(rootfd)<0'), native.index('root_mount_proc(rootfd)<0'))
        self.assertIn('root_mount_dev(rootfd)<0 || root_mount_proc(rootfd)<0 || fchdir(rootfd)<0', native)

    def test_device_fault_model_count_is_required_by_host_verifier(self):
        faults = (ROOT / "app/src/test/native/root_scope_faults.c").read_text()
        verifier = (TERMINAL / "native/verify_host.py").read_text()
        self.assertIn('int dev_tests=dev_mount_faults()', faults)
        self.assertIn('20+workspace_tests+dev_tests', faults)
        self.assertIn('root_scope_faults: 41 passed', verifier)
        self.assertIn("'passed':41", verifier)


if __name__ == "__main__":
    unittest.main()
