#!/usr/bin/env python3
"""Check only the explicitly listed baseline artifacts; no network or build."""
import hashlib
import json
from pathlib import Path


def verify(root):
    manifest = root / "THIRD_PARTY/task19/tracked-artifacts.json"
    records = json.loads(manifest.read_text(encoding="utf-8"))["records"]
    failures = []
    for record in records:
        path = root / record["path"]
        if not path.is_file():
            failures.append(record["path"] + ": missing")
            continue
        data = path.read_bytes()
        if len(data) != record["bytes"] or hashlib.sha256(data).hexdigest() != record["sha256"]:
            failures.append(record["path"] + ": byte identity mismatch")
    return len(records), failures


def main():
    root = Path(__file__).resolve().parents[2]
    count, failures = verify(root)
    if failures:
        print("\n".join(failures))
        return 1
    print(f"{count} listed artifacts/license files match; completeness and APK contents not evaluated")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
