"""Exact affected-page inventory; unknown or incomplete filters still fail closed."""
import json
from pathlib import Path
import tempfile
import unittest

from test_record_tool_tests_scope import recorder
import test_task_scroll_smoke_scope as task_scroll

EXTRAS = (
    'app.daiyu.android.UsagePresentationActivityTest',
    'app.daiyu.android.LocalCatalogModelsActivityTest',
    'app.daiyu.android.ModelBulkSelectionActivityTest',
    'app.daiyu.android.AssistantAvatarTest',
    'app.daiyu.android.AssistantNavigationActivityTest',
    'app.daiyu.android.AssistantPickerAdmissionTest',
    'app.daiyu.android.SkillInstallScreenTest',
    'app.daiyu.android.ui.navigation.PageBackGesturesActivityTest',
    'app.daiyu.android.ui.navigation.PageBackStackActivityTest',
    'app.daiyu.android.ui.navigation.PageEnterActivityTest',
    'app.daiyu.android.ui.navigation.PageRetainedContentActivityTest',
)
TARGETS = task_scroll.CLASSES + EXTRAS


class PagePerformanceSmokeScopeTests(unittest.TestCase):
    def test_exact_scope_adds_affected_pages_and_preserves_original_scroll_guards(self):
        self.assertEqual(TARGETS, recorder.PAGE_PERFORMANCE_PLATFORM_CLASSES)
        for names in (TARGETS, tuple(reversed(TARGETS))):
            command, scope = recorder.command_for('python3', dict(SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=','.join(names)))
            self.assertEqual('page-performance-targeted', scope)
            self.assertNotIn('discover', command)
            self.assertTrue(set(recorder.TASK_SCROLL_MODULES) <= set(command))
            self.assertIn('test_page_performance_contract', command)
        for raw in (','.join(TARGETS[:-1]), ','.join(TARGETS) + ',' + TARGETS[0],
                    ','.join(TARGETS) + ',app.daiyu.android.UnknownTest'):
            self.assertEqual('full', recorder.command_for('python3', dict(SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=raw))[1])

    def test_exact_inventory_assigns_every_method_once_and_does_not_scan_unrelated_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = task_scroll.TaskScrollSmokeScopeTests().fixture(root)
            for name in EXTRAS:
                path = root / 'app/src/androidTest/kotlin' / (name.replace('.', '/') + '.kt')
                path.parent.mkdir(parents=True, exist_ok=True)
                package, _, short = name.rpartition('.')
                path.write_text('\n'.join(('package ' + package, 'class ' + short,
                                           ' : TestCase() {', ' fun testOne() {}', '}', '')))
            selected = []
            for shard in range(4):
                result = helper.select(root, shard, classes=','.join(TARGETS))
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = json.loads((root / 'smoke/instrumentation-shards.json').read_text())
                selection = json.loads((root / 'smoke/selection.json').read_text())
                self.assertEqual('targeted', inventory['discovery_scope'])
                self.assertEqual(set(TARGETS), set(inventory['all_classes']))
                selected.extend(selection['selected_classes'])
            self.assertCountEqual(TARGETS, selected)
