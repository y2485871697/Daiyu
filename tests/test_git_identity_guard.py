"""Exercise the hook in disposable main-only repositories, with no network pushes."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1]
ANCHOR = "bab2326fb374e53d5e86efe8983508449d7d8339"
NAME = "y2485871697"
EMAIL = "y2485871697@users.noreply.github.com"
IDENTITY = (NAME, EMAIL, NAME, EMAIL)
ZERO = "0" * 40


class GitIdentityGuardTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="daiyu-identity-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / "含空格 clone"
        self.root.mkdir()
        self.env = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
        self.env.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull,
                        GIT_TERMINAL_PROMPT="0", HOME=self.tmp.name)
        self.run_git("init", "-q", "--initial-branch=main")
        self.run_git("config", "user.name", NAME)
        self.run_git("config", "user.email", EMAIL)
        self.run_git("remote", "add", "origin", "https://github.com/y2485871697/Daiyu.git")
        self.tree = self.run_git("mktree", data="").stdout.strip()
        self.counter = 0
        self.base = self.commit(parents=[], identity=("Eta Agent", "agent@localhost", "Eta Agent", "agent@localhost"))
        self.run_git("update-ref", "refs/heads/main", self.base)
        (self.root / "scripts/git-hooks").mkdir(parents=True)
        original = (SOURCE / "scripts/git-hooks/pre-push").read_text(encoding="utf-8")
        self.assertEqual(original.count("anchor=" + ANCHOR), 1)
        # Only the audited-history constant changes for this synthetic graph.
        self.hook = self.root / "scripts/git-hooks/pre-push"
        self.hook.write_text(original.replace("anchor=" + ANCHOR, "anchor=" + self.base), encoding="utf-8", newline="\n")
        self.hook.chmod(0o755)
        self.installer = self.root / "scripts/install_git_identity_guard.sh"
        shutil.copyfile(SOURCE / "scripts/install_git_identity_guard.sh", self.installer)
        self.installer.chmod(0o755)

    def run_git(self, *args, data=None, env=None, check=True, cwd=None):
        return subprocess.run(["git", *args], cwd=cwd or self.root,
                              input=data, text=True, encoding="utf-8", stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, env=env or self.env, check=check)

    def commit(self, parents=None, identity=IDENTITY):
        self.counter += 1
        env = dict(self.env)
        env.update(zip(("GIT_AUTHOR_NAME", "GIT_AUTHOR_EMAIL", "GIT_COMMITTER_NAME", "GIT_COMMITTER_EMAIL"), identity))
        env.update(GIT_AUTHOR_DATE="1700000000 +0000", GIT_COMMITTER_DATE="1700000000 +0000")
        args = ["commit-tree", self.tree]
        for parent in ([self.base] if parents is None else parents):
            args += ["-p", parent]
        return self.run_git(*args, data=f"synthetic fixture {self.counter}\n", env=env).stdout.strip()

    def row(self, local, remote=None, ref="refs/heads/main"):
        return f"refs/heads/main {local} {ref} {remote or self.base}\n"

    def check_hook(self, data, success, env=None, target="unused-local-test-remote"):
        p = subprocess.run(["sh", str(self.hook), "origin", target],
                           cwd=self.root, input=data.encode("utf-8"),
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env or self.env)
        stderr = p.stderr.decode("utf-8")
        self.assertEqual(p.returncode == 0, success, stderr)
        if not success:
            self.assertIn("Git identity guard:", stderr)
        return p

    def install(self, success=True, env=None, cwd=None):
        p = subprocess.run(["sh", str(self.installer)], cwd=cwd or self.root,
                           text=True, encoding="utf-8", stdout=subprocess.PIPE,
                           stderr=subprocess.PIPE, env=env or self.env)
        self.assertEqual(p.returncode == 0, success, p.stderr)
        return p

    def test_correct_new_identity_and_grandfathered_history(self):
        self.check_hook(self.row(self.commit()), True)

    def test_each_identity_field_is_checked(self):
        for field, bad in enumerate(("daiyu", "daiyu@users.noreply.github.com", "Daiyu Integrator", "agent@localhost")):
            with self.subTest(field=field):
                identity = list(IDENTITY)
                identity[field] = bad
                self.check_hook(self.row(self.commit(identity=identity)), False)

    def test_earlier_bad_commit_is_not_hidden_by_correct_tip(self):
        bad = self.commit(identity=("daiyu", "daiyu@local", NAME, EMAIL))
        self.check_hook(self.row(self.commit(parents=[bad])), False)

    def test_merge_second_parent_is_checked(self):
        good = self.commit()
        bad = self.commit(identity=(NAME, EMAIL, "Eta Agent", "agent@localhost"))
        self.check_hook(self.row(self.commit(parents=[good, bad])), False)

    def test_merged_old_history_is_rejected(self):
        old = self.commit(parents=[], identity=("daiyu", "daiyu@users.noreply.github.com", "daiyu", "daiyu@users.noreply.github.com"))
        self.check_hook(self.row(self.commit(parents=[self.commit(), old])), False)

    def test_rollback_with_empty_added_range_is_rejected(self):
        self.check_hook(self.row(self.base, remote=self.commit()), False)

    def test_non_fast_forward_is_rejected(self):
        self.check_hook(self.row(self.commit(), remote=self.commit()), False)

    def test_remote_not_on_corrected_history_is_rejected(self):
        foreign = self.commit(parents=[])
        self.check_hook(self.row(self.commit(parents=[foreign]), remote=foreign), False)

    def test_delete_create_other_ref_and_tag_are_rejected(self):
        good = self.commit()
        for row in (self.row(ZERO), self.row(good, remote=ZERO),
                    self.row(good, ref="refs/heads/other"), self.row(good, ref="refs/tags/v1")):
            with self.subTest(row=row):
                self.check_hook(row, False)

    def authorize_initial_push(self):
        self.run_git("config", "--local", "daiyu.allowInitialMainPush", "true")
        self.base = self.commit(parents=[])
        original = (SOURCE / "scripts/git-hooks/pre-push").read_text(encoding="utf-8")
        self.hook.write_text(original.replace("anchor=" + ANCHOR, "anchor=" + self.base),
                             encoding="utf-8", newline="\n")

    def test_authorized_initial_push_checks_every_commit(self):
        self.authorize_initial_push()
        target = "https://github.com/y2485871697/Daiyu.git"
        self.check_hook(self.row(self.commit(), remote=ZERO), True, target=target)
        bad = self.commit(identity=("Eta Agent", "agent@localhost", NAME, EMAIL))
        self.check_hook(self.row(self.commit(parents=[bad]), remote=ZERO), False, target=target)

    def test_initial_push_rejects_another_repository_and_missing_anchor(self):
        self.authorize_initial_push()
        self.check_hook(self.row(self.commit(), remote=ZERO), False)
        foreign = self.commit(parents=[])
        self.check_hook(self.row(foreign, remote=ZERO), False,
                        target="https://github.com/y2485871697/Daiyu.git")

    def test_initial_push_flag_is_one_time_and_does_not_allow_force_push(self):
        self.authorize_initial_push()
        self.run_git("config", "--local", "--unset", "daiyu.allowInitialMainPush")
        self.check_hook(self.row(self.commit(), remote=ZERO), False,
                        target="https://github.com/y2485871697/Daiyu.git")
        self.run_git("config", "--local", "daiyu.allowInitialMainPush", "true")
        self.check_hook(self.row(self.commit(), remote=self.commit()), False)

    def test_missing_local_and_remote_objects_are_rejected(self):
        self.check_hook(self.row("f" * 40), False)
        self.check_hook(self.row(self.commit(), remote="f" * 40), False)

    def test_non_commit_object_is_rejected(self):
        self.check_hook(self.row(self.tree), False)
        self.check_hook(self.row(self.commit(), remote=self.tree), False)

    def test_malformed_input_is_rejected(self):
        for row in ("invalid\n", "\n", self.row(self.commit()).rstrip() + " extra\n",
                    f"refs/heads/main zzz refs/heads/main {self.base}\n"):
            with self.subTest(row=row):
                self.check_hook(row, False)

    def test_all_input_rows_are_checked(self):
        self.check_hook(self.row(self.commit()) + self.row(self.commit(), ref="refs/tags/v1"), False)

    def test_empty_input_and_noop_update_are_allowed(self):
        self.check_hook("", True)
        self.check_hook(self.row(self.base), True)

    def test_shallow_repository_is_rejected(self):
        (self.root / ".git/shallow").write_text(self.base + "\n", encoding="ascii", newline="\n")
        self.check_hook(self.row(self.commit()), False)

    def test_grafts_are_rejected(self):
        (self.root / ".git/info/grafts").write_text(self.base + "\n", encoding="ascii", newline="\n")
        self.check_hook(self.row(self.commit()), False)

    def test_custom_grafts_environment_is_rejected(self):
        env = dict(self.env, GIT_GRAFT_FILE=str(self.root / ".git/custom-grafts"))
        self.check_hook(self.row(self.commit()), False, env=env)

    def test_input_without_final_newline_is_still_checked(self):
        self.check_hook(self.row(self.commit()).rstrip("\n"), True)
        self.check_hook("invalid", False)

    def test_replace_refs_do_not_hide_bad_identity(self):
        bad = self.commit(identity=("daiyu", EMAIL, NAME, EMAIL))
        good = self.commit()
        self.run_git("replace", bad, good)
        self.check_hook(self.row(bad), False)

    def test_installer_sets_only_local_identity_and_hooks(self):
        self.run_git("config", "user.name", "daiyu")
        self.run_git("config", "user.email", "daiyu@local")
        self.run_git("config", "project.keep", "untouched")
        self.install(cwd=Path(self.tmp.name))
        for key, expected in (("user.name", NAME), ("user.email", EMAIL),
                              ("user.useConfigOnly", "true"), ("core.hooksPath", "scripts/git-hooks"),
                              ("project.keep", "untouched")):
            self.assertEqual(self.run_git("config", "--local", "--get", key).stdout.strip(), expected)
        before = (self.root / ".git/config").read_bytes()
        self.install()
        self.assertEqual((self.root / ".git/config").read_bytes(), before)

    def test_existing_hooks_or_custom_path_are_not_overwritten(self):
        path = self.root / ".git/hooks/pre-commit"
        path.write_text("#!/bin/sh\nexit 0\n", encoding="ascii", newline="\n")
        before = (self.root / ".git/config").read_bytes()
        self.install(False)
        self.assertEqual((self.root / ".git/config").read_bytes(), before)
        self.assertEqual(path.read_text(), "#!/bin/sh\nexit 0\n")
        path.unlink()
        self.run_git("config", "core.hooksPath", "existing-hooks")
        before = (self.root / ".git/config").read_bytes()
        self.install(False)
        self.assertEqual((self.root / ".git/config").read_bytes(), before)

    def test_bad_environment_rejected_before_configuration_changes(self):
        for key in ("GIT_AUTHOR_NAME", "GIT_AUTHOR_EMAIL", "GIT_COMMITTER_NAME", "GIT_COMMITTER_EMAIL"):
            with self.subTest(key=key):
                env = dict(self.env, **{key: "incorrect"})
                before = (self.root / ".git/config").read_bytes()
                self.install(False, env=env)
                self.assertEqual((self.root / ".git/config").read_bytes(), before)

    def test_unexpected_origin_is_not_modified(self):
        self.run_git("remote", "set-url", "origin", "https://example.invalid/unrelated.git")
        before = (self.root / ".git/config").read_bytes()
        self.install(False)
        self.assertEqual((self.root / ".git/config").read_bytes(), before)

    def test_git_invokes_installed_hook_before_remote_update(self):
        self.install()
        remote = Path(self.tmp.name) / "remote.git"
        self.run_git("init", "-q", "--bare", "--initial-branch=main", str(remote))
        self.run_git("-C", str(remote), "fetch", "-q", str(self.root), self.base + ":refs/heads/main")
        self.run_git("remote", "set-url", "origin", str(remote))
        good = self.commit()
        self.run_git("update-ref", "refs/heads/main", good)
        self.run_git("push", "origin", "main")
        self.assertEqual(self.run_git("-C", str(remote), "rev-parse", "main").stdout.strip(), good)
        bad = self.commit(parents=[good], identity=("daiyu", "daiyu@local", NAME, EMAIL))
        self.run_git("update-ref", "refs/heads/main", bad)
        p = self.run_git("push", "origin", "main", check=False)
        self.assertNotEqual(p.returncode, 0)
        self.assertIn("Git identity guard: rejected commit", p.stderr)
        self.assertEqual(self.run_git("-C", str(remote), "rev-parse", "main").stdout.strip(), good)


if __name__ == "__main__":
    unittest.main()
