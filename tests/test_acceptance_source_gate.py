"""Synthetic local repositories only; no application acceptance or network."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

MODULE_PATH = Path(__file__).resolve().parents[1] / "scripts/acceptance/source_gate.py"
SPEC = importlib.util.spec_from_file_location("acceptance_source_gate", MODULE_PATH)
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)
SHA = "a" * 40


@unittest.skipUnless(shutil.which("git") and os.name == "posix", "local POSIX Git required")
class RepositoryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        # Resolve system temp aliases so the fixture itself has no symlink root.
        self.base = Path(self.tmp.name).resolve()
        self.root = self.base / "repository"
        self.root.mkdir()
        self.git("init", "--quiet")
        (self.root / "tracked.txt").write_text("fixture\n")
        self.git("add", "tracked.txt")
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@invalid",
                 "-c", "core.hooksPath=/dev/null", "commit", "--quiet", "-m", "fixture")
        self.sha = self.git("rev-parse", "HEAD").decode().strip()

    def git(self, *args, root=None):
        # Mutating commands are fixture setup ONLY, never part of source_gate.
        return subprocess.run(
            ("git",) + args, cwd=root or self.root, env=gate.git_environment(),
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=10, check=True, shell=False,
        ).stdout

    def verify(self, sha=None):
        return gate.verify(self.root, self.sha if sha is None else sha)

    def test_clean_full_sha_and_no_write(self):
        before = {p: p.read_bytes() for p in (self.root / ".git").rglob("*") if p.is_file()}
        self.assertEqual(self.verify(), {"code": "source_bound", "source_bound": True,
                         "clean": True, "head_sha": self.sha, "expected_sha": self.sha})
        after = {p: p.read_bytes() for p in (self.root / ".git").rglob("*") if p.is_file()}
        self.assertEqual(before, after)

    def test_dirty_worktree(self):
        (self.root / "tracked.txt").write_text("changed fixture\n")
        self.assertEqual(self.verify()["code"], "dirty_source")
        self.assertFalse(self.verify()["source_bound"])

    def test_staged_index(self):
        (self.root / "tracked.txt").write_text("staged fixture\n")
        self.git("add", "tracked.txt")
        self.assertEqual(self.verify()["code"], "dirty_source")

    def test_untracked_and_filename_not_reported(self):
        secret_name = "PRIVATE_FILENAME_MARKER"
        (self.root / secret_name).write_text("fixture")
        result = self.verify()
        self.assertEqual(result["code"], "dirty_source")
        self.assertNotIn(secret_name, json.dumps(result))
        self.assertNotIn(str(self.root), json.dumps(result))

    def test_ignorecase_cannot_hide_case_distinct_untracked_file(self):
        alias = self.root / "TRACKED.TXT"
        if alias.exists():
            self.skipTest("case-sensitive filesystem required")
        self.git("config", "core.ignoreCase", "true")
        self.assertTrue(self.verify()["source_bound"])
        alias.write_text("different untracked content\n")
        result = self.verify()
        self.assertEqual(result["code"], "dirty_source")
        self.assertFalse(result["source_bound"])
        self.assertFalse(result["clean"])

    def test_mismatched_head_does_not_rewrite_expected(self):
        other = "b" * len(self.sha)
        result = self.verify(other)
        self.assertEqual(result["code"], "head_mismatch")
        self.assertEqual(result["expected_sha"], other)
        self.assertEqual(result["head_sha"], self.sha)
        self.assertFalse(result["source_bound"])

    def test_empty_repository_and_non_repository(self):
        empty = self.base / "empty"
        empty.mkdir()
        self.git("init", "--quiet", root=empty)
        self.assertEqual(gate.verify(empty, SHA)["code"], "git_error")
        self.assertEqual(gate.verify(self.base, SHA)["code"], "invalid_root")

    def test_subdirectory_not_implicitly_discovered(self):
        child = self.root / "child"
        child.mkdir()
        self.assertEqual(gate.verify(child, SHA)["code"], "invalid_root")

    def test_nested_repository_root(self):
        child = self.root / "nested"
        child.mkdir()
        self.git("init", "--quiet", root=child)
        self.assertEqual(gate.verify(child, SHA)["code"], "nested_repository")
        self.assertEqual(self.verify()["code"], "dirty_source")

    def test_symlink_root_parent_and_git_directory(self):
        alias = self.base / "alias"
        alias.symlink_to(self.root, target_is_directory=True)
        self.assertEqual(gate.verify(alias, self.sha)["code"], "invalid_root")
        parent_alias = self.base / "parent-alias"
        parent_alias.symlink_to(self.base, target_is_directory=True)
        self.assertEqual(gate.verify(parent_alias / "repository", self.sha)["code"], "invalid_root")
        original = self.root / ".git"
        moved = self.base / "metadata"
        original.rename(moved)
        original.symlink_to(moved, target_is_directory=True)
        self.assertEqual(self.verify()["code"], "invalid_root")

    def test_commondir_layouts_rejected_before_git(self):
        common = self.base / "common"
        shutil.copytree(self.root / ".git", common)
        target = self.base / "commondir-target"
        target.write_text(str(common) + "\n")
        commondir = self.root / ".git" / "commondir"
        for kind in ("file", "directory", "symlink", "dangling_symlink"):
            with self.subTest(kind=kind):
                if kind == "file":
                    commondir.write_text(str(common) + "\n")
                elif kind == "directory":
                    commondir.mkdir()
                else:
                    commondir.symlink_to(target if kind == "symlink" else self.base / "missing")
                try:
                    with patch.object(gate.subprocess, "Popen") as popen:
                        result = self.verify()
                        self.assertEqual(result["code"], "invalid_root")
                        self.assertFalse(result["source_bound"])
                        self.assertFalse(result["clean"])
                        popen.assert_not_called()
                finally:
                    if kind == "directory":
                        commondir.rmdir()
                    else:
                        commondir.unlink()

    def test_invalid_sha_never_launches_git(self):
        for value in ("HEAD", "a" * 39, "A" * 40, SHA + "\n", "--help", "", None):
            with self.subTest(value=value), patch.object(gate.subprocess, "Popen") as popen:
                self.assertEqual(self.verify(value if value is not None else "not-sha")["code"],
                                 "invalid_expected_sha")
                popen.assert_not_called()
        self.assertEqual(gate.verify(self.root, None)["code"], "invalid_expected_sha")

    def test_unsafe_filter_and_include_config_never_launch_git(self):
        config = self.root / ".git/config"
        original = config.read_bytes()
        for addition in (b"\n[filter \"evil\"]\n clean = touch PRIVATE\n",
                         b"\n[include]\n path = PRIVATE\n",
                         b"\n[extensions]\n worktreeConfig = true\n"):
            config.write_bytes(original + addition)
            with patch.object(gate.subprocess, "Popen") as popen:
                self.assertEqual(self.verify()["code"], "unsafe_repository_config")
                popen.assert_not_called()

    def test_executable_config_overrides_are_never_run(self):
        # Each program would create a sentinel if executed. No shell is used by
        # the test or gate; the fixture script merely catches unexpected Git hooks.
        sentinel = self.base / "EXECUTED"
        program = self.base / "unexpected-hook"
        program.write_text("#!/bin/sh\ntouch '" + str(sentinel) + "'\nexit 1\n")
        program.chmod(0o755)
        with (self.root / ".git/config").open("a") as config:
            config.write("\n[core]\n fsmonitor = " + str(program)
                         + "\n hooksPath = " + str(self.base)
                         + "\n pager = " + str(program) + "\n")
        self.assertTrue(self.verify()["source_bound"])
        self.assertFalse(sentinel.exists())

    def test_assume_unchanged_and_skip_worktree_fail_closed(self):
        for flag in ("--assume-unchanged", "--skip-worktree"):
            self.git("update-index", flag, "tracked.txt")
            (self.root / "tracked.txt").write_text("hidden dirt\n")
            self.assertEqual(self.verify()["code"], "unsupported_index")
            self.git("update-index", "--no-assume-unchanged", "--no-skip-worktree", "tracked.txt")

    def test_submodule_gitlink_rejected_before_status(self):
        self.git("update-index", "--add", "--cacheinfo", "160000," + self.sha + ",child")
        real_run = gate.run_git
        calls = []

        def run(root, command):
            calls.append(command)
            return real_run(root, command)

        with patch.object(gate, "run_git", side_effect=run):
            self.assertEqual(self.verify()["code"], "unsupported_index")
        self.assertNotIn("status", calls)

    def test_timeout_error_and_output_overflow_fail_closed(self):
        for code in ("git_timeout", "git_error", "git_output_limit", "invalid_git_output"):
            with patch.object(gate, "run_git", side_effect=gate.GateError(code)):
                result = self.verify()
                self.assertEqual(result["code"], code)
                self.assertFalse(result["source_bound"])

    def test_change_between_observations(self):
        replies = [os.fsencode(self.root) + b"\n", self.sha.encode() + b"\n", b"", b"",
                   b"", SHA.encode() + b"\n"]
        with patch.object(gate, "run_git", side_effect=replies):
            self.assertEqual(self.verify()["code"], "source_changed")
        replies[-1] = self.sha.encode() + b"\n"
        replies.append(b"?? PRIVATE\x00")
        with patch.object(gate, "run_git", side_effect=replies):
            self.assertEqual(self.verify()["code"], "dirty_source")

    def test_fixed_readonly_argv_no_inherited_network_or_ledger(self):
        real_popen = gate.subprocess.Popen
        with patch.dict(os.environ, {"GIT_SSH_COMMAND": "PRIVATE", "GIT_CONFIG_COUNT": "99",
                                     "GIT_DIR": "PRIVATE", "HTTP_PROXY": "PRIVATE"}), \
                patch.object(gate.subprocess, "Popen", wraps=real_popen) as popen:
            self.assertTrue(self.verify()["source_bound"])
        self.assertEqual(popen.call_count, 7)
        allowed = {gate.GIT_PREFIX + suffix for suffix in gate.COMMANDS.values()}
        for call in popen.call_args_list:
            argv = call.args[0]
            self.assertIn(argv, allowed)
            self.assertFalse(call.kwargs["shell"])
            self.assertEqual(call.kwargs["cwd"], self.root)
            self.assertEqual(call.kwargs["stderr"], subprocess.DEVNULL)
            env = call.kwargs["env"]
            self.assertEqual(env["GIT_ALLOW_PROTOCOL"], "")
            self.assertEqual(env["GIT_NO_LAZY_FETCH"], "1")
            self.assertNotIn("PRIVATE", json.dumps(env))
            self.assertNotIn(self.sha, argv)
            self.assertFalse(set(argv) & {"fetch", "push", "config", "checkout", "clean", "diff"})

    def test_cli_output_exit_codes_and_no_path_leak(self):
        for expected, wanted_exit, code in ((self.sha, 0, "source_bound"),
                                            (SHA, 1, "head_mismatch"),
                                            ("PRIVATE", 2, "invalid_expected_sha")):
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                exit_code = gate.main(["--repo-root", str(self.root), "--expected-sha", expected])
            self.assertEqual(exit_code, wanted_exit)
            result = json.loads(output.getvalue())
            self.assertEqual(result["code"], code)
            self.assertNotIn(str(self.root), output.getvalue())
            self.assertNotIn("PRIVATE", output.getvalue())


class BoundaryTests(unittest.TestCase):
    def fake_process(self, data=b"", returncode=0):
        read_fd, write_fd = os.pipe()
        # Keep fixture below pipe capacity; output-limit tests lower the limit.
        os.write(write_fd, data)
        os.close(write_fd)
        stream = os.fdopen(read_fd, "rb", buffering=0)
        process = Mock(stdout=stream)
        process.wait.return_value = returncode
        process.poll.return_value = returncode
        self.addCleanup(stream.close)
        return process

    @patch.object(gate.selectors, "DefaultSelector")
    def test_bounded_reader_success_error_and_limit(self, selector_factory):
        # Readiness is synthetic; keep real pipe reads and byte/exit bounds.
        selector = selector_factory.return_value.__enter__.return_value
        for data, returncode, limit, code in ((b"ok", 0, 10, None),
                                              (b"PRIVATE", 1, 10, "git_error"),
                                              (b"overflow", 0, 2, "git_output_limit")):
            process = self.fake_process(data, returncode)
            selector.select.return_value = [(process.stdout, gate.selectors.EVENT_READ)]
            with patch.object(gate.subprocess, "Popen", return_value=process), \
                    patch.object(gate, "MAX_OUTPUT_BYTES", limit):
                if code:
                    with self.assertRaisesRegex(gate.GateError, "^" + code + "$"):
                        gate.run_git(Path("/"), "head")
                else:
                    self.assertEqual(gate.run_git(Path("/"), "head"), data)
            selector.register.assert_called_with(process.stdout, gate.selectors.EVENT_READ)
            selector.select.assert_called()

    def test_deadline_kills_and_reaps(self):
        process = self.fake_process()
        process.poll.return_value = None
        with patch.object(gate.subprocess, "Popen", return_value=process), \
                patch.object(gate.time, "monotonic", side_effect=[0, 10]):
            with self.assertRaisesRegex(gate.GateError, "^git_timeout$"):
                gate.run_git(Path("/"), "head")
        process.kill.assert_called_once()
        process.wait.assert_called_once_with(timeout=1)

    def test_spawn_error_and_unknown_command(self):
        with patch.object(gate.subprocess, "Popen", side_effect=OSError("PRIVATE")):
            with self.assertRaisesRegex(gate.GateError, "^git_error$"):
                gate.run_git(Path("/"), "head")
        with patch.object(gate.subprocess, "Popen") as popen:
            with self.assertRaisesRegex(gate.GateError, "^git_error$"):
                gate.run_git(Path("/"), "push")
            popen.assert_not_called()

    def test_sha256_and_malformed_head(self):
        for raw, valid in ((b"a" * 64 + b"\n", True), (b"HEAD\n", False),
                           (b"a" * 40, False), (b"a" * 40 + b"\nPRIVATE\n", False)):
            with patch.object(gate, "run_git", return_value=raw):
                if valid:
                    self.assertEqual(gate.read_head(Path("/")), "a" * 64)
                else:
                    with self.assertRaisesRegex(gate.GateError, "^invalid_git_output$"):
                        gate.read_head(Path("/"))

    def test_invalid_arguments_no_stderr_or_untrusted_echo(self):
        output, errors = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors), \
                patch.object(gate.subprocess, "Popen") as popen:
            self.assertEqual(gate.main(["--ledger", "PRIVATE"]), 2)
        popen.assert_not_called()
        self.assertEqual(json.loads(output.getvalue())["code"], "invalid_arguments")
        self.assertEqual(errors.getvalue(), "")
        self.assertNotIn("PRIVATE", output.getvalue())


if __name__ == "__main__":
    unittest.main()
