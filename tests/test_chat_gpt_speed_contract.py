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
                     'controller.saveConversationGptSpeed(conversationId?.let(::ConversationId), expected.editor, expected.editing, value, owner)',
                     'if (gptSpeedSaveOwner !== owner) return@saveConversationGptSpeed'):
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

    def test_configuration_eligibility_uses_final_model_without_obsolete_codex_gate(self):
        text = source('ChatGptSpeedSelection.kt')
        for token in ('ProviderProtocol.RESPONSES', 'GptServiceTier.isGptSpeedModel(model)',
                      'json.has("model")', 'editing.requestOptionsUnavailable'):
            self.assertIn(token, text)
        self.assertNotIn('Codex 不支持', text)
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

    def test_speed_save_retains_only_visual_enablement_and_not_interaction(self):
        host = source('MainActivity.kt')
        self.assertIn('val composerAvailable =', host)
        projection = host.split('val composerAvailable =', 1)[1].split('private fun cycleChatGptSpeed(', 1)[0]
        self.assertIn('val allowed = composerAvailable && !controller.dataBusy', projection)
        self.assertIn('gptSpeedSaving = composerAvailable && gptSpeedSaveOwner != null', projection)
        self.assertIn('enabled = allowed', projection)
        composer = source('ui/screens/chat/conversation/ChatComposer.kt')
        self.assertIn('val visualEnabled = state.enabled || state.gptSpeedSaving', composer)
        for unchanged in ('val toolbarEnabled = state.enabled && !editing', 'enabled = state.enabled,',
                          '.controlGesture(actions, primaryEnabled)', 'enabled = primaryEnabled || compressionBlocked,',
                          'mountedGeneration == currentEditorGeneration && state.enabled && currentEditorEnabled'):
            self.assertIn(unchanged, composer)
        self.assertIn('state.copy(enabled = visualEnabled).textActionEnabled()', composer)
        self.assertIn('visualEnabled = primaryVisualEnabled', composer)
        self.assertIn('if (compressionBlocked) actions.onCompressionSendBlocked()', composer)
        model = composer.split('ComposerChip(state.modelLabel', 1)[1].split('if (editing) ComposerCancelEdit', 1)[0]
        self.assertIn('state.enabled, actions.onModelClick', model)
        self.assertIn('visualEnabled = visualEnabled)', model)
        self.assertIn('val attachmentEnabled = state.enabled', composer)
        self.assertIn('enabled = attachmentEnabled', composer)
        self.assertIn('ComposerReferenceChips(references.references, state.enabled, visualEnabled)', composer)
        model = source('ui/screens/chat/conversation/ChatScreenModels.kt')
        self.assertIn('val gptSpeedSaving: Boolean = false', model)
        self.assertIn('fun ComposerUi.textActionEnabled(): Boolean = enabled &&', model)

    def test_optional_visual_enablement_is_not_used_as_an_action_gate(self):
        icon = source('ui/screens/chat/conversation/ChatTopBar.kt').split('internal fun ChatIconButton(', 1)[1]
        self.assertIn('visualEnabled: Boolean = enabled', icon)
        self.assertIn('enabled = enabled, role = Role.Button', icon)
        self.assertIn('tint = if (visualEnabled) tint else tint.copy(alpha = 0.38f)', icon)
        chip = source('ui/screens/chat/conversation/ChatComposer.kt').split('internal fun ComposerChip(', 1)[1]
        self.assertIn('visualEnabled: Boolean = enabled', chip)
        self.assertIn('indication = null, enabled = enabled, role = Role.Button', chip)
        self.assertIn('val contentColor = if (visualEnabled) chrome.toolbar else colors.disabledOnSurface', chip)
        references = source('ui/screens/chat/conversation/ComposerReferenceChips.kt')
        self.assertIn('visualEnabled: Boolean = enabled', references)
        self.assertIn('enabled = enabled, visualEnabled = visualEnabled', references)

    def test_reference_proportions_are_local_and_slider_stays_unchanged(self):
        picker = source('ui/components/subagent/ConversationThinkingPicker.kt')
        subagent, main = picker.split('ConversationAgentTypography {', 1)
        self.assertIn('ConversationThinkingMark(Modifier.size(34.dp))', subagent)
        for token in ('fontSize = 20.sp, lineHeight = 28.sp', 'Modifier.size(48.dp)',
                      'modifier = Modifier.size(36.dp).testTag("chat-thinking-speed-icon")',
                      'fontSize = 22.sp', 'lineHeight = 30.sp', 'bottom = 13.dp',
                      'height(28.dp)', '11.dp.toPx()'):
            self.assertIn(token, main)
        self.assertIn('val speedClick = onCycleSpeed?.takeIf { speedMode != null }', main)
        self.assertNotIn('Toast', main)
        regression = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ChatGptSpeedActivityTest.kt').read_text()
        self.assertIn('testPopupLargerIconAndValueRetainSliderAndReopeningDoesNotWriteSpeed', regression)
        for token in ('assertWidthIsEqualTo(48.dp)', 'assertWidthIsEqualTo(36.dp)',
                      'assertHeightIsEqualTo(28.dp)', '22f, value.layoutInput.style.fontSize.value',
                      '30f, value.layoutInput.style.lineHeight.value'):
            self.assertIn(token, regression)

if __name__ == '__main__':
    unittest.main()
