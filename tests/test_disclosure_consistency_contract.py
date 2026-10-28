"""Cross-page folding checks; preserve lazy catalog and side-effect boundaries."""
from pathlib import Path
import unittest

UI = Path(__file__).resolve().parents[1] / 'app/src/main/kotlin/app/daiyu/android/ui'
CHAT = UI / 'screens/chat/conversation'


class DisclosureConsistencyContractTest(unittest.TestCase):
    def test_question_details_keep_their_session_but_use_shared_motion(self):
        source = (CHAT / 'QuestionCard.kt').read_text()
        self.assertIn('SettingsExpandableContent(expanded) {', source)
        self.assertIn('settingsDisclosureRotation(expanded, expandedDegrees = 180f)', source)
        self.assertIn('val session = remember(owner, binding)', source)
        self.assertIn('session.bind(record, value, canAnswer, submit)', source)
        self.assertNotIn('if (expanded) {', source)

    def test_child_controls_remain_outside_animated_details(self):
        source = (CHAT / 'ChildTaskToolContent.kt').read_text()
        self.assertIn('SettingsExpandableContent(expanded) { details() }\n        controls()', source)
        self.assertIn('settingsDisclosureRotation(expanded, expandedDegrees = 180f)', source)
        self.assertIn('var expanded by remember(row.owner)', source)
        self.assertNotIn('if (expanded) details()', source)

    def test_tool_groups_and_both_work_headers_rotate_without_swapping_icons(self):
        source = (CHAT / 'ToolCategoryContent.kt').read_text()
        self.assertIn('SettingsExpandableContent(open) { body() }', source)
        self.assertIn('settingsDisclosureRotation(expanded)', source)
        self.assertNotIn('Icon(if (expanded)', source)
        work = (CHAT / 'WorkProcessCard.kt').read_text()
        self.assertEqual(2, work.count('settingsDisclosureRotation(expanded)'))
        self.assertNotIn('Icon(if (expanded)', work)
        self.assertIn('AnimatedVisibility(expanded)', work)

    def test_usage_keeps_existing_content_motion_and_animates_both_arrows(self):
        source = (UI / 'screens/stats/UsageStatsScreen.kt').read_text()
        self.assertEqual(2, source.count('settingsDisclosureRotation(expanded)'))
        self.assertGreaterEqual(source.count('AnimatedVisibility(visible = expanded)'), 2)
        self.assertNotIn('imageVector = if (expanded)', source)

    def test_model_catalog_animates_lazy_items_without_composing_closed_catalogs(self):
        source = (CHAT / 'ChatModelPicker.kt').read_text()
        self.assertIn('settingsDisclosureRotation(open)', source)
        self.assertIn('if (open) itemsIndexed(provider.models, key = { _, model -> "model:${provider.id}:${model.id}" })', source)
        self.assertIn('Modifier.animateItem(fadeInSpec = tween(220), placementSpec = tween(220), fadeOutSpec = null)', source)
        self.assertEqual(3, source.count('.animateItem('))
        self.assertNotIn('SettingsExpandableContent(', source)
        self.assertIn('searchCollapsed = if (open)', source)
        self.assertIn('onSelectOtherProvider(provider.id, model.id)', source)


if __name__ == '__main__':
    unittest.main()
