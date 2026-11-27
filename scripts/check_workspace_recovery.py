"""Cloud-only targeted checks of the production native metadata recovery implementation."""
from pathlib import Path
import hashlib
import os
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
LABEL = b"u:object_r:app_data_file:s0:c123,c456\0"
UNSCOPED = b"u:object_r:app_data_file:s0\0"
ATTR = "user.daiyu_test_selinux"
UID = 10123


class NativeWorkspaceRecoveryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if os.geteuid() != 0:
            raise RuntimeError("Run this cloud fault suite as root; do not skip root fixtures")
        cls.build = tempfile.TemporaryDirectory()
        cls.executable = Path(cls.build.name) / "repair"
        subprocess.run(["cc", "-std=c11", "-Wall", "-Wextra", "-Werror", "-Wno-unused-function",
                        str(ROOT / "app/src/test/native/workspace_repair_driver.c"), "-o", str(cls.executable)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.build.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.storage = Path(self.temp.name) / "files"
        self.storage.mkdir()
        os.chown(self.storage, UID, UID)
        os.setxattr(self.storage, ATTR, LABEL)
        self.workspace = self.storage / "workspace"
        self.workspace.mkdir()
        os.chown(self.workspace, UID, UID)
        os.setxattr(self.workspace, ATTR, LABEL)

    def tearDown(self):
        self.temp.cleanup()

    def run_helper(self, relative="", tree=False, repair=False, expected=None, expired=False, as_app=False):
        anchor = self.storage.stat()
        expected = expected or (0, 0)
        args = [str(self.executable), "--workspace-repair" if repair else "--workspace-check",
                str(self.storage), str(anchor.st_dev), str(anchor.st_ino), relative,
                str(expected[0]), str(expected[1]), "1" if tree else "0", "DWSP1",
                str(int(time.monotonic() * 1000) + (-1 if expired else 8000))]
        identity = {"user": UID, "group": UID, "extra_groups": []} if as_app else {}
        return subprocess.run(args, capture_output=True, timeout=15, **identity)

    def dirty(self, path, text=None):
        path.mkdir() if text is None else path.write_text(text)
        os.chown(path, 0, 0)
        os.setxattr(path, ATTR, UNSCOPED)
        return path

    def test_app_check_crosses_search_only_ancestors_without_root(self):
        os.chmod(self.temp.name, 0o711)
        os.chmod(self.build.name, 0o755)
        self.assertEqual(b"DWSP1 OK\n", self.run_helper(as_app=True).stdout)
        folder = self.dirty(self.workspace / "root-output")
        os.chmod(folder, 0o700)
        self.assertEqual(77, self.run_helper("root-output", as_app=True).returncode)
        self.assertEqual(0, folder.stat().st_uid)
        self.assertEqual(UNSCOPED, os.getxattr(folder, ATTR))
        self.assertEqual(0, self.run_helper("root-output", repair=True).returncode)
        checked = self.run_helper("root-output", as_app=True)
        self.assertEqual((0, b"DWSP1 OK\n"), (checked.returncode, checked.stdout))
        self.assertEqual(0o700, folder.stat().st_mode & 0o777)

    def test_repair_preserves_contents_mode_mtime_and_inode(self):
        folder = self.dirty(self.workspace / "backup")
        child = self.dirty(folder / "child")
        file = self.dirty(child / "source.kt", "preserve bytes")
        before = file.stat()
        digest = hashlib.sha256(file.read_bytes()).hexdigest()
        self.assertEqual(77, self.run_helper("backup", True).returncode)
        self.assertEqual(0, self.run_helper("backup", True, True, (folder.stat().st_dev, folder.stat().st_ino)).returncode)
        self.assertEqual(0, self.run_helper("backup", True).returncode)
        for path in (folder, child, file):
            self.assertEqual((UID, UID), (path.stat().st_uid, path.stat().st_gid))
            self.assertEqual(LABEL, os.getxattr(path, ATTR))
        after = file.stat()
        self.assertEqual((before.st_ino, before.st_mode, before.st_mtime_ns),
                         (after.st_ino, after.st_mode, after.st_mtime_ns))
        self.assertEqual(digest, hashlib.sha256(file.read_bytes()).hexdigest())

    def test_later_root_write_is_recovered_again(self):
        self.dirty(self.workspace / "first", "one")
        self.assertEqual(0, self.run_helper("first", repair=True).returncode)
        self.dirty(self.workspace / "second", "two")
        self.assertEqual(77, self.run_helper("second").returncode)
        self.assertEqual(0, self.run_helper("second", repair=True).returncode)

    def test_symlink_target_is_never_changed(self):
        outside = self.dirty(self.storage / "outside", "outside")
        before = outside.stat()
        (self.workspace / "link").symlink_to(outside)
        checked = self.run_helper("link")
        self.assertEqual((78, b""), (checked.returncode, checked.stdout))
        self.assertNotEqual(0, self.run_helper("link", repair=True).returncode)
        self.assertEqual(0, self.run_helper(tree=True, repair=True).returncode)
        self.assertEqual((before.st_uid, before.st_gid, before.st_mode),
                         (outside.stat().st_uid, outside.stat().st_gid, outside.stat().st_mode))
        self.assertEqual(UNSCOPED, os.getxattr(outside, ATTR))

    def test_hardlink_is_rejected_before_metadata_changes(self):
        outside = self.dirty(self.storage / "outside", "outside")
        os.link(outside, self.workspace / "link")
        self.assertNotEqual(0, self.run_helper("link", repair=True).returncode)
        self.assertEqual(0, outside.stat().st_uid)
        self.assertEqual(UNSCOPED, os.getxattr(outside, ATTR))

    def test_rootfs_outside_path_other_uid_and_foreign_label_are_rejected(self):
        for relative in ("../rootfs", "/rootfs", "a/../b", "a//b", "a/", "a\\b"):
            self.assertNotEqual(0, self.run_helper(relative, repair=True).returncode)
        foreign = self.dirty(self.workspace / "foreign", "foreign")
        os.chown(foreign, 10124, 10124)
        self.assertNotEqual(0, self.run_helper("foreign", repair=True).returncode)
        self.assertEqual(10124, foreign.stat().st_uid)
        os.chown(foreign, 0, 0)
        os.setxattr(foreign, ATTR, b"u:object_r:app_data_file:s0:c999\0")
        self.assertNotEqual(0, self.run_helper("foreign", repair=True).returncode)
        self.assertEqual(0, foreign.stat().st_uid)

    def test_replaced_target_and_expired_deadline_do_not_mutate(self):
        folder = self.dirty(self.workspace / "target")
        before = folder.stat()
        self.assertNotEqual(0, self.run_helper("target", repair=True, expected=(before.st_dev, before.st_ino + 1)).returncode)
        self.assertEqual(0, folder.stat().st_uid)
        self.assertNotEqual(0, self.run_helper("target", repair=True, expired=True).returncode)
        self.assertEqual(0, folder.stat().st_uid)

    def test_stale_target_does_not_repair_dirty_ancestors(self):
        parent = self.dirty(self.workspace / "parent")
        target = self.dirty(parent / "target")
        identity = target.stat()
        self.assertNotEqual(0, self.run_helper("parent/target", repair=True,
            expected=(identity.st_dev, identity.st_ino + 1)).returncode)
        for path in (parent, target):
            self.assertEqual((0, 0), (path.stat().st_uid, path.stat().st_gid))
            self.assertEqual(UNSCOPED, os.getxattr(path, ATTR))
        self.assertEqual(0, self.run_helper("parent/target", repair=True,
            expected=(identity.st_dev, identity.st_ino)).returncode)
        for path in (parent, target):
            self.assertEqual((UID, UID), (path.stat().st_uid, path.stat().st_gid))
            self.assertEqual(LABEL, os.getxattr(path, ATTR))

    def test_same_device_bind_mount_is_rejected(self):
        outside = self.dirty(self.storage / "outside")
        target = self.workspace / "mounted"
        target.mkdir()
        subprocess.run(["mount", "--bind", str(outside), str(target)], check=True)
        try:
            self.assertEqual(outside.stat().st_dev, self.workspace.stat().st_dev)
            self.assertNotEqual(0, self.run_helper("mounted", True, True).returncode)
            self.assertEqual(0, outside.stat().st_uid)
            self.assertEqual(UNSCOPED, os.getxattr(outside, ATTR))
        finally:
            subprocess.run(["umount", str(target)], check=True)

    def test_special_nodes_are_not_opened(self):
        os.mkfifo(self.workspace / "pipe")
        self.assertEqual(0, self.run_helper(tree=True, repair=True).returncode)


if __name__ == "__main__":
    unittest.main(verbosity=2)
