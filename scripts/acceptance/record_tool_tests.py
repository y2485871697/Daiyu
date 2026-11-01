#!/usr/bin/env python3
"""CI-only unittest transcript/receipt capture; not business acceptance.

The final offline entry never invokes this producer. Missing/interrupted
execution must stay missing, not turn into a synthetic success receipt.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


SPEECH_PLATFORM_CLASS = "app.daiyu.android.voice.SpeechAudioFileAndroidTest"
SPEECH_MODULES = (
    "test_chat_voice_feedback_contract", "test_toast_concise_copy_contract",
    "test_message_speech_button_contract", "test_record_tool_tests_scope",
    "test_speech_platform_discovery",
)


def command_for(python, env):
    if env.get("SMOKE_MODE") == "smoke" and env.get("SMOKE_TEST_CLASSES") == SPEECH_PLATFORM_CLASS:
        return [python, "-B", "-m", "unittest", *SPEECH_MODULES, "-v"], "speech-targeted"
    return [python, "-B", "-m", "unittest", "discover", "-s", "tests", "-v"], "full"


def record(python):
    sha = os.environ["GITHUB_SHA"]
    if not re.fullmatch(r"(?:[0-9a-f]{40}|[0-9a-f]{64})", sha):
        raise ValueError("invalid CI SHA")
    identity = dict(sha=sha, run_id=int(os.environ["GITHUB_RUN_ID"]),
                    attempt=int(os.environ["GITHUB_RUN_ATTEMPT"]))
    if identity["run_id"] <= 0 or identity["attempt"] <= 0:
        raise ValueError("invalid CI run selector")
    root = Path("tool-evidence")
    root.mkdir(exist_ok=True)
    receipt = root / "receipt.json"
    # Never leave a stale success receipt when the child cannot start or dies
    # together with its runner. No receipt is written until wait returns.
    receipt.unlink(missing_ok=True)
    command, scope = command_for(python, os.environ)
    child_env = None
    if scope != "full":
        child_env = os.environ.copy()
        child_env["PYTHONPATH"] = str(Path("tests").resolve()) + os.pathsep + child_env.get("PYTHONPATH", "")
    log = root / "unittest.log"
    with log.open("wb") as output:
        code = subprocess.call(command, stdout=output, stderr=subprocess.STDOUT, env=child_env)
    result = dict(identity=identity, command=command, exit_code=code)
    if scope != "full":
        result["scope"] = scope
    receipt.write_text(json.dumps(result) + "\n",
                       encoding="utf-8")
    with log.open("rb") as output:
        shutil.copyfileobj(output, sys.stdout.buffer)
    return code


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--python", choices=("python", "python3"), required=True)
    return record(parser.parse_args(argv).python)


if __name__ == "__main__":
    sys.exit(main())
