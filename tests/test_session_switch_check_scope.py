"""The session switch slice reuses cold-start without owning unrelated schema work."""
import ast
from pathlib import Path
import re
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[1]

class SessionSwitchCheckScope(unittest.TestCase):
    def test_scope_changes_only_the_explicit_contract_difference(self):
        workflow = (ROOT / '.github/workflows/chat-workflow-checks.yml').read_text()
        options = workflow.split('options: [', 1)[1].split(']', 1)[0]
        self.assertIn('session-switch', options)
        start = workflow.index('          import json, os, unittest')
        end = workflow.index('          for pattern in patterns:', start)
        source = textwrap.dedent(workflow[start:end])
        def patterns(scope):
            fake_os = type('Env', (), {'environ': {'WORKFLOW_CHECK_SCOPE': scope}})()
            tree = ast.parse(source)
            tree.body = [node for node in tree.body if not isinstance(node, (ast.Import, ast.ImportFrom))]
            env = {'os': fake_os}
            exec(compile(tree, '<workflow-contract-scope>', 'exec'), env)
            return env['patterns']
        cold, selected = patterns('cold-start'), patterns('session-switch')
        self.assertIn('test_context_usage_wiring_contract.py', cold)
        self.assertEqual(tuple(p for p in cold if p != 'test_context_usage_wiring_contract.py') +
                         ('test_page_back_repeat_contract.py', 'test_session_switch_check_scope.py'), selected)
        self.assertEqual(len(selected), len(set(selected)))
        self.assertIn('if [[ "$WORKFLOW_CHECK_SCOPE" == cold-start || "$WORKFLOW_CHECK_SCOPE" == session-switch ]]; then', workflow)
        self.assertEqual(2, workflow.count("if os.environ['WORKFLOW_CHECK_SCOPE'] in ('cold-start', 'session-switch'):"))
        report = workflow.split("if os.environ['WORKFLOW_CHECK_SCOPE'] in ('cold-start', 'session-switch'):", 2)[2].split("if os.environ['WORKFLOW_CHECK_SCOPE'] == 'task-scroll':", 1)[0]
        for suite in ('ToolReceiptSourceTest', 'ToolActivityUiTest', 'StreamingHistoryRefreshTest'):
            self.assertIn(suite, report)
        self.assertIn('assert names == expected', workflow)
        self.assertIn('assert not packages', workflow)

if __name__ == '__main__':
    unittest.main()
