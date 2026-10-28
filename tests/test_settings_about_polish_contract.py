"""Screenshot-correction guards; device layout assertions live in Android tests."""
from pathlib import Path
import re
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
SETTINGS = SRC / 'ui/screens/settings'

class SettingsAboutPolishContractTest(unittest.TestCase):
    def test_sponsor_hides_only_its_close_button(self):
        sheet = (SETTINGS / 'SettingsSponsorDialog.kt').read_text()
        self.assertIn('ChatSelectionSheet(title = "", onDismiss = onDismiss, showCloseButton = false)', sheet)
        shell = (SRC / 'ui/screens/chat/conversation/ChatSelectionSheet.kt').read_text()
        self.assertIn('showCloseButton: Boolean = true', shell)
        self.assertIn('if (showCloseButton) Box', shell)
        self.assertIn('PreviewDialog(onDismiss, bottomSheet = true, dismissEnabled = dismissEnabled)', shell)
    def test_qq_retains_intrinsic_multicolor_paths(self):
        icons = (SRC / 'ui/icons/CommunityBrandIcons.kt').read_text()
        qq = icons.split('val TencentQQ:', 1)[1].split('val GitHub:', 1)[0]
        for color in ['Color.Black', 'Color.White', 'Color(0xFFEB1923)', 'Color(0xFFFFC028)']:
            self.assertIn(f'fill = SolidColor({color})', qq)
        self.assertGreaterEqual(qq.count('addPath('), 6)
        self.assertIn('"Brand.TencentQQ" -> Color.Unspecified', icons)
        renderer = (SRC / 'ui/icons/DaiyuIcon.kt').read_text()
        self.assertIn('colorFilter = if (tint == Color.Unspecified) null else ColorFilter.tint(tint)', renderer)
        home = (SETTINGS / 'SettingsHomeScreen.kt').read_text()
        self.assertIn('tint = communityBrandIconColor(icon) ?: daiyuIconColor(icon)', home)
    def test_relay_labels_share_parent_icon_slot_without_shifting_row(self):
        home = (SETTINGS / 'SettingsHomeScreen.kt').read_text()
        self.assertIn('private val SettingsHomeIconSlotWidth = 38.dp', home)
        self.assertIn('Box(Modifier.size(SettingsHomeIconSlotWidth)', home)
        block = home.split('SettingsExpandableContent(relayExpanded) {', 1)[1].split('\n            }', 1)[0]
        self.assertIn('SettingsRow(child.title, onClick = { open(child) },', block)
        self.assertIn('leading = { Spacer(Modifier.width(SettingsHomeIconSlotWidth)) })', block)
        self.assertNotIn('Modifier.padding(start', block)
        row = (SETTINGS / 'SettingsUiComponents.kt').read_text()
        self.assertRegex(row, re.compile(r'if \(leading != null\) \{\s+leading\(\)\s+Spacer\(Modifier.width\(12.dp\)\)'))
    def test_android_regressions_cover_close_absence_and_text_geometry(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/SettingsExternalLinksActivityTest.kt').read_text()
        self.assertIn('ui.onNodeWithContentDescription("关闭").assertDoesNotExist()', test)
        self.assertIn('fun testRelayLabelsAlignWithParentTitleAndKeepFullRowWidth()', test)
        self.assertIn('titleBounds.left', test)
        self.assertIn('rowBounds.right', test)
        self.assertIn('node("preview-dialog-scrim").assertHasClickAction().performSemanticsAction(SemanticsActions.OnClick) { click -> assertTrue(click()) }', test)
        self.assertIn('instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)', test)
if __name__ == '__main__':
    unittest.main()
