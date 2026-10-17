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
    command = [python, "-B", "-m", "unittest", "discover", "-s", "tests", "-v"]
    log = root / "unittest.log"
    with log.open("wb") as output:
        code = subprocess.call(command, stdout=output, stderr=subprocess.STDOUT)
    receipt.write_text(json.dumps(dict(identity=identity, command=command, exit_code=code)) + "\n",
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
