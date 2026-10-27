"""Creation presentation and callback guards; no network, recording or paid calls."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"


def block(text, marker):
    start = text.index("{", text.index(marker))
    depth = 0
    for index in range(start, len(text)):
        depth += (text[index] == "{") - (text[index] == "}")
        if depth == 0:
            return text[start + 1:index]
    raise AssertionError(marker)


class VoiceCreationCardsContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.panel = (SRC / "VoiceAccountPanel.kt").read_text()
        first = 'if (page == "CREATE" && step == 0)'
        if first not in cls.panel:
            first = 'if (page == "IMPORT" || (page == "CREATE" && step == 0))'
        cls.steps = [block(cls.panel, first)] + [
            block(cls.panel, f'if (page == "CREATE" && step == {step})') for step in (1, 2)]

    def test_only_creation_opts_into_the_real_settings_scaffold(self):
        self.assertIn('settingsStyle = page == "CREATE"', self.panel)
        scaffold = (SRC / "VoicePersonalScaffold.kt").read_text()
        self.assertIn('settingsStyle: Boolean = false', scaffold)
        self.assertIn('if (settingsStyle) SettingsScaffoldPage(title, onBack, compactTitle = true, content = content)', scaffold)
        self.assertIn('else MiuixScaffoldPage(title, onBack, compactTitle = true', scaffold)
        self.assertNotIn('LocalSettingsPageStyle provides', scaffold)

    def test_each_step_uses_independent_shared_cards_without_custom_paint(self):
        cards = [
            ['name', 'advanced', 'slots', 'sync', 'manual', 'next-sample'],
            ['sample', 'next-confirm'],
            ['summary', 'consent', 'submit'],
        ]
        for step, ids in zip(self.steps, cards):
            for card in ids:
                self.assertIn(f'item {{ SettingsCard(Modifier.testTag("voice-create-{card}-card"))', step)
            for old in ('VoiceSettingsCard', 'TextButton(', '.background(', '.clip('):
                self.assertNotIn(old, step)
        self.assertIn('SettingsRow("名称", summary = name)', self.steps[2])
        self.assertIn('summary = if (postpaid) null else slotId', self.steps[2])

    def test_first_step_preserves_edit_selection_fallback_and_navigation(self):
        first = self.steps[0]
        for required in (
            'name = it.take(80); confirmation = null',
            'if (advancedCreation) SettingsSwitchRow("新建后付费音色", postpaid, enabled = enabled)',
            'personalVoiceSlots(voices, creating = true, postpaid = postpaid)',
            'enabled = enabled && voice.canTrain',
            'slotId = voice.id; postpaid = false; confirmation = null',
            'if (!enabled) null else ({ navigate("SYNC") })',
            'if (busy) null else ({ manualId = !manualId })',
            'AccountField("名额 ID", slotId, busy)',
            'if (!enabled || name.isBlank() || (!postpaid && (!slotId.matches(Regex("S_[A-Za-z0-9_-]+")) || voices.firstOrNull { it.id == slotId }?.canTrain == false))) null',
            'step = 1; agreed = false',
        ):
            self.assertIn(required, first)
        self.assertIn('if (slots.isNotEmpty()) item', first)
        self.assertNotIn('resources.voices.create(', first)

    def test_sample_selection_does_not_read_or_upload_audio(self):
        sample = self.steps[1]
        for required in ('tag = "voice-training-sample"', 'if (!enabled || pickerPending) null',
                         'samplePicker.begin(frozenConfiguration.revision)', 'picker.launch("audio/*")',
                         '未能打开录音选择器', 'if (!enabled || sampleUri == null) null',
                         'step = 2; agreed = false'):
            self.assertIn(required, sample)
        self.assertNotIn('readSample(', sample)
        self.assertNotIn('resources.voices.create(', sample)

    def test_submission_keeps_consent_confirmation_and_one_original_request(self):
        final = self.steps[2]
        for required in ('SettingsSwitchRow("我有权使用此录音并同意本次上传制作", agreed, enabled = enabled)',
                         'tag = "voice-training-submit"', 'if (!enabled || !agreed || sampleUri == null) null',
                         'ask("上传所选录音', 'PersonalVoices.readSample(context, uri)',
                         'check(current()); resources.voices.create(speech!!, sample, n, slot, confirmed = true)',
                         'finally { sample.clear(); sampleUri = null; agreed = false }'):
            self.assertIn(required, final)
        self.assertEqual(1, final.count('resources.voices.create('))
        self.assertIn('confirmation === pending && enabled', self.panel)
        self.assertIn('confirmation = null; operation?.cancel(); preview.stop()', self.panel)

    def test_import_stays_on_its_original_card_and_query_only_path(self):
        self.assertIn('if (page == "IMPORT") item', self.panel)
        imported = block(self.panel, 'if (page == "IMPORT") item')
        for required in ('VoiceSettingsCard {', 'AccountField("备注名称（可空）", name, busy)',
                         'personalVoiceSlots(voices, creating = false, postpaid = postpaid)',
                         'existingId == voice.id', 'enabled = enabled, summary = voiceSlotSummary(voice)',
                         'AccountField("已有音色 ID", existingId, busy)',
                         'if (!enabled || !existingId.matches(Regex("(?:S_|etaClone)[A-Za-z0-9_-]+"))) null',
                         'resources.voices.importExisting(speech!!, id, n)'):
            self.assertIn(required, imported)
        self.assertNotIn('SettingsCard(', imported)
        self.assertNotIn('resources.voices.create(', imported)


if __name__ == '__main__':
    unittest.main()
