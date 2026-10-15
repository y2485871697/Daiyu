#!/usr/bin/env python3
"""Read-only, fail-closed local source binding; not an APK or business verdict."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import selectors
import stat
import subprocess
import sys
import time

FULL_SHA = re.compile(r"(?:[0-9a-f]{40}|[0-9a-f]{64})\Z")
TIMEOUT_SECONDS = 5
MAX_OUTPUT_BYTES = 256 * 1024
MAX_CONFIG_BYTES = 64 * 1024
# No user input (including expected SHA) is interpolated into these argv.
GIT_PREFIX = (
    "git", "--no-pager", "--no-optional-locks",
    "-c", "core.fsmonitor=false", "-c", "core.hooksPath=/dev/null",
    "-c", "core.untrackedCache=false", "-c", "core.ignoreStat=false",
    "-c", "core.ignoreCase=false",
    "-c", "core.fileMode=true", "-c", "core.trustctime=true",
    "-c", "core.attributesFile=/dev/null", "-c", "diff.external=",
    "-c", "protocol.allow=never", "-c", "maintenance.auto=false",
    "-c", "gc.auto=0",
)
COMMANDS = {
    "root": ("rev-parse", "--show-toplevel"),
    "head": ("rev-parse", "--verify", "HEAD^{commit}"),
    "index": ("ls-files", "--stage", "-z"),
    "flags": ("ls-files", "-v", "-z"),
    "status": ("status", "--porcelain=v1", "-z", "--untracked-files=all",
               "--ignore-submodules=none", "--no-renames"),
}
CODES = frozenset({
    "source_bound", "invalid_arguments", "invalid_expected_sha", "invalid_root",
    "nested_repository", "unsafe_repository_config", "git_error", "git_timeout",
    "git_output_limit", "invalid_git_output", "unsupported_index", "head_mismatch",
    "dirty_source", "source_changed", "source_unavailable",
})


class GateError(ValueError):
    pass


def require(condition, code):
    if not condition:
        raise GateError(code)


def git_environment():
    # Do not inherit GIT_DIR, GIT_WORK_TREE, GIT_CONFIG_COUNT, external diff,
    # tracing, credential, SSH or proxy overrides from the caller.
    return {
        "PATH": os.environ.get("PATH", os.defpath), "LC_ALL": "C",
        "HOME": "/nonexistent", "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_CONFIG_SYSTEM": os.devnull, "GIT_CONFIG_GLOBAL": os.devnull,
        "GIT_OPTIONAL_LOCKS": "0", "GIT_TERMINAL_PROMPT": "0",
        "GIT_NO_LAZY_FETCH": "1", "GIT_ALLOW_PROTOCOL": "", "GIT_NO_REPLACE_OBJECTS": "1",
        "GIT_ATTR_NOSYSTEM": "1",
    }


def run_git(root, command):
    """Drain a bounded pipe, discard stderr, kill/reap on deadline or overflow."""
    require(command in COMMANDS, "git_error")
    process = None
    try:
        process = subprocess.Popen(
            GIT_PREFIX + COMMANDS[command], cwd=root, env=git_environment(),
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, shell=False, close_fds=True,
        )
        deadline = time.monotonic() + TIMEOUT_SECONDS
        data = bytearray()
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while True:
                remaining = deadline - time.monotonic()
                require(remaining > 0, "git_timeout")
                require(selector.select(remaining), "git_timeout")
                chunk = os.read(process.stdout.fileno(), min(4096, MAX_OUTPUT_BYTES + 1))
                if not chunk:
                    break
                data.extend(chunk)
                require(len(data) <= MAX_OUTPUT_BYTES, "git_output_limit")
        try:
            code = process.wait(timeout=max(0.001, deadline - time.monotonic()))
        except subprocess.TimeoutExpired:
            raise GateError("git_timeout") from None
        require(code == 0, "git_error")
        return bytes(data)
    except OSError:
        raise GateError("git_error") from None
    finally:
        if process is not None:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=1)
            if process.stdout is not None:
                process.stdout.close()


def checked_root(value):
    require(isinstance(value, (str, Path)), "invalid_root")
    path = Path(value)
    require(path.is_absolute() and ".." not in path.parts, "invalid_root")
    require(not any(p.is_symlink() for p in (path, *path.parents)), "invalid_root")
    require(path.is_dir(), "invalid_root")
    # No implicit discovery, linked worktrees, submodule roots or bare repos.
    require((path / ".git").is_dir() and not (path / ".git").is_symlink(), "invalid_root")
    # Shared metadata could make Git read a different config than check_config.
    commondir = path / ".git" / "commondir"
    require(not commondir.exists() and not commondir.is_symlink(), "invalid_root")
    require(not any((p / ".git").exists() or (p / ".git").is_symlink()
                    for p in path.parents), "nested_repository")
    return path


def check_config(root):
    """Conservative local config subset: no includes, filters or executable drivers.

    Git status can run clean filters, even without diff. Disabling external diff
    alone is insufficient. Reject unknown config sections and continuation syntax;
    system/global config and attributes are isolated separately.
    """
    require(not (root / ".git" / "config.worktree").exists(), "unsafe_repository_config")
    require(hasattr(os, "O_NOFOLLOW"), "source_unavailable")
    fd = os.open(root / ".git" / "config", os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, "rb") as stream:
        require(stat.S_ISREG(os.fstat(stream.fileno()).st_mode), "unsafe_repository_config")
        raw = stream.read(MAX_CONFIG_BYTES + 1)
    require(len(raw) <= MAX_CONFIG_BYTES and b"\\" not in raw and b"\x00" not in raw,
            "unsafe_repository_config")
    try:
        text = raw.decode("utf-8")
    except UnicodeError:
        raise GateError("unsafe_repository_config") from None
    section = None
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith(("#", ";")):
            continue
        if line.startswith("["):
            match = re.fullmatch(r'\[\s*(core|user|remote|branch|init|extensions)(?:\s+"[^"\r\n]+")?\s*\]',
                                 line, re.IGNORECASE)
            require(match is not None, "unsafe_repository_config")
            section = match.group(1).lower()
        else:
            match = re.fullmatch(r"([A-Za-z][A-Za-z0-9-]*)\s*(?:=.*)?", line)
            require(section is not None and match is not None, "unsafe_repository_config")
            if section == "extensions":
                require(match.group(1).lower() == "objectformat"
                        and re.fullmatch(r"objectformat\s*=\s*sha256", line, re.IGNORECASE),
                        "unsafe_repository_config")


def read_head(root):
    raw = run_git(root, "head")
    require(raw.endswith(b"\n") and raw.count(b"\n") == 1, "invalid_git_output")
    try:
        sha = raw[:-1].decode("ascii")
    except UnicodeError:
        raise GateError("invalid_git_output") from None
    require(FULL_SHA.fullmatch(sha), "invalid_git_output")
    return sha


def check_index(root):
    staged = run_git(root, "index")
    flags = run_git(root, "flags")
    for raw in (staged, flags):
        require(not raw or raw.endswith(b"\x00"), "invalid_git_output")
    for entry in staged.split(b"\x00")[:-1]:
        metadata, separator, name = entry.partition(b"\t")
        require(separator and name, "invalid_git_output")
        # Reject gitlinks before status can recurse into a submodule's config.
        require(re.fullmatch(rb"(?:100644|100755|120000) [0-9a-f]{40}(?:[0-9a-f]{24})? 0",
                             metadata), "unsupported_index")
    for entry in flags.split(b"\x00")[:-1]:
        # Lowercase = assume-unchanged, S = skip-worktree; neither may hide dirt.
        require(entry.startswith(b"H ") and len(entry) > 2, "unsupported_index")


def report(code, expected=None, head=None, clean=False):
    assert code in CODES
    result = {"code": code, "source_bound": code == "source_bound", "clean": clean}
    if expected is not None:
        result["expected_sha"] = expected
    if head is not None:
        result["head_sha"] = head
    return result


def verify(repo_root, expected_sha):
    """Only source_bound permits a *source* binding claim at observation time."""
    if not isinstance(expected_sha, str) or not FULL_SHA.fullmatch(expected_sha):
        return report("invalid_expected_sha")
    head = None
    try:
        root = checked_root(repo_root)
        check_config(root)
        require(run_git(root, "root") == os.fsencode(root) + b"\n", "invalid_root")
        head = read_head(root)
        require(head == expected_sha, "head_mismatch")
        check_index(root)
        require(not run_git(root, "status"), "dirty_source")
        # Recheck both observations: not an atomic snapshot or a filesystem lock.
        require(read_head(root) == head, "source_changed")
        require(not run_git(root, "status"), "dirty_source")
        return report("source_bound", expected_sha, head, clean=True)
    except GateError as exc:
        return report(str(exc) if str(exc) in CODES else "source_unavailable", expected_sha, head)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError):
        return report("source_unavailable", expected_sha, head)


class SafeParser(argparse.ArgumentParser):
    def error(self, message):
        raise GateError("invalid_arguments")


def main(argv=None):
    parser = SafeParser(description=__doc__, allow_abbrev=False, add_help=False)
    parser.add_argument("--repo-root", required=True)
    parser.add_argument("--expected-sha", required=True)
    try:
        args = parser.parse_args(argv)
        result = verify(args.repo_root, args.expected_sha)
    except GateError:
        result = report("invalid_arguments")
    print(json.dumps(result, sort_keys=True))
    if result["source_bound"]:
        return 0
    return 1 if result["code"] in {"head_mismatch", "dirty_source", "source_changed"} else 2


if __name__ == "__main__":
    sys.exit(main())
