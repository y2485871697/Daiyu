"""Pure validators for the narrow terminal retention / instrumentation cloud check."""
from __future__ import annotations

import xml.etree.ElementTree as ET

TERMINAL_SCREEN = "app.daiyu.android.terminal.TerminalScreenTest"


def select_terminal_screen(mode: str, smoke_api: str, class_name: str,
                           api: int, shard: int) -> str:
    """Validate the dedicated two-API, two-shard smoke selector."""
    if (mode != "smoke" or smoke_api != "all" or class_name != TERMINAL_SCREEN
            or type(api) is not int or api not in (26, 37)
            or type(shard) is not int or shard not in (0, 1)):
        raise ValueError("invalid_terminal_screen_selector")
    return class_name


def validate_suites(suites: list[ET.Element], expected: set[str]) -> None:
    """Reject missing/duplicate/unexpected JUnit suites and unsuccessful suites."""
    names = [suite.get("name") for suite in suites]
    if len(names) != len(expected) or set(names) != expected or len(set(names)) != len(names):
        raise ValueError("junit_suite_set_mismatch")
    for suite in suites:
        try:
            tests = int(suite.get("tests", "0"))
            failures = int(suite.get("failures", "0"))
            errors = int(suite.get("errors", "0"))
            skipped = int(suite.get("skipped", "0"))
        except ValueError:
            raise ValueError("invalid_junit_counts") from None
        if tests <= 0 or failures or errors or skipped:
            raise ValueError("unsuccessful_junit_suite")
