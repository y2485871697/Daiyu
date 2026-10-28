package app.daiyu.android

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import app.daiyu.android.ui.haptics.miuix.TextButton
import app.daiyu.android.ui.screens.assistants.assistantPreviewPalette
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import app.daiyu.android.ui.screens.settings.SettingsCard

@Composable
internal fun VoiceSettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    SettingsCard(modifier, content)
}

@Composable
internal fun VoiceAccountSaveButton(text: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = assistantPreviewPalette()
    TextButton(text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        .fillMaxWidth().testTag(tag),
        minHeight = 48.dp, cornerRadius = 24.dp,
        colors = ButtonDefaults.textButtonColorsPrimary(color = colors.primary, textColor = colors.onPrimary),
        enabled = enabled, onClick = onClick)
}

internal fun VoiceConfiguration.withSpeech(value: VoiceConfiguration) = copy(
    engine = value.engine, endpoint = value.endpoint, key = value.key, model = value.model,
    provider = value.provider, voice = value.voice, customHeaders = value.customHeaders,
    modelIdentity = value.modelIdentity, modelLabel = value.modelLabel,
)

internal fun mergeVoiceSettingsDraft(previous: VoiceConfiguration, draft: VoiceConfiguration,
    saved: VoiceConfiguration): VoiceConfiguration = saved.copy(
    engine = if (draft.engine != previous.engine) draft.engine else saved.engine,
    endpoint = if (draft.endpoint != previous.endpoint) draft.endpoint else saved.endpoint,
    key = if (draft.key != previous.key) draft.key else saved.key,
    model = if (draft.model != previous.model) draft.model else saved.model,
    provider = if (draft.provider != previous.provider) draft.provider else saved.provider,
    voice = if (draft.voice != previous.voice) draft.voice else saved.voice,
    customHeaders = if (draft.customHeaders != previous.customHeaders) draft.customHeaders else saved.customHeaders,
    modelIdentity = if (draft.modelIdentity != previous.modelIdentity) draft.modelIdentity else saved.modelIdentity,
    modelLabel = if (draft.modelLabel != previous.modelLabel) draft.modelLabel else saved.modelLabel,
    dictation = if (draft.dictation != previous.dictation) draft.dictation else saved.dictation,
    asrKey = if (draft.asrKey != previous.asrKey) draft.asrKey else saved.asrKey,
    asrResource = if (draft.asrResource != previous.asrResource) draft.asrResource else saved.asrResource,
    duplexKey = if (draft.duplexKey != previous.duplexKey) draft.duplexKey else saved.duplexKey,
    duplexProvider = if (draft.duplexProvider != previous.duplexProvider) draft.duplexProvider else saved.duplexProvider,
    duplexVoice = if (draft.duplexVoice != previous.duplexVoice) draft.duplexVoice else saved.duplexVoice,
    instructions = if (draft.instructions != previous.instructions) draft.instructions else saved.instructions,
)

internal val VoiceConfiguration.historyModel: String get() = modelIdentity.ifBlank { model }
