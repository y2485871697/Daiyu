"""Source contracts only; Kotlin and Compose execution await the approved batch."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'

def source(name):
    return (SRC / name).read_text()

class ChatGptSpeedContractTest(unittest.TestCase):
    def test_both_gestures_use_one_host_entry(self):
        host = source('MainActivity.kt')
        self.assertIn('onCycleGptSpeed = { cycleChatGptSpeed(renderedSpeed, renderedSpeedConversation) }', host)
        self.assertIn('onCycleSpeed = { cycleChatGptSpeed(thinkingSpeed, thinkingOwner) }', host)
        composer = source('ui/screens/chat/conversation/ChatComposer.kt')
        self.assertIn('state.gptSpeedMode != null && state.gptSpeedEnabled', composer)
        self.assertIn('actions.onCycleGptSpeed else null', composer)
        self.assertIn('onClick = actions.onOpenReasoning', composer)

    def test_save_is_fenced_and_only_success_emits_speed_toast(self):
        host = source('MainActivity.kt').split('private fun cycleChatGptSpeed(', 1)[1].split('private fun refreshConfirmedModelLabel()', 1)[0]
        for text in ('expected !== confirmedGptSpeed', 'selected?.id?.value != conversationId', 'gptSpeedSaveOwner != null',
                     'controller.saveProviderSettings(expected.editor, value, expected.editing',
                     'protocolChangeAuthorized = false, requestOptionsReplacementAuthorized = false',
                     'if (gptSpeedSaveOwner !== owner) return@saveProviderSettings'):
            self.assertIn(text, host)
        self.assertIn('if (success) {', host)
        self.assertGreater(host.index('toast(chatGptSpeedToast(next))'), host.index('if (success) {'))
        self.assertNotIn('reasoningPreference.set', host)
        self.assertNotIn('customBody =', host)

    def test_old_callbacks_are_revoked_at_visit_and_selection_boundaries(self):
        host = source('MainActivity.kt')
        selection = host.split('private fun select(conversation: Conversation?, prepared:', 1)[1].split('historySearchEpoch++', 1)[0]
        self.assertIn('gptSpeedSaveOwner?.revoke(); gptSpeedSaveOwner = null', selection)
        callback = host.split('if (gptSpeedSaveOwner !== owner)', 1)[1].split('if (success) {', 1)[0]
        self.assertIn('selected?.id?.value != conversationId', callback)
        self.assertIn('confirmedGptSpeed?.editor != expected.editor', callback)
        self.assertIn('confirmedGptSpeed?.editing?.settings?.model != value.model', callback)
        self.assertIn('retainedPage != "chat"', callback)

    def test_synchronous_rejection_releases_the_gate_and_resume_reloads_committed_state(self):
        host = source('MainActivity.kt')
        cycle = host.split('private fun cycleChatGptSpeed(', 1)[1].split('private fun refreshConfirmedModelLabel()', 1)[0]
        failure = cycle.split('} } catch (_: Exception) {', 1)[1]
        self.assertIn('owner.revoke()', failure)
        self.assertIn('gptSpeedSaveOwner === owner', failure)
        self.assertIn('gptSpeedSaveOwner = null', failure)
        self.assertNotIn('chatGptSpeedToast', failure)
        resume = host.split('override fun onResume()', 1)[1].split('private fun applyPreferredRefreshRate()', 1)[0]
        self.assertIn('if (retainedPage == "chat") refreshConfirmedModelLabel()', resume)
        self.assertIn('!controller.hasLiveChildTasks()', host)

    def test_configuration_eligibility_uses_final_model_and_codex_gate(self):
        text = source('ChatGptSpeedSelection.kt')
        for token in ('ProviderProtocol.RESPONSES', 'GptServiceTier.isGptSpeedModel(model)',
                      'json.has("model")', 'editing.requestOptionsUnavailable',
                      'editing.settings.oauth != null', 'next != GptSpeedMode.ULTRA_FAST'):
            self.assertIn(token, text)
        for message in ('已切换标准速度', '已切换快速速度', '已切换极速速度'):
            self.assertIn(message, text)

    def test_two_new_theme_glyphs_and_shared_animated_projection(self):
        icon = source('ui/components/ChatThinkingSpeedIcon.kt')
        for token in ('AnimatedContent', 'scaleIn', 'scaleOut', 'fadeIn', 'fadeOut', 'togetherWith',
                      'ChatPreviewIcons.ThoughtFast', 'ChatPreviewIcons.ThoughtUltraFast', 'tint = tint'):
            self.assertIn(token, icon)
        composer = source('ui/screens/chat/conversation/ChatComposer.kt')
        picker = source('ui/components/subagent/ConversationThinkingPicker.kt')
        self.assertIn('ChatThinkingSpeedIcon(state.gptSpeedMode', composer)
        self.assertIn('ChatThinkingSpeedIcon(speedMode', picker)
        glyphs = source('ui/icons/ChatPreviewIcons.kt')
        self.assertIn('previewIcon("ThoughtFast")', glyphs)
        self.assertIn('previewIcon("ThoughtUltraFast")', glyphs)

    def test_reference_proportions_are_local_and_slider_stays_unchanged(self):
        picker = source('ui/components/subagent/ConversationThinkingPicker.kt')
        subagent, main = picker.split('ConversationAgentTypography {', 1)
        self.assertIn('ConversationThinkingMark(Modifier.size(34.dp))', subagent)
        for token in ('fontSize = 14.sp, lineHeight = 22.sp', 'Modifier.size(36.dp)',
                      'modifier = Modifier.size(24.dp)', 'fontSize = 18.sp', 'lineHeight = 28.sp', 'bottom = 13.dp',
                      'height(28.dp)', '11.dp.toPx()'):
            self.assertIn(token, main)
        self.assertIn('val speedClick = onCycleSpeed?.takeIf { speedMode != null }', main)
        self.assertNotIn('Toast', main)

if __name__ == '__main__':
    unittest.main()
