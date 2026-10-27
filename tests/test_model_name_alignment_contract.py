"""Source wiring only; rendering/geometry checks live in ModelNameAlignmentTest."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/kotlin/app/daiyu/android'


def source(path):
    return (SOURCE / path).read_text()


class ModelNameAlignmentContract(unittest.TestCase):
    def test_shared_correction_only_offsets_content_without_changing_measurement(self):
        helper = source('ui/icons/ModelNameAlignment.kt')
        self.assertIn('fun Modifier.modelNameOpticalAlignment(): Modifier = offset(y = (-1).dp)', helper)
        for forbidden in ('padding(', 'size(', 'height(', 'graphicsLayer', 'clickable', 'fontSize', 'lineHeight'):
            self.assertNotIn(forbidden, helper)

    def test_composer_replaces_previous_correction_instead_of_stacking_it(self):
        composer = source('ui/screens/chat/conversation/ChatComposer.kt').split('internal fun ComposerChip(', 1)[1]
        self.assertIn('weight(1f, fill = false).modelNameOpticalAlignment()', composer)
        self.assertEqual(1, composer.count('modelNameOpticalAlignment()'))
        self.assertNotIn('offset(', composer)
        for preserved in ('heightIn(min = 26.dp)', 'Modifier.size(13.dp)', 'fontSize = 13.sp, lineHeight = 18.sp',
                          'PlatformTextStyle(includeFontPadding = false)', 'onClick = onClick, onLongClick = onLongClick'):
            self.assertIn(preserved, composer)

    def test_picker_moves_name_and_id_together_not_icon_indicator_or_touch_row(self):
        picker = source('ui/screens/chat/conversation/ChatModelPicker.kt')
        self.assertIn('Column(Modifier.weight(1f).modelNameOpticalAlignment()) {\n                                        Text(model.displayName', picker)
        self.assertEqual(1, picker.count('modelNameOpticalAlignment()'))
        for preserved in ('heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 12.dp)',
                          'fontSize = 16.sp, lineHeight = 24.sp', 'Modifier.padding(top = 3.dp)',
                          'Modifier.size(22.dp).testTag("chat-model-brand:', 'Box(Modifier.size(22.dp).testTag("chat-model-indicator:'):
            self.assertIn(preserved, picker)
        self.assertNotIn('modelNameOpticalAlignment', source('ui/icons/ModelBrandIcon.kt'))

    def test_subagent_only_corrects_name_and_keeps_chip_background_stationary(self):
        row = source('ui/components/SubAgentProfileRow.kt')
        chat = row.split('} else if (LocalConversationAgentStyle.current) {', 1)[1].split('\n        } else {', 1)[0]
        self.assertIn('modelNameOpticalAlignment().testTag("subagent-model-name-${profile.id}")', chat)
        self.assertEqual(1, row.count('modelNameOpticalAlignment()'))
        chip = chat.split('Text(providerName,', 1)[1].split('Icon(Icons.Rounded.ExpandMore', 1)[0]
        self.assertNotIn('modelNameOpticalAlignment', chip)
        self.assertIn('onLongClick = { if (currentUsable && canThink) { thinkingPicker = true } }', chat)

    def test_settings_alignment_is_opt_in_for_real_model_and_provider_names(self):
        settings = source('ui/screens/settings/SettingsUiComponents.kt')
        self.assertIn('textModifier: Modifier = Modifier,', settings)
        self.assertIn('Column(Modifier.weight(1f).then(textModifier)', settings)
        self.assertNotIn('modelNameOpticalAlignment', settings)
        models = source('ui/screens/settings/ModelSettingsScreen.kt')
        self.assertIn('title = entry.displayName,\n                            textModifier = Modifier.modelNameOpticalAlignment()', models)
        providers = source('ui/screens/settings/ProviderDirectoryPage.kt')
        self.assertIn('SettingsRow(registration.name,tag="provider-open-${registration.slotId}",textModifier=Modifier.modelNameOpticalAlignment()', ''.join(providers.split()))
        self.assertEqual(1, providers.count('modelNameOpticalAlignment()'))

    def test_all_direct_brand_icon_presentations_use_shared_alignment(self):
        paths = {p.relative_to(SOURCE).as_posix() for p in SOURCE.rglob('*.kt')
                 if 'ModelBrandIcon(' in p.read_text() and p.name != 'ModelBrandIcon.kt'}
        self.assertEqual({
            'ui/screens/chat/conversation/ChatComposer.kt',
            'ui/screens/chat/conversation/ChatModelPicker.kt',
            'ui/components/SubAgentProfileRow.kt',
            'ui/screens/settings/ModelSettingsScreen.kt',
        }, paths)
        for path in paths:
            self.assertIn('modelNameOpticalAlignment()', source(path), path)


    def test_picker_geometry_is_sampled_in_one_ui_turn_after_entry_and_scroll(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/icons/ModelNameAlignmentTest.kt').read_text()
        helper = test.split('private fun pickerGeometry(', 1)[1].split('fun testSharedCorrection', 1)[0]
        lookup, sample = helper.split('return ui.runOnIdle {', 1)
        self.assertIn('.assertIsDisplayed().fetchSemanticsNode()', lookup)
        self.assertNotIn('fetchSemanticsNode()', sample)
        self.assertIn('assertSame(rowNode.root, node.root)', sample)
        self.assertIn('val position = node.positionInRoot', sample)
        self.assertIn('val size = node.size', sample)
        self.assertNotIn('boundsInRoot', helper)
        actual = test.split('fun testActualPickerKeepsSingleAndTwoLineNameBlocksAtSameOpticalOffset()', 1)[1].split('fun testSettingsCorrection', 1)[0]
        self.assertLess(actual.index('awaitDialogEnter('), actual.index('performScrollToKey('))
        self.assertLess(actual.index('performScrollToKey('), actual.index('awaitVisualIdle()'))
        self.assertLess(actual.index('awaitVisualIdle()'), actual.index('pickerGeometry(id, hasApiId)'))
        for oracle in ('icon.center.y - density.roundToInt(), (name.top + contentBottom) / 2f, 1f',
                       'row.center.y, icon.center.y, 1f', '22f, icon.width / density, .5f',
                       'row.height / density >= 55.5f', 'name.bottom <= checkNotNull(apiId).top'):
            self.assertIn(oracle, actual)


if __name__ == '__main__':
    unittest.main()
