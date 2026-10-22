"""Source-only composer color contracts; Android pixel tests are not run here."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation'
ANDROID_TEST = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'


class ComposerSurfaceColorContractTest(unittest.TestCase):
    def test_light_card_has_a_stronger_local_tint_while_dark_is_unchanged(self):
        surface = (CHAT / 'ChatComposerSurface.kt').read_text()
        self.assertIn('val tint = if (dark) Color(0xFF1F1C29) else Color(0xFFE8E3F0)', surface)
        self.assertIn('background(tint.copy(alpha = if (dark) .36f else .60f))', surface)
        self.assertIn('background.luminance() < 0.3f', surface)
        self.assertIn('RoundedCornerShape(22.dp)', surface)
        self.assertIn('blurRadius = 24f', surface)
        self.assertIn('saturation = 1.35f', surface)
        self.assertIn('if (backdrop == null) Modifier else Modifier.textureBlur', surface)
        old = [round(channel * .32 + 255 * .68) for channel in (250, 247, 255)]
        new = [round(channel * .60 + 255 * .40) for channel in (232, 227, 240)]
        self.assertEqual([241, 238, 246], new)
        self.assertTrue(all(before - after >= 9 for before, after in zip(old, new)))

    def test_send_uses_a_neutral_chat_only_fill_and_keeps_append_colors(self):
        appearance = (CHAT / 'ChatAppearance.kt').read_text()
        self.assertIn('sendContainer = if (dark) buttons.primary else Color(0xFFE8E7EE)', appearance)
        self.assertIn('sendContent = if (dark) buttons.onPrimary else buttons.accent', appearance)
        self.assertIn('appendContainer = buttons.primary', appearance)
        self.assertIn('appendContent = buttons.onPrimary', appearance)

    def test_disabled_light_idle_send_does_not_fade_its_background_again(self):
        composer = (CHAT / 'ChatComposer.kt').read_text()
        self.assertIn('.copy(alpha = if (primaryEnabled || (!dark && state.phase == ComposerPhase.IDLE)) 1f else .4f)', composer)
        self.assertIn('enabled = primaryEnabled, containerSize = 28.dp', composer)
        self.assertIn('state.textActionEnabled()', composer)
        icon = (CHAT / 'ChatTopBar.kt').read_text()
        self.assertIn('enabled = enabled, role = Role.Button', icon)
        self.assertIn('tint = if (enabled) tint else tint.copy(alpha = 0.38f)', icon)
        for enabled in (False, True):
            for dark in (False, True):
                for phase in ('IDLE', 'RUNNING', 'PAUSED'):
                    old = 1.0 if enabled else .4
                    new = 1.0 if enabled or (not dark and phase == 'IDLE') else .4
                    if dark or phase != 'IDLE':
                        self.assertEqual(old, new)

    def test_android_regressions_cover_pixels_and_disabled_clicks(self):
        glass = (ANDROID_TEST / 'ChatGlassBackdropActivityTest.kt').read_text()
        chrome = (ANDROID_TEST / 'ChatChromeActivityTest.kt').read_text()
        interaction = (ANDROID_TEST / 'ChatThemeInteractionActivityTest.kt').read_text()
        self.assertIn('fun testComposerTintStaysVisibleWithoutBackdropInBothThemes()', glass)
        self.assertIn('fun testComposerBlursTheMessagesBehindItInBothThemes()', glass)
        self.assertIn('fun testEmptySendKeepsItsCircularBackgroundWithoutBecomingClickable()', chrome)
        self.assertIn('send.assertIsNotEnabled()', chrome)
        self.assertIn('if (canSend) listOf(draft) else emptyList<String>()', chrome)
        self.assertIn('Color(0xFFE8E7EE), colors.sendContainer', interaction)
        self.assertIn('assertEquals(buttons.primary, colors.appendContainer)', interaction)
        for source in (glass, chrome):
            self.assertIn('compositeOver', source)
            self.assertIn('captureToImage()', source)


if __name__ == '__main__':
    unittest.main()
