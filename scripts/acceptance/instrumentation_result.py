#!/usr/bin/env python3
"""One strict am instrument -w summary parser for live smoke and offline review.

No normalization except CRLF; no execution, environment reads or derived flags.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys

NEGATIVE = re.compile(
    r"\b(?:fail(?:ure|ures|ed)?|crash(?:ed|ing)?|fatal|exception|errors?|"
    r"abort(?:ed)?|timed[ -]?out|timeout|cancel(?:led|ed)?|skipped|ignored)\b"
    r"|\b[A-Za-z_$][\w$]*(?:Error|Exception)\b"
    r"|INSTRUMENTATION_(?:FAILED|ABORTED)|shortMsg\s*=", re.IGNORECASE)
OK = re.compile(r"OK \(([1-9][0-9]{0,8}) tests?\)\Z")
MAX_TEXT_BYTES = 4 * 1024 * 1024


class ResultError(ValueError):
    pass


def require(condition, code):
    if not condition:
        raise ResultError(code)


def report_text(raw):
    try:
        text = raw.decode("utf-8").replace("\r\n", "\n")
    except UnicodeError:
        raise ResultError("invalid_report_encoding") from None
    require(text.strip() and all(c in "\n\t" or (ord(c) >= 32 and ord(c) != 127)
                                 for c in text), "invalid_report_text")
    require(not NEGATIVE.search(text), "negative_report_signal")
    return text


def instrumentation(raw, expected_tests):
    require(type(expected_tests) is int and 0 < expected_tests <= 999999999,
            "invalid_expected_tests")
    text = report_text(raw)
    successes, terminal_codes = [], []
    for line in text.split("\n"):
        require("INSTRUMENTATION_" not in line
                or (line.startswith("INSTRUMENTATION_") and line.count("INSTRUMENTATION_") == 1),
                "unsupported_instrumentation_line")
        match = OK.fullmatch(line)
        if match:
            successes.append(int(match[1]))
        elif re.search(r"\bOK\b", line):
            raise ResultError("ambiguous_instrumentation_ok")
        if line.startswith("INSTRUMENTATION_CODE:"):
            require(line == "INSTRUMENTATION_CODE: -1", "instrumentation_terminal_conflict")
            terminal_codes.append(line)
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            require(line in ("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: 1"),
                    "instrumentation_status_conflict")
        elif line.startswith("INSTRUMENTATION_STATUS: numtests="):
            require(line == f"INSTRUMENTATION_STATUS: numtests={expected_tests}",
                    "instrumentation_count_conflict")
        elif line.startswith("INSTRUMENTATION_STATUS: current="):
            count = line.partition("=")[2]
            require(re.fullmatch(r"[1-9][0-9]{0,8}", count)
                    and int(count) <= expected_tests, "instrumentation_count_conflict")
        elif line.startswith("INSTRUMENTATION_RESULT:"):
            require(line.startswith("INSTRUMENTATION_RESULT: stream="),
                    "unsupported_instrumentation_result")
        elif line.startswith("INSTRUMENTATION_"):
            require(line.startswith(tuple("INSTRUMENTATION_STATUS: " + key + "="
                                          for key in ("id", "class", "test", "stream"))),
                    "unsupported_instrumentation_line")
    require(len(successes) == 1 and successes[0] == expected_tests,
            "instrumentation_unique_ok_required")
    require(len(terminal_codes) == 1, "instrumentation_terminal_conflict")
    # A terminal success before the summary, or followed by another frame, is
    # not the final result of this invocation. Do not merge repeated runs.
    require(text.rstrip("\n").endswith("\nINSTRUMENTATION_CODE: -1"),
            "instrumentation_not_terminal")
    return successes[0]


class Parser(argparse.ArgumentParser):
    def error(self, message):
        raise ResultError("invalid_cli_arguments")


def main(argv=None):
    parser = Parser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--expected-tests", required=True, type=int)
    try:
        args = parser.parse_args(argv)
        with args.output.open("rb") as stream:
            raw = stream.read(MAX_TEXT_BYTES + 1)
        require(len(raw) <= MAX_TEXT_BYTES, "file_too_large")
        count = instrumentation(raw, args.expected_tests)
    except ResultError as error:
        print(json.dumps({"valid": False, "error": str(error)}, sort_keys=True))
        return 2
    except (OSError, ValueError, TypeError, OverflowError):
        print('{"valid": false, "error": "unreadable_or_invalid_input"}')
        return 2
    print(json.dumps({"valid": True, "tests_passed": count}, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
