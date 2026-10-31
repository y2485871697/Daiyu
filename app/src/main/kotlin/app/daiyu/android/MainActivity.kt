package app.daiyu.android

import app.daiyu.android.ui.components.materialImeCursorColor
import app.daiyu.android.ui.components.managedInput

import app.daiyu.android.ui.haptics.setHapticNegativeButton
import app.daiyu.android.ui.haptics.setHapticPositiveButton
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import app.daiyu.android.ui.navigation.PageBackHandler as BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.screens.chat.attachments.ImageAttachments
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import app.daiyu.android.ui.components.dialogEnterMotion
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.daiyu.android.ui.navigation.RootExitConfirmation
import androidx.lifecycle.lifecycleScope
import app.daiyu.android.tasksurface.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.daiyu.android.voice.AndroidSpeechPlayer
import app.daiyu.android.voice.AudioOwnerGate
import app.daiyu.android.voice.VoiceOwner
import app.daiyu.android.voice.ActivityVoiceAudio
import app.daiyu.android.voice.control.*
import app.daiyu.android.voice.releaseOnMain
import app.daiyu.android.voice.ComposerDictationHost
import app.daiyu.android.voice.DictationDraftTarget
import app.daiyu.android.voice.SpeechError
import app.daiyu.android.voice.SpeechPhase
import app.daiyu.android.voice.SpeechPlaybackState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.daiyu.android.model.catalog.CatalogModelParameters
import app.daiyu.android.assistants.AssistantSkill
import app.daiyu.android.assistants.AssistantSelectionOwner
import app.daiyu.android.context.ContextService
import app.daiyu.android.context.ContextManagementOwner
import app.daiyu.android.context.ContextManagementControllerAdapter
import app.daiyu.android.ui.screens.chat.context.ContextManagementRoute
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceSettingsStore
import app.daiyu.android.delegation.ConversationSubAgentPreferences
import app.daiyu.android.delegation.ReasoningEffort
import app.daiyu.android.domain.*
import app.daiyu.android.domain.appearance.IconNotificationPort
import app.daiyu.android.domain.appearance.IconSyncResult
import app.daiyu.android.domain.runtime.ExecutionPhase
import app.daiyu.android.domain.runtime.SupplementAdmission
import app.daiyu.android.ui.actions.ConversationExportSelection
import app.daiyu.android.ui.actions.SupplementEditor
import app.daiyu.android.ui.screens.conversation.ChildTaskRecordsScreen
import app.daiyu.android.storage.ProviderEditorSlot
import app.daiyu.android.storage.EditingSettings
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.ui.FeatureRoute
import app.daiyu.android.ui.RetainedConversationData
import app.daiyu.android.ui.BrowserApprovalHost
import app.daiyu.android.ui.ChildBrowserApprovalHost
import app.daiyu.android.ui.ShizukuApprovalHost
import app.daiyu.android.ui.screens.shizuku.ShizukuScreen
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.daiyu.android.ui.SubAgentSettingsScreen
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.app.resolveAppearanceColors
import app.daiyu.android.ui.app.colorContentColor
import app.daiyu.android.ui.components.*
import app.daiyu.android.ui.components.subagent.SubAgentThinkingEffortPickerDialog
import app.daiyu.android.ui.screens.chat.conversation.ComposerReferences
import app.daiyu.android.ui.model.ConversationPaneUiState
import app.daiyu.android.ui.model.ConversationSummaryUi
import app.daiyu.android.ui.model.MessageSearchHit
import app.daiyu.android.ui.screens.assistants.AssistantEditScreen
import app.daiyu.android.ui.screens.assistants.AssistantPickerDialog
import app.daiyu.android.assistants.create
import app.daiyu.android.ui.screens.chat.ManageChatsScreen
import app.daiyu.android.ui.screens.chat.conversation.*
import app.daiyu.android.ui.screens.settings.*
import app.daiyu.android.ui.screens.search.ConversationSearchScreen
import app.daiyu.android.ui.app.SearchHistoryDialog
import app.daiyu.android.ui.screens.search.MessageSearchScreen
import app.daiyu.android.ui.screens.stats.UsageStatsScreen
import app.daiyu.android.vdapproval.R08PlanEditorHost
import app.daiyu.android.vdapproval.R08PlanEditor
import app.daiyu.android.vdapproval.R08FrozenPlan
import app.daiyu.android.vdapproval.R08CleanupUiOwner
import app.daiyu.android.vdapproval.R08CleanupApprovalRequest
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.ui.graphics.asImageBitmap

internal val RetainedPausedResumeAction = androidx.compose.ui.semantics.SemanticsPropertyKey<() -> Unit>("RetainedPausedResumeAction")

class MainActivity : ComponentActivity() {
    private val controller get() = (application as ChatApplication).controller
    private var balanceRuntime by mutableStateOf<app.daiyu.android.balance.BalanceRuntime?>(null)
    private val pets get() = (application as ChatApplication).pets
    private val openPetSettings: () -> Unit = {
        if (activityUsable()) { dismissSidebar(); retainedPage = "pet-settings" }
    }
    private val terminalProvider get() = (application as ChatApplication).terminalProvider
    private var linuxSettingsVisit: Any? = null
    private var linuxSettingsPageOwner by mutableStateOf<app.daiyu.android.terminal.LinuxSettingsPageOwner?>(null)
    private var terminalPageOwner by mutableStateOf<Any?>(null)
    private var terminalReturnPage = "settings"
    private var linuxSettingsReturnPage = "settings"
    private val mcpSettings by lazy { app.daiyu.android.mcp.McpSettingsStore(applicationContext) }
    private var mcpSettingsPageOwner: app.daiyu.android.mcp.McpSettingsOwner? = null
    private val engineeringUi get() = (application as ChatApplication).engineeringUiForTests
    private val browserSettings get() = controller.browserSettings
    private var titleSettingsPageOwner: app.daiyu.android.title.TitleModelSettingsEditor? = null
    private var compressionSettingsPageOwner: app.daiyu.android.context.CompressionSettingsEditor? = null
    private class GenerationSettingsVisit(val editor: GenerationSettingsEditor) {
        val active = AtomicBoolean(true)
    }
    private var generationSettingsVisit by mutableStateOf<GenerationSettingsVisit?>(null)
    private var browserSettingsPageOwner: app.daiyu.android.browser.BrowserSettingsSaveOwner? = null
    private var reconnectSettingsPageOwner by mutableStateOf<app.daiyu.android.model.reconnect.ReconnectSettingsSaveOwner?>(null)
    private var platformSettingsPageOwner: Any? = null
    private var platformSettingsRevision by mutableStateOf(0)
    private val platformSaf by lazy { app.daiyu.android.platform.PlatformSafGrants(applicationContext) }
    private var platformPermissionOwner: Any? = null
    private var platformSafRequest: Pair<Any, Boolean>? = null
    private fun platformPageCurrent(owner: Any?) = owner != null && platformSettingsPageOwner === owner &&
        activityUsable() && retainedPage == "platform-settings"
    private val platformPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val owner = platformPermissionOwner; platformPermissionOwner = null
        if (platformPageCurrent(owner)) {
            platformSettingsRevision++
            toast("系统授权结果已更新；工具选择和私密出站同意未更改")
        }
    }
    private val platformSafPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request = platformSafRequest; platformSafRequest = null
        if (request != null && platformPageCurrent(request.first) && result.resultCode == RESULT_OK) {
            val accepted = result.data?.let { platformSaf.accept(it, request.second) } == true
            platformSettingsRevision++
            toast(if (accepted) "精确持久只读授权已保存；未开启工具，未发送URI或数据" else "未确认精确持久只读授权；文件访问仍拒绝")
        }
    }
    private fun requestPlatformPermission(tool: app.daiyu.android.platform.PlatformReadTool, permission: String) {
        val owner = platformSettingsPageOwner
        if (!platformPageCurrent(owner) || platformPermissionOwner != null || platformSafRequest != null) return
        if (tool in setOf(app.daiyu.android.platform.PlatformReadTool.CROSS_APP_NOTIFICATIONS,
                app.daiyu.android.platform.PlatformReadTool.NOTIFICATION_HISTORY) && permission.isEmpty()) {
            startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } else if (tool == app.daiyu.android.platform.PlatformReadTool.PERMISSIONS && permission.isEmpty()) {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else {
            val permissions = app.daiyu.android.platform.PlatformSettingsAccess.permissionRequest(tool, permission)
            platformPermissionOwner = owner
            try { platformPermissionRequest.launch(permissions) }
            catch (failure: Exception) { platformPermissionOwner = null; throw failure }
        }
    }
    private fun pickPlatformSaf(tree: Boolean) {
        val owner = platformSettingsPageOwner
        if (!platformPageCurrent(owner) || platformPermissionOwner != null || platformSafRequest != null) return
        platformSafRequest = checkNotNull(owner) to tree
        try { platformSafPicker.launch(app.daiyu.android.platform.PlatformSettingsAccess.pickerIntent(tree)) }
        catch (failure: Exception) { platformSafRequest = null; throw failure }
    }
    private var hapticsHost: app.daiyu.android.haptics.HapticsActivityHost? = null
    private fun syncHaptics() {
        hapticsHost?.sync(selected?.id?.value, retainedPage == "chat" && !sidebarVisible,
            retainedPage == "haptics")
    }
    private val retainedData by lazy { RetainedConversationData(controller.database, ContextService(applicationContext, controller.database)) }
    private val assistantStore get() = controller.assistantStore
    private val assistantAssets get() = controller.assistantAssets
    private val reasoningPreference get() = controller.reasoningPreference
    private val subAgentModelSource = app.daiyu.android.delegation.SavedCatalogSubAgentModelSource()
    private var subAgentSourceRevision by mutableStateOf(0)
    private val subAgentRepository by lazy {
        ConversationSubAgentPreferences(getSharedPreferences("subagent_configuration", MODE_PRIVATE))
    }
    // The host is cheap; its Android player (and system TTS) exist only after an explicit click.
    // Process-lifetime gate survives recreation, including an unconfirmed Android release.
    private val audioGate = ActivityVoiceAudio.gate
    private val voiceRuntime get() = controller.voiceRuntime
    private class UniversalSend(val turn: VoiceControlTurn, val expected: ChatController.TextSendOwner,
        val result: (VoiceControlReceipt) -> Unit) {
        val retained = mutableSetOf<VoiceAssistantOwner>()
    }
    private val universalTickets = java.util.IdentityHashMap<ChatController.TextSendTicket, UniversalSend>()
    private val universalAssistants = mutableMapOf<VoiceAssistantOwner, Message>()
    private val universalLifecycleKey = java.util.UUID.randomUUID().toString()
    @Volatile private var universalAdmissionSnapshot = VoiceAdmission.REVOKED
    @Volatile private var universalFrozen: FrozenVoiceSpeech? = null
    @Volatile private var universalOffline = false
    private var universalEditor: ChatController.TextSendOwner? = null
    private var universalOwnMutation = false
    private var universalPermissionPermit: String? = null
    private var universalUnobserve: (() -> Unit)? = null
    private var accountPreviewRevoke: (() -> Unit)? = null
    private val universalMicrophonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val original = universalPermissionPermit
        universalPermissionPermit = null
        refreshUniversalAdmission()
        if (original != null && universalVoiceDelegate.isInitialized()) universalVoice.permissionResult(original, granted)
    }
    private val universalVoiceDelegate: Lazy<ActivityUniversalVoiceHost> = lazy {
        universalUnobserve = voiceRuntime.observeInvalidation { profile ->
            if (profile == null || profile == VoiceProfile.CONVERSATION) {
                universalAdmissionSnapshot = VoiceAdmission.REVOKED
                releaseOnMain(android.os.Handler(android.os.Looper.getMainLooper())) { revokeUniversalVoice() }
            }
        }
        val bodies = AndroidUniversalOwnerObserver(controller, ::universalQuestionWaiting)
        ActivityUniversalVoiceHost(applicationContext, voiceRuntime,
            entrySnapshot = { profile ->
                val config = voiceRuntime.conversationConfiguration.value
                universalOffline = profile.id.endsWith(":offline")
                universalEditor = ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision)
                refreshUniversalAdmission()
                VoiceEntrySnapshot(selected?.id?.value, universalLifecycleKey, setOf(VoiceEntryMode.UNIVERSAL),
                    profileReady = config != null && profile.id == "${config.revision}:${if (universalOffline) "offline" else "configured"}" &&
                        draft.isEmpty() && controller.active == null && universalPermissionPermit == null &&
                        universalAdmissionSnapshot in setOf(VoiceAdmission.READY, VoiceAdmission.PERMISSION_REQUIRED),
                    foreground = activityUsable() && voiceForeground && retainedPage == "chat" && !sidebarVisible,
                    questionWaiting = controller.active?.let(::universalQuestionWaiting) == true,
                    microphoneGranted = microphoneGranted())
            },
            admission = { universalAdmissionSnapshot },
            prepareConversationSpeech = {
                voiceRuntime.prepareSpeech(VoiceProfile.CONVERSATION).also { universalFrozen = it }
            },
            current = {
                val frozen = universalFrozen
                frozen != null && voiceRuntime.current(frozen) &&
                    (!universalOffline || app.daiyu.android.voice.offline.OfflineSpeechPack.state.value.ready)
            },
            recognitionConfiguration = {
                val cfg = checkNotNull(universalFrozen).configuration
                when {
                    universalOffline -> UniversalRecognitionConfiguration.Offline
                    cfg.dictation == "DOUBAO" -> UniversalRecognitionConfiguration.Doubao(cfg.asrKey, cfg.asrResource)
                    cfg.dictation == "SYSTEM" -> UniversalRecognitionConfiguration.Offline
                    else -> error("universal_recognition_not_configured")
                }
            },
            requestMicrophonePermission = { permit ->
                check(universalPermissionPermit == null)
                universalPermissionPermit = permit.id
                universalMicrophonePermission.launch(android.Manifest.permission.RECORD_AUDIO)
            },
            submit = { turn, text, result ->
                refreshUniversalAdmission()
                if (universalAdmissionSnapshot != VoiceAdmission.READY || selected?.id?.value != turn.permit.conversationId || draft.isNotEmpty()) {
                    result(VoiceControlReceipt.Rejected(turn.requestId))
                } else {
                    val before = checkNotNull(universalEditor)
                    universalOwnMutation = true
                    try { updateDraft(text) } finally { universalOwnMutation = false }
                    if (draft != text || selected?.id?.value != turn.permit.conversationId) {
                        revokeUniversalVoice(); result(VoiceControlReceipt.Rejected(turn.requestId))
                    } else {
                        val expected = ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision)
                        migrateUniversalEditor(before, expected)
                        refreshUniversalAdmission()
                        try { sendDraft(text, UniversalSend(turn, expected, result)) }
                        catch (_: Exception) { revokeUniversalVoice(); result(VoiceControlReceipt.Unknown(turn.requestId)) }
                    }
                }
            },
            observe = { owner, body ->
                var complete = false
                val source = bodies.observe(owner) { snapshot ->
                    if (snapshot.status == VoiceBodyStatus.COMPLETE) complete = true
                    if (snapshot.status == VoiceBodyStatus.QUESTION_WAITING) {
                        universalAdmissionSnapshot = VoiceAdmission.QUESTION_WAITING
                        revokeUniversalVoice()
                    }
                    body(snapshot)
                }
                VoiceControlCancellation { source.cancel(); if (complete) universalAssistants.remove(owner); Unit }
            },
            stopAcceptedRun = { owner -> universalAssistants.remove(owner)?.let { controller.stop(it) }; Unit },
            revokePendingTicket = { permit ->
                universalTickets.entries.filter { it.value.turn.permit.id == permit.id }.map { it.key }
                    .forEach { controller.cancelTextSend(it) }
                // Keep the original callback association: Unknown is not proof that nothing was saved.
            })
    }
    private val universalVoice: ActivityUniversalVoiceHost get() = universalVoiceDelegate.value
    private fun migrateUniversalEditor(before: ChatController.TextSendOwner, after: ChatController.TextSendOwner) {
        universalEditor = after
        if (voiceConversationDialogOwner == before) voiceConversationDialogOwner = after
    }
    private fun microphoneGranted() = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun universalQuestionWaiting(message: Message): Boolean = controller.questionWaiting(message)
    private fun revokeUniversalVoice() {
        universalAdmissionSnapshot = VoiceAdmission.REVOKED // Fence BEFORE drain/old callbacks.
        if (universalVoiceDelegate.isInitialized()) universalVoice.state.value.permitId?.let { universalVoice.revoke(it) }
    }
    private fun refreshUniversalAdmission() {
        if (universalOwnMutation) return // Only the exact original send may migrate its own editor.
        val expected = universalEditor
        val active = controller.active
        val exactActive = active == null || universalAssistants.keys.any {
            it.messageId == active.id.value && it.runId == active.runId.value && it.conversationId == active.conversationId.value
        } || (pendingTextSend?.let { universalTickets.containsKey(it.ticket) } == true &&
            universalVoiceDelegate.isInitialized() && universalVoice.state.value.phase == VoiceControlPhase.SUBMITTING)
        // While SUBMITTING there is no input/output. Only this original ticket can bridge controller
        // publication to its durable receipt; no arbitrary active/latest message is ever bound.
        val pending = pendingTextSend
        val controllerTicket = controller.textSendState()?.ticket
        val excluded = !activityUsable() || !voiceForeground || retainedPage != "chat" || sidebarVisible ||
            !selectionReady || !controller.ready || controller.dataBusy || !exactActive ||
            expected != ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision) ||
            imageHost.hasAttachments || imageHost.sending || videoSending || videoHost.state.items.isNotEmpty() ||
            shizukuWritesBlocked || terminalEditor != null || (virtualDisplayEditor != null || virtualDisplayPlanVisit != null) ||
            mcpApproval != null || mcpPreparing || browserApproval != null || browserPreparing || generationApproval != null ||
            (pending != null && !universalTickets.containsKey(pending.ticket)) ||
            (controllerTicket != null && !universalTickets.containsKey(controllerTicket))
        universalAdmissionSnapshot = when {
            excluded -> VoiceAdmission.REVOKED
            active?.let(::universalQuestionWaiting) == true -> VoiceAdmission.QUESTION_WAITING
            !microphoneGranted() -> VoiceAdmission.PERMISSION_REQUIRED
            else -> VoiceAdmission.READY
        }
        if (universalAdmissionSnapshot == VoiceAdmission.REVOKED || universalAdmissionSnapshot == VoiceAdmission.QUESTION_WAITING) {
            if (universalVoiceDelegate.isInitialized() && universalVoice.state.value.active) revokeUniversalVoice()
        }
    }
    private fun voiceConfigurationToast(text: String) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    private fun chatVoiceConfigurationProblem(mode: ChatMenuAction): String? {
        val missing = when (mode) {
            ChatMenuAction.VOICE_INPUT -> "请先配置语音输入"
            ChatMenuAction.VOICE_DOUBAO -> "请先配置豆包实时通话"
            else -> "请先配置语音对话"
        }
        val config = try {
            if (mode == ChatMenuAction.VOICE_CHAT) voiceRuntime.prepareSpeech(VoiceProfile.CONVERSATION).configuration
            else voiceRuntime.snapshot()
        } catch (_: Exception) { return missing }
        if (mode == ChatMenuAction.VOICE_DOUBAO) return missing.takeIf { config.duplexKey.isBlank() }
        val offlineReady = if (mode == ChatMenuAction.VOICE_INPUT) offlineSpeechReady()
            else app.daiyu.android.voice.offline.OfflineSpeechPack.state.value.ready
        if (mode == ChatMenuAction.VOICE_CHAT && offlineReady) return null
        return when (config.dictation) {
            "DOUBAO" -> missing.takeIf {
                config.asrKey.isBlank() || config.asrResource !in app.daiyu.android.voice.providers.DoubaoAsrProtocol.resources
            }
            "SYSTEM" -> if (offlineReady) null
                else "请先下载离线语音包"
            else -> missing
        }
    }
    private fun startUniversalVoice(profile: VoiceControlProfile, limits: VoiceControlLimits) {
        if (!VoiceSettingsPreferences(applicationContext).enabled("conversation")) { toast("请先在语音设置中启用语音会话"); return }
        if (!activityUsable() || !voiceForeground || retainedPage != "chat" || sidebarVisible) return
        stopCascadeVoice(); invalidateConfiguredVoice(); dictationHost.invalidate(); offlineHost.invalidate()
        speechHost.stop(speechHost.owner)
        try { universalVoice.start(profile, limits) }
        catch (_: Exception) { revokeUniversalVoice(); toast("持续语音未启动：检查会话独立配置、离线包或音频释放状态") }
    }
    private var preparedSpeech: FrozenVoiceSpeech? = null
    private var messageSpeechAttempt by mutableStateOf(0L)
    private var reportedMessageSpeechError: Pair<Long, SpeechPlaybackState>? = null
    private var voiceConsent: android.app.AlertDialog? = null
    private var voiceConversationDialogOwner by mutableStateOf<ChatController.TextSendOwner?>(null)
    private var voiceRequestEpoch = 0L
    private val speechHost by lazy {
        VoiceHost(lifecycleScope, audioGate) {
            val frozen = checkNotNull(preparedSpeech) { "voice_not_prepared" }
            check(voiceRuntime.current(frozen.configuration)) { "voice_configuration_changed" }
            preparedSpeech = null
            if (frozen.configuration.engine == "SYSTEM") AndroidVoiceHostPlayer(AndroidSpeechPlayer(applicationContext))
            else RemoteVoiceHostPlayer(applicationContext, lifecycleScope, voiceRuntime, frozen)
        }
    }
    private fun voiceEditorCurrent(target: DictationDraftTarget) =
        activityUsable() && voiceForeground && retainedPage == "chat" && !sidebarVisible &&
            selectionReady && controller.ready && !controller.dataBusy &&
            !imageHost.sending && !shizukuWritesBlocked && terminalEditor == null &&
            virtualDisplayEditor == null && virtualDisplayPlanVisit == null && !videoSending && pendingTextSend == null &&
            controller.textSendState() == null &&
            target.conversationId == selected?.id?.value &&
            target.editorGeneration == editorGeneration &&
            (target.draftRevision == draftRevision ||
                (dictationDraft?.first == target && dictationDraftRevision == draftRevision))
    private var applyingDictationDraft = false
    private var dictationDraftRevision: Long? = null
    private var dictationDraft: Pair<DictationDraftTarget, app.daiyu.android.voice.offline.SpeechDraft>? = null
    private fun captureDictationDraft(target: DictationDraftTarget) {
        dictationDraftRevision = draftRevision
        val projection = ComposerReferences(draft)
        dictationDraft = target to app.daiyu.android.voice.offline.SpeechDraft(projection.text,
            projection.toVisible(selectionStart), projection.toVisible(selectionEnd))
    }
    private data class DictationProjection(
        val target: DictationDraftTarget, val text: String, val cursor: Int,
    )
    private fun currentDictationProjection(
        offline: app.daiyu.android.voice.offline.OfflineComposerHost.State = offlineHost.state.value,
        system: app.daiyu.android.voice.ComposerDictationState = dictationHost.state.value,
        remote: Pair<DictationDraftTarget, String>? = remoteVoice.preview.value,
    ): DictationProjection? {
        if (inlineMessageEditor != null) return null
        val captured = dictationDraft ?: return null
        val target = captured.first
        if (!voiceEditorCurrent(target)) return null
        val text = when {
            offline.target == target -> offline.preview
            system.target == target -> system.preview
            remote?.first == target -> remote.second
            else -> return null
        }
        if (text.isEmpty()) return null
        val original = ComposerReferences(draft)
        val preview = captured.second.preview(original.text, text) ?: return null
        val next = original.edit(preview.text)
        return DictationProjection(target, next, ComposerReferences(next).toSource(preview.cursor))
    }
    private fun applyRecognizedPartial(target: DictationDraftTarget, recognized: String) {
        if (recognized.isBlank()) return
        val captured = dictationDraft?.takeIf { it.first == target }?.second
        check(voiceEditorCurrent(target) && captured != null) { "dictation_editor_changed" }
        val projection = ComposerReferences(draft)
        val body = checkNotNull(captured.accept(projection.text, recognized)) { "dictation_draft_changed" }
        val next = projection.edit(body)
        // Only this owned recognition write may advance the draft without cancelling its recorder.
        applyingDictationDraft = true
        try { updateDraft(next) } finally { applyingDictationDraft = false }
        check(draft == next) { "dictation_draft_not_saved" }
        selectionStart = ComposerReferences(next).toSource(captured.cursor); selectionEnd = selectionStart
        publishChat(rowsChanged = false)
    }
    private fun applyRecognizedDraft(target: DictationDraftTarget, recognized: String) {
        val captured = dictationDraft?.takeIf { it.first == target }?.second
        check(voiceEditorCurrent(target) && captured != null) { "dictation_editor_changed" }
        val projection = ComposerReferences(draft)
        val body = checkNotNull(captured.accept(projection.text, recognized)) { "dictation_draft_changed" }
        val next = projection.edit(body)
        dictationDraft = null
        dictationDraftRevision = null
        updateDraft(next)
        check(draft == next) { "dictation_draft_not_saved" }
        selectionStart = ComposerReferences(next).toSource(captured.cursor); selectionEnd = selectionStart
        publishChat()
    }
    private val remoteVoice by lazy {
        ActivityRemoteVoiceHost(applicationContext, lifecycleScope, voiceRuntime, ::voiceEditorCurrent,
            applyDraft = ::applyRecognizedDraft, applyPartialDraft = ::applyRecognizedPartial,
            requestPermission = { microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO) })
    }
    private val cascadeVoice by lazy {
        ActivityCascadeVoiceHost(applicationContext, voiceRuntime) {
            activityUsable() && voiceForeground && retainedPage == "chat" && !sidebarVisible &&
                controller.ready && !controller.dataBusy
        }
    }
    private fun stopCascadeVoice() {
        try { cascadeVoice.close() } catch (_: Exception) { toast("级联音频释放未确认，后续音频已阻止") }
    }
    private fun invalidatePreparedSpeech() {
        voiceRequestEpoch++
        dictationDraft = null
        dictationDraftRevision = null
        voiceConsent?.dismiss(); voiceConsent = null; preparedSpeech = null
    }
    private fun invalidateConfiguredVoice() {
        accountPreviewRevoke?.invoke()
        if (!universalOwnMutation) revokeUniversalVoice()
        invalidatePreparedSpeech()
        try { remoteVoice.invalidate() } catch (_: Exception) { toast("语音资源释放未确认，新的音频操作已阻止") }
    }
    private fun requestConfiguredSpeech(expectedOwner: String?, id: String, text: String) {
        if (!activityUsable() || !voiceForeground || retainedPage != "chat" || sidebarVisible ||
            speechHost.owner != expectedOwner || voiceHostText(chatState.turns, id) != text) return
        invalidateConfiguredVoice()
        val epoch = voiceRequestEpoch
        val frozen = try { voiceRuntime.prepareSpeech() } catch (_: Exception) {
            toast("语音未配置或凭据不可用，请打开语音设置"); return
        }
        fun current() = activityUsable() && voiceForeground && retainedPage == "chat" && !sidebarVisible &&
            speechHost.owner == expectedOwner && epoch == voiceRequestEpoch &&
            voiceRuntime.current(frozen.configuration) && voiceHostText(chatState.turns, id) == text
        fun play(value: FrozenVoiceSpeech) {
            if (!current()) return
            preparedSpeech = value
            messageSpeechAttempt++
            try { speechHost.speak(expectedOwner, id, text) } finally { preparedSpeech = null }
        }
        val config = frozen.configuration
        if (!config.voice.startsWith(app.daiyu.android.voice.personal.MimoPersonalVoices.PREFIX)) {
            play(frozen); return
        }
        val reference = voiceRuntime.personal.state.value.firstOrNull {
            it.id == config.voice && it.providerId == config.referenceOwner()
        }
        if (config.engine != "MIMO" || reference == null) { toast("参考录音已删除或账户已更换，请重新选择"); return }
        if (current()) lifecycleScope.launch {
            try { play(voiceRuntime.authorizeReference(frozen)) }
            catch (_: Exception) { if (current()) toast("参考录音或配置已失效；未发送") }
        }
    }
    private var voiceForeground = false
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        dictationHost.permissionResult(granted)
        // A permission sheet may pause/invalidate its editor token. Report only the app-level
        // permission outcome in that case; never resurrect that token or start a recognizer.
        if (activityUsable()) toast(if (granted) "麦克风权限已授予，请再次点击语音开始听写"
            else "麦克风权限被拒绝；草稿未更改")
    }
    private val dictationHost: ComposerDictationHost by lazy {
        ComposerDictationHost(applicationContext, audioGate,
            isEditorCurrent = ::voiceEditorCurrent,
            requestPermission = { microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO) },
            applyDraft = ::applyRecognizedDraft, applyPartialDraft = ::applyRecognizedPartial)
    }
    @Volatile private var offlineSpeechReady: () -> Boolean = {
        app.daiyu.android.voice.offline.OfflineSpeechPack.state.value.ready
    }
    private var offlineSpeechSessionFactory: (
        VoiceOwner, () -> Boolean, app.daiyu.android.voice.offline.OfflineSpeechSession.Events,
    ) -> app.daiyu.android.voice.offline.OfflineSpeechAttempt = { owner, allowed, events ->
        app.daiyu.android.voice.offline.OfflineSpeechSession(audioGate, owner, allowed,
            app.daiyu.android.voice.offline.AndroidOfflineSpeechDevice(applicationContext), events)
    }
    private var offlineMicrophonePermissionOwner: app.daiyu.android.voice.offline.OfflineComposerHost? = null
    private val offlineMicrophonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val original = offlineMicrophonePermissionOwner
        offlineMicrophonePermissionOwner = null
        original?.permissionResult(granted)
        if (activityUsable()) toast(if (granted) "麦克风权限已授予，请再次点击离线语音" else "麦克风权限被拒绝；草稿未更改")
    }
    private val offlineHost: app.daiyu.android.voice.offline.OfflineComposerHost by lazy {
        app.daiyu.android.voice.offline.OfflineComposerHost(applicationContext,
            requestPermission = {
                check(offlineMicrophonePermissionOwner == null)
                offlineMicrophonePermissionOwner = offlineHost
                try { offlineMicrophonePermission.launch(android.Manifest.permission.RECORD_AUDIO) }
                catch (failure: Exception) { offlineMicrophonePermissionOwner = null; throw failure }
            },
            ready = { offlineSpeechReady() },
            sessionFactory = { owner, allowed, events -> offlineSpeechSessionFactory(owner, allowed, events) },
            current = ::voiceEditorCurrent,
            inputEnabled = { VoiceSettingsPreferences(applicationContext).enabled("input") &&
                voiceRuntime.configuration.value?.dictation != "DOUBAO" },
            currentText = { ComposerReferences(draft).text },
            applyPartialDraft = ::applyRecognizedPartial,
            applyDraft = { target, text, cursor ->
                check(voiceEditorCurrent(target)) { "dictation_editor_changed" }
                dictationDraft = null
                dictationDraftRevision = null
                val next = ComposerReferences(draft).edit(text)
                updateDraft(next)
                check(draft == next) { "offline_dictation_draft_not_saved" }
                selectionStart = ComposerReferences(next).toSource(cursor); selectionEnd = selectionStart
                publishChat()
            })
    }
    private fun startOfflineDictation() {
        if (offlineHost.state.value.target?.let(::voiceEditorCurrent) == true) {
            offlineHost.invalidate()
            invalidatePreparedSpeech()
            return
        }
        invalidateConfiguredVoice()
        dictationHost.invalidate()
        val projection = ComposerReferences(draft)
        val target = DictationDraftTarget(selected?.id?.value, editorGeneration, draftRevision)
        captureDictationDraft(target)
        offlineHost.start(target, projection.text, projection.toVisible(selectionStart), projection.toVisible(selectionEnd))
    }
    private val imageHost by lazy { ImageChatHost.obtain(controller, { if (activityUsable()) publishChat() },
        { if (activityUsable()) toast(it) }, ::settleImageReceipt) }
    private var videoComposerControls by mutableStateOf<app.daiyu.android.ui.screens.chat.attachments.VideoComposerControls?>(null)
    private data class VideoPickEntry(val owner: ChatController.TextSendOwner, val epoch: Long)
    private var videoPickEntry by mutableStateOf<VideoPickEntry?>(null)
    private var videoSending by mutableStateOf(false)
    private var videoReceiptRequest: VideoDraftRequest? = null
    private var videoReceiptEditor: ControllerVideoEditor? = null
    private var videoDialog: android.app.AlertDialog? = null
    private val videoHost get() = controller.videoHost
    private val videoUi = object : ControllerVideoUi {
        override fun editor() = ControllerVideoEditor(selected?.id?.value, editorGeneration, draftRevision,
            draft, activityUsable() && voiceForeground && retainedPage == "chat" && !sidebarVisible)

    }
    private var toolLocationDialog: android.app.AlertDialog? = null
    private var toolLocationQuestion: RunScreenLocation.Question? = null
    private val toolLocationUi = object : RunScreenLocationUi {
        override fun show(question: RunScreenLocation.Question) {
            fun available() = activityUsable() && voiceForeground && retainedPage == "chat" && !sidebarVisible && selectionReady
            if (!available()) { question.answer(null); return }
            toolLocationDialog?.dismiss()
            toolLocationQuestion = question
            toolLocationDialog = android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("选择本次运行的执行位置")
                .setMessage("只选择执行位置。后台不可用时不会改用前台；取消后本次运行不再询问。")
                .setHapticPositiveButton("本次使用前台") { _, _ -> question.answer(if (available()) TaskSurface.FOREGROUND else null) }
                .setHapticNegativeButton("本次使用后台") { _, _ -> question.answer(if (available()) TaskSurface.BACKGROUND else null) }
                .setOnCancelListener { question.answer(null) }.create().also { dialog ->
                    dialog.setOnDismissListener {
                        question.answer(null)
                        if (toolLocationDialog === dialog) { toolLocationDialog = null; toolLocationQuestion = null }
                    }
                    dialog.show()
                }
        }
        override fun dismiss(question: RunScreenLocation.Question) {
            if (toolLocationQuestion === question) { toolLocationDialog?.dismiss(); toolLocationDialog = null; toolLocationQuestion = null }
        }
    }
    private var commandDialog: android.app.AlertDialog? = null
    private var commandRequest: app.daiyu.android.integration.tools.CommandApprovalRequest? = null
    private val commandApprovalUi = object : app.daiyu.android.integration.tools.CommandApprovalUi {
        override fun show(request: app.daiyu.android.integration.tools.CommandApprovalRequest, answer: (Boolean) -> Unit) {
            fun available() = activityUsable() && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                retainedPage == "chat" && !sidebarVisible && selectionReady &&
                selected?.id == request.owner.conversationId
            if (!available()) { answer(false); return }
            commandDialog?.dismiss()
            commandRequest = request
            val decided = AtomicBoolean(false)
            fun decide(value: Boolean) {
                if (decided.compareAndSet(false, true)) answer(value && available() && commandRequest === request)
            }
            commandDialog = android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("批准本次终端命令").setMessage(request.disclosure)
                .setHapticPositiveButton("仅允许本次") { _, _ -> decide(true) }
                .setHapticNegativeButton("拒绝") { _, _ -> decide(false) }
                .setOnCancelListener { decide(false) }.create().also { dialog ->
                    dialog.setOnDismissListener {
                        decide(false)
                        if (commandDialog === dialog) { commandDialog = null; commandRequest = null }
                    }
                    dialog.show()
                }
        }
        override fun dismiss(request: app.daiyu.android.integration.tools.CommandApprovalRequest) {
            if (commandRequest === request) { commandDialog?.dismiss(); commandDialog = null; commandRequest = null }
        }
    }
    private fun revokeCommandApproval() {
        controller.revokeCommandApproval()
        toolLocationQuestion?.answer(null)
        toolLocationDialog?.dismiss()
        commandDialog?.dismiss(); commandDialog = null; commandRequest = null
    }

    private fun fenceVideoRoute(detachHost: Boolean = true) {
        videoPickEntry = null
        controller.invalidateVideo(videoUi) // Gate and real session first; dismiss is not revocation.
        videoDialog?.dismiss(); videoDialog = null
        if (detachHost && controller.ownsVideoUi(videoUi)) videoHost.detach()
    }
    private fun videoComposerAvailable() = terminalEditor == null && virtualDisplayEditor == null && virtualDisplayPlanVisit == null &&
        shizukuEditor == null && mcpApproval == null && !mcpPreparing && browserApproval == null && generationApproval == null &&
        pendingTextSend == null && controller.textSendState() == null &&
        !imageHost.hasAttachments && !imageHost.busy && !shizukuWritesBlocked
    @Composable private fun VideoComposerPanel() {
        val pick = videoPickEntry
        app.daiyu.android.ui.screens.chat.attachments.VideoAttachments(videoHost, selected?.id?.value,
            app.daiyu.android.ui.screens.chat.attachments.VideoDraftText(draft, editorGeneration, draftRevision),
            onHandoff = { request, text, settle ->
                handoffVideo(request, ControllerVideoEditor(request.conversation, text.editorGeneration,
                    text.revision, text.text, true), settle)
            }, enabled = videoComposerAvailable(),
            parentBusy = videoSending || controller.active != null || !controller.ready || controller.dataBusy,
            compact = true, manageLifecycle = false, pickRequest = pick, onControls = { controls ->
                videoComposerControls = controls
                if (controls != null && pick != null && videoPickEntry === pick) {
                    videoPickEntry = null
                    if (activityUsable() && voiceForeground && retainedPage == "chat" && !sidebarVisible &&
                        pick.owner == ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision) &&
                        pick.epoch == contextManagementEntryEpoch) controls.select()
                }
            }, onControlsDisposed = { disposed ->
                if (videoComposerControls === disposed) videoComposerControls = null
            })
    }
    private fun handoffVideo(request: VideoDraftRequest, original: ControllerVideoEditor,
        settle: (VideoDraftReceipt) -> Boolean) {
        if (videoSending || videoUi.editor() != original || !videoComposerAvailable()) { settle(VideoDraftReceipt(request.conversation, request.owner, request.revision,
                request.videos, VideoDraftOutcome.REJECTED)); return }
        videoSending = true
        videoReceiptRequest = request; videoReceiptEditor = original
        val queued = controller.sendVideos(selected, request, original) { receipt, _ ->
            // Settle the retained host even when the originating Activity has been destroyed.
            settle(receipt)
            if (activityUsable()) { settleRetainedVideoReceipt(); publishChat() }
        }
        if (!queued) {
            videoSending = false
            videoReceiptRequest = null; videoReceiptEditor = null
            settle(VideoDraftReceipt(request.conversation, request.owner, request.revision, request.videos,
                VideoDraftOutcome.REJECTED))
        }
    }
    private fun settleRetainedVideoReceipt() {
        if (!activityUsable() || !selectionReady) return
        val sending = controller.videoSendState() ?: return
        val request = sending.request
        val now = videoUi.editor()
        videoSending = sending.receipt == null
        if (videoReceiptRequest !== request) {
            if (!now.visible) return
            // A replacement Activity adopts once, after its initial DB draft has loaded. A route
            // departure in this Activity keeps its old captured editor and cannot re-adopt it.
            videoReceiptRequest = request
            if (now.conversation == request.conversation &&
                (now.text == request.text || sending.receipt?.outcome == VideoDraftOutcome.ACCEPTED && now.text.isEmpty())) {
                videoReceiptEditor = now
            } else videoReceiptEditor = null
        }
        videoSending = sending.receipt == null
        val receipt = sending.receipt ?: return
        videoHost.settle(receipt)
        val original = videoReceiptEditor
        val accepted = original != null && (
            videoEditorReceiptMatches(original, now, request, receipt) ||
                // The recreated editor may have loaded the already-consumed empty DB draft.
                original == now && original.visible && original.text.isEmpty() &&
                original.conversation == request.conversation && videoReceiptMatches(request, receipt) &&
                receipt.outcome == VideoDraftOutcome.ACCEPTED)
        val conversation = sending.conversation
        controller.acknowledgeVideoSend(request)
        videoSending = false
        if (accepted && conversation != null) {
            selected = conversation; editorGeneration++; draftRevision++
            draft = ""; selectionStart = 0; selectionEnd = 0
            controller.preferences.edit().putString("selected", conversation.id.value).apply()
            publishChat(); loadAcceptedHistory(conversation)
        }
    }
    // A mount is an editor capability, never saved across Activity recreation or A -> A entry.
    private class ShizukuEditor(val conversation: Conversation?, val generation: Long,
        val revision: Long, val text: String) {
        var preview: ControllerShizukuPreparation? = null
    }
    private var shizukuEditor by mutableStateOf<ShizukuEditor?>(null)
    private var shizukuInFlight by mutableStateOf<ControllerShizukuPreparation?>(null)
    private val shizukuWritesBlocked: Boolean get() = shizukuInFlight != null ||
        controller.shizukuPreparation()?.isSubmitted() == true || controller.generationSubmissionPending() ||
        controller.virtualDisplayEntry() != null || virtualDisplayPlanVisit != null || controller.guiEntry() != null
    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        imageHost.picked(uris)
    }
    private class EditImagePick(val visit: Any, val host: MessageEditImageDraft,
        val selection: app.daiyu.android.ui.screens.chat.attachments.ImageAttachmentSelection) {
        var live = true
        var firstPickerHop = true
        var pickerAway = false
        var returned = false
        var uris: List<Uri> = emptyList()
    }
    private var editImagePick: EditImagePick? = null
    private val editImagePicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        editImagePick?.let { request ->
            request.returned = true; request.uris = uris
            settleEditImages()
        }
    }
    private fun pickEditImages() {
        val editor = inlineMessageEditor ?: return
        if (!chatReferenceAvailable() || !generationResumed() || !voiceForeground || editImagePick != null) return
        val selection = editor.additions.select() ?: return
        val request = EditImagePick(editor.visit, editor.additions, selection)
        editImagePick = request
        try { editImagePicker.launch("image/*") }
        catch (_: Exception) {
            editImagePick = null; editor.additions.cancelSelection(selection)
            toast("无法打开图片选择器；编辑内容保留")
        }
    }
    private fun settleEditImages() {
        val request = editImagePick ?: return
        if (!request.returned) return
        if (!request.live || inlineMessageEditor?.visit !== request.visit) {
            request.host.cancelSelection(request.selection); editImagePick = null; return
        }
        if (!generationResumed() || !voiceForeground) return
        editImagePick = null
        if (chatReferenceAvailable()) request.host.picked(request.selection, request.uris)
        else request.host.cancelSelection(request.selection)
    }
    private class ComposerShortcutPick(
        val trigger: app.daiyu.android.ui.screens.chat.conversation.ComposerShortcut,
        val text: String, val owner: ChatController.TextSendOwner,
    ) {
        var choices by mutableStateOf<List<app.daiyu.android.ui.screens.chat.conversation.ComposerShortcutChoice>?>(null)
        var error by mutableStateOf<String?>(null)
    }
    private var composerShortcutPick by mutableStateOf<ComposerShortcutPick?>(null)
    private fun composerShortcutAvailable() = activityUsable() && selectionReady && retainedPage == "chat" &&
        !sidebarVisible && inlineMessageEditor == null && controller.ready && !controller.dataBusy && chatState.composer.enabled &&
        pendingTextSend == null && controller.textSendState() == null && !imageHost.sending && !videoSending &&
        !shizukuWritesBlocked && terminalEditor == null && virtualDisplayEditor == null && guiEditor == null
    private fun shortcutPickPresented(pick: ComposerShortcutPick) = composerShortcutPick === pick &&
        composerShortcutAvailable() && selected?.id == pick.owner.conversationId &&
        editorGeneration == pick.owner.editorGeneration && draftRevision == pick.owner.draftRevision && draft == pick.text
    private fun shortcutPickCurrent(pick: ComposerShortcutPick) = shortcutPickPresented(pick) && voiceForeground && generationResumed()

    private fun openComposerShortcut(trigger: app.daiyu.android.ui.screens.chat.conversation.ComposerShortcut, expected: String) {
        if (!composerShortcutAvailable() || !voiceForeground || !generationResumed() || draft != expected) return
        val pick = ComposerShortcutPick(trigger, expected,
            ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision))
        composerShortcutPick = pick
        lifecycleScope.launch {
            try {
                val choices = withContext(Dispatchers.IO) {
                    if (trigger.kind == app.daiyu.android.ui.screens.chat.conversation.ComposerShortcutKind.CONVERSATION) {
                        val attached = app.daiyu.android.references.PromptReferences.conversations(listOf(expected)).map { it.id }.toSet()
                        controller.database.readableDatabase.rawQuery(
                            "SELECT id,title FROM conversations ORDER BY updated DESC,id", null).use { cursor ->
                            buildList {
                                while (cursor.moveToNext()) {
                                    val id = cursor.getString(0)
                                    if (id != pick.owner.conversationId?.value && id !in attached)
                                        add(app.daiyu.android.ui.screens.chat.conversation.ComposerShortcutChoice(id,
                                            cursor.getString(1).ifBlank { "新会话" }))
                                }
                            }
                        }
                    } else {
                        val state = controller.skillManagement().snapshot()
                        val selected = app.daiyu.android.references.PromptReferences.skillIds(expected)
                        val assistantId = pick.owner.conversationId?.let { controller.database.assistantId(it) }
                            ?: assistantStore.selectedId.value
                        val enabled = assistantStore.read(assistantId)?.enabledSkillIds.orEmpty().toSet()
                        if (!state.enabled) emptyList() else state.entries.filter {
                            it.skill.enabled && it.id !in selected && (it.id in enabled || it.skill.name in enabled)
                        }
                            .map { app.daiyu.android.ui.screens.chat.conversation.ComposerShortcutChoice(
                                it.id, it.skill.name, it.skill.description) }
                    }
                }
                if (shortcutPickPresented(pick)) pick.choices = choices
            } catch (_: Exception) { if (shortcutPickPresented(pick)) pick.error = "读取失败，请重新选择" }
        }
    }
    private fun chooseComposerShortcut(pick: ComposerShortcutPick,
        choice: app.daiyu.android.ui.screens.chat.conversation.ComposerShortcutChoice) {
        if (!shortcutPickCurrent(pick) || pick.choices?.none { it.id == choice.id } != false) return
        val projection = ComposerReferences(pick.text)
        val trigger = pick.trigger
        if (trigger.start !in 0..trigger.end || trigger.end > projection.text.length) return
        val conversation = trigger.kind == app.daiyu.android.ui.screens.chat.conversation.ComposerShortcutKind.CONVERSATION
        if (conversation && projection.references.count { it.kind == "会话" } >= 16) { toast("最多引用16个会话"); return }
        val replacement = if (conversation) "" else "/skill:${choice.id} "
        val body = projection.text.replaceRange(trigger.start, trigger.end, replacement)
        var next = projection.edit(body)
        if (conversation) next = next + (if (next.isEmpty()) "" else "\n") +
            app.daiyu.android.references.PromptReferences.encode(choice.id, choice.title)
        composerShortcutPick = null
        updateDraft(next)
        if (draft == next) {
            selectionStart = ComposerReferences(next).toSource(trigger.start + replacement.length)
            selectionEnd = selectionStart
            publishChat(rowsChanged = false)
        }
    }

    private class ChatReferencePick(val tree: Boolean, val owner: ChatController.TextSendOwner, val text: String,
        val editVisit: Any? = null) {
        var live = true
        var firstPickerHop = true
        var pickerAway = false
        var returned = false
        var uri: Uri? = null
        var resultData: Intent? = null
        var settling = false
    }
    private var chatReferencePick: ChatReferencePick? = null
    private val chatReferencePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request = chatReferencePick
        if (request != null && !request.returned) {
            request.returned = true
            request.uri = if (request.live && result.resultCode == RESULT_OK) result.data?.let {
                app.daiyu.android.platform.PlatformSettingsAccess.acceptedUri(it, request.tree)
            } else null
            request.resultData = if (request.uri != null) result.data else null
            settleChatReference()
        }
    }
    private fun chatReferenceAvailable() = activityUsable() && selectionReady && retainedPage == "chat" &&
        !sidebarVisible && controller.ready && !controller.dataBusy && controller.active == null &&
        pendingTextSend == null && controller.textSendState() == null &&
        (inlineMessageEditor?.let { !it.uncertain } ?: (!imageHost.busy && !imageHost.hasAttachments && videoHost.state.items.isEmpty())) &&
        !videoSending && !shizukuWritesBlocked &&
        terminalEditor == null && virtualDisplayEditor == null && virtualDisplayPlanVisit == null &&
        guiEditor == null && shizukuEditor == null && browserApproval == null && !browserPreparing &&
        generationApproval == null && generationRequest == null && mcpApproval == null && !mcpPreparing && prompt == null
    private fun editReferencePending(editor: InlineMessageEditor) =
        chatReferencePick?.let { it.live && it.editVisit === editor.visit } == true
    private fun chatReferenceMatches(request: ChatReferencePick) =
        request.owner == ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision) &&
            request.editVisit === inlineMessageEditor?.visit && request.text == (inlineMessageEditor?.text ?: draft)
    private fun revokeChatReferencePick(preserveShortcut: Boolean = false) {
        if (!preserveShortcut) composerShortcutPick = null
        val request = chatReferencePick ?: return
        request.live = false; request.uri = null
        // Keep an unreturned slot reserved so its ActivityResult cannot enter a newer request.
        if (request.returned && !request.settling) chatReferencePick = null
    }
    private fun pickChatReference(tree: Boolean) {
        if (!generationResumed() || !voiceForeground || !chatReferenceAvailable() || chatReferencePick != null) return
        val request = ChatReferencePick(tree, ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision),
            inlineMessageEditor?.text ?: draft, inlineMessageEditor?.visit)
        chatReferencePick = request
        try { chatReferencePicker.launch(app.daiyu.android.platform.PlatformSettingsAccess.pickerIntent(tree)) }
        catch (_: Exception) {
            if (chatReferencePick === request) chatReferencePick = null
            toast("无法打开文件选择器；原草稿保留")
        }
    }
    private fun settleChatReference() {
        val request = chatReferencePick ?: return
        if (!request.returned || request.settling) return
        if (!request.live) { chatReferencePick = null; return }
        if (!generationResumed() || !voiceForeground) return
        val uri = request.uri ?: run { chatReferencePick = null; return }
        if (!chatReferenceAvailable() || !chatReferenceMatches(request)) { chatReferencePick = null; return }
        val raw = uri.toString()
        if (raw.any { it.code < 0x20 || it.code == 0x7f }) { chatReferencePick = null; return }
        val data = request.resultData
        request.settling = true
        if (request.editVisit != null) publishChat(rowsChanged = false)
        lifecycleScope.launch {
            try {
                val description = withContext(Dispatchers.IO) {
                    if (data == null || !platformSaf.accept(data, request.tree)) return@withContext null
                    try {
                        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME,
                            android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            if (!cursor.moveToFirst()) null else {
                                val name = cursor.getString(0).orEmpty().take(200).replace('\n', ' ').replace('\r', ' ')
                                val size = if (cursor.isNull(1)) "大小未知" else "${cursor.getLong(1)} 字节"
                                "$name（$size）"
                            }
                        } ?: "名称及大小未知"
                    } catch (_: Exception) { "名称及大小未知" }
                }
                if (chatReferencePick !== request || !request.live || !generationResumed() || !voiceForeground ||
                    !chatReferenceAvailable() || !chatReferenceMatches(request)) return@launch
                if (description == null) { toast("文件只读授权未保存，请重新选择；原草稿保留"); return@launch }
                val reference = "${if (request.tree) "目录" else "文件"}引用（未读取）：$raw"
                val instructions = if (request.tree) "用 list_files 按需列举，勿自动读取全部文件。"
                    else "用 file_metadata 查看元数据；用 read_text_file 按行或 query 检索，按 next_offset_bytes 续读；勿每轮展开全文。不支持的文档格式应明确说明。"
                val details = "$reference\n附件信息（按需读取）：$description；$instructions"
                val next = if (request.text.isEmpty()) details else "${request.text}\n$details"
                val editor = inlineMessageEditor
                if (request.editVisit != null && editor?.visit === request.editVisit) {
                    inlineMessageEditor = editor.copy(text = next, selectionStart = next.length, selectionEnd = next.length)
                    publishChat(rowsChanged = false)
                } else if (request.editVisit == null && editor == null) {
                    updateDraft(next)
                    if (draft == next) {
                        selectionStart = next.length; selectionEnd = next.length; publishChat(rowsChanged = false)
                    } else toast("引用未加入；原草稿保留")
                }
            } finally {
                if (chatReferencePick === request) {
                    chatReferencePick = null
                    if (request.editVisit != null && activityUsable()) publishChat(rowsChanged = false)
                }
            }
        }
    }
    private val rootExitConfirmation = RootExitConfirmation()
    private var rootExitToast: Toast? = null

    private fun resetRootExitConfirmation() {
        rootExitConfirmation.reset()
        rootExitToast?.cancel()
        rootExitToast = null
    }

    private fun handleRootBack(hideIme: () -> Unit) {
        if (!activityUsable() || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) ||
            retainedPage != "chat" || sidebarVisible) return
        if (ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
            resetRootExitConfirmation()
            hideIme()
            return
        }
        if (rootExitConfirmation.shouldExit(SystemClock.elapsedRealtime())) {
            resetRootExitConfirmation()
            finish()
        } else {
            rootExitToast?.cancel()
            rootExitToast = Toast.makeText(this, "再按一次返回退出应用", Toast.LENGTH_SHORT).also { it.show() }
        }
    }

    private var sidebarVisibleState by mutableStateOf(false)
    private var sidebarVisible: Boolean
        get() = sidebarVisibleState
        set(value) {
            if (value != sidebarVisibleState) resetRootExitConfirmation()
            // Opening revokes even without a visit; closing cannot revive clicks rendered behind it.
            if (value != sidebarVisibleState) revokeContextManagementEntry()
            if (value && !sidebarVisibleState) revokeCommandApproval()
            if (value && !sidebarVisibleState) { revokeChatReferencePick(); voiceConversationDialogOwner = null; revokePlatformActionVisit(); cancelBrowserDownloadVisit(); revokeTerminalJobsVisit(); stopCascadeVoice(); invalidateConfiguredVoice(); fenceVideoRoute(); cancelGenerationEditor(); cancelGenerationOutputs(); cancelTerminalEditor(); cancelVirtualDisplayEditor(); cancelGuiEditor(); cancelMcpEditor() }
            sidebarVisibleState = value
        }
    private class ContextManagementVisit(val owner: ContextManagementOwner,
        val adapter: ContextManagementControllerAdapter)
    private var contextManagementVisit by mutableStateOf<ContextManagementVisit?>(null)
    // Entry authority is separate from the shared editor generation and is never restored/saved.
    private var contextManagementEntryEpoch by mutableStateOf(0L)
    // Revoke BEFORE navigation/lifecycle changes, including when no route has been opened yet.
    private fun revokeContextManagementEntry(closeRoute: Boolean = true): Boolean {
        contextManagementEntryEpoch++
        val visit = contextManagementVisit
        contextManagementVisit = null
        if (visit != null) controller.invalidateContextManagement(visit.adapter)
        // Explicit navigation closes the page; lifecycle revocation keeps only a paused shell.
        if (closeRoute && retainedPage == "context-management") retainedPage = "chat"
        return visit != null
    }
    private class BrowserResumeTarget(val conversationId: ConversationId?, val owner: WorkStepOwnerUi?) {
        var presentation = app.daiyu.android.browser.BrowserSheetUiState()
    }
    private var browserResumeTarget by mutableStateOf<BrowserResumeTarget?>(null)
    private var browserVisualEpoch by mutableStateOf(0L)
    private var browserVisualVisit by mutableStateOf<BrowserVisualVisit?>(null)
    private val humanBrowsers = mutableMapOf<ConversationId?, LocalBrowserVisual>()
    private fun closeBrowserVisualVisit(clearTarget: Boolean = true) {
        browserVisualEpoch++
        val original = browserVisualVisit
        browserVisualVisit = null
        if (clearTarget) browserResumeTarget = null
        original?.close()
    }
    private fun browserVisualPageCurrent(original: BrowserVisualVisit): Boolean =
        activityUsable() && voiceForeground && generationResumed() && retainedPage == "browser" &&
            !sidebarVisible && browserVisualVisit === original && selected?.id == original.conversationId

    private fun openBrowserVisualPage(expectedOwner: WorkStepOwnerUi? = null) {
        if (!activityUsable() || !voiceForeground || retainedPage != "chat" || sidebarVisible ||
            !selectionReady || !controller.ready || controller.dataBusy || pendingTextSend != null ||
            controller.textSendState() != null) return
        closeBrowserVisualVisit()
        val original = selected?.id?.let { controller.openBrowserVisual(it, expectedOwner) }
        if (expectedOwner != null && original == null) return
        retainedPage = "browser"
        browserResumeTarget = BrowserResumeTarget(selected?.id, original?.owner?.let {
            WorkStepOwnerUi(it.conversationId.value, it.runId.value, it.id.value)
        })
        browserVisualVisit = original ?: openHumanBrowserVisit()
    }

    private fun openHumanBrowserVisit(): BrowserVisualVisit? = humanBrowsers.getOrPut(selected?.id) {
        LocalBrowserVisual(applicationContext, selected?.id)
    }.let { human ->
        if (human.needsReplacement) {
            browserResumeTarget?.presentation = app.daiyu.android.browser.BrowserSheetUiState()
            human.close()
            LocalBrowserVisual(applicationContext, selected?.id).also {
                humanBrowsers[selected?.id] = it
            }.openVisit()
        } else human.openVisit()
    }

    private fun resumeBrowserVisualPage(target: BrowserResumeTarget?, epoch: Long) {
        if (target == null || browserResumeTarget !== target || epoch != browserVisualEpoch ||
            !activityUsable() || !voiceForeground || !generationResumed() || retainedPage != "browser" ||
            sidebarVisible || selected?.id != target.conversationId || !selectionReady ||
            !controller.ready || controller.dataBusy || pendingTextSend != null ||
            controller.textSendState() != null || browserVisualVisit != null) return
        val next = if (target.owner != null) {
            controller.openBrowserVisual(checkNotNull(target.conversationId), target.owner)
        } else openHumanBrowserVisit()
        if (next == null) toast("原浏览器任务已结束或不可用；请返回会话后重新打开")
        else { browserVisualEpoch++; browserVisualVisit = next }
    }

    private fun resumeContextManagementPage(conversation: ConversationId?, generation: Long, epoch: Long) {
        if (conversation == null || selected?.id != conversation || editorGeneration != generation ||
            contextManagementEntryEpoch != epoch || contextManagementVisit != null ||
            !activityUsable() || !voiceForeground || !generationResumed() || sidebarVisible ||
            retainedPage != "context-management") return
        val owner = ContextManagementOwner(conversation, epoch)
        val adapter = controller.openContextManagement(owner)
        if (adapter == null) toast("上下文页面暂不可用；请等待当前任务结束后再试")
        else { contextManagementEntryEpoch++; contextManagementVisit = ContextManagementVisit(owner, adapter) }
    }

    private var retainedPageState by mutableStateOf("chat")
    private var offlineSpeechReturnPage = "chat"
    private var usageReturnPage = "chat"
    private var providersReturnPage = "chat"
    private var skillsReturnPage = "chat"
    private var retainedPage: String
        get() = retainedPageState
        set(value) {
            if (value != retainedPageState) {
                revokeWorkspaceVisit()
                resetRootExitConfirmation()
                revokeCommandApproval()
                revokeTerminalJobsVisit()
                childStatusVisit = null
                usageVisit = null
                revokeBackupExportPage()
                if (value == "chat") refreshConfirmedModelLabel()
            }
            if (value != "browser") closeBrowserVisualVisit()
            // Revoke before any navigation work, not a later Compose disposal or IO callback.
            if (value != retainedPageState) revokeContextManagementEntry(closeRoute = false)
            if (value != retainedPageState) { revokeChatReferencePick(); voiceConversationDialogOwner = null; revokePlatformActionVisit(); cancelDeferredOpenedBackup() }
            if (value != retainedPageState) { gptSpeedSaveOwner?.revoke(); gptSpeedSaveOwner = null }
            if (value != "chat") cancelBrowserDownloadVisit()
            if (value != "reconnect-settings") {
                reconnectSettingsPageOwner?.revoke()
                reconnectSettingsPageOwner = null
            }
            if (value != retainedPageState) { stopCascadeVoice(); invalidateConfiguredVoice(); fenceVideoRoute(); cancelGenerationOutputs(); cancelVirtualDisplayEditor(); cancelGuiEditor() }
            if (value != "models") { modelSaveOwner?.revoke(); modelSaveOwner = null }
            if (value != "session-gateway") {
                gatewayOwner?.revoke(); gatewayOwner = null
                gatewayEditing = null; gatewayProviderEditor = null; gatewayDraft = null
                gatewayReplacementAuthorized = false; gatewayReloadPending = false; gatewayError = null
            }
            if (value != "generation-settings") {
                generationSettingsVisit?.active?.set(false)
                generationSettingsVisit = null
            }
            if (value != "title-model-settings") {
                titleSettingsPageOwner?.revoke()
                titleSettingsPageOwner = null
            }
            if (value != "compression-settings") {
                compressionSettingsPageOwner?.revoke()
                compressionSettingsPageOwner = null
            }
            if (value == "platform-settings") {
                if (platformSettingsPageOwner == null) platformSettingsPageOwner = Any()
            } else {
                platformSettingsPageOwner = null
                // Retain outstanding request tokens until their callback consumes them. Otherwise a
                // late result could be confused with a second picker opened on a newer page visit.
            }
            if (value != "backup") { if (prompt?.backup == true) prompt = null; backupImportPickerHopPending = false; backupImportVisit = null; pendingBackupImportResult = null; cancelRestorePreview() }
            if (value != "terminal") terminalPageOwner = null
            if (value != "linux-settings" && value != "chat-workspace") {
                linuxSettingsVisit = null
                linuxSettingsPageOwner?.close()
                linuxSettingsPageOwner = null
            }
            if (value != "mcp-settings") {
                mcpSettingsPageOwner?.revoke(); mcpSettingsPageOwner = null
            }
            if (value != "browser-settings") {
                browserSettingsPageOwner?.revoke()
                browserSettingsPageOwner = null
            }
            if (value != "chat") { cancelPaneConversationSelection(); dictationHost.invalidate(); offlineHost.invalidate(); cancelShizukuEditor(); cancelBrowserEditor() }
            speechHost.setVisible(value == "chat" && !sidebarVisible)
            imageHost.setVisible(value == "chat" && !sidebarVisible, selected?.id?.value)
            retainedPageState = value
            syncHaptics()
        }
    private var paneState by mutableStateOf(ConversationPaneUiState(emptyList(), null, "", conversationsLoaded = false))
    private data class PaneRevision(val data: Long, val title: Long,
        val active: Triple<ConversationId, MessageId, RunId>?)
    private var paneSnapshotRevision: PaneRevision? = null
    private fun currentPaneRevision() = PaneRevision(controller.dataRevision, controller.titleRevision,
        controller.active?.let { Triple(it.conversationId, it.id, it.runId) })
    internal var paneSnapshotReader: (ConversationId?) -> RetainedConversationData.PaneSnapshot =
        { activeConversationId -> retainedData.paneSnapshot(activeConversationId) }
    internal data class PaneConversationSnapshot(
        val conversation: Conversation,
        val conversations: List<Conversation>,
        val messages: List<Pair<Long, Message>>,
    )
    internal var paneConversationReader: (String) -> PaneConversationSnapshot = { id ->
        val conversations = controller.database.conversations()
        val conversation = conversations.single { it.id.value == id }
        PaneConversationSnapshot(conversation, conversations, controller.database.messages(conversation.id))
    }
    private var paneSelectionJob: Job? = null
    private var paneSelectionRequest: Any? = null
    private var pendingPaneConversationId: String? = null
    private var paneRefreshSerial = 0L
    private var paneRefreshPending = false
    private var paneRefreshRunning = false
    private var paneRefreshClosed = false
    private var paneReadFailureReported = false
    private var chatState by mutableStateOf(ChatScreenState("代鱼"))
    private var interactiveModeEnabled by mutableStateOf(false)
    private var interactiveModeObservation: AutoCloseable? = null
    @Volatile private var selected: Conversation? = null
        set(value) {
            if (field?.id != value?.id) {
                revokeWorkspaceVisit()
                closeBrowserVisualVisit()
                if (retainedPage == "browser") retainedPage = "chat"
            }
            if (field?.id != value?.id && revokeContextManagementEntry()) editorGeneration++
            if (field?.id != value?.id) { revokePlatformActionVisit(); cancelBrowserDownloadVisit(); cancelStaticGenerationVisit() }
            field = value
        }
    private var draft by mutableStateOf("")
    private var selectionStart = 0
    private var selectionEnd = 0
    @Volatile private var selectionReady = false
    private data class InlineMessageEditor(val key: String, val text: String,
        val target: app.daiyu.android.storage.MessageActionTarget,
        val images: List<app.daiyu.android.ui.screens.chat.conversation.MessageContentUi.Image>,
        val videos: List<app.daiyu.android.media.video.MessageVideoReference>,
        val additions: MessageEditImageDraft,
        val uncertain: Boolean = false,
        val selectionStart: Int = text.length, val selectionEnd: Int = text.length,
        val visit: Any = Any()) {
        val canSubmit: Boolean get() = !uncertain && !additions.blocked &&
            (text.isNotBlank() || images.isNotEmpty() || videos.isNotEmpty() || additions.ready.isNotEmpty())
    }
    private var inlineMessageEditor by mutableStateOf<InlineMessageEditor?>(null)
    private val messageActionHost by lazy { ControllerMessageActionHost(controller) }
    private var messageActionVisit = Any()
    private var messageActionSnapshot: app.daiyu.android.storage.MessageActionSnapshot? = null
    private data class MessageActionRenderKey(val conversation: ConversationId, val editor: Long,
        val rows: String, val data: Long)
    private var messageActionKey: MessageActionRenderKey? = null
    private var messageActionLoading: MessageActionRenderKey? = null
    private var messageActionAttempted: MessageActionRenderKey? = null
    private fun clearInlineMessageEditor() {
        inlineMessageEditor?.additions?.close()
        inlineMessageEditor = null
        editImagePick?.live = false
        if (editImagePick?.returned == true) editImagePick = null
        if (chatReferencePick?.editVisit != null) revokeChatReferencePick()
    }
    private fun suspendMessageActionEditor() {
        messageActionHost.invalidate()
        messageActionVisit = Any()
        messageActionSnapshot = null; messageActionKey = null
        messageActionLoading = null; messageActionAttempted = null
    }
    private fun messageEditTargetCurrent(editor: InlineMessageEditor): Boolean =
        messageActionSnapshot?.let { snapshot ->
            snapshot.matchesRendered(shown.map { it.second }) &&
                editor.target == (snapshot.renderedTargets[editor.key] ?: snapshot.targets[editor.key])
        } == true
    private fun retainUncertainMessageEdit() {
        val editor = inlineMessageEditor ?: return
        inlineMessageEditor = editor.copy(uncertain = true)
        // This edit already preserved the normal draft; consume its revision without revoking its temporary attachments.
        dataRevision = controller.dataRevision
    }
    private fun invalidateMessageActionEditor() {
        clearInlineMessageEditor()
        suspendMessageActionEditor()
    }
    private fun prepareMessageActionSnapshot() {
        val owner = selected?.id ?: run { invalidateMessageActionEditor(); return }
        if (!activityUsable() || !voiceForeground || !selectionReady || !controller.ready || controller.dataBusy || controller.active != null) return
        val key = MessageActionRenderKey(owner, editorGeneration,
            app.daiyu.android.domain.context.ContextRevision.digest(shown.map { it.second }), controller.dataRevision)
        if (messageActionKey == key || messageActionLoading == key || messageActionAttempted == key) return
        messageActionSnapshot = null; messageActionKey = null
        // Admitted failures and mismatches consume the key; a busy rejection has not read anything.
        messageActionAttempted = key; messageActionLoading = key
        val visit = messageActionVisit
        val admitted = controller.loadMessageActionSnapshot(owner) { candidate ->
            if (messageActionVisit === visit && messageActionLoading == key) {
                messageActionLoading = null
                val rows = shown.map { it.second }
                if (activityUsable() && voiceForeground && selected?.id == key.conversation && editorGeneration == key.editor &&
                    controller.dataRevision == key.data &&
                    app.daiyu.android.domain.context.ContextRevision.digest(rows) == key.rows &&
                    candidate != null && candidate.conversationId == owner && candidate.matchesRendered(rows)) {
                    messageActionSnapshot = candidate; messageActionKey = key
                    publishChat()
                }
            }
        }
        if (!admitted) {
            messageActionLoading = null
            messageActionAttempted = null
        }
    }
    @Volatile private var editorGeneration = 0L
        set(value) {
            if (field != value) { closeBrowserVisualVisit() }
            if (field != value) { revokeChatReferencePick(); revokePlatformActionVisit(); cancelBrowserDownloadVisit(); cancelStaticGenerationVisit(); revokeTerminalJobsVisit(); revokeContextManagementEntry(); cancelGenerationEditor(); invalidateMessageActionEditor(); invalidateConfiguredVoice(); dictationHost.invalidate(); offlineHost.invalidate() }
            field = value
        }
    private var draftRevision = 0L
        set(value) {
            if (field != value) {
                revokeChatReferencePick(); revokePlatformActionVisit(); cancelGenerationEditor(); messageActionHost.invalidate()
                if (!applyingDictationDraft) { invalidateConfiguredVoice(); dictationHost.invalidate(); offlineHost.invalidate() }
            }
            field = value
            if (applyingDictationDraft) dictationDraftRevision = value
        }
    private data class PendingTextSend(val owner: ChatController.TextSendOwner, val text: String,
        val ticket: ChatController.TextSendTicket = ChatController.TextSendTicket(),
        val editorOwner: ChatController.TextSendOwner = owner)
    private var pendingTextSend by mutableStateOf<PendingTextSend?>(null)
    private var acceptedTextPreview: Pair<ConversationId, app.daiyu.android.ui.screens.chat.conversation.ChatSubmissionPreview>? = null
    private class TerminalEditor(val conversation: Conversation?, val draft: TerminalDraftSnapshot) {
        var command by mutableStateOf("")
        var linux by mutableStateOf<app.daiyu.android.terminal.LinuxGenerationRef?>(null)
        var preparing by mutableStateOf(false)
        var choosing by mutableStateOf(false)
        var confirmed by mutableStateOf(false)
        var preparation by mutableStateOf<ControllerTerminalPreparation?>(null)
        var jobMode by mutableStateOf<String?>(null) // null is existing PTY; never silently reroute it.
        var jobPreparation by mutableStateOf<ControllerTerminalJobPreparation?>(null)
    }
    private var terminalEditor by mutableStateOf<TerminalEditor?>(null)
    private class VirtualDisplayPlanVisit(val conversation: Conversation?,
        val editor: ChatController.TextSendOwner, val text: String) {
        val host = R08PlanEditorHost()
    }
    private var virtualDisplayPlanVisit by mutableStateOf<VirtualDisplayPlanVisit?>(null)
    private class VirtualDisplayCleanupVisit(val entry: ControllerVirtualDisplayEntry,
        val owner: R08CleanupUiOwner, val adapter: ControllerVirtualCleanupAdapter) {
        var request by mutableStateOf<R08CleanupApprovalRequest?>(null)
        var decided by mutableStateOf(false)
        var status by mutableStateOf("请选择显式观察任务或仅申请 Release；打开页面不执行 owner 操作。")
        var tasks by mutableStateOf<ControllerVirtualDisplaySession.RetainedTasks?>(null)
        var selectedTaskIds by mutableStateOf<Set<Int>>(emptySet())
        var busy by mutableStateOf(false)
        var observationStarted by mutableStateOf(false)
        var shutdownStarted by mutableStateOf(false)
    }
    // Workers read only this atomic owner, never Compose state or the current text editor.
    private val currentCleanupOwner = AtomicReference<R08CleanupUiOwner?>(null)
    private var virtualDisplayCleanupVisit by mutableStateOf<VirtualDisplayCleanupVisit?>(null)
    private class TaskLocationVisit(val owner: Any, val surface: ControllerTaskSurface,
        val current: () -> Boolean, val proceed: () -> Unit, val cancel: () -> Unit) {
        // These closures retain the exact leaf question identity, not a lookup of the latest one.
        var questionUi by mutableStateOf<(@Composable () -> Unit)?>(null)
        var cancelQuestion: (() -> Unit)? = null
    }
    private var taskLocationVisit by mutableStateOf<TaskLocationVisit?>(null)
    private var guiEditor by mutableStateOf<ControllerGuiEntry?>(null)
    private var virtualDisplayEditor by mutableStateOf<ControllerVirtualDisplayEntry?>(null)
    private var virtualDisplayRequest by mutableStateOf<app.daiyu.android.vdapproval.VirtualDisplayApprovalRequest?>(null)
    private var mcpApproval by mutableStateOf<ControllerMcpPreparation?>(null)
    private var mcpPreparing by mutableStateOf(false)
    private var mcpRequest: Any? = null
    // One-way visit identity: returning to chat/resuming never restores an old input or preview.
    private var mcpVisit: Any? = null
    private var mcpInput: InputPrompt? = null
    private var browserApproval by mutableStateOf<ControllerBrowserPreparation?>(null)
    private var browserPreparing by mutableStateOf(false)
    private var browserRequest: Any? = null
    private var generationApproval by mutableStateOf<ChatController.GenerationEntry?>(null)
    private var generationForm by mutableStateOf(app.daiyu.android.ui.GenerationApprovalForm())
    private var generationFormRevision by mutableStateOf(0L)
    private var generationRequest: Any? = null
    private var generationVisit: Any? = null
    private var generationKind: app.daiyu.android.model.generation.GenerationKind? = null
    private var generationInputPending by mutableStateOf(false)
    private val generationInputsDelegate = lazy {
        // A blocked provider must not retain this Activity across recreation.
        val resolver = applicationContext.contentResolver
        GenerationInputHost(current = ::generationInputIdentity,
            read = { source, request ->
                val cancellation = android.os.CancellationSignal()
                request.onCancel { cancellation.cancel() }
                try {
                    GenerationInputImport.read(source, open = {
                        val uri = Uri.parse(source)
                        check(uri.scheme == "content")
                        cancellation.throwIfCanceled()
                        val descriptor = resolver.openAssetFileDescriptor(uri, "r", cancellation)
                            ?: error("generation_input_unavailable")
                        val stream = try { descriptor.createInputStream() }
                        catch (failure: Exception) { descriptor.close(); throw failure }
                        // Registration also closes a stream returned AFTER logical cancellation.
                        request.onCancel { stream.close() }
                        stream
                    }, inspector = app.daiyu.android.media.AndroidImageInspector(),
                        protocol = request.protocol, target = request.target, reference = request.reference,
                        cancelled = { request.cancelled.get() || Thread.currentThread().isInterrupted })
                } finally { request.clearCancellation() }
            }, dispatch = { action -> runOnUiThread { action() } },
            changed = { generationInputPending = it }, finished = { request, result ->
                if (generationInputIdentity() == request.identity && generationResumed()) {
                    result.fold(onSuccess = { input ->
                        val replacedMask = request.target == GenerationInputTarget.REFERENCE && generationForm.mask != null
                        generationForm = if (request.target == GenerationInputTarget.REFERENCE)
                            generationForm.copy(references = listOf(input), mask = null)
                        else generationForm.copy(mask = input)
                        generationFormRevision++
                        if (replacedMask) toast("原图已更新；旧 mask 已移除，请重新选择同尺寸 mask 并完整预览")
                    }, onFailure = { toast("原图或 mask 无效、过大或不可读取；未发送请求，表单保留") })
                }
            })
    }
    private val generationInputs get() = generationInputsDelegate.value
    private val generationInputPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (generationInputsDelegate.isInitialized()) {
            generationInputs.deliver(uri?.toString())
            if (generationResumed()) generationInputs.resume()
        }
    }

    private fun generationResumed() = activityUsable() &&
        lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)

    private fun generationInputIdentity(): GenerationInputIdentity? {
        val entry = generationApproval ?: return null
        val visit = generationVisit ?: return null
        val profile = generationForm.profileId ?: return null
        if (entry.submitted || entry.abandoned || controller.currentGenerationEntry() !== entry ||
            retainedPage != "chat" || sidebarVisible || !selectionReady ||
            selected?.id != entry.conversation?.id || editorGeneration != entry.editorGeneration ||
            draftRevision != entry.initialEditor.revision || draft != entry.initialEditor.text ||
            imageHost.hasAttachments || videoHost.state.items.isNotEmpty() || pendingTextSend != null) return null
        return GenerationInputIdentity(entry, visit, selected?.id?.value, editorGeneration,
            draftRevision, draft, generationFormRevision, profile,
            GenerationFormInputs.ids(generationForm.references, generationForm.mask))
    }

    private fun pickGenerationInput(target: GenerationInputTarget) {
        if (!generationResumed() || generationInputPending) return
        val identity = generationInputIdentity() ?: return toast("请先选择本次生成专用配置")
        val entry = generationApproval ?: return
        val protocol = entry.settings.profiles.singleOrNull { it.id == identity.profileId }?.protocol ?: return
        generationInputs.resume()
        // Opening/cancelling a selection also invalidates any retained old preview callback.
        generationFormRevision++
        val frozen = generationInputIdentity() ?: return
        val request = generationInputs.begin(frozen, protocol, target, generationForm.references.singleOrNull())
            ?: return toast("此协议不支持该输入，或已有选择尚未结束；未发送请求")
        try { generationInputPicker.launch(if (protocol == app.daiyu.android.model.generation.GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART) "image/png" else "image/*") }
        catch (_: Exception) { generationInputs.launchFailed(request); toast("无法打开图片选择器；表单保留") }
    }

    private fun generationFormEditor() = GenerationEditorSnapshot(generationFormRevision, draft,
        GenerationFormInputs.ids(generationForm.references, generationForm.mask))
    private var generationOutputs by mutableStateOf<List<ChatController.GenerationAssetDisplay>?>(null)
    private var historicalGenerationOutputs by mutableStateOf<List<ChatController.HistoricalGenerationAssetDisplay>?>(null)
    private var pendingHistoricalOriginal: app.daiyu.android.backup.HistoricalGenerationOutput? = null
    private var staticGenerationOutputs by mutableStateOf<List<ChatController.StaticGenerationAssetDisplay>?>(null)
    private var staticGenerationVisit: java.util.concurrent.atomic.AtomicBoolean? = null
    private data class StaticSaveSelection(val requestCode: Int,
        val output: app.daiyu.android.storage.StaticGenerationOutput,
        val active: java.util.concurrent.atomic.AtomicBoolean, var pickerHopPending: Boolean = true)
    private var pendingStaticOriginal: StaticSaveSelection? = null
    private var unknownBranchOutcome by mutableStateOf<MessageActionResult.Unknown?>(null)
    private var childStopChoice by mutableStateOf<ChatController.ChildStopChoice?>(null)
    private fun refreshUnknownBranchOutcome() {
        if (activityUsable() && voiceForeground) unknownBranchOutcome = controller.pendingBranchOutcome()
    }
    private fun dismissUnknownBranchOutcome(receipt: MessageActionResult.Unknown) {
        if (!activityUsable() || !voiceForeground || unknownBranchOutcome !== receipt) return
        controller.dismissBranchOutcome(receipt)
        refreshUnknownBranchOutcome()
    }
    // Memory-only, one-way capability for a chat/editor visit. Workers only read its
    // atomic cancellation flag, never route/sidebar/Compose state that can undergo ABA.
    private class BrowserDownloadVisit(val conversation: ConversationId, val generation: Long) {
        val active = AtomicBoolean(true)
    }
    private var browserDownloadVisit: BrowserDownloadVisit? = null
    private data class BrowserDownloadListing(val visit: BrowserDownloadVisit,
        val references: List<app.daiyu.android.browser.download.BrowserCompletedDownload>)
    private var browserDownloadOutputs by mutableStateOf<BrowserDownloadListing?>(null)
    private data class BrowserSaveSelection(val requestCode: Int, val visit: BrowserDownloadVisit,
        val reference: app.daiyu.android.browser.download.BrowserCompletedDownload)
    private var pendingBrowserDownload: BrowserSaveSelection? = null
    private var nextBrowserSaveRequestCode = 14000
    private var generationMediaVisit: AtomicBoolean? = null
    private var generationMediaDialog: android.app.Dialog? = null
    private data class GenerationMediaSave(val requestCode: Int,
        val binding: app.daiyu.android.storage.GenerationAttemptBinding, val assetKey: String,
        val active: AtomicBoolean, var pickerHopPending: Boolean = true)
    private var pendingGenerationMediaOriginal: GenerationMediaSave? = null

    private fun cancelGenerationMedia() {
        generationMediaVisit?.set(false); generationMediaVisit = null
        generationMediaDialog?.dismiss(); generationMediaDialog = null
        pendingGenerationMediaOriginal?.let { if (pendingBackupRequest == it.requestCode) pendingBackupRequest = null }
        pendingGenerationMediaOriginal = null
    }

    @Suppress("DEPRECATION") private fun generationMediaAction(step: WorkStepUi, request: ToolCardActionRequest) {
        val owner = step.owner ?: return
        val browser = step.toolName in setOf("browser_read", "browser_write") && step.hasBrowserScreenshot
        val assetKey = if (browser && request.assetKey == null)
            app.daiyu.android.media.BrowserScreenshotStore.MEDIA_KEY else request.assetKey ?: return toast("未找到原件")
        if (browser) {
            if (assetKey != app.daiyu.android.media.BrowserScreenshotStore.MEDIA_KEY ||
                request.action !in setOf(ToolCardAction.OPEN_IMAGE, ToolCardAction.SAVE_MEDIA)) return toast("未找到原件")
        } else if (step.toolName !in setOf("generate_images", "generate_videos") || GenerationMediaKey.parse(assetKey) == null)
            return toast("未找到原件")
        cancelGenerationMedia()
        val active = AtomicBoolean(true).also { generationMediaVisit = it }
        val editor = editorGeneration
        val visit = contextManagementEntryEpoch
        val binding = app.daiyu.android.storage.GenerationAttemptBinding(owner.conversationId, owner.messageId,
            owner.runId, step.toolCallId, step.toolName)
        fun current() = active.get() && generationMediaVisit === active && activityUsable() && voiceForeground &&
            retainedPage == "chat" && !sidebarVisible && selected?.id?.value == owner.conversationId &&
            editorGeneration == editor && contextManagementEntryEpoch == visit && resolveToolCardAction(chatState.turns, request) != null
        val accepted = controller.loadGenerationMediaOriginal(binding, assetKey, active) { original ->
            if (!current()) { original?.close(); return@loadGenerationMediaOriginal }
            if (original == null) { cancelGenerationMedia(); toast("原件读取失败"); return@loadGenerationMediaOriginal }
            val metadata = original.metadata
            when (request.action) {
                ToolCardAction.SAVE_MEDIA -> {
                    original.close()
                    backupGuard {
                        check(pendingBackupRequest == null) { "文件选择尚未返回" }
                        val extension = when (metadata.mimeType) {
                            "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"
                            "video/mp4" -> "mp4"; "video/webm" -> "webm"; else -> error("不支持的原件类型")
                        }
                        val selection = GenerationMediaSave(allocateOriginalSaveRequestCode(), binding, assetKey, active)
                        pendingGenerationMediaOriginal = selection; pendingBackupRequest = selection.requestCode
                        try { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE); type = metadata.mimeType
                            putExtra(Intent.EXTRA_TITLE, "daiyu-original.$extension")
                        }, selection.requestCode) } catch (failure: Exception) { cancelGenerationMedia(); throw failure }
                    }
                }
                ToolCardAction.OPEN_IMAGE -> {
                    if (metadata.kind != app.daiyu.android.model.generation.GenerationKind.IMAGE) {
                        original.close(); cancelGenerationMedia(); toast("原件不是图片")
                    } else lifecycleScope.launch {
                        val bitmap = try { withContext(Dispatchers.IO) {
                            original.use {
                                it.requireActive()
                                android.graphics.BitmapFactory.decodeStream(it.stream, null,
                                    android.graphics.BitmapFactory.Options().apply {
                                        var sample = 1
                                        while (metadata.width.toLong() * metadata.height / sample / sample > 8_000_000) sample *= 2
                                        inSampleSize = sample
                                    })
                            }
                        } } catch (cancelled: kotlinx.coroutines.CancellationException) { original.close(); throw cancelled }
                        catch (_: Exception) { null }
                        if (!current()) { bitmap?.recycle(); return@launch }
                        if (bitmap == null) { cancelGenerationMedia(); toast("图片读取失败") }
                        else try {
                            generationMediaDialog = app.daiyu.android.ui.GenerationMediaDialog.image(this@MainActivity, bitmap) {
                                active.set(false)
                            }.also { it.show() }
                        } catch (_: Exception) { bitmap.recycle(); cancelGenerationMedia(); toast("图片打开失败") }
                    }.invokeOnCompletion { original.close() }
                }
                ToolCardAction.PLAY_VIDEO -> {
                    if (metadata.kind != app.daiyu.android.model.generation.GenerationKind.VIDEO) {
                        original.close(); cancelGenerationMedia(); toast("原件不是视频")
                    } else try {
                        generationMediaDialog = app.daiyu.android.ui.GenerationMediaDialog.video(this, original, audioGate, VoiceOwner(owner.conversationId, owner.runId)) {
                            active.set(false)
                        }.also { it.show() }
                    } catch (_: Exception) { original.close(); cancelGenerationMedia(); toast("视频播放失败") }
                }
                else -> original.close()
            }
        }
        if (!accepted) { cancelGenerationMedia(); toast("原件暂不可读") }
    }

    private var generationOutputsRequest: Any? = null
    private var generationOutputsOwner: Pair<ConversationId, Long>? = null
    private fun generationOutputsPresented() = activityUsable() && retainedPage == "chat" && !sidebarVisible &&
        generationOutputsOwner != null && generationOutputsOwner == selected?.id?.let { it to editorGeneration }
    private var generationReceiptNotice by mutableStateOf<ChatController.GenerationReceiptNotice?>(null)
    private val supplementDraft get() = controller.supplementDraft
    private var restoredSupplementEditor: SupplementEditor? = null
    private var supplementMountEditor: SupplementEditor? = null
    private val selections get() = controller.selections
    private var shown = mutableListOf<Pair<Long, Message>>()
    private data class ReplyOwner(val conversation: String, val run: String, val message: String)
    private data class ReplyIdentityKey(val owners: Set<ReplyOwner>, val dataRevision: Long)
    private var replyIdentityKey: ReplyIdentityKey? = null
    private var replyIdentityRequest: ReplyIdentityKey? = null
    private var replyIdentityJob: Job? = null
    private var replyIdentities = emptyMap<String, app.daiyu.android.assistants.AssistantReplyIdentity>()
    private fun refreshReplyIdentities() {
        val messages = shown.map { it.second }.filter { it.role == Message.ASSISTANT_ROLE }
        val key = ReplyIdentityKey(messages.map { ReplyOwner(it.conversationId.value, it.runId.value, it.id.value) }.toSet(), controller.dataRevision)
        if (replyIdentityRequest != null && replyIdentityRequest != key) {
            replyIdentityJob?.cancel()
            replyIdentityRequest = null
        }
        if (key == replyIdentityKey || key == replyIdentityRequest) return
        replyIdentityRequest = key
        replyIdentityJob = lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    messages.mapNotNull { message -> controller.database.replyIdentity(message)?.let { message.id.value to it } }.toMap()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { emptyMap() }
            if (replyIdentityRequest == key) {
                replyIdentityKey = key
                replyIdentityRequest = null
                replyIdentities = result
                publishChat()
            }
        }
    }
    private data class QuestionRowUi(val message: Message, val retryCleanup: Boolean)
    private var questionRows by mutableStateOf<Map<String, QuestionRowUi>>(emptyMap())
    private var questionCards by mutableStateOf<Map<Pair<WorkStepOwnerUi, String>, ChatController.QuestionProjection>>(emptyMap())
    private var titleRevision = -1L
    private var modelDeletionRevision = -1L
    private var dataRevision = -1L
    private var scrollSerial = 0L
    private var historyRequestSerial = 0L
    private var controlOwner: Message? = null
    private var controlTouchOwner: Message? = null
    private var controlTouchActive = false
    private var controlTouchGeneration = 0L
    private val listener: () -> Unit = { refresh() }
    @Volatile private var activityCallbacksAllowed = false
    private var refreshRateWindowResumed = false
    private var refreshRateRequestRelinquished = false
    private var previousPreferredRefreshRate: Float? = null
    private var requestedPreferredRefreshRate: Float? = null
    private val refreshRateRequest = Runnable { applyPreferredRefreshRate() }

    // Prompts are one-shot capabilities, not saved state. A replaced/dismissed prompt cannot commit.
    private sealed interface Prompt { val backup: Boolean }
    private class InputPrompt(val title: String, val initial: String, val hint: String,
        val confirm: String, val action: (String) -> Unit, override val backup: Boolean = false) : Prompt
    private class FolderPrompt(val folders: List<app.daiyu.android.ui.model.ConversationFolderUi>,
        val initialFolderId: String?, val action: (String?) -> Unit, override val backup: Boolean = false) : Prompt
    private class ChoicePrompt(val title: String, val options: List<String>, val action: (Int) -> Unit,
        override val backup: Boolean = false) : Prompt
    private class ConfirmPrompt(val title: String, val message: String, val confirm: String,
        val destructive: Boolean = false, val action: () -> Unit, override val backup: Boolean = false,
        val showCloseButton: Boolean = true, val secondaryCancel: Boolean = false) : Prompt
    private var prompt by mutableStateOf<Prompt?>(null)
    private var lastOpenedBackupUri: String? = null
    // One validated, bounded URI only: never an Intent/ClipData, descriptor, saved state or grant.
    // Recovery completion may offer the normal manual choice, but must not read/import a file.
    private class DeferredOpenedBackup(val uri: Uri, val waitingPrompt: Prompt)
    private var deferredOpenedBackup: DeferredOpenedBackup? = null
    private var pendingBackupRequest: Int? = null
    private var backupExportPageOwner by mutableStateOf<Any>(Any())
    private var pendingBackupExportOwner: Any? = null
    private var pendingBackupIncludeLinux = false
    private var backupExportPickerHopPending = false
    private fun backupExportPageCurrent(owner: Any?) = owner != null && backupExportPageOwner === owner &&
        activityUsable() && retainedPage == "backup"
    private fun revokeBackupExportPage() {
        backupExportPageOwner = Any()
        pendingBackupExportOwner = null
        pendingBackupIncludeLinux = false
        backupExportPickerHopPending = false
        // Keep the outstanding request slot until its result is consumed.
    }
    private var exportPickerPending by mutableStateOf(false)
    private var conversationExport = ConversationExportSelection()
    private var taskRecordsConversationId by mutableStateOf<String?>(null)
    // Frozen entry context. Readers run on IO and must not inspect mutable Compose/host state.
    private class SearchPage(
        val route: String, val returnPage: String, val conversationId: String?,
        val active: Message?, val folderId: String?,
    )
    private var searchPage by mutableStateOf<SearchPage?>(null)
    private var historySearchEpoch by mutableLongStateOf(0L)
    private var configurationRestorePreview by mutableStateOf<ChatController.ConfigurationRestorePreview?>(null)
    private var restorePreviewText by mutableStateOf("")
    private var backupImportVisit: Any? = null
    private var backupImportPickerHopPending = false
    private data class BackupImportResult(val visit: Any, val uri: Uri, val outcome: ChatController.BackupImportOutcome)
    private var pendingBackupImportResult: BackupImportResult? = null
    private var backupBusy by mutableStateOf(false)
    private var backupStatus by mutableStateOf<String?>(null)
    private var editingSettings: EditingSettings? = null
    private var modelDraft by mutableStateOf<ModelSettingsDraftUi?>(null)
    private var modelError by mutableStateOf<String?>(null)
    private var editingCatalogIdentity by mutableStateOf<String?>(null)
    private var modelEditingOwner by mutableStateOf<Any?>(null)
    private var modelSaveOwner: ModelSettingsSaveOwner? = null
    private var gatewayOwner by mutableStateOf<ModelSettingsSaveOwner?>(null)
    private var gatewayProviderEditor: ProviderEditorSlot? = null
    private var gatewayEditing: EditingSettings? = null
    private var gatewayDraft by mutableStateOf<app.daiyu.android.model.sessiongateway.SessionGatewayRule?>(null)
    private var gatewayError by mutableStateOf<String?>(null)
    private var gatewayReplacementAuthorized by mutableStateOf(false)
    private var gatewayReloadPending by mutableStateOf(false)
    private var chatModelPicker by mutableStateOf<ChatModelPickerState?>(null)
    private var chatModelProviderEditor: ProviderEditorSlot? = null
    private var chatModelSettings: ModelSettings? = null
    private data class ChatModelProviderChoice(val editor: ProviderEditorSlot, val settings: ModelSettings?,
        val catalog: app.daiyu.android.model.catalog.SavedModelCatalogSnapshot<CatalogModelParameters>?)
    private data class ChatModelCatalogLoad(val activeEditor: ProviderEditorSlot?,
        val choices: Map<String, ChatModelProviderChoice>, val groups: List<ChatModelProviderUi>)
    private var chatModelProviderChoices: Map<String, ChatModelProviderChoice> = emptyMap()
    private var chatModelSession: LocalCatalogChatModelPickerSession? = null
    private var chatModelSelectionFailed = false
    private var chatModelCancelled = AtomicBoolean(false)
    private var chatModelOwner: Any? = null
    private fun allowTaskConfigurationChange(): Boolean {
        if (!controller.taskConfigurationBusy()) return true
        toast("任务占用中")
        return false
    }
    private fun allowTaskConfigurationChange(conversation: ConversationId?): Boolean {
        if (!controller.taskConfigurationBusy(conversation)) return true
        toast("任务占用中")
        return false
    }
    private fun allowAssistantSelection(owner: AssistantSelectionOwner): Boolean =
        if (owner.global) allowTaskConfigurationChange(null)
        else allowTaskConfigurationChange(ConversationId(checkNotNull(owner.conversationId)))
    private var assistantPicker by mutableStateOf(false)
    private var assistantSelectionOwner by mutableStateOf<AssistantSelectionOwner?>(null)
    private fun openAssistantPicker(global: Boolean = false) {
        guard {
            if(!controller.ready) return@guard
            val owner=selected?.id
            assistantSelectionOwner=AssistantSelectionOwner(owner?.value,editorGeneration,
                if(global || owner==null) assistantStore.selectedId.value else controller.database.assistantId(owner), global || owner==null)
            assistantPicker=true
        }
    }
    private fun selectCurrentConversationAssistant(id: String, conversation: ConversationId?, generation: Long) {
        if (!activityUsable() || selected?.id != conversation || editorGeneration != generation) return
        if (!allowTaskConfigurationChange(conversation)) return
        val current = selected
        if (current == null) assistantStore.select(id)
        else {
            val expected = controller.database.assistantId(current.id)
            check(controller.bindConversationAssistant(current.id, expected, id)) { "助手绑定已变化；请重新打开选择器" }
            selected = current.copy(assistantId = id)
        }
        refresh()
    }
    private fun assistantOwnerMatches(owner: AssistantSelectionOwner): Boolean = activityUsable() &&
        owner.accepts(assistantSelectionOwner,selected?.id?.value,editorGeneration,assistantPicker)
    private var assistantEditingId by mutableStateOf<String?>(null)
    private var assistantEditReturnPage = "chat"
    private var assistantEditReturnToSidebar = false
    private fun closeAssistantEditor() {
        assistantEditingId = null
        retainedPage = assistantEditReturnPage
        if (assistantEditReturnToSidebar) openSidebar()
        assistantEditReturnToSidebar = false
    }
    private fun dismissAssistantFlow() {
        val returnPage = if (retainedPage == "assistant-edit") assistantEditReturnPage else retainedPage
        assistantEditingId = null
        assistantPicker = false
        assistantSelectionOwner = null
        assistantEditReturnPage = "chat"
        assistantEditReturnToSidebar = false
        dismissSidebar()
        if (returnPage == "chat") returnToChat() else retainedPage = returnPage
    }
    private var assistantAvatarRevision by mutableStateOf(0L)
    private var collaborationOwner by mutableStateOf<String?>(null)
    private var collaborationVisible by mutableStateOf(false)
    private data class WorkspaceVisit(val token: Any, val conversation: ConversationId?, val generation: Long,
        val editor: app.daiyu.android.workspace.ConversationWorkspaceEditor,
        val source: String, val path: String, val busy: Boolean = false, val error: String? = null,
        val choosing: Boolean = false, val listing: Boolean = false,
        val selectionMode: Boolean = false, val selectedWorkspaces: Set<Int> = emptySet(),
        val editingWorkspaceIndex: Int? = null, val creatingFolder: Boolean = false,
        val directory: app.daiyu.android.workspace.ConversationWorkspaceDirectory? = null,
        val managingEntry: app.daiyu.android.workspace.PrivateWorkspaceFiles.Entry? = null,
        val writeCancellation: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false))
    private var ordinaryDialogLease by mutableStateOf<app.daiyu.android.ui.components.DialogActionLease?>(null)
    private fun dialogActionsCurrent(lease: app.daiyu.android.ui.components.DialogActionLease?) =
        lease != null && lease.isActive && ordinaryDialogLease === lease && voiceForeground && activityUsable()
    private fun suspendOrdinaryDialogActions() {
        ordinaryDialogLease?.revoke()
        ordinaryDialogLease = null
        chatModelCancelled.set(true)
    }
    private fun resumeOrdinaryDialogActions() {
        ordinaryDialogLease?.revoke()
        ordinaryDialogLease = app.daiyu.android.ui.components.DialogActionLease()
        revalidateWorkspacePresentation()
        chatModelOwner?.let { reloadChatModels(it, retainPresentation = true) }
        resumeOutputPresentations()
    }
    private var workspaceVisit by mutableStateOf<WorkspaceVisit?>(null)
    private var workspaceOpenToken: Any? = null
    private fun revokeWorkspaceVisit() { workspaceOpenToken = null; workspaceVisit?.writeCancellation?.set(true); workspaceVisit = null }
    private fun workspacePresented(visit: WorkspaceVisit) = activityUsable() &&
        retainedPage == "chat" && !sidebarVisible && selected?.id == visit.conversation &&
        workspaceVisit?.token === visit.token && editorGeneration == visit.generation
    private fun workspaceCurrent(visit: WorkspaceVisit) = workspacePresented(visit) && voiceForeground
    private fun openConversationWorkspace() {
        if (!activityUsable() || !voiceForeground || retainedPage != "chat" || sidebarVisible ||
            workspaceOpenToken != null || workspaceVisit != null) return
        val owner = selected?.id
        val generation = editorGeneration
        val token = Any()
        workspaceOpenToken = token
        fun current() = workspaceOpenToken === token && activityUsable() &&
            retainedPage == "chat" && !sidebarVisible && selected?.id == owner && editorGeneration == generation
        lifecycleScope.launch {
            try {
                val editor = withContext(Dispatchers.IO) { controller.workspaceEditorOnWorker(owner) }
                if (current()) workspaceVisit = WorkspaceVisit(token, owner, generation, editor, editor.selectedSourceId, editor.path)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { if (current()) toast("工作区信息读取失败") }
            finally { if (workspaceOpenToken === token) workspaceOpenToken = null }
        }
    }
    private fun revalidateWorkspacePresentation() {
        val visit = workspaceVisit ?: return
        if (!workspaceCurrent(visit) || visit.busy || visit.listing) return
        val loading = visit.copy(busy = true, managingEntry = null)
        workspaceVisit = loading
        lifecycleScope.launch {
            try {
                val (editor, directory) = withContext(Dispatchers.IO) {
                    val editor = controller.workspaceEditorOnWorker(visit.conversation)
                    editor to if (visit.choosing && editor.binding == visit.editor.binding)
                        controller.workspaceDirectoryOnWorker(visit.source, visit.path) else null
                }
                if (workspacePresented(loading) && workspaceVisit === loading) {
                    workspaceVisit = if (editor.binding == visit.editor.binding) visit.copy(editor = editor, managingEntry = null,
                        directory = directory ?: visit.directory)
                    else loading.copy(editor = editor, source = editor.selectedSourceId, path = editor.path,
                        busy = false, choosing = false, listing = false, directory = null,
                        selectionMode = false, selectedWorkspaces = emptySet(), editingWorkspaceIndex = null,
                        error = "工作区绑定已变化，请重新选择")
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (workspacePresented(loading) && workspaceVisit === loading) workspaceVisit = visit.copy(
                    directory = visit.directory?.copy(selectable = false), error = "工作区暂不可用，请重新选择")
            }
        }
    }
    private fun browseConversationWorkspace(visit: WorkspaceVisit, source: String = visit.source, path: String = visit.path,
        editingIndex: Int? = visit.editingWorkspaceIndex) {
        if (!workspaceCurrent(visit) || workspaceVisit !== visit || visit.busy || visit.listing) return
        val loading = visit.copy(source = source, path = path, choosing = true, listing = true, error = null,
            editingWorkspaceIndex = editingIndex)
        workspaceVisit = loading
        lifecycleScope.launch {
            try {
                val directory = withContext(Dispatchers.IO) { controller.workspaceDirectoryOnWorker(source, path) }
                if (workspacePresented(loading) && workspaceVisit === loading) {
                    workspaceVisit = loading.copy(source = source, path = directory.path, directory = directory, listing = false)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (workspacePresented(loading) && workspaceVisit === loading) {
                    workspaceVisit = loading.copy(listing = false, directory = loading.directory?.copy(selectable = false),
                        error = "目录读取失败，请重新选择")
                }
            }
        }
    }
    private fun manageConversationWorkspaceEntry(visit: WorkspaceVisit, name: String) {
        if (!workspaceCurrent(visit) || workspaceVisit !== visit || !visit.choosing || visit.busy || visit.listing) return
        val entry = visit.directory?.entries?.singleOrNull { it.name == name } ?: return
        workspaceVisit = visit.copy(managingEntry = entry)
    }
    private fun modifyConversationWorkspaceEntry(visit: WorkspaceVisit, name: String?) {
        val entry = visit.managingEntry ?: return
        if (!workspaceCurrent(visit) || workspaceVisit !== visit || !visit.choosing || visit.busy || visit.listing) return
        val lease = ordinaryDialogLease ?: return
        val changing = visit.copy(busy = true, managingEntry = null, error = null)
        workspaceVisit = changing
        fun cancelled() = !lease.isActive || !workspaceCurrent(changing) || workspaceVisit !== changing
        lifecycleScope.launch {
            try {
                val directory = withContext(Dispatchers.IO) {
                    controller.modifyWorkspaceEntryOnWorker(visit.source, visit.path, entry, name, ::cancelled)
                    controller.workspaceDirectoryOnWorker(visit.source, visit.path)
                }
                if (workspacePresented(changing) && workspaceVisit === changing) workspaceVisit = changing.copy(busy = false, directory = directory)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                val refreshed = try { withContext(Dispatchers.IO) { controller.workspaceDirectoryOnWorker(visit.source, visit.path) } }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                if (workspacePresented(changing) && workspaceVisit === changing) workspaceVisit = changing.copy(busy = false,
                    directory = refreshed ?: visit.directory?.copy(selectable = false),
                    error = if (name == null) "删除未完成；部分内容可能已移除，请检查列表" else "重命名失败；名称可能已存在或条目已变更")
            }
        }
    }
    private fun editConversationWorkspace(visit: WorkspaceVisit, index: Int) {
        if (visit.selectionMode || visit.choosing) return
        val binding = visit.editor.workspaces.bindings.getOrNull(index) ?: return
        val source = visit.editor.sources.firstOrNull { it.id == "private" } ?: return
        val path = (binding as? app.daiyu.android.workspace.ConversationWorkspaceBinding.Private)?.path ?: source.initialPath
        browseConversationWorkspace(visit, source.id, path, editingIndex = index)
    }
    private fun createWorkspaceFolder(visit: WorkspaceVisit, name: String,
        lease: app.daiyu.android.ui.components.DialogActionLease?) {
        if (!dialogActionsCurrent(lease) || !workspaceCurrent(visit) || workspaceVisit !== visit ||
            !visit.choosing || !visit.creatingFolder || visit.busy || visit.listing || visit.directory?.selectable != true) return
        val saving = visit.copy(creatingFolder = false, busy = true, error = null)
        workspaceVisit = saving
        lifecycleScope.launch {
            try {
                val directory = withContext(Dispatchers.IO) {
                    controller.createWorkspaceDirectoryOnWorker(visit.source, visit.directory.path, name) {
                        !lease!!.isActive || visit.writeCancellation.get()
                    }
                    controller.workspaceDirectoryOnWorker(visit.source, visit.directory.path)
                }
                if (workspacePresented(saving) && workspaceVisit === saving)
                    workspaceVisit = saving.copy(busy = false, directory = directory, path = directory.path)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (workspacePresented(saving) && workspaceVisit === saving) workspaceVisit = saving.copy(busy = false,
                    error = when (failure.message) {
                        "workspace_directory_exists" -> "同名文件或文件夹已存在"
                        "workspace_invalid_directory_name" -> "文件夹名称无效"
                        else -> "新建文件夹未完成，请刷新检查目录"
                    })
            }
        }
    }
    private fun copyWorkspacePath(visit: WorkspaceVisit, path: String) {
        if (!workspaceCurrent(visit) || workspaceVisit !== visit || !visit.choosing || visit.busy || visit.listing ||
            path != (visit.directory?.path ?: visit.path)) return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("工作区路径", path))
        toast("已复制路径")
    }
    private fun cancelWorkspaceSelection(visit: WorkspaceVisit) {
        if (workspaceCurrent(visit) && workspaceVisit === visit && !visit.busy) {
            workspaceVisit = visit.copy(source = visit.editor.selectedSourceId, path = visit.editor.path,
                choosing = false, listing = false, directory = null, error = null, editingWorkspaceIndex = null, creatingFolder = false)
        }
    }
    private fun saveConversationWorkspace(visit: WorkspaceVisit) {
        if (!workspaceCurrent(visit) || workspaceVisit !== visit || visit.busy || visit.listing ||
            !visit.choosing || visit.directory?.selectable != true) return
        persistConversationWorkspaces(visit) {
            controller.saveWorkspaceOnWorker(visit.conversation, visit.editor.binding, visit.source, visit.path,
                replaceIndex = visit.editingWorkspaceIndex)
        }
    }
    private fun selectConversationWorkspace(visit: WorkspaceVisit, index: Int) {
        if (visit.choosing || index == visit.editor.workspaces.activeIndex) return
        persistConversationWorkspaces(visit) {
            controller.selectWorkspaceOnWorker(visit.conversation, visit.editor.binding, index)
        }
    }
    private fun removeConversationWorkspaces(visit: WorkspaceVisit, indices: Set<Int>) {
        if (visit.choosing || indices.isEmpty()) return
        persistConversationWorkspaces(visit) {
            controller.removeWorkspacesOnWorker(visit.conversation, visit.editor.binding, indices)
        }
    }
    private fun persistConversationWorkspaces(visit: WorkspaceVisit, change: () -> Boolean) {
        if (!workspaceCurrent(visit) || workspaceVisit !== visit || visit.busy || visit.listing) return
        val lease = ordinaryDialogLease ?: return
        val saving = visit.copy(busy = true, error = null)
        workspaceVisit = saving
        lifecycleScope.launch {
            try {
                val editor = withContext(Dispatchers.IO) {
                    check(lease.isActive) { "workspace_visit_paused" }
                    check(change()) { "workspace_binding_changed" }
                    controller.workspaceEditorOnWorker(visit.conversation)
                }
                if (workspacePresented(saving) && workspaceVisit === saving) {
                    workspaceVisit = saving.copy(editor = editor, source = editor.selectedSourceId, path = editor.path,
                        busy = false, choosing = false, listing = false, directory = null,
                        selectionMode = false, selectedWorkspaces = emptySet(), editingWorkspaceIndex = null)
                    refresh()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val message = when (failure.message) {
                    "workspace_visit_paused" -> "操作已暂停，请返回前台后重新选择"
                    "workspace_binding_changed" -> "会话正在运行或绑定已变化，请稍后重新打开"
                    "workspace_duplicate" -> "这个目录已在工作区列表中，请直接切换到该工作区"
                    "workspace_collection_too_large" -> "已保存的工作区路径过多，请先移除不需要的工作区"
                    "workspace_rootfs_changed" -> "Rootfs 已更换或不可用，请重新选择"
                    "workspace_private_root_required", "workspace_selection_root_required" -> "只能选择工作区及其子目录"
                    "workspace_symlink_not_supported" -> "工作区不支持符号链接，请选择实际目录"
                    else -> "工作区路径不可用，请选择已存在的目录"
                }
                if (workspacePresented(saving) && workspaceVisit === saving) workspaceVisit = visit.copy(error = message)
            }
        }
    }

    private var childStatusVisit by mutableStateOf<Pair<Any, String?>?>(null)
    private data class UsageVisit(val token: Any, val conversation: ConversationId?)
    private var usageVisit by mutableStateOf<UsageVisit?>(null)
    private var automaticCompression by mutableStateOf<Boolean?>(null)
    private var automaticCompressionPending by mutableStateOf(false)
    private var confirmedModelLabel by mutableStateOf("模型")
    private var confirmedReasoningModel by mutableStateOf("")
    private var confirmedGptSpeed: ChatGptSpeedSelection? = null
    private var gptSpeedSaveOwner: ModelSettingsSaveOwner? = null
    private var contextModelRevision by mutableStateOf(0L)
    private var contextReceiptRevision by mutableStateOf(0L)
    private var conversationUsageRevision by mutableStateOf(0L)
    private var modelLabelRead: Any? = null
    private val voiceEntryPreferences by lazy { getSharedPreferences("chat-voice-entry", MODE_PRIVATE) }
    private var voiceEntryMode by mutableStateOf(ChatMenuAction.VOICE_INPUT)
    private var thinkingPicker by mutableStateOf(false)
    private val appearanceStore by lazy { AppearanceSettingsStore(this) }
    private var appearance by mutableStateOf(AppearanceSettings())
    // There are no application-owned notifications to refresh in the current host.
    private val iconNotifications = IconNotificationPort { }

    private fun reportAutomaticIconSync(result: Result<IconSyncResult>) {
        val failure = app.daiyu.android.appearance.iconSyncFailureMessage(result)
        if (failure != null && activityUsable()) toast("图标同步未完成：$failure")
    }

    private fun activityUsable() = activityCallbacksAllowed && !isFinishing && !isDestroyed
    private fun requireActivityUsable() { check(activityUsable()) { "页面已关闭；未执行操作" } }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        restoredSupplementEditor = state?.getString("supplement-editor-conversation")?.let { conversation ->
            SupplementEditor(conversation, state.getLong("supplement-editor-generation"),
                state.getLong("supplement-editor-revision"), state.getString("supplement-editor-text") ?: "")
        }
        enableEdgeToEdge()
        voiceEntryMode = when (voiceEntryPreferences.getString("mode", null)) {
            ChatMenuAction.VOICE_CHAT.name -> ChatMenuAction.VOICE_CHAT
            ChatMenuAction.VOICE_DOUBAO.name -> ChatMenuAction.VOICE_DOUBAO
            else -> ChatMenuAction.VOICE_INPUT
        }
        refreshAutomaticCompression()
        controller.providerBalances.start(lifecycleScope, controller.providers::providerIdsFlow,
            { controller.providers.snapshot().providers.map { it.slotId } })
        refreshConfirmedModelLabel()
        nextBrowserSaveRequestCode = state?.getInt("browser-save-next", 14000) ?: 14000
        if (state?.getBoolean("balance-page", false) == true) retainedPageState = "balance-settings"
        // An Activity mount never inherits an approval capability from an older mount.
        controller.virtualDisplayEntry()?.let(controller::cancelVirtualDisplay)
        controller.guiEntry()?.let(controller::cancelGui)
        app.daiyu.android.voice.offline.OfflineSpeechPack.initialize(applicationContext)
        val voiceController = controller
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { voiceController.voiceAccounts.voices.load() } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* Personal account voices fail closed; settings reports local recovery. */ }
        }
        lifecycleScope.launch {
            try { voiceController.voiceRuntime.load() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* Settings page offers explicit repair. */ }
        }
        linuxSettingsReturnPage = state?.getString("linux-settings-return-page")
            ?.takeIf { it in setOf("settings", "chat", "terminal") } ?: "settings"
        if (state?.getBoolean("terminal-page", false) == true) {
            terminalPageOwner = Any()
            terminalReturnPage = state.getString("terminal-return-page")
                ?.takeIf { it in setOf("settings", "chat", "linux-settings", "chat-workspace") } ?: "settings"
            retainedPageState = "terminal"
        }
        hapticsHost = app.daiyu.android.haptics.HapticsActivityHost(
            (application as ChatApplication).hapticsRuntime, window.decorView, this, controller)
        interactiveModeObservation = controller.interactiveMode.observe { interactiveModeEnabled = it }
        appearance = loadAppearance()
        activityCallbacksAllowed = true
        pendingBackupRequest = state?.getInt("pending-backup-request", 0)?.takeIf {
            it != 0 && it != 101 && it != 103 && it != 107 && it !in 12000..65534
        }
        conversationExport = ConversationExportSelection(
            (state?.getStringArrayList("pending-conversation-exports")
                ?: state?.getString("pending-conversation-export")?.let(::listOf))
                .takeIf { pendingBackupRequest == 105 })
        exportPickerPending = pendingBackupRequest == 105
        taskRecordsConversationId = state?.getString("task-records-conversation")
        if (taskRecordsConversationId != null) retainedPage = "child-tasks"
        if (intent?.action == ChatApplication.OPEN_PET_SETTINGS) retainedPage = "pet-settings"
        app.daiyu.android.diagnostics.WindowFramePerformance.install(this, controller.diagnostics)
        setContent {
            app.daiyu.android.ui.screens.settings.ApplicationPetMount(pets, this@MainActivity, Modifier.fillMaxSize()) {
            AgentAppTheme(appearance, applyInterfaceScale = true, onResolvedDarkModeChange = ::updateSystemBars) {
                val iconCoordinator = rememberIconAppearanceCoordinator(iconNotifications, ::reportAutomaticIconSync)
                val focus = LocalFocusManager.current
                val keyboard = LocalSoftwareKeyboardController.current
                val view = LocalView.current
                val balanceState = balanceRuntime?.state?.collectAsStateWithLifecycle()?.value
                if (retainedPage == "chat") app.daiyu.android.diagnostics.ObserveFrameContext(
                    "chat-session", if (chatState.conversationKey == null) "new" else "selected", chatState.conversationKey)
                val chatPageState = rememberSaveableStateHolder()
                val settingsPageState = rememberSaveableStateHolder()
                val settingsScrollBehavior = top.yukonga.miuix.kmp.basic.MiuixScrollBehavior()
                val assistantProfiles by assistantStore.assistants.collectAsState()
                val activeAssistant by assistantStore.selectedId.collectAsState()
                val renderedAssistantConversation = selected?.id
                val renderedAssistantGeneration = editorGeneration
                var projectedAssistants by remember { mutableStateOf<List<AssistantDisplay>>(emptyList()) }
                LaunchedEffect(assistantProfiles, assistantAvatarRevision) {
                    val profiles = assistantProfiles
                    val revision = assistantAvatarRevision
                    val assets = assistantAssets
                    val projected = withContext(Dispatchers.IO) { profiles.toAssistantDisplays(assets) }
                    if (activityUsable() && assistantAvatarRevision == revision &&
                        assistantStore.assistants.value == profiles) projectedAssistants = projected
                }
                LaunchedEffect(assistantProfiles, activeAssistant) { if (activityUsable()) publishChat() }
                LaunchedEffect(retainedPage == "subagents" || collaborationVisible, subAgentSourceRevision, backupBusy) {
                    if ((retainedPage == "subagents" || collaborationVisible) && !backupBusy &&
                        lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                        subAgentModelSource.refreshAll(controller::savedSubAgentProviders)
                    } else subAgentModelSource.clear()
                }
                LaunchedEffect(sidebarVisible, retainedPage) {
                    if (sidebarVisible || retainedPage != "chat") focus.clearFocus()
                }
                if (retainedPage == "chat" && !sidebarVisible) {
                    DisposableEffect(videoHost, this@MainActivity) {
                        val binding = videoHost.bindLifecycle(this@MainActivity)
                        onDispose { binding.close() }
                    }
                }
                androidx.activity.compose.BackHandler(enabled = retainedPage == "chat" && !sidebarVisible) {
                    handleRootBack { hideChatInputIme(focus, keyboard, view) }
                }
                app.daiyu.android.ui.navigation.PageBackGestureHost(
                    routeKey = if (retainedPage == "chat" && sidebarVisible) "chat-sidebar" else retainedPage,
                    contentKey = when (retainedPage) {
                        "assistant-edit" -> assistantEditReturnPage
                        "context-management" -> "chat"
                        "history-search" -> "chat"
                        "browser" -> "chat"
                        else -> retainedPage
                    },
                    captureFromContent = retainedPage == "chat" && !sidebarVisible,
                    animateBackEnabled = retainedPage != "assistant-edit" && retainedPage != "history-search" &&
                        retainedPage != "context-management" && retainedPage != "browser",
                    canNavigateBack = retainedPage != "chat",
                    swipeDismissEnabled = appearance.swipeDismissEnabled,
                    predictiveBackEnabled = appearance.predictiveBackEnabled,
                    dispatchBack = { onBackPressedDispatcher.onBackPressed() },
                    backTargetKey = pageBackPreviewTarget(),
                    previewScopeKey = selected?.id,
                ) {
                BackHandler(enabled = retainedPage != "chat") {
                    when (retainedPage) {
                        "backup", "subagents", "appearance", "haptics", "voice-settings", "browser-settings", "mcp-settings", "gui-settings", "compression-settings", "reconnect-settings", "pet-settings", "vision-settings", "session-gateway", "balance-settings", "title-model-settings", "platform-settings" -> retainedPage = "settings"
                        "linux-settings", "chat-workspace" -> closeLinuxSettingsPage()
                        "generation-settings" -> retainedPage = "settings"
                        "legacy-library" -> retainedPage = "backup"
                        "terminal" -> closeTerminalPage()
                        "usage" -> closeUsagePage()
                        "providers" -> closeProvidersPage()
                        "skills" -> closeSkillsPage()
                        "offline-speech" -> retainedPage = offlineSpeechReturnPage
                        "pet-model-settings" -> retainedPage = "pet-settings"
                        "assistant-edit" -> closeAssistantEditor()
                        "conversation-search", "message-search", "history-search" -> searchPage?.let(::closeSearch)
                        else -> returnToChat()
                    }
                }
                when (if (retainedPage == "assistant-edit") assistantEditReturnPage else retainedPage) {
                    "balance-settings" -> {
                        val runtime = balanceRuntime
                        if (runtime != null) app.daiyu.android.balance.ui.BalanceSettingsScreen(
                            runtime = runtime, onBack = { retainedPage = "settings" },
                            modifier = Modifier.fillMaxSize())
                        else Column(Modifier.fillMaxSize().padding(16.dp)) {
                            app.daiyu.android.ui.haptics.miuix.TextButton(text = "返回", onClick = { retainedPage = "settings" })
                            top.yukonga.miuix.kmp.basic.Text("请先选择提供商，再配置其独立余额")
                        }
                    }
                    "pet-settings" -> app.daiyu.android.ui.screens.settings.PetSettingsScreen(
                        host = pets.host, modifier = Modifier.fillMaxSize(),
                        onBack = { retainedPage = "settings" },
                        models = controller.independentModels,
                        source = remember(pets.host) { app.daiyu.android.ui.screens.settings.SettingsModelSource(applicationContext, controller.providers) },
                        assistants = assistantProfiles, pets = pets,
                        onOverlayAllowedChange = { pets.setOverlayAllowed(it) },
                        onOpenOverlayPermission = {
                            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")))
                        },
                    )
                    "pet-model-settings", "vision-settings" -> key(retainedPage) {
                        val petModel = retainedPage == "pet-model-settings"
                        app.daiyu.android.ui.screens.settings.IndependentModelScreen(controller.independentModels,
                            source = remember(retainedPage) { app.daiyu.android.ui.screens.settings.SettingsModelSource(applicationContext, controller.providers) },
                            slot = if (petModel) app.daiyu.android.storage.IndependentModelSettings.Slot.PET
                            else app.daiyu.android.storage.IndependentModelSettings.Slot.VISION,
                            assistants = assistantProfiles, pets = pets,
                            onBack = { retainedPage = if (petModel) "pet-settings" else "settings" },
                            modifier = Modifier.fillMaxSize())
                    }
                    "manage" -> ManageChatsScreen(
                        conversations = paneState.historyConversations,
                        onBack = { returnToChat() },
                        onOpenConversation = { id -> guard { openRetainedConversation(id) } },
                        onTogglePin = { id -> guard { toggleRetainedPin(id) } },
                        onDeleteConversation = { item -> guard { deleteRetainedConversation(item.id) } },
                        onDeleteAll = { guard {
                            check(controller.active == null) { "请先停止当前运行" }
                            controller.mutateGenerationOwners(completed = { success ->
                                if (activityUsable() && success) { select(null); updatePane() }
                            }, mutation = { retainedData.deleteAll() })
                        } },
                        onOpenMessageSearch = { guard { openSearch("message-search") } },
                        onOpenConversationSearch = { guard { searchConversations() } },
                    )
                    "conversation-search", "message-search" -> searchPage?.let { page ->
                        key(page) {
                            if (page.route == "conversation-search") ConversationSearchScreen(
                                loadConversations = { retainedData.summaries(page.active) },
                                folders = paneState.folders, initialFolderId = page.folderId,
                                onOpenConversation = { id ->
                                    if (searchPage === page && retainedPage == page.route) guard { openRetainedConversation(id) }
                                },
                                onBack = { closeSearch(page) },
                            ) else MessageSearchScreen(
                                onSearchPage = { request, cursor, signal ->
                                    check(request.conversationId == null || request.conversationId == page.conversationId) {
                                        "搜索范围已改变"
                                    }
                                    retainedData.searchPage(request.query, request.conversationId, cursor,
                                        cancellationSignal = signal)
                                },
                                currentConversationId = page.conversationId,
                                supportsCurrentConversation = true,
                                onOpenHit = { hit ->
                                    if (searchPage === page && retainedPage == page.route) guard { openHistoryHit(hit) }
                                },
                                onBack = { closeSearch(page) },
                            )
                        }
                    }
                    "usage" -> UsageStatsScreen(onBack = { closeUsagePage() }, load = { retainedData.stats() })
                    "child-tasks" -> taskRecordsConversationId?.let { id ->
                        ChildTaskRecordsScreen(id, load = { target -> controller.childTasks(ConversationId(target)) },
                            loadLive = { target -> controller.liveChildTasks(ConversationId(target)) },
                            onPause = { record -> controller.pauseChildTask(record) },
                            onResume = { record -> controller.resumeChildTask(record) },
                            onCommand = { command -> controller.directChildTask(command) },
                            onStop = { record -> controller.stopChildTask(record) },
                            onBack = { returnToChat() })
                    }
                    "permission-check" -> app.daiyu.android.ui.screens.settings.PermissionCheckScreen(
                        onBack = { returnToChat() },
                    )
                    "shizuku-settings" -> ShizukuScreen(
                        authorization = (application as ChatApplication).shizuku, session = null,
                        onBack = { returnToChat() },
                        onReady = { _, commands -> commands.close() }, // No run is owned by this permission page.
                    )
                    "terminal" -> terminalPageOwner?.let { page ->
                        val state by terminalProvider.state.collectAsStateWithLifecycle()
                        val ready = state as? app.daiyu.android.terminal.TerminalApplicationState.Ready
                        if (ready == null) TerminalLoadingPage(state, onBack = {
                            if (activityUsable() && retainedPage == "terminal" && terminalPageOwner === page)
                                closeTerminalPage()
                        }, compactTitle = true) else {
                            val workspace = ready.application.terminalSessions
                            app.daiyu.android.ui.screens.terminal.TerminalScreen(
                                workspace = workspace,
                                isCurrent = { terminalPageCurrent(page) },
                                onOpenTasks = { openTerminalTasks(page) },
                                onCreateSession = { createTerminalSession(page) },
                                onSend = { id, text -> if (terminalPageCurrent(page)) {
                                    if (id == null) createTerminalSession(page, text) else workspace.send(id, text, selected?.id)
                                } },
                                onOpenEnvironment = { if (terminalPageCurrent(page)) openLinuxSettings() },
                                onBack = {
                                    if (activityUsable() && retainedPage == "terminal" && terminalPageOwner === page)
                                        closeTerminalPage()
                                },
                            )
                        }
                    }
                    "linux-settings", "chat-workspace" -> {
                        val route = retainedPage
                        val state by terminalProvider.state.collectAsStateWithLifecycle()
                        val ready = state as? app.daiyu.android.terminal.TerminalApplicationState.Ready
                        val page = linuxSettingsPageOwner
                        val visit = linuxSettingsVisit
                        val leave: () -> Unit = {
                            if (activityUsable() && retainedPage == route && linuxSettingsVisit === visit && linuxSettingsPageOwner === page) {
                                closeLinuxSettingsPage()
                            }
                        }
                        if (ready == null || page == null) TerminalLoadingPage(state, onBack = leave,
                            title = if (route == "chat-workspace") "工作区" else "Linux 工具环境", compactTitle = true, settingsStyle = true)
                        else key(page) {
                            LinuxSettingsScreen(ready.application.settings, page, host = ready.application.terminalOwner.host,
                                filesOnly = route == "chat-workspace", createRootfsDirectory = { installation, directory, name, cancelled ->
                                    ready.application.createRootfsDirectoryOnWorker(filesDir, installation, directory, name) {
                                        page.state.value.revoked || cancelled()
                                    }
                                }, onOpenTerminal = {
                                    if (activityUsable() && retainedPage == route && linuxSettingsPageOwner === page)
                                        openTerminal()
                                }, onBack = leave)
                        }
                    }
                    "generation-settings" -> generationSettingsVisit?.let { visit -> key(visit) {
                        app.daiyu.android.ui.GenerationSettingsHost(
                            editor = visit.editor,
                            ownerCurrent = { visit.active.get() },
                            onBack = {
                                if (activityUsable() && generationSettingsVisit === visit && retainedPage == "generation-settings")
                                    retainedPage = "settings"
                            },
                        )
                    } }
                    "title-model-settings" -> titleSettingsPageOwner?.let { page -> key(page) {
                        app.daiyu.android.title.TitleModelSettingsScreen(page, source = remember(page) {
                            app.daiyu.android.ui.screens.settings.SettingsModelSource(applicationContext, controller.providers)
                        }, ownerCurrent = {
                            activityUsable() && titleSettingsPageOwner === page && retainedPage == "title-model-settings"
                        }, onBack = { retainedPage = "settings" })
                    } }
                    "compression-settings" -> compressionSettingsPageOwner?.let { page -> key(page) {
                        CompressionSettingsScreen(page, source = remember(page) {
                            app.daiyu.android.ui.screens.settings.SettingsModelSource(applicationContext, controller.providers)
                        }, onBack = {
                            if (activityUsable() && compressionSettingsPageOwner === page && retainedPage == "compression-settings")
                                retainedPage = "settings"
                        }, ownerCurrent = {
                            activityUsable() && compressionSettingsPageOwner === page && retainedPage == "compression-settings"
                        })
                    } }
                    "reconnect-settings" -> reconnectSettingsPageOwner?.let { page -> key(page) {
                        ReconnectSettingsScreen(controller.reconnectSettings,
                            onBack = {
                                if (activityUsable() && reconnectSettingsPageOwner === page && retainedPage == "reconnect-settings")
                                    retainedPage = "settings"
                            }, ownerCurrent = {
                                activityUsable() && reconnectSettingsPageOwner === page && retainedPage == "reconnect-settings"
                            }, saveOwner = page)
                    } }
                    "mcp-settings" -> mcpSettingsPageOwner?.let { page -> key(page) {
                        app.daiyu.android.ui.screens.settings.McpSettingsScreen(mcpSettings, page,
                            ownerCurrent = { activityUsable() && mcpSettingsPageOwner === page && retainedPage == "mcp-settings" },
                            onBack = { if (mcpSettingsPageOwner === page) retainedPage = "settings" })
                    } }
                    "browser-settings" -> browserSettingsPageOwner?.let { page -> key(page) {
                        BrowserSettingsScreen(browserSettings,
                            onBack = {
                                if (activityUsable() && browserSettingsPageOwner === page && retainedPage == "browser-settings")
                                    retainedPage = "settings"
                            },
                            ownerCurrent = {
                                activityUsable() && browserSettingsPageOwner === page && retainedPage == "browser-settings"
                            }, saveOwner = page)
                    } }
                    "gui-settings" -> app.daiyu.android.ui.screens.settings.GuiSettingsPanel(
                        onBack = { if (activityUsable() && retainedPage == "gui-settings") retainedPage = "settings" },
                        session = null,
                        ownerCurrent = { activityUsable() && retainedPage == "gui-settings" },
                    )
                    "skills" -> SkillManagementScreen(
                        engineeringUi = engineeringUi,
                        managementFactory = { controller.skillManagement() },
                        onBack = { closeSkillsPage() },
                    )
                    "voice-settings" -> VoiceSettingsPanel(voiceRuntime, remoteVoice, controller.voiceAccounts,
                        modelSource = remember { app.daiyu.android.ui.screens.settings.SettingsModelSource(applicationContext, controller.providers) },
                        installPreviewRevoke = { revoke ->
                            accountPreviewRevoke = revoke
                            val unregister: () -> Unit = { if (accountPreviewRevoke === revoke) accountPreviewRevoke = null }
                            unregister
                        },
                        isCurrent = { activityUsable() && voiceForeground && retainedPage == "voice-settings" },
                        invalidate = { invalidateConfiguredVoice(); speechHost.stop(speechHost.owner); dictationHost.invalidate() },
                        onBack = { if (activityUsable()) retainedPage = "settings" })
                    "session-gateway" -> {
                        val owner = gatewayOwner
                        val value = gatewayDraft
                        if (owner != null && value != null) {
                            SessionGatewaySettingsScreen(
                                draft = value,
                                onDraftChange = { if (gatewayCurrent(owner) && !controller.dataBusy) gatewayDraft = it },
                                onSave = { saveSessionGateway(owner, it) },
                                onBack = { if (gatewayCurrent(owner)) retainedPage = "settings" },
                                errorText = gatewayError,
                                enabled = !gatewayReloadPending && gatewayEditing != null && controller.ready && controller.active == null && !controller.dataBusy,
                                requestOptionsUnavailable = gatewayEditing?.requestOptionsUnavailable == true,
                                replacementAuthorized = gatewayReplacementAuthorized,
                                onReplacementAuthorization = {
                                    if (gatewayCurrent(owner) && !controller.dataBusy) gatewayReplacementAuthorized = it
                                },
                            )
                        } else Column {
                            app.daiyu.android.ui.haptics.miuix.TextButton(text = "返回", onClick = { retainedPage = "settings" })
                            top.yukonga.miuix.kmp.basic.Text(gatewayError ?: "正在加载会话网关设置")
                        }
                    }
                    "platform-settings" -> platformSettingsPageOwner?.let { owner -> key(owner) {
                        PlatformToolSettingsScreen(applicationContext, controller.platformSettings, platformSettingsRevision,
                            grants = { platformSaf.grants() }, ownerCurrent = { platformPageCurrent(owner) },
                            onBack = { if (platformPageCurrent(owner)) retainedPage = "settings" },
                            onPermission = ::requestPlatformPermission, onPick = ::pickPlatformSaf,
                            onCopy = { uri ->
                                if (platformPageCurrent(owner)) {
                                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("SAF URI", uri.toString()))
                                    toast("仅复制精确URI；尚未发送给模型")
                                }
                            }, onRevoke = { uri ->
                                if (platformPageCurrent(owner)) { platformSaf.revoke(uri); platformSettingsRevision++ }
                            })
                    } }
                    "settings" -> settingsPageState.SaveableStateProvider("settings-home") {
                        Column(Modifier.fillMaxSize()) {
                        if (engineeringUi) app.daiyu.android.ui.haptics.miuix.TextButton(text = "保留会话网关",
                            modifier = Modifier.testTag("open-session-gateway"), onClick = { guard { openSessionGateway() } })
                        Box(Modifier.weight(1f)) { DaiyuSettingsScreen(
                        scrollBehavior = settingsScrollBehavior,
                        diagnostics = controller.diagnostics,
                        engineeringUi = engineeringUi,
                        onBack = { returnToChat() }, onRoute = { route -> openSettingsFromPage(route) },
                        onOpenSubAgents = { retainedPage = "subagents" },
                        onOpenAssistants = { openAssistantPicker(global = true) },
                        onOpenAppearance = { retainedPage = "appearance" },
                        onOpenHaptics = {
                            if (activityUsable() && retainedPage == "settings") retainedPage = "haptics"
                        },
                        onOpenVoice = {
                            if (activityUsable() && retainedPage == "settings") retainedPage = "voice-settings"
                        },
                        onOpenPlatformTools = {
                            if (activityUsable() && retainedPage == "settings") retainedPage = "platform-settings"
                        },
                    ) } } }
                    "offline-speech" -> app.daiyu.android.ui.screens.settings.OfflineSpeechSettingsScreen(
                        applicationContext, onBack = { if (activityUsable() && retainedPage == "offline-speech") retainedPage = offlineSpeechReturnPage },
                        ownerCurrent = { activityUsable() && retainedPage == "offline-speech" })
                    "haptics" -> hapticsHost?.let { host ->
                        host.settingsOwner?.let { owner -> key(owner) {
                            HapticsSettingsScreen(host.runtime, owner.feedback,
                                onBack = {
                                    if (host.isCurrentSettings(owner) && retainedPage == "haptics") {
                                        retainedPage = "settings"
                                    }
                                },
                                ownerCurrent = { host.isCurrentSettings(owner) && retainedPage == "haptics" })
                        } }
                    }
                    "providers" -> ProviderManagementRoute(controller,
                        conversationId = selected?.id,
                        ownerCurrent = { activityUsable() && voiceForeground && retainedPage == "providers" && !sidebarVisible },
                        onBack = { closeProvidersPage() },
                        onDetailBack = { if (activityUsable() && retainedPage == "providers") returnToChat() },
                        engineeringUi = engineeringUi)
                    "models" -> modelDraft?.let { value ->
                        val connectionTest: app.daiyu.android.ui.screens.settings.ModelConnectionTester? = modelEditingOwner?.let { owner ->
                            { draft, cancelled, deliver -> requestModelConnectionTest(owner, draft, cancelled, deliver) }
                        }
                        val identity = editingCatalogIdentity
                        val localModelOwner = modelEditingOwner
                        val localModelEditing = editingSettings
                        val localModelCurrent = { activityUsable() && retainedPage == "models" && !sidebarVisible &&
                            localModelOwner != null && modelEditingOwner === localModelOwner && editingSettings === localModelEditing }
                        if (identity == null) ModelSettingsScreen(
                            engineeringUi = engineeringUi,
                            testConnection = connectionTest,
                            draft = value, onDraftChange = { changeModelDraft(it) },
                            onSave = { saveModelSettings(it) }, onFetchCatalog = {},
                            onBack = { returnToChat() }, errorText = modelError,
                            credentialUnavailable = editingSettings?.credentialUnavailable == true,
                            requestOptionsUnavailable = editingSettings?.requestOptionsUnavailable == true,
                            catalogEnabled = false, enabled = !controller.dataBusy,
                        ) else key(modelEditingOwner) { ProviderCatalogSettingsScreen(
                            engineeringUi = engineeringUi,
                            testConnection = connectionTest,
                            draft = value, onDraftChange = { changeModelDraft(it) },
                            onSave = { saveModelSettings(it) }, onBack = { returnToChat() },
                            providerIdentity = identity, persistence = controller.settings.modelCatalog,
                            localModelActions = app.daiyu.android.ui.screens.settings.LocalCatalogModelActions(
                                currentModelId = editingSettings?.settings?.model.orEmpty(),
                                rename = { ticket, name, cancelled, completed ->
                                    if (localModelCurrent() && !cancelled())
                                        controller.renameConfiguredCatalogModel(ticket, name, cancelled) { result ->
                                            if (localModelCurrent() && !cancelled()) completed(result)
                                        }
                                    else completed(app.daiyu.android.model.catalog.CatalogLocalEditResult.CANCELLED)
                                },
                                delete = { ticket, cancelled, completed ->
                                    if (localModelCurrent() && !cancelled())
                                        controller.deleteConfiguredCatalogModel(ticket, cancelled) { result ->
                                            if (localModelCurrent() && !cancelled()) completed(result)
                                        }
                                    else completed(app.daiyu.android.model.catalog.CatalogLocalEditResult.CANCELLED)
                                },
                                deleteSelected = { tickets, cancelled, completed ->
                                    if (localModelCurrent() && !cancelled())
                                        controller.deleteConfiguredCatalogModels(tickets, cancelled, completed)
                                    else completed(app.daiyu.android.model.catalog.CatalogLocalEditResult.CANCELLED)
                                },
                            ),
                            createModel = { CatalogModelParameters.newModel(it) },
                            fetchCatalog = ::requestSettingsCatalog,
                            isRemoteModel = { it.settings.source == app.daiyu.android.model.catalog.CatalogModelSource.REMOTE },
                            catalogConnectionSaved = editingSettings?.settings?.let { saved ->
                                runCatching { value.toSettings(saved) == saved }.getOrDefault(false)
                            } ?: false,
                            enabled = controller.ready && controller.active == null && !controller.dataBusy,
                            modelDeletionEnabled = controller.ready && !controller.dataBusy,
                            errorText = modelError,
                            credentialUnavailable = editingSettings?.credentialUnavailable == true,
                            requestOptionsUnavailable = editingSettings?.requestOptionsUnavailable == true,
                        ) }
                    }
                    "backup" -> {
                        val exportOwner = backupExportPageOwner
                        BackupScreen(
                        engineeringUi = engineeringUi,
                        onImport = { backupGuard { pickBackupAuto() } },
                        onExport = { includeLinux -> if (backupExportPageCurrent(exportOwner)) backupGuard {
                            pickBackup(true, true, includeLinux)
                        } },
                        onBack = { if (activityUsable() && retainedPage == "backup") retainedPage = "settings" },
                        onExportCompact = { if (backupExportPageCurrent(exportOwner)) backupGuard { pickBackup(true, false) } },
                        onExportComplete = { if (backupExportPageCurrent(exportOwner)) backupGuard { pickBackup(true, true) } },
                        onImportCompact = { backupGuard { pickBackup(false, false) } },
                        onImportComplete = { backupGuard { pickBackup(false, true) } },
                        restorePreviewText = restorePreviewText,
                        onPreviewRestore = { backupGuard { previewConfigurationRestore() } },
                        onRestoreConfiguration = { confirmConfigurationRestore() },
                        canRestoreConfiguration = configurationRestorePreview?.plan?.isNoOp == false,
                        busy = backupBusy,
                        statusText = backupStatus,
                    )
                    }
                    "appearance" -> AppearanceSettingsScreen(
                        state = appearance,
                        onChange = { saveAppearance(it) },
                        onBack = { retainedPage = "settings" },
                        iconContent = { expanded ->
                            IconAppearanceEditor(iconCoordinator, expanded,
                                ownerCurrent = { activityUsable() && retainedPage == "appearance" })
                        },
                    )
                    "subagents" -> SubAgentSettingsScreen(onBack = { if (activityUsable() && retainedPage == "subagents") retainedPage = "settings" },
                        repositoryFactory = { subAgentRepository }, isCurrentRoute = retainedPage == "subagents",
                        modelSource = subAgentModelSource)
                    else -> ConversationSidePaneScaffold(
                        state = paneState, visible = sidebarVisible, backHandlerEnabled = true,
                        assistants = projectedAssistants, selectedAssistantId = if (selected == null) activeAssistant else selected?.assistantId.orEmpty(),
                        onOpen = { openSidebar() }, onDismiss = { dismissSidebar() },
                        onSearchChange = { paneState = paneState.copy(searchQuery = it) },
                        onConversationSelected = { id -> selectPaneConversation(id) },
                        onConversationRename = { item -> editRetainedConversation(item, false) },
                        onConversationDelete = { item -> confirmDelete(item.id) },
                        onMoveConversationToFolder = { item -> editRetainedConversation(item, true) },
                        onConversationTogglePin = { item -> guard { toggleRetainedPin(item.id) } },
                        onConversationExport = { item -> backupGuard { pickConversationExports(listOf(item.id)) } },
                        onConversationsExport = { ids -> backupGuard { pickConversationExports(ids) } },
                        exportEnabled = controller.ready && controller.active == null && !controller.hasLiveChildTasks() &&
                            controller.problem?.startsWith("会话恢复失败") != true && !backupBusy && !exportPickerPending && pendingBackupRequest == null,
                        onNewConversation = { guard { createNewConversation(); dismissSidebar() } },
                        onSelectFolder = { id -> paneState = paneState.copy(selectedFolderId = id); updatePane() },
                        onCreateFolder = { name -> guard { retainedData.createFolder(name); updatePane() } },
                        onRenameFolder = { old, name -> guard {
                            retainedData.changeFolder(old, name)
                            if (paneState.selectedFolderId == old) paneState = paneState.copy(selectedFolderId = name)
                            updatePane()
                        } },
                        onDeleteFolder = { name -> guard {
                            retainedData.changeFolder(name, null)
                            if (paneState.selectedFolderId == name) paneState = paneState.copy(selectedFolderId = null)
                            updatePane()
                        } },
                        onSelectAssistant = { id -> guard {
                            if (sidebarVisible) selectCurrentConversationAssistant(id, renderedAssistantConversation, renderedAssistantGeneration)
                        } },
                        onEditAssistant = { id -> guard {
                            if (!allowTaskConfigurationChange()) return@guard
                            checkNotNull(assistantStore.read(id)) { "助手已不存在" }
                            assistantEditReturnPage = retainedPage
                            assistantEditReturnToSidebar = true
                            dismissSidebar(); assistantEditingId = id; retainedPage = "assistant-edit"
                        } },
                        onOpenAssistants = { dismissSidebar(); openAssistantPicker(global = true) },
                        onOpenSettings = { dismissSidebar(); retainedPage = "settings" },
                        onOpenModelProviders = { dismissSidebar(); guard { modelSettings() } },
                        onOpenUsageStats = { dismissSidebar(); openUsagePage("chat") },
                        onOpenSkills = { dismissSidebar(); guard { openSkillManagement() } },
                        onOpenPermissions = { dismissSidebar(); retainedPage = "permission-check" },
                    ) {
                        chatPageState.SaveableStateProvider(chatState.conversationKey ?: "new-chat") {
                            val messageSpeech by speechHost.state.collectAsStateWithLifecycle()
                            val speechMessageId = messageSpeech.requestId.takeIf {
                                messageSpeech.owner == speechHost.owner && voiceHostIsActive(messageSpeech)
                            }
                            LaunchedEffect(messageSpeechAttempt, messageSpeech) {
                                val event = messageSpeechAttempt to messageSpeech
                                if (messageSpeech == speechHost.state.value && messageSpeech.owner == speechHost.owner &&
                                    messageSpeech.phase in setOf(SpeechPhase.UNAVAILABLE, SpeechPhase.ERROR) &&
                                    reportedMessageSpeechError != event) {
                                    reportedMessageSpeechError = event
                                    voiceHostStatusResource(messageSpeech)?.let { voiceConfigurationToast(getString(it)) }
                                }
                            }
                            val contextUsage = app.daiyu.android.ui.screens.chat.conversation.rememberChatContextUsage(
                                controller, selected?.id, contextReceiptRevision, controller.dataRevision,
                                contextModelRevision, chatState.composer.phase, ready = controller.ready)
                            val usageConfiguration = androidx.compose.ui.platform.LocalConfiguration.current
                            val usageLocale = remember(usageConfiguration) { java.util.Locale.getDefault() }
                            val contextUsageLabels = app.daiyu.android.ui.screens.chat.conversation.rememberChatContextUsageLabels(contextUsage, usageLocale)
                            BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
                                val extrasMaxHeight = minOf(200.dp, maxHeight * 0.35f)
                                Column(Modifier.fillMaxSize()) {
                                    DaiyuChatScreen(chatState.copy(interactiveModeEnabled = interactiveModeEnabled, balanceState = balanceState,
                                        automaticCompression = automaticCompression, automaticCompressionPending = automaticCompressionPending,
                                        composer = projectedVoiceComposer().copy(voiceMode = voiceEntryMode, modelLabel = confirmedModelLabel,
                                            contextFraction = contextUsage.fraction, contextUsageText = contextUsageLabels.first,
                                            contextTokensText = contextUsageLabels.second,
                                            voiceModes = buildSet {
                                                val voiceSettings = VoiceSettingsPreferences(applicationContext)
                                                if (voiceSettings.enabled("input")) add(ChatMenuAction.VOICE_INPUT)
                                                if (voiceSettings.enabled("conversation")) add(ChatMenuAction.VOICE_CHAT)
                                                if (voiceSettings.enabled("realtime")) add(ChatMenuAction.VOICE_DOUBAO)
                                            },
                                            videoSendAvailable = inlineMessageEditor == null && videoComposerControls?.canSubmit == true,
                                            videoSendBlocked = inlineMessageEditor == null && (videoComposerControls?.blocked == true ||
                                                videoHost.state.items.isNotEmpty() && videoComposerControls?.canSubmit != true))), chatActions(completionReadAction()), modifier = Modifier.weight(1f), turnExtras = { turn ->
                                        if (turn.segmentIndex == 0) {
                                            QuestionCleanup(turn.id)
                                        }
                                    }, speechMessageId = speechMessageId, dedicatedTool = { step -> DedicatedQuestion(step) },
                                        toolContent = { step -> ToolTrustedContent(step) }, imageLoader = imageHost.historyLoader,
                                        assistant = projectedAssistants.firstOrNull { it.id == (if (selected == null) activeAssistant else selected?.assistantId) },
                                        composerAttachments = { if (!sidebarVisible) key(editorGeneration) {
                                            val editor = inlineMessageEditor
                                            if (editor == null) {
                                                ImageComposerPanel()
                                                VideoComposerPanel()
                                            } else {
                                                app.daiyu.android.ui.screens.chat.conversation.ComposerEditAttachments(
                                                    editor.images, editor.videos, imageHost.historyLoader)
                                                val additions by editor.additions.attachments.state.collectAsStateWithLifecycle()
                                                ImageAttachments(additions, editor.additions.actions, editor.additions.loader,
                                                    enabled = chatState.composer.enabled && !controller.dataBusy && !editor.uncertain, compact = true)
                                                if (editor.uncertain) top.yukonga.miuix.kmp.basic.Text(
                                                    "保存结果待核实；保留编辑附件，不会自动重发",
                                                    modifier = Modifier.testTag("message-edit-unknown"))
                                            }
                                        } })
                                    Column(Modifier.fillMaxWidth().heightIn(max = extrasMaxHeight)
                                        .verticalScroll(rememberScrollState()).testTag("chat-extra-actions")) {
                                        DictationComposerEntry()
                                        ShizukuComposerEntry()
                                    }
                                }
                            }
                        }
                    }
                }
                if (retainedPage == "assistant-edit") assistantEditingId?.let { id ->
                    var skills by remember(id) { mutableStateOf(emptyList<AssistantSkill>()) }
                    LaunchedEffect(id) {
                        skills = withContext(Dispatchers.IO) {
                            try { controller.skillManagement().snapshot().entries.map { entry ->
                                AssistantSkill(entry.id, entry.skill.name, entry.skill.description)
                            } } catch (_: Exception) { emptyList() }
                        }
                    }
                    AssistantEditScreen(assistantId = id, onBack = { closeAssistantEditor() },
                        store = assistantStore, assets = assistantAssets, installedSkills = skills,
                        onOutsideDismiss = {
                            if (retainedPage == "assistant-edit" && assistantEditingId == id) dismissAssistantFlow()
                        },
                        onSaved = { savedId ->
                            if (activityUsable()) {
                                assistantAvatarRevision++
                                if (retainedPage == "assistant-edit" && assistantEditingId == savedId) refresh()
                            }
                        })
                }
                if (retainedPage == "history-search") searchPage?.let { page ->
                    key(page) {
                        SearchHistoryDialog(conversationId = page.conversationId,
                            onSearchPage = { request, cursor, signal ->
                                check(page.conversationId != null && request.conversationId == page.conversationId)
                                retainedData.searchPage(request.query, page.conversationId, cursor,
                                    cancellationSignal = signal)
                            }, ownerCurrent = {
                                activityUsable() && searchPage === page && retainedPage == page.route &&
                                    selected?.id?.value == page.conversationId
                            }, onOpenHit = historySearchHitAction(page), onDismiss = { closeSearch(page) })
                    }
                }
                if (retainedPage == "context-management") {
                    val visit = contextManagementVisit
                    if (visit != null) key(visit) {
                        ContextManagementRoute(visit.adapter,
                            source = remember(visit) {
                                app.daiyu.android.ui.screens.settings.SettingsModelSource(applicationContext, controller.providers)
                            }, onClose = {
                                if (contextManagementVisit === visit) returnToChat()
                            })
                    } else {
                        val conversation = selected?.id
                        val generation = editorGeneration
                        val epoch = contextManagementEntryEpoch
                        val closePaused: () -> Unit = {
                            if (retainedPage == "context-management" && contextManagementVisit == null &&
                                contextManagementEntryEpoch == epoch) returnToChat()
                        }
                        app.daiyu.android.ui.components.UsageGlassDialog("压缩会话", onDismiss = closePaused) {
                            top.yukonga.miuix.kmp.basic.Text("页面已暂停；继续查看会重新读取上下文，压缩操作需重新确认",
                                modifier = Modifier.testTag("context-management-paused"))
                            val resume = { resumeContextManagementPage(conversation, generation, epoch) }
                            app.daiyu.android.ui.components.UsageAction("继续查看", resume,
                                modifier = Modifier.testTag("context-management-resume").semantics {
                                    this[RetainedPausedResumeAction] = resume
                                })
                            app.daiyu.android.ui.components.UsageAction("返回", closePaused)
                        }
                    }
                }
                if (retainedPage == "browser") {
                    val original = browserVisualVisit
                    if (original != null) key(original) {
                        app.daiyu.android.ui.screens.browser.BrowserVisualScreen(original,
                            ownerCurrent = { browserVisualPageCurrent(original) },
                            uiState = browserResumeTarget?.presentation,
                            onBack = { if (activityUsable() && browserVisualVisit === original && retainedPage == "browser") returnToChat() })
                    } else {
                        val target = browserResumeTarget
                        val epoch = browserVisualEpoch
                        val closePaused: () -> Unit = {
                            if (retainedPage == "browser" && browserVisualVisit == null &&
                                browserResumeTarget === target && browserVisualEpoch == epoch) returnToChat()
                        }
                        app.daiyu.android.ui.components.UsageGlassDialog("浏览器", onDismiss = closePaused) {
                            top.yukonga.miuix.kmp.basic.Text("浏览器已暂停；点击继续查看后才重新连接，不会自动恢复任务",
                                modifier = Modifier.testTag("browser-paused"))
                            val resume = { resumeBrowserVisualPage(target, epoch) }
                            app.daiyu.android.ui.components.UsageAction("继续查看", resume,
                                modifier = Modifier.testTag("browser-resume").semantics {
                                    this[RetainedPausedResumeAction] = resume
                                })
                            app.daiyu.android.ui.components.UsageAction("返回", closePaused)
                        }
                    }
                }
                }
                TerminalTasksSurface()
                PromptSurface()
                UnknownBranchSurface()
                ChildStopChoiceSurface()
                VirtualDisplayR08Surfaces()
                taskLocationVisit?.let { visit -> key(visit) { visit.questionUi?.invoke() } }
                val vdEntry = virtualDisplayEditor
                val vdRequest = virtualDisplayRequest
                if (vdEntry != null && vdRequest != null) {
                    app.daiyu.android.ui.components.DaiyuDialog(onDismissRequest = virtualDisplayCancelAction(vdEntry, vdRequest),
                        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                        androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                            app.daiyu.android.vdapproval.VirtualDisplayApprovalScreen(vdRequest,
                                onDecision = { original, approved ->
                                    val current = activityUsable() && virtualDisplayEditor === vdEntry &&
                                        virtualDisplayRequest === original && retainedPage == "chat" && !sidebarVisible
                                    if (!current) false else controller.answerVirtualDisplay(vdEntry, original, approved)
                                        .also { if (it) { virtualDisplayRequest = null; refresh() } }
                                })
                        }
                    }
                }
                generationReceiptNotice?.let { notice ->
                    androidx.compose.ui.window.Popup(alignment = androidx.compose.ui.Alignment.TopCenter) {
                        androidx.compose.material3.Surface(modifier = Modifier.dialogEnterMotion(), tonalElevation = 8.dp) {
                            app.daiyu.android.ui.haptics.material.TextButton(
                                modifier = Modifier.testTag("generation-receipt-ack"),
                                onClick = {
                                    if (activityUsable() && controller.acknowledgeGenerationReceipt(notice)) {
                                        generationReceiptNotice = null
                                        refresh()
                                    }
                                }) {
                                androidx.compose.material3.Text("生成消息已保存，确认收件（保留当前草稿）")
                            }
                        }
                    }
                }
                chatModelPicker?.let { picker ->
                    val lease = ordinaryDialogLease
                    val owner = chatModelOwner
                    val session = chatModelSession
                    val dismiss = { if (dialogActionsCurrent(lease) && chatModelOwner === owner) dismissChatModels() }
                    if (session == null) ChatModelPicker(picker, onSelect = {}, onDismiss = dismiss)
                    else LocalCatalogChatModelPicker(session, enabled = picker.enabled,
                        loading = picker.loading, message = picker.message, switching = picker.switching,
                        onSelect = { if (dialogActionsCurrent(lease)) selectChatModel(it, owner, session) },
                        onSelectOtherProvider = { providerId, ticket -> if (dialogActionsCurrent(lease)) selectChatModel(ticket, owner, session, providerId) },
                        onDismiss = dismiss)
                }
                composerShortcutPick?.let { pick ->
                    val lease = ordinaryDialogLease
                    app.daiyu.android.ui.screens.chat.conversation.ComposerShortcutPicker(
                        pick.trigger.kind, pick.trigger.query, pick.choices, pick.error,
                        onChoose = { if (dialogActionsCurrent(lease)) chooseComposerShortcut(pick, it) },
                        onDismiss = { if (dialogActionsCurrent(lease) && composerShortcutPick === pick) composerShortcutPick = null })
                }
                assistantSelectionOwner?.let { owner -> key(owner) {
                var confirmedAssistantId by remember(owner) { mutableStateOf(owner.expectedAssistantId) }
                val editAssistant = { id: String ->
                    if (assistantOwnerMatches(owner) && retainedPage != "assistant-edit") {
                        assistantEditReturnPage = retainedPage
                        assistantEditReturnToSidebar = false
                        assistantEditingId = id; retainedPage = "assistant-edit"
                    }
                }
                AssistantPickerDialog(show = assistantPicker, store = assistantStore, assets = assistantAssets,
                        selectedId = confirmedAssistantId, useGlobalSelection = false,
                        editing = retainedPage == "assistant-edit",
                        canMutate = { assistantOwnerMatches(owner) && !controller.dataBusy },
                        canInteract = { assistantOwnerMatches(owner) && allowTaskConfigurationChange() },
                        canSelect = { assistantOwnerMatches(owner) && allowAssistantSelection(owner) },
                        onDelete = { id -> guard {
                            if (!assistantOwnerMatches(owner) || !allowTaskConfigurationChange()) return@guard
                            controller.deleteAssistant(id)
                        } },
                        onSelect = { id -> guard {
                            check(assistantOwnerMatches(owner)) { "选择器所属页面已变化；未改变绑定" }
                            if (!allowAssistantSelection(owner)) return@guard
                            if(owner.global) assistantStore.select(id)
                            else {
                                val conversationId=ConversationId(checkNotNull(owner.conversationId))
                                check(controller.bindConversationAssistant(conversationId,confirmedAssistantId,id)) { "当前正在准备或运行，或绑定已变化；未改变绑定" }
                                selected=selected?.copy(assistantId=id)
                            }
                            confirmedAssistantId = id
                            refresh()
                        } },
                        onEdit = { id -> if (allowTaskConfigurationChange()) editAssistant(id) },
                        onCreate = { guard {
                            if (assistantOwnerMatches(owner) && retainedPage != "assistant-edit") {
                                editAssistant(assistantStore.create().id)
                            }
                        } },
                        onDismiss = { if(assistantSelectionOwner===owner) {
                            assistantPicker = false
                            assistantSelectionOwner = null
                        } },
                        onOutsideDismiss = { if(assistantSelectionOwner===owner) dismissAssistantFlow() })
                } }
                usageVisit?.let { visit ->
                    if (retainedPage == "chat" && !sidebarVisible && selected?.id == visit.conversation) {
                        key(visit.token) { ChatUsageDialog(controller, visit.conversation,
                            onDismiss = { if (usageVisit === visit) usageVisit = null },
                            usageRevision = conversationUsageRevision) }
                    } else LaunchedEffect(visit) { if (usageVisit === visit) usageVisit = null }
                }
                childStatusVisit?.let { visit ->
                    if (retainedPage == "chat" && !sidebarVisible && selected?.id?.value == visit.second) {
                        key(visit.first) {
                            SubAgentStatusHost(controller, visit.second,
                                onDismiss = { if (childStatusVisit === visit) childStatusVisit = null })
                        }
                    } else LaunchedEffect(visit) { if (childStatusVisit === visit) childStatusVisit = null }
                }
                workspaceVisit?.let { visit ->
                    val lease = ordinaryDialogLease
                    if (workspacePresented(visit)) {
                        app.daiyu.android.ui.components.ConversationWorkspaceSheet(
                            visible = true, path = visit.path, busy = visit.busy, error = visit.error,
                            workspaceRoots = visit.editor.privateWorkspaceRoots,
                            onCopyPath = { if (dialogActionsCurrent(lease)) copyWorkspacePath(visit, it) },
                            onCreateFolder = {
                                if (dialogActionsCurrent(lease) && workspaceCurrent(visit) && workspaceVisit === visit &&
                                    visit.choosing && !visit.busy && !visit.listing && visit.directory?.selectable == true)
                                    workspaceVisit = visit.copy(creatingFolder = true)
                            },
                            workspaces = visit.editor.workspacePaths.map {
                                app.daiyu.android.ui.components.WorkspaceItemOption(it)
                            }, activeWorkspaceIndex = visit.editor.workspaces.activeIndex,
                            onSelectWorkspace = { if (dialogActionsCurrent(lease)) selectConversationWorkspace(visit, it) },
                            onEditWorkspace = { if (dialogActionsCurrent(lease)) editConversationWorkspace(visit, it) },
                            editingWorkspace = visit.editingWorkspaceIndex != null,
                            onDeleteWorkspaces = { if (dialogActionsCurrent(lease)) removeConversationWorkspaces(visit, it) },
                            selectionMode = visit.selectionMode, selectedWorkspaces = visit.selectedWorkspaces,
                            onSelectionChange = { mode, indices ->
                                if (dialogActionsCurrent(lease) && workspaceCurrent(visit) && workspaceVisit === visit && !visit.busy && !visit.listing) {
                                    workspaceVisit = visit.copy(selectionMode = mode, selectedWorkspaces = indices)
                                }
                            },
                            choosing = visit.choosing, listing = visit.listing,
                            directory = visit.directory?.let { directory ->
                                app.daiyu.android.ui.components.WorkspaceDirectoryOption(directory.path, directory.parentPath,
                                    directory.folders, directory.selectable,
                                    directory.entries.map { app.daiyu.android.ui.components.WorkspaceFileOption(it.name, it.directory) }, directory.truncated)
                            },
                            onBrowse = {
                                visit.editor.sources.firstOrNull { it.id == "private" }?.takeIf { dialogActionsCurrent(lease) }?.let { source ->
                                    browseConversationWorkspace(visit, source.id, source.initialPath, editingIndex = null)
                                }
                            },
                            onLongPressFile = { if (dialogActionsCurrent(lease)) manageConversationWorkspaceEntry(visit, it) },
                            onNavigate = { path -> if (dialogActionsCurrent(lease)) browseConversationWorkspace(visit, path = path) }, onCancel = { if (dialogActionsCurrent(lease)) cancelWorkspaceSelection(visit) },
                            onSave = { if (dialogActionsCurrent(lease)) saveConversationWorkspace(visit) },
                            onDismiss = { if (dialogActionsCurrent(lease) && workspaceVisit === visit) {
                                if (visit.selectionMode && !visit.busy) {
                                    workspaceVisit = visit.copy(selectionMode = false, selectedWorkspaces = emptySet())
                                } else revokeWorkspaceVisit()
                            } })
                        visit.managingEntry?.let { entry -> key(visit.token, entry, lease) {
                            app.daiyu.android.ui.components.WorkspaceEntryDialogs(entry.name, entry.directory,
                                enabled = { dialogActionsCurrent(lease) && workspaceCurrent(visit) && workspaceVisit === visit && !visit.busy && !visit.listing },
                                onRename = { if (dialogActionsCurrent(lease)) modifyConversationWorkspaceEntry(visit, it) },
                                onDelete = { if (dialogActionsCurrent(lease)) modifyConversationWorkspaceEntry(visit, null) },
                                onDismiss = { if (dialogActionsCurrent(lease) && workspaceVisit === visit) workspaceVisit = visit.copy(managingEntry = null) })
                        } }
                        if (visit.creatingFolder) app.daiyu.android.ui.components.WorkspaceCreateFolderDialog(
                            onConfirm = { createWorkspaceFolder(visit, it, lease) },
                            onDismiss = { if (workspaceVisit === visit) workspaceVisit = visit.copy(creatingFolder = false) })
                    } else LaunchedEffect(visit.token) { if (workspaceVisit?.token === visit.token) workspaceVisit = null }
                }
                val owner = collaborationOwner
                ConversationCollaborationDialog(show = collaborationVisible, conversationId = owner,
                    onDismiss = { collaborationVisible = false; collaborationOwner = null }, taskRunning = backupBusy,
                    ownerMatches = { activityUsable() && selected?.id?.value == owner && retainedPage == "chat" && !controller.dataBusy },
                    repositoryFactory = { subAgentRepository }, modelSource = subAgentModelSource)
                // Saved independently for the standalone composer or this conversation.
                // The preparation worker freezes it once; edits never mutate an active request.
                val thinkingOwner = chatState.conversationKey
                val thinkingSpeed = confirmedGptSpeed
                SubAgentThinkingEffortPickerDialog(show = thinkingPicker, effort = ReasoningEffort.textPreference(reasoningPreference.get(thinkingOwner), model = confirmedReasoningModel),
                    mainChat = true, speedMode = chatState.composer.gptSpeedMode,
                    speedEnabled = chatState.composer.gptSpeedEnabled,
                    onCycleSpeed = { cycleChatGptSpeed(thinkingSpeed, thinkingOwner) },
                    options = ReasoningEffort.textChoices(confirmedReasoningModel),
                    onDismiss = { thinkingPicker = false }, onEffortChange = { effort ->
                        if (activityUsable() && selected?.id?.value == thinkingOwner) {
                            if (allowTaskConfigurationChange(thinkingOwner?.let(::ConversationId))) try {
                                if (controller.setChatReasoning(thinkingOwner, effort)) publishChat()
                                else toast("任务占用中")
                            } catch (_: Exception) { toast("思考偏好保存未确认；请重新保存") }
                        }
                    })
            }
        }
        }
        controller.observe(listener)
        refresh()
        // Recreating an Activity never replays an external VIEW/import confirmation.
        if (state == null) offerOpenedBackup(intent)
        consumeOpenedAttachments()
    }

    @Suppress("DEPRECATION")
    private fun updateSystemBars(isDark: Boolean) {
        val background = resolveAppearanceColors(appearance, isDark).background
        val lightBars = colorContentColor(background) == Color.Black
        window.statusBarColor = background.toArgb()
        window.navigationBarColor = background.toArgb()
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = lightBars
        controller.isAppearanceLightNavigationBars = lightBars
    }

    private fun chatActions(onVisibleCompleted: (Set<String>) -> Unit): ChatScreenActions {
        val dictationProjection = currentDictationProjection()
        val renderedOwner = controlOwner
        val renderedTextSend = pendingTextSend
        val renderedConversation = chatState.conversationKey
        val renderedRevealOwner = controller.active?.takeIf { it.conversationId.value == renderedConversation }
        val renderedRevealTurns = chatState.turns.filterIsInstance<ChatTurnUi.Assistant>().associateBy { it.listKey }
        val renderedRevealAdvance = hapticsHost?.revealAdvance(renderedConversation)
        val renderedEditorGeneration = editorGeneration
        val renderedContextEntryEpoch = contextManagementEntryEpoch
        val renderedDraftRevision = draftRevision
        val renderedInlineVisit = inlineMessageEditor?.visit
        val renderedActionVisit = messageActionVisit
        val renderedSpeechOwner = speechHost.owner
        fun editorMatches() = activityUsable() && voiceForeground && messageActionVisit === renderedActionVisit &&
            selectionReady && pendingTextSend == null && controller.textSendState() == null &&
            selected?.id?.value == renderedConversation && editorGeneration == renderedEditorGeneration &&
            inlineMessageEditor?.visit === renderedInlineVisit
        fun gestureOwner() = currentControlOwner()?.takeIf {
            editorMatches() && currentChatControlAvailable() && renderedOwner != null && it.id == renderedOwner.id && it.runId == renderedOwner.runId && it.conversationId == renderedOwner.conversationId
        }
        fun actionUnavailable() { toast("操作未完成，请稍后再试") }
        val actionSnapshot = messageActionSnapshot?.takeIf {
            it.conversationId.value == renderedConversation && it.matchesRendered(shown.map { row -> row.second })
        }
        val messageCallbacks = messageActionHost.bind(actionSnapshot,
            isCurrent = { editorMatches() && draftRevision == renderedDraftRevision &&
                voiceForeground && retainedPage == "chat" && !sidebarVisible &&
                terminalEditor == null && virtualDisplayEditor == null && !videoSending &&
                !imageHost.sending && !shizukuWritesBlocked },
            result = { outcome -> guard {
                when (outcome) {
                    is MessageActionResult.EditDraft -> {
                        outcome.target?.let { target ->
                            val key = app.daiyu.android.timeline.TimelineAnchor(target.conversationId.value,
                                target.runId.value, target.messageId.value, target.segmentIndex).listKey
                            val turn = chatState.turns.filterIsInstance<ChatTurnUi.User>().single { it.listKey == key }
                            invalidateConfiguredVoice(); dictationHost.invalidate(); offlineHost.invalidate()
                            composerShortcutPick = null
                            clearInlineMessageEditor()
                            inlineMessageEditor = InlineMessageEditor(key, outcome.text, target,
                                turn.content.filterIsInstance<app.daiyu.android.ui.screens.chat.conversation.MessageContentUi.Image>(),
                                outcome.videos, MessageEditImageDraft(target.conversationId.value,
                                    turn.content.count { it is app.daiyu.android.ui.screens.chat.conversation.MessageContentUi.Image },
                                    controller.imageAttachments, lifecycleScope,
                                    { if (activityUsable()) publishChat(rowsChanged = false) },
                                    { if (activityUsable()) toast(it) }))
                            publishChat(rowsChanged = false)
                        }
                    }
                    is MessageActionResult.Branch -> select(outcome.conversation)
                    MessageActionResult.Deleted -> { clearInlineMessageEditor(); refresh() }
                    is MessageActionResult.Regenerated -> {
                        if (outcome.outcome == RegenerationSendOutcome.ACCEPTED) {
                            clearInlineMessageEditor()
                            shown.clear(); select(outcome.conversation); refresh()
                        } else if (outcome.outcome == RegenerationSendOutcome.UNKNOWN) {
                            retainUncertainMessageEdit()
                        }
                        when (outcome.outcome) {
                            RegenerationSendOutcome.ACCEPTED -> Unit
                            RegenerationSendOutcome.NOT_SENT -> toast(controller.problem ?: "本次未发送；原消息、摘要及草稿保留")
                            RegenerationSendOutcome.UNKNOWN -> {
                                refreshMessageActionHistory()
                                toast("重新生成保存结果未确认；请核实历史，不会自动重发")
                            }
                        }
                    }
                    is MessageActionResult.Rejected -> {
                        if (actionSnapshot == null && messageActionLoading != null) {
                            toast("正在准备消息操作，请稍后再试")
                        } else if (actionSnapshot == null) {
                            prompt = ChoicePrompt("消息操作快照不可用", listOf("重新获取消息操作", "取消"), { choice ->
                                if (choice == 0 && editorMatches() && draftRevision == renderedDraftRevision &&
                                    retainedPage == "chat" && !sidebarVisible && messageActionLoading == null) guard {
                                    messageActionSnapshot = null; messageActionKey = null; messageActionAttempted = null
                                    prepareMessageActionSnapshot(); publishChat()
                                }
                            })
                        } else toast(outcome.reason)
                    }
                    is MessageActionResult.Unknown -> {
                        retainUncertainMessageEdit()
                        refreshMessageActionHistory(); refreshUnknownBranchOutcome(); toast(outcome.reason)
                    }
                }
            } })
        fun currentChat() = editorMatches() && draftRevision == renderedDraftRevision && controller.ready &&
            !controller.dataBusy && voiceForeground && retainedPage == "chat" && !sidebarVisible
        fun requestDictation() {
            if (!VoiceSettingsPreferences(applicationContext).enabled("input")) { toast("请先在语音设置中启用语音输入"); return }
            if (editorMatches() && terminalEditor == null && virtualDisplayEditor == null && virtualDisplayPlanVisit == null && !videoSending &&
                draftRevision == renderedDraftRevision && voiceForeground &&
                retainedPage == "chat" && !sidebarVisible &&
                controller.ready && !controller.dataBusy && !imageHost.sending && !shizukuWritesBlocked) {
                run {
                    val target = DictationDraftTarget(selected?.id?.value, editorGeneration, draftRevision)
                    val config = try { voiceRuntime.snapshot() } catch (_: Exception) { null }
                    when (config?.dictation) {
                        "SYSTEM" -> {
                            if (offlineSpeechReady()) startOfflineDictation()
                            else voiceConfigurationToast("请先下载离线语音包")
                        }
                        "DOUBAO" -> {
                            val ownedTarget = remoteVoice.dictationTarget?.takeIf(::voiceEditorCurrent)
                            if (ownedTarget != null) remoteVoice.dictate(ownedTarget)
                            else {
                                revokeUniversalVoice(); invalidatePreparedSpeech(); dictationHost.invalidate(); offlineHost.invalidate()
                                captureDictationDraft(target); remoteVoice.dictate(target)
                            }
                        }
                        else -> toast("听写未配置或凭据不可用，请打开语音设置")
                    }
                }
            }
        }
        fun openContextPage() {
            val conversation = selected
            if (editorMatches() && voiceForeground && retainedPage == "chat" && !sidebarVisible &&
                renderedContextEntryEpoch == contextManagementEntryEpoch && conversation != null &&
                !renderedConversation.isNullOrBlank() && conversation.id.value == renderedConversation &&
                editorGeneration == renderedEditorGeneration) {
                val owner = ContextManagementOwner(conversation.id, renderedContextEntryEpoch)
                val adapter = controller.openContextManagement(owner)
                if (adapter == null) toast("上下文页面未打开；当前正在准备、运行或执行任务，请稍后再试")
                else {
                    retainedPage = "context-management"
                    contextManagementVisit = ContextManagementVisit(owner, adapter)
                }
            }
        }
        val renderedSpeed = confirmedGptSpeed
        val renderedSpeedConversation = selected?.id?.value
        return ChatScreenActions(
        onOpenWorkspace = { if (currentChat()) openConversationWorkspace() },
        onReferenceShortcut = { trigger, expected ->
            if (editorMatches() && inlineMessageEditor == null && chatState.composer.enabled && draft == expected)
                openComposerShortcut(trigger, expected)
        },
        onCycleGptSpeed = { cycleChatGptSpeed(renderedSpeed, renderedSpeedConversation) },
        onVisibleCompleted = onVisibleCompleted,
        onVisibleTextAdvance = { key ->
            val active = controller.active
            val turn = renderedRevealTurns[key]
            val live = active != null && renderedRevealOwner != null && active.id == renderedRevealOwner.id &&
                active.runId == renderedRevealOwner.runId && active.conversationId == renderedRevealOwner.conversationId &&
                active.id.value == turn?.ownerMessageId && active.status == RunStatus.RUNNING && controller.isRunUnfenced(active)
            val completedDrain = active == null && turn?.status == RunStatus.COMPLETED
            if (activityUsable() && voiceForeground && retainedPage == "chat" && !sidebarVisible &&
                selected?.id?.value == renderedConversation && editorGeneration == renderedEditorGeneration &&
                turn != null && (live || completedDrain)) {
                renderedRevealAdvance?.invoke()
            }
        },
        onInteractiveModeChange = { enabled -> if (activityUsable()) controller.interactiveMode.setEnabled(enabled) },
        onMore = { if (currentChat()) refreshAutomaticCompression() },
        onVoice = voice@{
            val feature = when (voiceEntryMode) {
                ChatMenuAction.VOICE_INPUT -> "input"
                ChatMenuAction.VOICE_DOUBAO -> "realtime"
                else -> "conversation"
            }
            if (!VoiceSettingsPreferences(applicationContext).enabled(feature)) return@voice
            if (!currentChat()) return@voice
            if (voiceEntryMode != ChatMenuAction.VOICE_DOUBAO || !remoteVoice.duplexActive) {
                chatVoiceConfigurationProblem(voiceEntryMode)?.let { voiceConfigurationToast(it); return@voice }
            }
            when (voiceEntryMode) {
                ChatMenuAction.VOICE_INPUT -> requestDictation()
                ChatMenuAction.VOICE_DOUBAO -> if (currentChat()) {
                    if (remoteVoice.duplexActive) remoteVoice.invalidate()
                    else { stopCascadeVoice(); invalidateConfiguredVoice(); dictationHost.invalidate(); offlineHost.invalidate()
                        speechHost.stop(speechHost.owner); remoteVoice.startDuplex {
                            activityUsable() && voiceForeground && retainedPage == "chat" && !sidebarVisible &&
                                editorGeneration == renderedEditorGeneration && draftRevision == renderedDraftRevision
                        }
                    }
                }
                else -> if (currentChat()) voiceConversationDialogOwner = ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision)
            }
        },
        onRegenerate = { id ->
            val target = actionSnapshot?.renderedTargets?.get(id) ?: actionSnapshot?.targets?.get(id)
            val index = chatState.turns.indexOfFirst { it.matchesKey(id) }
            if (target != null && index >= 0 && chatState.turns.drop(index + 1).any { it is ChatTurnUi.User }) {
                prompt = ConfirmPrompt("重新生成将删除此回复及后面的消息", "", "确定",
                    action = { messageCallbacks.onRegenerate(id) }, showCloseButton = false, secondaryCancel = true)
            } else messageCallbacks.onRegenerate(id)
        }, onSpeak = speak@{ id ->
            if (!activityUsable() || !voiceForeground || retainedPage != "chat" || sidebarVisible ||
                speechHost.owner != renderedSpeechOwner || selected?.id?.value != renderedConversation ||
                editorGeneration != renderedEditorGeneration) return@speak
            val playback = speechHost.state.value
            if (playback.owner == renderedSpeechOwner && playback.requestId == id && voiceHostIsActive(playback)) {
                invalidatePreparedSpeech()
                speechHost.stop(renderedSpeechOwner, id)
            } else if (editorMatches() && controller.ready && !controller.dataBusy) {
                // Resolve the CURRENT displayed answer, never a captured stream/tool payload.
                voiceHostText(chatState.turns, id)?.let { text ->
                    requestConfiguredSpeech(renderedSpeechOwner, id, text)
                }
            }
        },
        onBranch = messageCallbacks.onBranch, onEditUser = messageCallbacks.onEditUser,
        editingMessageKey = inlineMessageEditor?.key,
        onCancelMessageEdit = { if (currentChat() && editorMatches() && renderedInlineVisit != null &&
            inlineMessageEditor?.visit === renderedInlineVisit) {
            clearInlineMessageEditor(); publishChat(rowsChanged = false)
        } },
        onDeleteTurn = { id ->
            val firstSourceKey = chatState.turns.firstOrNull { it.matchesKey(id) }?.listKey ?: id
            prompt = ConfirmPrompt("删除这条消息及后面的内容？", "", "确定",
                action = { messageCallbacks.onDeleteTurn(firstSourceKey) }, showCloseButton = false, secondaryCancel = true)
        }, onRetryStep = messageCallbacks.onRetryStep,
        onOpenSidebar = { openSidebar() }, onTextChange = { text -> if (editorMatches() && currentDictationProjection() == null) {
            val editor = inlineMessageEditor
            if (editor == null) updateDraft(text)
            else if (!controller.dataBusy) {
                inlineMessageEditor = editor.copy(text = text); publishChat(rowsChanged = false)
            }
        } },
        onProjectedDraftEdit = { expected, next, start, end ->
            val editor = inlineMessageEditor
            if (!editorMatches() || controller.dataBusy || renderedDraftRevision != draftRevision ||
                currentDictationProjection() != dictationProjection ||
                (dictationProjection?.text ?: editor?.text ?: draft) != expected) false
            else if (dictationProjection != null) {
                if (next == expected) {
                    if (start != dictationProjection.cursor || end != dictationProjection.cursor) {
                        invalidateConfiguredVoice(); dictationHost.invalidate(); offlineHost.invalidate()
                        selectionStart = start.coerceIn(0, draft.length)
                        selectionEnd = end.coerceIn(0, draft.length)
                        publishChat(rowsChanged = false)
                    }
                    true
                } else {
                    invalidateConfiguredVoice(); dictationHost.invalidate(); offlineHost.invalidate()
                    updateDraft(next)
                    if (draft != next) false else {
                        selectionStart = start.coerceIn(0, next.length)
                        selectionEnd = end.coerceIn(0, next.length)
                        publishChat(rowsChanged = false)
                        true
                    }
                }
            }
            else if (editor != null) {
                inlineMessageEditor = editor.copy(text = next, selectionStart = start.coerceIn(0, next.length),
                    selectionEnd = end.coerceIn(0, next.length))
                publishChat(rowsChanged = false)
                true
            } else {
                updateDraft(next)
                if (draft != next) false else {
                    selectionStart = start.coerceIn(0, next.length)
                    selectionEnd = end.coerceIn(0, next.length)
                    publishChat(rowsChanged = false)
                    true
                }
            }
        },
        onSelectionChange = { start, end ->
            val editor = inlineMessageEditor
            if (editorMatches() && currentDictationProjection() == null && editor != null && !controller.dataBusy) {
                inlineMessageEditor = editor.copy(selectionStart = start.coerceIn(0, editor.text.length),
                    selectionEnd = end.coerceIn(0, editor.text.length))
                publishChat(rowsChanged = false)
            } else if (editorMatches() && currentDictationProjection() == null && voiceForeground && retainedPage == "chat" && !sidebarVisible &&
                chatState.composer.enabled && !imageHost.sending && (selectionStart != start || selectionEnd != end)) {
                offlineHost.invalidate()
                if (dictationDraft != null) { dictationHost.invalidate(); invalidateConfiguredVoice() }
                if (BuildConfig.DEBUG && selectionStart != selectionEnd && start == end) {
                    android.util.Log.d("DaiyuSelection", "collapse host=${System.identityHashCode(this)} " +
                        "editor=$editorGeneration lifecycle=${lifecycle.currentState} " +
                        "windowFocused=${window.decorView.hasWindowFocus()} " +
                        "old=($selectionStart,$selectionEnd) next=($start,$end)",
                        Throwable("selection-collapse-origin"))
                }
                selectionStart = start; selectionEnd = end
                chatState = chatState.copy(composer = chatState.composer.copy(
                    selectionStart = start, selectionEnd = end))
            }
        },
        onCompressionSendBlocked = { toast("正在压缩上下文") },
        onSend = send@{ text ->
            if (compressionSendBlocked()) return@send
            if (currentDictationProjection() != null) return@send
            val editor = inlineMessageEditor
            if (editor != null) {
                if (currentChat() && editorMatches() && !controller.dataBusy && text == editor.text && editor.canSubmit && !editReferencePending(editor) &&
                    editor.target == (actionSnapshot?.renderedTargets?.get(editor.key) ?: actionSnapshot?.targets?.get(editor.key)))
                    messageCallbacks.onSaveEditImages(editor.key, text, editor.additions.ready)
                return@send
            }
            if (editorMatches() && terminalEditor == null && !mcpPreparing && mcpApproval == null && text == draft && pendingTextSend == null && !shizukuWritesBlocked) guard {
            invalidateConfiguredVoice()
            dictationHost.invalidate()
            offlineHost.invalidate()
            cancelShizukuEditor()
            cancelBrowserEditor()
            if (videoHost.state.items.isNotEmpty()) { stopCascadeVoice(); videoComposerControls?.send?.invoke() }
            else if (videoComposerControls?.blocked == true) return@guard
            else if (imageHost.hasAttachments) { stopCascadeVoice(); sendImageDraft(text) }
            else if (!imageHost.busy && !videoSending) sendDraft(text)
        } },
        onSupplement = supplement@{ text ->
            if (compressionSendBlocked()) return@supplement
            if (currentDictationProjection() != null) return@supplement
            if (editorMatches() && text == draft && !imageHost.sending && !shizukuWritesBlocked) guard {
            offlineHost.invalidate()
            gestureOwner()?.let { submitSupplement(it, text) }
        } },
        onCancelSend = { renderedTextSend?.let(::cancelPendingTextSend) },
        onPrimaryTap = { guard {
            gestureOwner()?.let { owner -> controller.runState(owner)?.let { state ->
                val accepted = if (state.pauseRequested) controller.resume(owner) else controller.pause(owner)
                if (accepted) refresh()
            } }
        } },
        onPrimaryLongPress = { guard { gestureOwner()?.let { owner -> controller.stopMain(owner); refresh() } } },
        onControlPress = {
            controlTouchGeneration++; controlTouchActive = true; controlTouchOwner = controlOwner
        },
        onControlRelease = {
            val generation = controlTouchGeneration
            window.decorView.post { if (controlTouchGeneration == generation) { controlTouchActive = false; controlTouchOwner = null } }
        },
        onModelClick = { guard { if (currentChat()) openChatModels() } }, onReasoningClick = { thinkingPicker = true },
        onAssistantClick = { openAssistantPicker() }, onCopy = { id -> copyMessage(id) },
        onMenuAction = { action -> guard {
            if (inlineMessageEditor != null) {
                if (currentChat()) when (action) {
                    ChatMenuAction.ATTACH_IMAGE -> pickEditImages()
                    ChatMenuAction.ATTACH_FILE -> pickChatReference(false)
                    ChatMenuAction.ATTACH_FOLDER -> pickChatReference(true)
                    ChatMenuAction.ATTACH_VIDEO -> toast("原视频编辑重新发送仍需独立上传批准；请用新消息添加视频")
                    else -> Unit
                }
            } else when (action) {
            ChatMenuAction.ATTACH_IMAGE -> {
                if (editorMatches() && videoHost.state.items.isEmpty() && !videoSending) {
                    pickImages()
                }
            }
            ChatMenuAction.ATTACH_VIDEO -> {
                if (currentChat() && renderedContextEntryEpoch == contextManagementEntryEpoch &&
                    !imageHost.hasAttachments && !imageHost.busy && !shizukuWritesBlocked) {
                    cancelShizukuEditor(); cancelBrowserEditor(); cancelGenerationEditor()
                    videoPickEntry = VideoPickEntry(ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision),
                        contextManagementEntryEpoch)
                }
            }
            ChatMenuAction.ATTACH_FILE -> if (currentChat() && renderedContextEntryEpoch == contextManagementEntryEpoch) pickChatReference(false)
            ChatMenuAction.ATTACH_FOLDER -> if (currentChat() && renderedContextEntryEpoch == contextManagementEntryEpoch) pickChatReference(true)
            ChatMenuAction.INTERACTIVE_MODE -> if (currentChat())
                controller.interactiveMode.setEnabled(!controller.interactiveMode.enabled)
            ChatMenuAction.CONTEXT_STATS -> if (currentChat()) usageVisit = UsageVisit(Any(), selected?.id)
            ChatMenuAction.COMPRESS_CONTEXT -> openContextPage()
            ChatMenuAction.AUTO_COMPRESS -> if (currentChat()) toggleAutomaticCompression()
            ChatMenuAction.VOICE_INPUT, ChatMenuAction.VOICE_CHAT, ChatMenuAction.VOICE_DOUBAO -> if (currentChat() &&
                VoiceSettingsPreferences(applicationContext).enabled(when (action) {
                    ChatMenuAction.VOICE_INPUT -> "input"; ChatMenuAction.VOICE_DOUBAO -> "realtime"; else -> "conversation"
                })) {
                if (voiceEntryPreferences.edit().putString("mode", action.name).commit()) voiceEntryMode = action
                else toast("语音入口未保存；保持原选择")
            }
            ChatMenuAction.NEW_CHAT -> if (currentChat()) createNewConversation()
            ChatMenuAction.RENAME -> selected?.let { conversation ->
                editConversation(conversation.id.value, false)
            }
            ChatMenuAction.SUB_AGENT -> if (currentChat()) childStatusVisit = Any() to selected?.id?.value
            ChatMenuAction.COLLABORATION -> { collaborationOwner = selected?.id?.value; collaborationVisible = true }
            ChatMenuAction.LOAD_EARLIER -> loadEarlier()
            ChatMenuAction.LOCATE_USER -> chooseUserMessage()
            ChatMenuAction.BOTTOM -> jumpToBottom()
            ChatMenuAction.SEARCH -> if (currentChat()) openSearch("history-search")
            ChatMenuAction.SETTINGS -> retainedPage = "settings"
            ChatMenuAction.BROWSER_TOOLS -> if (currentChat()) openBrowserApproval()
            ChatMenuAction.GUI_TOOLS -> if (currentChat()) openGuiPreparation()
            ChatMenuAction.MCP_TOOLS -> if (currentChat()) openMcpApproval()
            ChatMenuAction.TERMINAL_TOOLS -> if (currentChat() && draft.isNotBlank() && controller.active == null) {
                cancelBrowserEditor(); cancelShizukuEditor(); dictationHost.invalidate()
                terminalEditor = TerminalEditor(selected, terminalDraft())
            }
            ChatMenuAction.GENERATE_IMAGE -> if (currentChat()) openGenerationApproval(app.daiyu.android.model.generation.GenerationKind.IMAGE)
            ChatMenuAction.GENERATE_VIDEO -> if (currentChat()) openGenerationApproval(app.daiyu.android.model.generation.GenerationKind.VIDEO)
            ChatMenuAction.GENERATION_SETTINGS -> if (currentChat()) openGenerationSettings()
            ChatMenuAction.OPEN_TERMINAL -> openTerminal()
            ChatMenuAction.OPEN_BROWSER -> if (currentChat() && renderedContextEntryEpoch == contextManagementEntryEpoch) openBrowserVisualPage()
            ChatMenuAction.OPEN_WORKSPACE -> if (currentChat()) openLinuxSettings(filesOnly = true)
            ChatMenuAction.CONVERSATION_ID -> selected?.let {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("会话 ID", it.id.value))
            }
            else -> actionUnavailable()
        } } },
        )
    }

    @Composable private fun projectedVoiceComposer(): ComposerUi {
        val system by dictationHost.state.collectAsStateWithLifecycle()
        val offline by offlineHost.state.collectAsStateWithLifecycle()
        val remote by remoteVoice.listeningTarget.collectAsStateWithLifecycle()
        val remotePreview by remoteVoice.preview.collectAsStateWithLifecycle()
        val universal by universalVoice.state.collectAsStateWithLifecycle()
        val projection = currentDictationProjection(offline, system, remotePreview)
        return chatState.composer.copy(
            text = projection?.text ?: chatState.composer.text,
            selectionStart = projection?.cursor ?: chatState.composer.selectionStart,
            selectionEnd = projection?.cursor ?: chatState.composer.selectionEnd,
            dictationPreview = projection != null,
            listening = composerListening(system, offline, remote, universal))
    }

    private fun composerListening(
        system: app.daiyu.android.voice.ComposerDictationState,
        offline: app.daiyu.android.voice.offline.OfflineComposerHost.State,
        remote: DictationDraftTarget?,
        universal: VoiceControlState,
    ): Boolean {
        if (!activityUsable() || !voiceForeground || retainedPage != "chat" || sidebarVisible) return false
        fun current(target: DictationDraftTarget?) = target != null && voiceEditorCurrent(target)
        return (system.listening && dictationHost.state.value == system && current(system.target)) ||
            (offline.listening && offlineHost.state.value == offline && current(offline.target)) ||
            (remoteVoice.listeningTarget.value == remote && current(remote)) ||
            (universalVoice.state.value == universal && universal.phase == VoiceControlPhase.LISTENING && universal.permitId != null &&
                universalAdmissionSnapshot == VoiceAdmission.READY &&
                universalEditor == ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision))
    }

    @Composable private fun completionReadAction(): (Set<String>) -> Unit {
        val generation = editorGeneration
        val revision = controller.dataRevision
        val id = selected?.id
        val completed = shown.mapNotNull { (_, message) ->
            if (message.conversationId == id && message.role == Message.ASSISTANT_ROLE && message.status == RunStatus.COMPLETED)
                message.id.value to message.runId.value else null
        }.toSet()
        val owner = if (completionPageReadable() && id != null && completed.isNotEmpty())
            app.daiyu.android.ui.screens.chat.conversation.CompletionReadOwner(id, generation, revision, completed) else null
        // Capture the exact event off the UI thread, before the measured-frame callback.
        val receiptState = app.daiyu.android.ui.screens.chat.conversation.rememberCompletionReceipt(owner) { conversation ->
            app.daiyu.android.storage.ConversationCompletionMarkers.read(controller.database.readableDatabase, conversation)
        }
        val receipt = receiptState.value?.takeIf(::completionShown)
        // An earlier segment of the same physical owner is not its newly completed final segment.
        val finalKey = receipt?.let { expected -> chatState.turns.filterIsInstance<ChatTurnUi.Assistant>()
            .lastOrNull { it.ownerMessageId == expected.messageId && it.status == RunStatus.COMPLETED }?.listKey }
        return { visibleKeys ->
            if (receipt != null && finalKey != null && finalKey in visibleKeys &&
                acknowledgeCompletion(receipt, generation, revision)) receiptState.value = null
        }
    }
    private fun completionPageReadable() = activityUsable() && voiceForeground && retainedPage == "chat" &&
        !sidebarVisible && selectionReady && controller.ready && !controller.dataBusy &&
        controller.problem?.startsWith("会话恢复失败") != true
    private fun completionShown(receipt: app.daiyu.android.storage.ConversationCompletionMarkers.Receipt) =
        selected?.id?.value == receipt.conversationId && shown.any { (_, message) ->
            message.conversationId.value == receipt.conversationId && message.id.value == receipt.messageId &&
                message.runId.value == receipt.runId && message.role == Message.ASSISTANT_ROLE &&
                message.status == RunStatus.COMPLETED
        }
    private fun acknowledgeCompletion(receipt: app.daiyu.android.storage.ConversationCompletionMarkers.Receipt,
        generation: Long, revision: Long): Boolean {
        if (!completionPageReadable() || editorGeneration != generation || controller.dataRevision != revision ||
            !completionShown(receipt)) return false
        var acknowledged = false
        guard {
            acknowledged = app.daiyu.android.storage.ConversationCompletionMarkers.acknowledge(
                controller.database.writableDatabase, receipt)
            if (acknowledged) updatePane()
        }
        return acknowledged
    }

    @Composable private fun DictationComposerEntry() {
        val pack by app.daiyu.android.voice.offline.OfflineSpeechPack.state.collectAsStateWithLifecycle()
        val offline by offlineHost.state.collectAsStateWithLifecycle()
        val capturedGeneration = editorGeneration
        val capturedRevision = draftRevision
        val capturedConversation = selected?.id?.value
        val capturedVoiceEpoch = voiceRequestEpoch
        val capturedCascadeToken = cascadeVoice.operationToken
        val capturedRemoteToken = remoteVoice.operationToken
        val capturedSpeechOwner = speechHost.owner
        fun currentEditor() = activityUsable() && voiceForeground && retainedPage == "chat" &&
            !sidebarVisible && editorGeneration == capturedGeneration && draftRevision == capturedRevision &&
            selected?.id?.value == capturedConversation
        fun currentVoiceControls() = currentEditor() && voiceRequestEpoch == capturedVoiceEpoch &&
            cascadeVoice.operationToken == capturedCascadeToken && remoteVoice.operationToken == capturedRemoteToken &&
            speechHost.owner == capturedSpeechOwner
        if (engineeringUi) app.daiyu.android.ui.haptics.miuix.TextButton(text = "离线语音设置", modifier = Modifier.testTag("offline-speech-open"),
            onClick = { if (activityUsable() && retainedPage == "chat" && !sidebarVisible) {
                offlineSpeechReturnPage = "chat"
                retainedPage = "offline-speech"
            } })
        if (engineeringUi && pack.enabled) app.daiyu.android.ui.haptics.miuix.TextButton(
            text = if (offline.listening) "取消离线听写" else "离线听写（本机麦克风）",
            modifier = Modifier.testTag("offline-speech-microphone"),
            onClick = {
                if (activityUsable() && retainedPage == "chat" && !sidebarVisible && voiceForeground &&
                    editorGeneration == capturedGeneration && draftRevision == capturedRevision &&
                    selected?.id?.value == capturedConversation) startOfflineDictation()
            })
        app.daiyu.android.ui.components.DictationStatusNotice(
            app.daiyu.android.voice.ComposerDictationState(offline.target, offline.listening, status = offline.status),
            engineeringUi, tag = "offline-speech-status")
        if (engineeringUi && offline.preview.isNotEmpty()) top.yukonga.miuix.kmp.basic.Text(text = "离线听写（已保留在草稿）：${offline.preview}",
            modifier = Modifier.testTag("offline-speech-preview"))
        val cascadeStatus by cascadeVoice.status.collectAsStateWithLifecycle()
        val conversationVoiceConfig by voiceRuntime.conversationConfiguration.collectAsStateWithLifecycle()
        val dialogOwner = voiceConversationDialogOwner
        val showConversationDialog = currentEditor() && dialogOwner != null &&
            dialogOwner == ChatController.TextSendOwner(selected?.id, capturedGeneration, capturedRevision)
        ProductionUniversalVoicePanel(universalVoice, conversationVoiceConfig,
            configure = { if (currentEditor()) retainedPage = "voice-settings" },
            start = { profile, limits ->
                if (currentEditor() && voiceRequestEpoch == capturedVoiceEpoch && controller.ready && !controller.dataBusy)
                    startUniversalVoice(profile, limits)
            },
            openRequested = showConversationDialog,
            onCloseRequest = { if (voiceConversationDialogOwner == dialogOwner) voiceConversationDialogOwner = null },
            showEntry = engineeringUi,
            cascadeEntry = {
        ProductionPersonalCascadeConsent(
            host = cascadeVoice,
            isCurrent = {
                currentEditor() && voiceRequestEpoch == capturedVoiceEpoch &&
                    remoteVoice.operationToken == capturedRemoteToken && speechHost.owner == capturedSpeechOwner &&
                    controller.active == null && !universalVoice.state.value.active && voiceEditorCurrent(
                        DictationDraftTarget(capturedConversation, capturedGeneration, capturedRevision))
            },
            onFailure = { toast(it) },
        )
            })
        if (cascadeStatus.isNotEmpty()) {
            top.yukonga.miuix.kmp.basic.Text(cascadeStatus,
                modifier = Modifier.testTag("voice-cascade-status").semantics { liveRegion = LiveRegionMode.Polite })
            app.daiyu.android.ui.haptics.miuix.TextButton(text = "停止级联", modifier = Modifier.testTag("voice-cascade-stop"), onClick = {
                if (currentVoiceControls()) {
                    stopCascadeVoice(); invalidateConfiguredVoice(); dictationHost.invalidate(); offlineHost.invalidate()
                }
            })
        }
        val remoteStatus by remoteVoice.status.collectAsStateWithLifecycle()
        if (!engineeringUi && !remoteVoice.duplexActive) app.daiyu.android.ui.components.DictationStatusNotice(
            app.daiyu.android.voice.ComposerDictationState(target = remoteVoice.dictationTarget, status = remoteStatus), false)
        if (remoteStatus.isNotEmpty() && (engineeringUi || remoteVoice.duplexActive)) {
            top.yukonga.miuix.kmp.basic.Text(remoteStatus,
                modifier = Modifier.testTag("remote-voice-status").semantics { liveRegion = LiveRegionMode.Polite })
            app.daiyu.android.ui.haptics.miuix.TextButton(text = "停止云端语音", modifier = Modifier.testTag("remote-voice-stop"), onClick = {
                if (currentVoiceControls()) {
                    stopCascadeVoice(); invalidateConfiguredVoice(); speechHost.stop(capturedSpeechOwner)
                }
            })
        }
        val state by dictationHost.state.collectAsStateWithLifecycle()
        app.daiyu.android.ui.components.DictationStatusNotice(state, engineeringUi)
        if (engineeringUi && state.preview.isNotEmpty()) {
            top.yukonga.miuix.kmp.basic.Text(text = "识别预览（非最终草稿）：${state.preview}",
                modifier = Modifier.testTag("dictation-partial"))
        }
        if (engineeringUi && state.listening) {
            val target = state.target
            app.daiyu.android.ui.haptics.miuix.TextButton(text = "停止听写", modifier = Modifier.testTag("dictation-stop"),
                onClick = { if (dictationHost.state.value.target == target) dictationHost.invalidate() })
        }
    }

    @Composable private fun ShizukuComposerEntry() {
        val images by imageHost.session.attachments.state.collectAsStateWithLifecycle()
        val hasImages = images.items.isNotEmpty() || videoHost.state.items.isNotEmpty() || videoSending
        val generation = editorGeneration
        val revision = draftRevision
        val available = chatState.composer.enabled && activityUsable() && retainedPage == "chat" && !sidebarVisible && selectionReady &&
            controller.ready && !controller.dataBusy && controller.active == null &&
            !imageHost.busy && !shizukuWritesBlocked && !hasImages && draft.isNotBlank() && terminalEditor == null
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            if (engineeringUi) Row(Modifier.fillMaxWidth()) {
                app.daiyu.android.ui.haptics.miuix.TextButton(
                    text = if (hasImages) "Shizuku 仅限无附件文本" else "Shizuku 文本",
                    enabled = available && shizukuEditor == null,
                    modifier = Modifier.weight(1f).testTag("shizuku-text-entry").heightIn(min = 48.dp),
                    onClick = {
                        if (available && generation == editorGeneration && revision == draftRevision &&
                            !imageHost.hasAttachments && controller.shizukuPreparation() == null) {
                            shizukuEditor = ShizukuEditor(selected, generation, revision, draft)
                        }
                    })
                app.daiyu.android.ui.haptics.miuix.TextButton(text = "Shizuku 权限设置",
                    modifier = Modifier.weight(1f).testTag("shizuku-settings-entry").heightIn(min = 48.dp),
                    onClick = { if (activityUsable()) retainedPage = "shizuku-settings" })
            }
            if (engineeringUi) app.daiyu.android.ui.haptics.miuix.TextButton(
                text = if (mcpPreparing) "正在冻结 MCP 本轮范围" else "MCP 调用",
                enabled = available && !mcpPreparing && mcpApproval == null && pendingTextSend == null,
                modifier = Modifier.testTag("mcp-approval-entry").heightIn(min = 48.dp),
                onClick = ::openMcpApproval)
            mcpApproval?.let { pending ->
                val visit = mcpVisit
                app.daiyu.android.ui.McpApprovalHost(pending,
                    onCancel = { if (mcpVisit === visit && mcpApproval === pending) cancelMcpEditor() })
            }
            if (engineeringUi) app.daiyu.android.ui.haptics.miuix.TextButton(
                text = if (browserPreparing) "正在准备浏览器" else "浏览器操作",
                enabled = available && !browserPreparing && browserApproval == null && pendingTextSend == null,
                modifier = Modifier.testTag("browser-approval-entry").heightIn(min = 48.dp),
                onClick = ::openBrowserApproval)
            if (engineeringUi) app.daiyu.android.ui.haptics.miuix.TextButton(
                text = "图片 / 视频生成",
                enabled = available && pendingTextSend == null && generationApproval == null &&
                    !controller.generationSubmissionPending(),
                modifier = Modifier.testTag("generation-approval-entry").heightIn(min = 48.dp),
                onClick = { openGenerationApproval() })
            if (engineeringUi) app.daiyu.android.ui.haptics.miuix.TextButton(text = "GUI 精确动作审批（前台屏幕）",
                enabled = available && pendingTextSend == null && guiEditor == null,
                modifier = Modifier.testTag("gui-prepare-entry").heightIn(min = 48.dp),
                onClick = ::openGuiPreparation)
            if (engineeringUi) app.daiyu.android.ui.haptics.miuix.TextButton(text = "GUI 系统无障碍设置",
                modifier = Modifier.testTag("gui-settings-entry").heightIn(min = 48.dp),
                onClick = { if (activityUsable()) {
                    when (val result = app.daiyu.android.gui.GuiSystemSettings.openFromUser(this@MainActivity)) {
                        is app.daiyu.android.gui.GuiResult.Value -> Unit
                        is app.daiyu.android.gui.GuiResult.Rejected -> toast("无法打开系统无障碍设置：${result.code}")
                        is app.daiyu.android.gui.GuiResult.Unknown -> toast("系统设置结果未知，不会自动重试：${result.code}")
                    }
                } })
            guiEditor?.let { entry ->
                androidx.compose.material3.Text(if (entry.submitted) "GUI 已交给本次任务；Unknown 不重试，资源释放前不可再次批准。"
                    else "请切换到目标应用，在无障碍浮层读取并确认唯一真实目标。返回聊天或取消可撤销。")
                app.daiyu.android.ui.haptics.miuix.TextButton(text = "撤销 GUI 准备 / 停止原任务",
                    modifier = Modifier.testTag("gui-cancel"), onClick = { cancelGuiEditor(entry); publishChat() })
            }
            if (engineeringUi) app.daiyu.android.ui.haptics.miuix.TextButton(text = "副屏运行准备（需单独批准）",
                enabled = available && pendingTextSend == null && virtualDisplayEditor == null && virtualDisplayPlanVisit == null &&
                    controller.virtualDisplayEntry() == null,
                modifier = Modifier.testTag("vd-prepare-entry").heightIn(min = 48.dp),
                onClick = ::openVirtualDisplayPreparation)
            virtualDisplayEditor?.let { entry ->
                androidx.compose.material3.Text("已冻结 ${entry.writes.size} 项写步骤、${entry.reads.size} 项读取；消息受理后仍须审阅完整副屏授权。")
                app.daiyu.android.ui.haptics.miuix.TextButton(text = "接受消息并审阅完整副屏授权",
                    enabled = !entry.submitted && entry.taskSurface.ready(TaskSurface.BACKGROUND)?.selfTarget == null &&
                        entry.taskSurface.ready(TaskSurface.BACKGROUND) != null,
                    modifier = Modifier.testTag("vd-submit"),
                    onClick = { submitVirtualDisplay(entry) })
                app.daiyu.android.ui.haptics.miuix.TextButton(text = "停止副屏运行 / 取消准备",
                    modifier = Modifier.testTag("vd-cancel"), onClick = virtualDisplayCancelAction(entry, null))
            }
            controller.virtualDisplayEntry()?.let { originalEntry ->
                app.daiyu.android.ui.haptics.miuix.TextButton(text = "原副屏任务交付 / Release 收尾",
                    enabled = originalEntry.revoked && selected?.id == originalEntry.conversationId,
                    modifier = Modifier.testTag("vd-cleanup-entry"),
                    onClick = { openVirtualDisplayCleanup(originalEntry) })
                app.daiyu.android.ui.haptics.miuix.TextButton(text = "核实副屏停止（UNKNOWN 继续保留）",
                    modifier = Modifier.testTag("vd-observe-close"),
                    onClick = { controller.observeVirtualDisplayClose(originalEntry) })
            }
            if (engineeringUi) app.daiyu.android.ui.haptics.miuix.TextButton(text = "读取本会话生成资产",
                modifier = Modifier.testTag("generation-output-entry").heightIn(min = 48.dp),
                onClick = ::openGenerationOutputs)
            if (engineeringUi) app.daiyu.android.ui.haptics.miuix.TextButton(text = "保存本会话浏览器下载原件",
                modifier = Modifier.testTag("browser-download-output-entry").heightIn(min = 48.dp),
                onClick = ::openBrowserDownloadOutputs)
            BrowserDownloadOutputsSurface()
            TerminalComposerEntry()
            // AC037 dedicated user-initiated actions; no default model WRITE advertisement.
            PlatformActionComposerEntry(available && !hasImages)
            GenerationSurfaces()
            browserApproval?.let { pending ->
                val observedState by produceState(pending.state, pending) {
                    while (true) {
                        value = pending.state
                        kotlinx.coroutines.delay(100)
                    }
                }
                val childApprovalReady by produceState(pending.childPlan?.confirmed != false, pending) {
                    while (true) {
                        value = pending.childPlan?.confirmed != false
                        kotlinx.coroutines.delay(100)
                    }
                }
                BrowserApprovalHost(
                    draft = BrowserDraftSnapshot(BrowserDraftOwner(selected?.id, editorGeneration, draftRevision), draft),
                    assistant = pending.assistant, settings = pending.settings,
                    preparation = pending, state = observedState,
                    onPrepare = { _, _ -> false },
                    onConfirm = { confirmed, displayed, current ->
                        if (!activityUsable() || browserApproval !== confirmed || current != confirmed.draft ||
                            imageHost.hasAttachments || videoHost.state.items.isNotEmpty() || pendingTextSend != null || !confirmed.confirm(displayed, current)) false
                        else {
                            val owner = ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision)
                            val submitted = PendingTextSend(owner, draft)
                            pendingTextSend = submitted
                            val queued = controller.sendBrowser(confirmed, current, submitted.ticket) { result ->
                                if (browserApproval === confirmed) browserApproval = null
                                completeTextSend(submitted, result)
                            }
                            if (!queued) { pendingTextSend = null; controller.rejectBrowser(confirmed) }
                            queued
                        }
                    }, onCancel = { if (browserApproval === it) cancelBrowserEditor() },
                    problem = controller.problem,
                    childApprovalReady = childApprovalReady,
                    childApproval = { ChildBrowserApprovalHost(pending, controller::prepareBrowserChild) })
            }
            if (controller.browserChildPlans().isNotEmpty()) {
                app.daiyu.android.ui.haptics.miuix.TextButton(text = "停止工具型浏览器子任务（UNKNOWN 保留）",
                    modifier = Modifier.testTag("child-browser-stop"), onClick = controller::stopBrowserChildren)
            }
            val editor = shizukuEditor
            if (editor != null && !hasImages) key(editor) {
                Column(Modifier.testTag("shizuku-text-panel")) {
                    ShizukuApprovalHost(controller, editor.conversation, editor.text,
                        isCurrent = { shizukuEditorCurrent(editor) },
                        onAccepted = { preview, saved -> acceptShizuku(editor, preview, saved) })
                    app.daiyu.android.ui.haptics.miuix.TextButton(text = "取消 Shizuku 命令",
                        modifier = Modifier.testTag("shizuku-text-cancel"), onClick = ::cancelShizukuEditor)
                }
            }
        }
    }

    // AC037 isolated composer integration: compare the ORIGINAL owner/revision before approval
    // and before editor clearing. The database consumes that exact draft in sendPrepared's CAS.
    private var platformActionVisit by mutableStateOf(Any())
    private fun revokePlatformActionVisit() {
        platformActionVisit = Any()
        controller.platformActionPreparation()?.let(controller::rejectPlatformAction)
    }
    private fun platformActionCurrent(visit: Any, expected: ChatController.TextSendOwner, text: String): Boolean =
        platformActionVisit === visit && voiceForeground && activityUsable() && retainedPage == "chat" && !sidebarVisible && selectionReady &&
            ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision) == expected &&
            draft == text && !imageHost.hasAttachments && videoHost.state.items.isEmpty() &&
            !videoSending && pendingTextSend == null && shizukuEditor == null &&
            browserApproval == null && generationApproval == null && terminalEditor == null &&
            mcpApproval == null && !mcpPreparing && guiEditor == null && virtualDisplayEditor == null

    @Composable private fun PlatformActionComposerEntry(available: Boolean) {
        val visit = platformActionVisit
        val owner = ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision)
        val originalText = draft
        val current: (ChatController.TextSendOwner, String) -> Boolean = remember(visit) {
            { expected, value -> platformActionCurrent(visit, expected, value) }
        }
        app.daiyu.android.ui.PlatformActionApprovalHost(controller, selected, originalText, owner, visit,
            enabled = available && current(owner, originalText), isCurrent = current, showEntry = engineeringUi,
            onAccepted = { preview, saved ->
                if (current(owner, originalText) && preview.conversation?.id == owner.conversationId &&
                    preview.text == originalText && saved.id == preview.conversationId) {
                    selected = saved; editorGeneration++; draftRevision++
                    draft = ""; selectionStart = 0; selectionEnd = 0
                    controller.preferences.edit().putString("selected", saved.id.value).apply()
                    publishChat(); loadAcceptedHistory(saved)
                }
            })
    }

    private fun terminalDraft() = TerminalDraftSnapshot(
        TerminalDraftOwner(selected?.id, editorGeneration, draftRevision), draft)

    private fun terminalEditorCurrent(editor: TerminalEditor): Boolean = activityUsable() &&
        terminalEditor === editor && retainedPage == "chat" && !sidebarVisible && selectionReady &&
        terminalDraft() == editor.draft && !imageHost.hasAttachments &&
        videoHost.state.items.isEmpty() && !videoSending && pendingTextSend == null &&
        shizukuEditor == null && browserApproval == null && generationApproval == null

    private fun terminalInputEditable(editor: TerminalEditor) = terminalEditorCurrent(editor) &&
        !editor.preparing && !editor.choosing && editor.preparation == null && editor.jobPreparation == null

    private fun cancelTerminalEditor() {
        val editor = terminalEditor
        terminalEditor = null
        editor?.preparation?.let(controller::rejectTerminal)
        editor?.jobPreparation?.let(controller::rejectTerminalJob)
        // A late prepare completion still belongs to this editor and rejects itself in finally.
    }

    @Composable private fun TerminalComposerEntry() {
        val appState by terminalProvider.state.collectAsStateWithLifecycle()
        val application = (appState as? app.daiyu.android.terminal.TerminalApplicationState.Ready)?.application
        val canEnter = activityUsable() && retainedPage == "chat" && !sidebarVisible && selectionReady &&
            controller.ready && !controller.dataBusy && controller.active == null && !imageHost.busy &&
            !imageHost.hasAttachments && videoHost.state.items.isEmpty() && !videoSending &&
            !shizukuWritesBlocked && pendingTextSend == null && controller.textSendState() == null && draft.isNotBlank() && terminalEditor == null &&
            shizukuEditor == null && browserApproval == null && !browserPreparing && generationApproval == null
        val renderedDraft = terminalDraft()
        if (engineeringUi) app.daiyu.android.ui.haptics.miuix.TextButton(text = "终端命令",
            enabled = canEnter, modifier = Modifier.testTag("terminal-chat-entry"), onClick = {
                if (canEnter && activityUsable() && retainedPage == "chat" && !sidebarVisible &&
                    renderedDraft == terminalDraft() && terminalEditor == null && !imageHost.hasAttachments &&
                    videoHost.state.items.isEmpty() && !videoSending && pendingTextSend == null && shizukuEditor == null && browserApproval == null && generationApproval == null) {
                    cancelBrowserEditor(); cancelShizukuEditor(); dictationHost.invalidate()
                    terminalEditor = TerminalEditor(selected, terminalDraft())
                }
            })
        val editor = terminalEditor ?: return
        key(editor) {
            Column(Modifier.fillMaxWidth().testTag("terminal-chat-editor")) {
                androidx.compose.material3.Text(if (editor.jobMode == null)
                    "聊天草稿不是 shell。PTY仅发送下面独立输入区的 UTF-8 字节；不补回车。需要执行时请在输入中明确换行。点击运行后准备并提交任务。"
                    else "固定命令 job：独立 shell 使用私有 stdin 脚本（末尾补换行），不保留跨命令变量或 cwd；聊天草稿不是命令。async 随 run 关闭请求停止；daemon 仅 Android 应用身份通过 setsid，在同 app 进程内独立于 run，不承诺跨重启、后代退出或长期存活。")
                Row {
                    listOf(null to "PTY", "async" to "async job", "daemon" to "daemon job").forEach { (mode, label) ->
                        app.daiyu.android.ui.haptics.material.TextButton(enabled = terminalInputEditable(editor) &&
                            (mode != "daemon" || editor.linux == null), onClick = {
                            if (terminalInputEditable(editor) && (mode != "daemon" || editor.linux == null)) editor.jobMode = mode
                        }, modifier = Modifier.testTag("terminal-mode-${mode ?: "pty"}")) {
                            androidx.compose.material3.Text("${if (editor.jobMode == mode) "已选 " else ""}$label")
                        }
                    }
                }
                androidx.compose.material3.OutlinedTextField(value = editor.command,
                    onValueChange = { if (terminalInputEditable(editor)) editor.command = it },
                    enabled = terminalInputEditable(editor),
                    label = { androidx.compose.material3.Text("精确终端命令（包含所需换行）") },
                    modifier = Modifier.managedInput().fillMaxWidth().testTag("terminal-exact-command"),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(cursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.primary), errorCursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.error)),
                )
                val canChoose = terminalInputEditable(editor)
                app.daiyu.android.ui.haptics.material.TextButton(enabled = canChoose, onClick = {
                    if (terminalInputEditable(editor)) editor.linux = null
                }, modifier = Modifier.testTag("terminal-chat-android")) {
                    androidx.compose.material3.Text(if (editor.linux == null) "已选 Android（应用 UID）" else "选择 Android（应用 UID）")
                }
                if (application != null) {
                    val host = application.terminalOwner.host
                    val status by host.state.collectAsStateWithLifecycle()
                    LaunchedEffect(application) { host.refreshLinux() }
                    status.linux.choices.forEach { choice ->
                        app.daiyu.android.ui.haptics.material.TextButton(enabled = canChoose && editor.jobMode != "daemon", onClick = {
                            if (terminalInputEditable(editor) && editor.jobMode != "daemon") {
                                // Explicit selection only; prepare itself never changes environment.
                                editor.choosing = true
                                host.selectLinux(choice.reference).whenComplete { result, failure ->
                                    runOnUiThread {
                                        if (terminalEditor === editor) {
                                            editor.choosing = false
                                            if (failure == null && result?.ok == true) editor.linux = choice.reference
                                            else toast("Linux 选择未确认；未准备或启动终端")
                                        }
                                    }
                                }
                            }
                        }) { androidx.compose.material3.Text(
                            "${if (editor.linux == choice.reference) "已选" else "选择"}可信 Linux：${choice.label}") }
                    }
                    if (status.linux.choices.isEmpty()) androidx.compose.material3.Text("无已安装可信 Linux；不会自动下载或安装。")
                }
                val pending = editor.preparation
                val jobPending = editor.jobPreparation
                if (pending == null && jobPending == null) {
                    app.daiyu.android.ui.haptics.material.Button(enabled = terminalInputEditable(editor) && editor.command.isNotEmpty(),
                        modifier = Modifier.testTag("terminal-prepare"), onClick = { prepareTerminal(editor) }) {
                        androidx.compose.material3.Text(if (editor.preparing) "准备中" else "运行终端任务")
                    }
                } else {
                    androidx.compose.material3.Text("正在提交终端任务")
                }
                app.daiyu.android.ui.haptics.material.TextButton(onClick = { if (terminalEditor === editor) cancelTerminalEditor() },
                    modifier = Modifier.testTag("terminal-chat-cancel")) { androidx.compose.material3.Text("取消终端任务") }
            }
        }
    }

    private fun prepareTerminalJob(editor: TerminalEditor) {
        val mode = editor.jobMode ?: return
        val command = editor.command
        val linux = editor.linux
        editor.preparing = true
        lifecycleScope.launch {
            var prepared: ControllerTerminalJobPreparation? = null
            var retained = false
            try {
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                    prepared = controller.prepareTerminalJobSend(editor.conversation, editor.draft, command, mode, linux)
                }
                if (terminalEditorCurrent(editor) && editor.command == command && editor.linux == linux && editor.jobMode == mode) {
                    editor.jobPreparation = prepared
                    retained = prepared != null
                    if (!retained) toast(controller.problem ?: "job 准备未受理；未启动")
                    else sendTerminalJob(editor, checkNotNull(prepared))
                }
            } finally {
                if (!retained) prepared?.let(controller::rejectTerminalJob)
                editor.preparing = false
            }
        }
    }
    private fun sendTerminalJob(editor: TerminalEditor, pending: ControllerTerminalJobPreparation) {
        if (!terminalEditorCurrent(editor) || editor.jobPreparation !== pending) return
        val submitted = PendingTextSend(ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision), draft)
        pendingTextSend = submitted
        val queued = controller.sendTerminalJob(pending, terminalDraft(), submitted.ticket) { result ->
            if (terminalEditor === editor) terminalEditor = null
            completeTextSend(submitted, result)
        }
        if (!queued) { pendingTextSend = null; cancelTerminalEditor(); toast("job 发送未受理；不自动重发") }
        publishChat()
    }

    private class TerminalJobsVisit(val conversation: ConversationId?, val editorGeneration: Long,
        sessions: List<app.daiyu.android.integration.tools.TerminalJobSession>, val terminalPage: Any) {
        var sessions by mutableStateOf(sessions)
        var history by mutableStateOf(emptyList<app.daiyu.android.terminal.TerminalJobHistoryReader.Record>())
        var historyError by mutableStateOf<String?>(null)
        var historyPage by mutableStateOf<app.daiyu.android.terminal.TerminalJobHistoryReader.Record?>(null)
        var nextOffset by mutableStateOf(0L)
        var hasMore by mutableStateOf(false)
        var output by mutableStateOf("")
        var outputId by mutableStateOf<String?>(null)
        var taskStatus by mutableStateOf(emptyMap<String, String>())
        var busy by mutableStateOf(false)
        val revoked = java.util.concurrent.atomic.AtomicBoolean(false)
    }
    private var terminalJobsVisit by mutableStateOf<TerminalJobsVisit?>(null)
    private fun revokeTerminalJobsVisit() {
        terminalJobsVisit?.revoked?.set(true)
        terminalJobsVisit = null
    }
    private fun terminalJobsPresented(visit: TerminalJobsVisit) = activityUsable() &&
        retainedPage == "terminal" && terminalPageOwner === visit.terminalPage && !sidebarVisible &&
        terminalJobsVisit === visit && !visit.revoked.get() &&
        selected?.id == visit.conversation && editorGeneration == visit.editorGeneration
    private fun terminalJobsCurrent(visit: TerminalJobsVisit) = terminalJobsPresented(visit) && voiceForeground
    private fun openTerminalTasks(page: Any) {
        val conversation = selected?.id
        if (!terminalPageCurrent(page)) return
        revokeTerminalJobsVisit()
        val visit = TerminalJobsVisit(conversation, editorGeneration, conversation?.let(controller::terminalJobRuns).orEmpty(), page)
        terminalJobsVisit = visit
        refreshTerminalJobs(visit)
    }
    @Composable private fun TerminalTasksSurface() {
        val visit = terminalJobsVisit?.takeIf { terminalJobsPresented(it) } ?: return
        val lease = ordinaryDialogLease
        key(visit) {
            app.daiyu.android.ui.screens.terminal.TerminalTasksDialog(
                busy = visit.busy,
                rows = visit.sessions.map { session -> app.daiyu.android.ui.screens.terminal.TerminalTaskRow(
                    session.plan.command, terminalTaskSummary(session, visit.taskStatus[session.plan.runId.value]),
                    id = session.plan.runId.value,
                    onRead = { if (dialogActionsCurrent(lease) && terminalJobsCurrent(visit)) terminalJobAction(visit, session, false) },
                    onStop = if (session.jobId == null) null else ({ if (dialogActionsCurrent(lease) && terminalJobsCurrent(visit)) terminalJobAction(visit, session, true) }))
                } + visit.history.map { record -> app.daiyu.android.ui.screens.terminal.TerminalTaskRow(
                    record.command ?: record.jobId, "状态未知", id = record.jobId,
                    onRead = { if (dialogActionsCurrent(lease) && terminalJobsCurrent(visit)) terminalHistoryPage(visit, record, 0) }) },
                output = app.daiyu.android.ui.screens.terminal.terminalTaskOutput(visit.output),
                outputId = visit.outputId,
                error = visit.historyError,
                onDismiss = { if (dialogActionsCurrent(lease) && terminalJobsVisit === visit) revokeTerminalJobsVisit() })
        }
    }
    private fun terminalTaskSummary(session: app.daiyu.android.integration.tools.TerminalJobSession, state: String?): String {
        val environment = session.plan.configuration.linux?.label ?: "Android"
        val identity = if (session.plan.configuration.root) "root" else "user"
        val label = when (state) { "running" -> "运行中"; "exited", "stopped" -> "已退出"; else -> "状态未知" }
        return "$environment · $identity · $label"
    }
    private fun refreshTerminalJobs(visit: TerminalJobsVisit) {
        if (!terminalJobsCurrent(visit) || visit.busy) return
        val conversation = visit.conversation ?: run {
            visit.sessions = emptyList(); visit.history = emptyList(); visit.historyPage = null
            visit.hasMore = false; visit.nextOffset = 0; visit.output = ""; visit.historyError = null
            return
        }
        val lease = ordinaryDialogLease ?: return
        visit.busy = true
        visit.historyError = null
        visit.historyPage = null; visit.hasMore = false; visit.nextOffset = 0
        lifecycleScope.launch {
            try {
                val (snapshot, statuses) = withContext(Dispatchers.IO) {
                    val snapshot = controller.terminalJobsOnWorker(conversation) { lease.isActive && !visit.revoked.get() }
                    snapshot to snapshot.sessions.associate { session -> session.plan.runId.value to
                        controller.inspectTerminalJobOnWorker(conversation, session).optString("state") }
                }
                if (terminalJobsPresented(visit)) {
                    visit.taskStatus = statuses
                    visit.sessions = snapshot.sessions
                    visit.history = snapshot.history
                    visit.output = ""; visit.outputId = null
                    visit.historyError = snapshot.error?.let { "无法读取任务记录" }
                }
            } catch (_: Exception) {
                if (terminalJobsPresented(visit)) {
                    visit.historyError = "无法读取任务记录"
                }
            } finally { visit.busy = false }
        }
    }
    private fun terminalHistoryPage(visit: TerminalJobsVisit,
        record: app.daiyu.android.terminal.TerminalJobHistoryReader.Record, offset: Long) {
        if (!terminalJobsCurrent(visit) || visit.busy || record !in visit.history) return
        val conversation = visit.conversation ?: return
        visit.outputId = record.jobId; visit.output = ""
        val lease = ordinaryDialogLease ?: return
        visit.busy = true
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val result = withContext(Dispatchers.IO) {
                    val head = controller.terminalJobHistoryPageOnWorker(conversation, record, offset, 1) { lease.isActive && !visit.revoked.get() }
                    if (!head.optBoolean("ok")) head else controller.terminalJobHistoryPageOnWorker(conversation, record,
                        (head.optLong("total_bytes") - 65536).coerceAtLeast(0), 65536) { lease.isActive && !visit.revoked.get() }
                }
                if (terminalJobsPresented(visit)) {
                    visit.output = result.toString(2) // Replace one bounded page; never accumulate the whole log.
                    visit.outputId = record.jobId
                    visit.historyPage = record
                    visit.nextOffset = result.optLong("next_offset", offset)
                    visit.hasMore = result.optBoolean("ok") && result.optBoolean("has_more")
                }
            } catch (_: Exception) {
                if (terminalJobsPresented(visit)) {
                    visit.output = "无法读取日志"
                    visit.hasMore = false
                }
            } finally { visit.busy = false }
        }
    }
    private fun terminalJobAction(visit: TerminalJobsVisit,
        session: app.daiyu.android.integration.tools.TerminalJobSession, stop: Boolean) {
        if (!terminalJobsCurrent(visit) || visit.busy || session !in visit.sessions) return
        val conversation = visit.conversation ?: return
        visit.historyPage = null; visit.hasMore = false; visit.nextOffset = 0
        if (!stop) { visit.outputId = session.plan.runId.value; visit.output = "" }
        val lease = ordinaryDialogLease ?: return
        visit.busy = true
        lifecycleScope.launch {
            var stopped = false
            try {
                val result = withContext(Dispatchers.IO) {
                    // Recheck UI capability before worker admission; the original session/owner is immutable.
                    if (stop) controller.stopTerminalJobOnWorker(conversation, session) { lease.isActive && !visit.revoked.get() }
                    else {
                        check(lease.isActive && !visit.revoked.get())
                        controller.terminalJobLogOnWorker(conversation, session)
                    }
                }
                if (terminalJobsPresented(visit)) {
                    stopped = stop && result.optBoolean("ok") && result.optBoolean("stopped")
                    if (!stop) visit.output = result.toString(2)
                    else if (!result.optBoolean("ok") || !result.optBoolean("stopped")) visit.historyError =
                        result.optString("error").ifBlank { "任务尚未确认停止" }
                    visit.taskStatus = visit.taskStatus + (session.plan.runId.value to result.optString("state"))
                }
            } catch (_: Exception) {
                if (terminalJobsPresented(visit)) {
                    if (stop) visit.historyError = "任务操作未确认" else visit.output = "任务操作未确认"
                }
            } finally {
                visit.busy = false
                if (stopped && terminalJobsCurrent(visit)) refreshTerminalJobs(visit)
            }
        }
    }

    private fun prepareTerminal(editor: TerminalEditor) {
        if (!terminalInputEditable(editor)) return
        if (editor.jobMode != null) return prepareTerminalJob(editor)
        val command = editor.command
        val linux = editor.linux
        editor.preparing = true
        lifecycleScope.launch {
            var prepared: ControllerTerminalPreparation? = null
            var retained = false
            try {
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                    prepared = controller.prepareTerminalSend(editor.conversation, editor.draft, command, linux)
                }
                if (terminalEditorCurrent(editor) && editor.command == command && editor.linux == linux) {
                    editor.preparation = prepared
                    retained = prepared != null
                    if (!retained) toast(controller.problem ?: "终端准备未受理；草稿保留")
                    else sendTerminal(editor, checkNotNull(prepared))
                }
            } finally {
                if (!retained) prepared?.let(controller::rejectTerminal)
                editor.preparing = false
            }
        }
    }

    private fun sendTerminal(editor: TerminalEditor, pending: ControllerTerminalPreparation) {
        if (!terminalEditorCurrent(editor) || editor.preparation !== pending) return
        val configuration = runCatching { controller.terminalConfiguration(editor.conversation) }.getOrNull()
            ?: return cancelTerminalEditor()
        val current = terminalDraft()
        val submitted = PendingTextSend(ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision), draft)
        pendingTextSend = submitted
        val queued = controller.sendTerminal(pending, pending.preview, current, configuration.first,
            configuration.second, submitted.ticket) { result ->
            if (terminalEditor === editor) terminalEditor = null
            completeTextSend(submitted, result)
        }
        if (!queued) { pendingTextSend = null; cancelTerminalEditor(); toast("终端发送未受理；草稿保留，不自动重发") }
        publishChat()
    }

    private fun cancelGenerationEditor() {
        if (generationInputsDelegate.isInitialized()) generationInputs.invalidate()
        generationVisit = null
        generationRequest = null
        val entry = generationApproval
        generationApproval = null
        if (entry != null && !entry.submitted) controller.abandonGeneration(entry)
    }

    private fun openGenerationApproval(kind: app.daiyu.android.model.generation.GenerationKind? = null) {
        if (videoHost.state.items.isNotEmpty() || videoSending) return
        if (!activityUsable() || retainedPage != "chat" || sidebarVisible || !selectionReady ||
            pendingTextSend != null || imageHost.hasAttachments || draft.isBlank() ||
            controller.generationSubmissionPending()) return
        cancelGenerationEditor(); cancelBrowserEditor(); cancelShizukuEditor()
        val request = Any()
        generationRequest = request
        val generation = editorGeneration
        val revision = draftRevision
        val text = draft
        val conversation = selected
        lifecycleScope.launch {
            var entry: ChatController.GenerationEntry? = null
            var retained = false
            try {
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                    entry = controller.prepareGeneration(conversation, generation,
                        GenerationEditorSnapshot(revision, text))
                }
                if (activityUsable() && generationRequest === request && generation == editorGeneration &&
                    revision == draftRevision && text == draft && conversation?.id == selected?.id &&
                    retainedPage == "chat" && !sidebarVisible && !imageHost.hasAttachments && videoHost.state.items.isEmpty()) {
                    generationKind = kind
                    generationApproval = entry
                    generationVisit = Any()
                    generationForm = app.daiyu.android.ui.GenerationApprovalForm(prompt = text,
                        maxPolls = entry?.settings?.maxPolls?.toString() ?: "4")
                    generationFormRevision = revision
                    retained = entry != null
                    if (!retained) toast(controller.problem ?: "生成入口未受理；草稿保留")
                }
            } finally {
                if (!retained) entry?.let(controller::abandonGeneration)
                if (generationRequest === request) generationRequest = null
            }
        }
    }

    // Mount outside chat/composer surfaces: a replacement foreground can inspect the exact
    // locator without returning to the original conversation, draft or approval editor.
    @Composable private fun ChildStopChoiceSurface() {
        val choice = childStopChoice ?: return
        if (!voiceForeground) return
        fun finish(disposition: app.daiyu.android.domain.delegation.ChildTaskStopDisposition) {
            if (!activityUsable()) return
            controller.finishChildStopChoice(choice, disposition)
            refresh()
        }
        app.daiyu.android.ui.components.OptionSelectionSheet(
            title = "主回复已停止",
            onDismiss = { finish(app.daiyu.android.domain.delegation.ChildTaskStopDisposition.PAUSE) }) {
            app.daiyu.android.ui.components.OptionSelectionItem(
                "继续子代理", selected = false, modifier = Modifier.testTag("main-stop-continue-children"),
                onClick = { finish(app.daiyu.android.domain.delegation.ChildTaskStopDisposition.CONTINUE) })
            app.daiyu.android.ui.components.OptionSelectionItem(
                "暂停子代理", selected = true, modifier = Modifier.testTag("main-stop-keep-children"),
                onClick = { finish(app.daiyu.android.domain.delegation.ChildTaskStopDisposition.PAUSE) })
            app.daiyu.android.ui.components.OptionSelectionItem(
                "停止子代理", selected = false, modifier = Modifier.testTag("main-stop-cancel-children"),
                onClick = { finish(app.daiyu.android.domain.delegation.ChildTaskStopDisposition.STOP) })
        }
    }

    @Composable private fun UnknownBranchSurface() {
        unknownBranchOutcome?.takeIf { voiceForeground }?.let { receipt ->
            val locator = checkNotNull(receipt.locator)
            // Back/outside clicks do not silently consume a controller-owned UNKNOWN receipt.
            app.daiyu.android.ui.components.DaiyuAlertDialog(onDismissRequest = {},
                title = { androidx.compose.material3.Text("分支结果未确认") },
                text = { androidx.compose.material3.Text("目标会话：${locator.conversationId.value}\n静态身份：${locator.staticIdentities.joinToString()}\n只读核实不会重新分支、生成或清理未知文件。此提示仅在当前进程保留，进程重启后请手动核实历史，不要重试。") },
                confirmButton = { app.daiyu.android.ui.haptics.material.TextButton(modifier = Modifier.testTag("branch-unknown-inspect"), onClick = {
                    if (activityUsable() && voiceForeground && controller.pendingBranchOutcome() === receipt) {
                        if (!controller.inspectBranch(locator) { verified ->
                            if (activityUsable() && voiceForeground && unknownBranchOutcome === receipt) toast(if (verified)
                                "目标会话及静态原件已核实；未重试" else "目标尚不能完整核实；保留UNKNOWN，不要重试")
                        }) toast("请等待当前数据操作完成；UNKNOWN仍保留，未重试")
                    }
                }) { androidx.compose.material3.Text("只读核实") } },
                dismissButton = { app.daiyu.android.ui.haptics.material.TextButton(modifier = Modifier.testTag("branch-unknown-dismiss"),
                    onClick = { dismissUnknownBranchOutcome(receipt) }) {
                    androidx.compose.material3.Text("关闭（不重试）")
                } })
        }
    }

    @Composable private fun GenerationSurfaces() {
        generationApproval?.let { entry ->
            app.daiyu.android.ui.components.DaiyuDialog(onDismissRequest = ::cancelGenerationEditor) {
                androidx.compose.material3.Surface {
                    Column(Modifier.fillMaxWidth().heightIn(max = 620.dp)
                        .verticalScroll(rememberScrollState()).padding(16.dp)) {
                        androidx.compose.material3.Text(ControllerGenerationResources.DOWNLOAD_STATUS)
                        androidx.compose.material3.Text("原图 / mask 仅本地导入原字节（最多20MiB）；选择文件或保存配置不会发送请求。")
                        if (generationInputPending) {
                            androidx.compose.material3.Text("正在选择或校验输入；本次没有可执行的付费确认，请返回后重新完整预览。")
                        } else app.daiyu.android.ui.GenerationApprovalHost(
                            identity = entry.identity,
                            editor = generationFormEditor(),
                            profiles = entry.settings.profiles.filter { generationKind?.accepts(it.protocol) != false },
                            mediaKind = generationKind,
                            downloads = entry.settings.downloads,
                            form = generationForm,
                            confirmationHandoff = entry.handoff,
                            onFormChange = { value ->
                                if (!entry.submitted && generationResumed()) {
                                    if (generationInputsDelegate.isInitialized()) generationInputs.invalidate()
                                    generationForm = value; generationFormRevision++
                                }
                            },
                            onPickReference = { pickGenerationInput(GenerationInputTarget.REFERENCE) },
                            onPickMask = { pickGenerationInput(GenerationInputTarget.MASK) },
                            onConfirmed = {
                                val valid = generationResumed() && !generationInputPending &&
                                    generationInputIdentity()?.entry === entry
                                val profile = entry.settings.profiles.singleOrNull { it.id == generationForm.profileId }
                                val accepted = valid && profile != null && generationKind?.accepts(profile.protocol) != false &&
                                    controller.sendGeneration(entry, generationFormEditor())
                                generationVisit = null
                                generationApproval = null
                                if (!accepted) controller.abandonGeneration(entry)
                                if (accepted) publishChat()
                                else toast("本次生成未受理；草稿保留，请等待清理后重新预览")
                                accepted
                            },
                            onCancelled = ::cancelGenerationEditor)
                        app.daiyu.android.ui.haptics.material.TextButton(onClick = ::cancelGenerationEditor) {
                            androidx.compose.material3.Text("关闭生成表单")
                        }
                    }
                }
            }
        }
        generationOutputs?.takeIf { generationOutputsPresented() }?.let { outputs ->
            val lease = ordinaryDialogLease
            val request = generationOutputsRequest
            val close = { if (dialogActionsCurrent(lease) && generationOutputsRequest === request) cancelGenerationOutputs() }
            val originalStaticVisit = staticGenerationVisit
            app.daiyu.android.ui.components.DaiyuAlertDialog(onDismissRequest = close,
                title = { androidx.compose.material3.Text("生成产物") },
                text = {
                    Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                        if (outputs.isEmpty()) androidx.compose.material3.Text("暂无产物")
                        outputs.forEach { output ->
                            val meta = output.metadata
                            androidx.compose.material3.Text("${meta.kind} · ${meta.mimeType} · ${meta.bytes} bytes · ${meta.width}×${meta.height}")
                            output.thumbnail?.let { bitmap ->
                                androidx.compose.foundation.Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "已校验的生成图片", modifier = Modifier.fillMaxWidth())
                            }
                        }
                        androidx.compose.material3.Text("分支产物")
                        if (staticGenerationOutputs == null) androidx.compose.material3.Text("正在加载…")
                        staticGenerationOutputs?.forEach { display ->
                            val source = display.output.source
                            androidx.compose.material3.Text("静态 ${source.kind} · ${source.mimeType} · ${source.bytes} bytes · ${source.width}×${source.height}")
                            display.thumbnail?.let { bitmap -> androidx.compose.foundation.Image(bitmap = bitmap.asImageBitmap(),
                                contentDescription = "分支静态生成图片", modifier = Modifier.fillMaxWidth()) }
                            app.daiyu.android.ui.haptics.material.TextButton(onClick = { if (dialogActionsCurrent(lease)) backupGuard { pickStaticGenerationOriginal(display.output, originalStaticVisit) } }) {
                                androidx.compose.material3.Text("保存静态原件")
                            }
                        }
                        androidx.compose.material3.Text("历史产物")
                        val historical = historicalGenerationOutputs
                        if (historical == null) androidx.compose.material3.Text("正在加载…")
                        else if (historical.isEmpty()) androidx.compose.material3.Text("暂无历史产物")
                        historical?.forEach { display ->
                            val history = display.history
                            androidx.compose.material3.Text("历史 ${history.kind} · ${history.mimeType} · ${history.bytes} bytes · ${history.width}×${history.height}")
                            display.thumbnail?.let { bitmap ->
                                androidx.compose.foundation.Image(bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "备份中的历史生成图片", modifier = Modifier.fillMaxWidth())
                            }
                            app.daiyu.android.ui.haptics.material.TextButton(onClick = {
                                if (dialogActionsCurrent(lease)) backupGuard { pickHistoricalGenerationOriginal(history) }
                            }) { androidx.compose.material3.Text("保存历史原件") }
                        }
                    }
                }, confirmButton = { app.daiyu.android.ui.haptics.material.TextButton(onClick = close) {
                    androidx.compose.material3.Text("关闭")
                } })
        }
    }

    private fun cancelBrowserDownloadVisit(preservePresentation: Boolean = false) {
        browserDownloadVisit?.active?.set(false)
        browserDownloadVisit = null
        if (!preservePresentation) browserDownloadOutputs = null
        pendingBrowserDownload?.let { selection ->
            // Do not clear another feature's picker or recycle this request code.
            if (pendingBackupRequest == selection.requestCode) pendingBackupRequest = null
        }
        pendingBrowserDownload = null
    }

    private fun browserDownloadPresented(listing: BrowserDownloadListing) = activityUsable() &&
        retainedPage == "chat" && !sidebarVisible && selected?.id == listing.visit.conversation &&
        editorGeneration == listing.visit.generation
    private fun resumeOutputPresentations() {
        if (generationOutputsOwner != null) {
            if (generationOutputsPresented()) reloadGenerationOutputs() else cancelGenerationOutputs()
        }
        browserDownloadOutputs?.let { listing ->
            if (browserDownloadPresented(listing)) openBrowserDownloadOutputs() else cancelBrowserDownloadVisit()
        }
    }
    private fun browserDownloadVisitCurrent(visit: BrowserDownloadVisit) =
        visit.active.get() && browserDownloadVisit === visit && activityUsable() &&
            selected?.id == visit.conversation && editorGeneration == visit.generation &&
            selectionReady && retainedPage == "chat" && !sidebarVisible

    private fun openBrowserDownloadOutputs() {
        val conversation = selected?.id ?: return
        if (!activityUsable() || !selectionReady || retainedPage != "chat" || sidebarVisible) return
        val visit = browserDownloadVisit?.takeIf(::browserDownloadVisitCurrent) ?: run {
            cancelBrowserDownloadVisit()
            BrowserDownloadVisit(conversation, editorGeneration).also { browserDownloadVisit = it }
        }
        browserDownloadOutputs = BrowserDownloadListing(visit, controller.completedBrowserDownloads(conversation))
    }

    @Composable private fun BrowserDownloadOutputsSurface() {
        browserDownloadOutputs?.takeIf(::browserDownloadPresented)?.let { listing ->
            val lease = ordinaryDialogLease
            val close = { if (dialogActionsCurrent(lease) && browserDownloadOutputs === listing) cancelBrowserDownloadVisit() }
            app.daiyu.android.ui.components.DaiyuAlertDialog(onDismissRequest = close,
                title = { androidx.compose.material3.Text("浏览器已完成下载原件") },
                text = {
                    Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                        if (listing.references.isEmpty()) androidx.compose.material3.Text("暂无下载")
                        listing.references.forEach { reference ->
                            androidx.compose.material3.Text("${reference.lease.owner.kind} · ${reference.size} bytes · SHA-256 ${reference.sha256}")
                            app.daiyu.android.ui.haptics.material.TextButton(
                                enabled = dialogActionsCurrent(lease) && browserDownloadVisitCurrent(listing.visit) && controller.active == null && !controller.dataBusy,
                                modifier = Modifier.testTag("browser-download-save:${reference.id}"),
                                onClick = { if (dialogActionsCurrent(lease)) backupGuard { pickBrowserDownloadOriginal(listing, reference) } }
                            ) { androidx.compose.material3.Text("另存为原文件") }
                        }
                    }
                }, confirmButton = { app.daiyu.android.ui.haptics.material.TextButton(onClick = close) {
                    androidx.compose.material3.Text("关闭")
                } })
        }
    }

    @Suppress("DEPRECATION") private fun pickBrowserDownloadOriginal(listing: BrowserDownloadListing,
        reference: app.daiyu.android.browser.download.BrowserCompletedDownload) {
        check(pendingBackupRequest == null && pendingBrowserDownload == null) { "文件选择尚未返回；请先完成或取消选择" }
        check(browserDownloadVisitCurrent(listing.visit) && browserDownloadOutputs === listing) {
            "原会话已变化；请重新打开下载原件"
        }
        controller.browserDownloads.assertOwned(listing.visit.conversation.value, reference)
        val selection = BrowserSaveSelection(allocateOriginalSaveRequestCode(), listing.visit, reference)
        pendingBrowserDownload = selection
        pendingBackupRequest = selection.requestCode
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, "daiyu-browser-${reference.id}.download")
            }, selection.requestCode)
        } catch (failure: Exception) {
            pendingBrowserDownload = null; pendingBackupRequest = null; throw failure
        }
    }

    private fun assertBrowserSaveOwner(selection: BrowserSaveSelection) {
        check(browserDownloadVisitCurrent(selection.visit)) { "原会话已变化；本次保存已失效" }
    }

    // Shared with browser originals: never recycle a code or restore an original's authority.
    // Only the monotonic next value is saved; legacy backup/input request ranges stay untouched.
    private fun allocateOriginalSaveRequestCode(): Int {
        check(nextBrowserSaveRequestCode in 14000..65534) { "文件选择次数已达上限；请重启应用" }
        return nextBrowserSaveRequestCode++
    }
    private fun cancelStaticGenerationVisit() {
        staticGenerationVisit?.set(false); staticGenerationVisit = null
        pendingStaticOriginal?.let { if (pendingBackupRequest == it.requestCode) pendingBackupRequest = null }
        pendingStaticOriginal = null
    }
    private fun cancelGenerationOutputs() = clearGenerationOutputs(false)
    private fun clearGenerationOutputs(preserveStaticSelection: Boolean, preserveMediaSelection: Boolean = false,
        preservePresentation: Boolean = false) {
        if (!preserveMediaSelection) cancelGenerationMedia()
        if (!preserveStaticSelection) cancelStaticGenerationVisit()
        generationOutputsRequest = null
        if (!preservePresentation) {
            generationOutputsOwner = null
            generationOutputs = null
            historicalGenerationOutputs = null
            staticGenerationOutputs = null
        }
    }

    private fun openGenerationOutputs() {
        val conversation = selected ?: return
        if (!activityUsable() || !selectionReady || retainedPage != "chat" || sidebarVisible) return
        cancelGenerationOutputs()
        generationOutputsOwner = conversation.id to editorGeneration
        reloadGenerationOutputs()
    }
    private fun reloadGenerationOutputs() {
        val conversation = selected ?: return
        if (!generationOutputsPresented() || !voiceForeground) return
        val request = Any().also { generationOutputsRequest = it }
        val visit = staticGenerationVisit?.takeIf { it.get() }
            ?: java.util.concurrent.atomic.AtomicBoolean(true).also { staticGenerationVisit = it }
        val generation = editorGeneration
        if (!controller.loadGenerationOutputs(conversation.id) { outputs ->
            if (activityUsable() && generationOutputsRequest === request && retainedPage == "chat" &&
                !sidebarVisible && selected?.id == conversation.id && editorGeneration == generation) {
                if (outputs == null) toast("活动生成资产读取失败或授权已撤销；未重试生成")
                generationOutputs = outputs ?: emptyList()
            }
        }) {
            if (generationOutputsRequest === request) generationOutputsRequest = null
            toast("请等待当前数据操作完成")
            return
        }
        if (!controller.loadHistoricalGenerationOutputs(conversation.id) { outputs ->
            if (activityUsable() && generationOutputsRequest === request && retainedPage == "chat" &&
                !sidebarVisible && selected?.id == conversation.id && editorGeneration == generation) {
                historicalGenerationOutputs = outputs
                if (outputs == null) toast("历史原件校验失败；未恢复授权或重发请求")
            }
        }) toast("历史原件暂不可读；请等待当前数据操作完成")
        if (!controller.loadStaticGenerationOutputs(conversation.id) { outputs ->
            if (visit.get() && activityUsable() && generationOutputsRequest === request && retainedPage == "chat" &&
                !sidebarVisible && selected?.id == conversation.id && editorGeneration == generation) {
                staticGenerationOutputs = outputs
                if (outputs == null) toast("静态原件校验失败；未重试生成或分支")
            }
        }) toast("静态原件暂不可读；请等待当前数据操作完成")
    }

    @Suppress("DEPRECATION") private fun pickStaticGenerationOriginal(output: app.daiyu.android.storage.StaticGenerationOutput,
        expectedVisit: java.util.concurrent.atomic.AtomicBoolean?) {
        check(pendingBackupRequest == null) { "文件选择尚未返回" }
        val active = checkNotNull(expectedVisit)
        check(active === staticGenerationVisit && active.get() && activityUsable() && retainedPage == "chat" && !sidebarVisible &&
            selected?.id?.value == output.destination.conversationId) { "原会话访问已失效" }
        val extension = when (output.source.mimeType) {
            "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"
            "video/mp4" -> "mp4"; "video/webm" -> "webm"
            else -> error("不支持的原件类型")
        }
        val selection = StaticSaveSelection(allocateOriginalSaveRequestCode(), output, active)
        pendingStaticOriginal = selection; pendingBackupRequest = selection.requestCode
        try { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = output.source.mimeType
            putExtra(Intent.EXTRA_TITLE, "daiyu-static-original.$extension")
        }, selection.requestCode) } catch (failure: Exception) {
            pendingStaticOriginal = null; pendingBackupRequest = null; throw failure
        }
    }

    @Suppress("DEPRECATION") private fun pickHistoricalGenerationOriginal(
        history: app.daiyu.android.backup.HistoricalGenerationOutput) {
        check(pendingBackupRequest == null) { "文件选择尚未返回；请先完成或取消选择" }
        check(selected?.id?.value == history.conversationId && activityUsable()) { "原会话已变化；请重新打开历史" }
        val extension = when (history.mimeType) {
            "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"
            "video/mp4" -> "mp4"; "video/webm" -> "webm"
            else -> throw IllegalArgumentException("不支持的历史原件类型")
        }
        pendingHistoricalOriginal = history; pendingBackupRequest = 106
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = history.mimeType
                putExtra(Intent.EXTRA_TITLE, "daiyu-historical-original.$extension")
            }, 106)
        } catch (failure: Exception) {
            pendingHistoricalOriginal = null; pendingBackupRequest = null; throw failure
        }
    }

    private fun settleGenerationReceipt() {
        generationReceiptNotice = controller.pendingGenerationReceiptNotice()
        val notice = generationReceiptNotice ?: return
        val entry = notice.entry
        if (!entry.submitted || selected?.id != entry.conversation?.id ||
            editorGeneration != entry.editorGeneration || draftRevision != entry.initialEditor.revision ||
            draft != entry.initialEditor.text || imageHost.hasAttachments) return
        val accepted = controller.consumeGenerationReceipt(entry, editorGeneration,
            GenerationEditorSnapshot(draftRevision, draft)) { conversation ->
            selected = conversation
            editorGeneration++; draftRevision++
            draft = ""; selectionStart = 0; selectionEnd = 0
            controller.preferences.edit().putString("selected", conversation.id.value).apply()
        }
        if (accepted) {
            generationReceiptNotice = null
            publishChat(); selected?.let(::loadAcceptedHistory)
        }
    }

    private fun taskLocationCurrent(visit: TaskLocationVisit): Boolean =
        taskLocationVisit === visit && activityUsable() && voiceForeground && visit.current()

    private fun clearTaskLocation(owner: Any) {
        val visit = taskLocationVisit?.takeIf { it.owner === owner } ?: return
        visit.cancelQuestion?.invoke() // Deny the ORIGINAL question before unmounting.
        visit.cancelQuestion = null
        visit.questionUi = null
        taskLocationVisit = null
    }

    private fun cancelTaskLocation(visit: TaskLocationVisit) {
        visit.cancelQuestion?.invoke()
        visit.cancelQuestion = null
        visit.cancel() // Controller admission fence precedes hiding the location UI.
        if (taskLocationVisit === visit) {
            visit.questionUi = null
            taskLocationVisit = null
        }
    }

    private fun startTaskLocation(owner: Any, surface: ControllerTaskSurface,
        current: () -> Boolean, proceed: () -> Unit, cancel: () -> Unit) {
        taskLocationVisit?.let(::cancelTaskLocation)
        val visit = TaskLocationVisit(owner, surface, current, proceed, cancel)
        taskLocationVisit = visit // Retain before disk read; pause/navigation can fence this read.
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { surface.freezeOnWorker() } }
            catch (_: Exception) {
                if (taskLocationCurrent(visit)) toast("任务位置读取失败；未执行，草稿保留")
                cancelTaskLocation(visit)
                return@launch
            }
            if (!taskLocationCurrent(visit)) cancelTaskLocation(visit) else continueTaskLocation(visit)
            publishChat()
        }
    }

    private fun taskLocationResolved(visit: TaskLocationVisit, answer: TaskSurface?) {
        // The dialog already answered its retained original question. A stale callback cannot
        // supply a default foreground answer or resurrect a cancelled/replaced visit.
        if (answer == null || !taskLocationCurrent(visit)) cancelTaskLocation(visit)
        else continueTaskLocation(visit)
        publishChat()
    }

    private fun continueTaskLocation(visit: TaskLocationVisit) {
        if (!taskLocationCurrent(visit)) { cancelTaskLocation(visit); return }
        when (val decision = visit.surface.decision()) {
            is TaskSurfaceDecision.Ready -> {
                val expected = if (visit.owner is ControllerVirtualDisplayEntry) TaskSurface.BACKGROUND else TaskSurface.FOREGROUND
                if (decision.surface != expected ||
                    (visit.owner is ControllerVirtualDisplayEntry && decision.selfTarget != null)) {
                    toast("此完整计划在所选位置没有等价后端；未执行，不会回退到另一位置")
                    cancelTaskLocation(visit)
                    return
                }
                // Resolving location is not the end of the run. Keep its snapshot through drain.
                visit.cancelQuestion = null
                visit.questionUi = null
                taskLocationVisit = null
                visit.proceed()
            }
            TaskSurfaceDecision.AskRequired -> {
                if (visit.questionUi != null) return
                val adapter = checkNotNull(visit.surface.adapter)
                val request = visit.surface.request
                val question = adapter.askFromUser(request) ?: run { cancelTaskLocation(visit); return }
                visit.cancelQuestion = { adapter.answerFromUser(request, question, null); Unit }
                visit.questionUi = {
                    app.daiyu.android.ui.screens.settings.TaskSurfaceLocationDialog(adapter, request, question) { answer ->
                        taskLocationResolved(visit, answer)
                    }
                }
            }
            TaskSurfaceDecision.AskPending -> Unit // Keep the existing question, never create another entry.
            is TaskSurfaceDecision.NotExecuted -> {
                toast("未执行：${decision.message}")
                cancelTaskLocation(visit)
            }
        }
    }

    private fun guiOwnerCurrent(entry: ControllerGuiEntry): Boolean = activityUsable() &&
        guiEditor === entry && controller.guiEntry() === entry && !entry.revoked &&
        retainedPage == "chat" && !sidebarVisible && selectionReady &&
        controller.ready && !controller.dataBusy && selected?.id == entry.conversation?.id &&
        entry.editor == ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision) &&
        entry.text == draft && !imageHost.busy && !imageHost.hasAttachments && videoHost.state.items.isEmpty() && !videoSending

    private fun openGuiPreparation() {
        if (!activityUsable() || retainedPage != "chat" || sidebarVisible || !selectionReady ||
            pendingTextSend != null || imageHost.busy || imageHost.hasAttachments ||
            videoHost.state.items.isNotEmpty() || videoSending || shizukuWritesBlocked || draft.isBlank()) return
        val editor = ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision)
        val text = draft
        prompt = InputPrompt("本次 GUI 精确动作", "{\"operation\":\"tap\"}",
            "operation：tap、long_press、scroll_forward、scroll_backward、input、back、home、recents、notifications。" +
                "仅 input 必须带完整 text（最多 4096 字符）；不接受坐标。可选 target_package 填写你明确选择的完整目标包名；" +
                "缺省按普通外部目标选择任务位置。前台仍须在真实目标应用浮层选择节点并最终确认。",
            "打开真实目标确认", { raw ->
                if (!activityUsable() || retainedPage != "chat" || sidebarVisible || !selectionReady ||
                    editor != ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision) ||
                    text != draft || shizukuWritesBlocked || pendingTextSend != null || imageHost.hasAttachments ||
                    videoHost.state.items.isNotEmpty() || videoSending) return@InputPrompt
                val parsed = try { app.daiyu.android.gui.GuiUserActionRequest.parse(raw) }
                catch (_: Exception) { toast("GUI 参数无效；未打开授权、未执行动作"); return@InputPrompt }
                cancelBrowserEditor(); cancelShizukuEditor(); dictationHost.invalidate()
                val entry = controller.prepareGuiSend(selected, editor, text, parsed.operation, parsed.text, parsed.targetPackage)
                if (entry == null) { toast("GUI 不可用或仍待释放；草稿保留"); return@InputPrompt }
                guiEditor = entry // Retain before location routing, screen lease or overlay attachment.
                startTaskLocation(entry, entry.taskSurface, current = { guiOwnerCurrent(entry) && !entry.submitted },
                    proceed = { attachPreparedGui(entry) }, cancel = {
                        if (guiEditor === entry) cancelGuiEditor(entry) else controller.cancelGui(entry)
                    })
                publishChat()
            })
    }

    private fun attachPreparedGui(entry: ControllerGuiEntry) {
        if (!guiOwnerCurrent(entry) || !controller.openPreparedGui(entry)) {
            cancelGuiEditor(entry); toast("GUI 未执行：服务不可用或位置已撤销；草稿保留")
            return
        }
        val result = try { checkNotNull(entry.session).prepareExactActionWithProductDefaults(entry.operation, entry.input,
                    ownerCurrent = { guiOwnerCurrent(entry) },
                    onNotExecuted = { code ->
                        if (guiEditor === entry) { cancelGuiEditor(entry); toast("GUI 未执行：$code"); publishChat() }
                    }, onRevoked = {
                        if (entry.submitted && guiEditor === entry) cancelGuiEditor(entry)
                        else controller.cancelGui(entry)
                    }, onConfirmed = { delivery ->
                        if (!guiOwnerCurrent(entry) || entry.submitted || pendingTextSend != null) false else {
                            try {
                                if (selected == null) controller.database.draft(entry.text)
                                else controller.database.draft(checkNotNull(selected).id, entry.text)
                                val pending = PendingTextSend(entry.editor, entry.text)
                                pendingTextSend = pending
                                controller.sendGui(entry, delivery, pending.ticket) { receipt ->
                                    completeTextSend(pending, receipt)
                                }.also { accepted -> if (!accepted) {
                                    pendingTextSend = null
                                    toast("GUI 任务未受理；草稿保留，不自动重试")
                                } }
                            } catch (_: Exception) { pendingTextSend = null; false }
                        }
                    }) } catch (_: Exception) { app.daiyu.android.gui.GuiResult.Rejected("GUI_OVERLAY_UNAVAILABLE") }
        if (result !is app.daiyu.android.gui.GuiResult.Value) {
            cancelGuiEditor(entry)
            toast(if (result is app.daiyu.android.gui.GuiResult.Unknown)
                "GUI 结果未确认；保留原记录，不自动重试" else "GUI 未执行：当前目标不可用；草稿保留")
        }
        publishChat()
    }

    private fun cancelGuiEditor(original: ControllerGuiEntry) {
        if (guiEditor !== original) return
        controller.cancelGui(original) // fence before hiding; retain Controller resource until real release
        clearTaskLocation(original)
        guiEditor = null
    }
    private fun cancelGuiEditor() { guiEditor?.let(::cancelGuiEditor) }

    private fun openVirtualDisplayPreparation() {
        if (videoHost.state.items.isNotEmpty() || videoSending) return
        if (!activityUsable() || retainedPage != "chat" || sidebarVisible || !selectionReady ||
            !controller.ready || controller.dataBusy || controller.active != null ||
            controller.virtualDisplayEntry() != null || controller.textSendState() != null ||
            pendingTextSend != null || imageHost.busy || imageHost.hasAttachments || draft.isBlank()) return
        cancelBrowserEditor(); cancelShizukuEditor()
        virtualDisplayPlanVisit = VirtualDisplayPlanVisit(selected,
            ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision), draft)
        publishChat()
    }

    private fun cancelVirtualDisplayPlan(original: VirtualDisplayPlanVisit) {
        original.host.cancel()
        if (virtualDisplayPlanVisit === original) {
            virtualDisplayPlanVisit = null
            publishChat(rowsChanged = false)
        }
    }

    private fun freezeVirtualDisplayPlan(original: VirtualDisplayPlanVisit, frozen: R08FrozenPlan) {
        val current = activityUsable() && virtualDisplayPlanVisit === original && retainedPage == "chat" &&
            !sidebarVisible && selectionReady && selected === original.conversation &&
            original.editor == ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision) &&
            draft == original.text && !imageHost.busy && !imageHost.hasAttachments &&
            videoHost.state.items.isEmpty() && !videoSending && pendingTextSend == null &&
            controller.textSendState() == null && controller.ready && !controller.dataBusy &&
            controller.active == null && controller.virtualDisplayEntry() == null && virtualDisplayEditor == null
        if (!current) { cancelVirtualDisplayPlan(original); return }
        // Freeze is not acceptance or authorization. Reuse the original prepare/submit/SQLite gate.
        virtualDisplayEditor = controller.prepareVirtualDisplaySend(original.conversation, original.editor,
            original.text, frozen.writes, frozen.reads)
        virtualDisplayPlanVisit = null
        val entry = virtualDisplayEditor
        if (entry == null) toast("副屏计划未受理；保留草稿，不自动重发")
        else startTaskLocation(entry, entry.taskSurface, current = { virtualDisplayOwnerCurrent(entry) },
            proceed = { publishChat() }, cancel = {
                if (virtualDisplayEditor === entry) cancelVirtualDisplayEditor(entry) else controller.cancelVirtualDisplay(entry)
            })
        publishChat()
    }

    private fun cleanupVisitCurrent(original: VirtualDisplayCleanupVisit): Boolean = activityUsable() &&
        virtualDisplayCleanupVisit === original && currentCleanupOwner.get() === original.owner &&
        original.owner.isActive && retainedPage == "chat" && !sidebarVisible &&
        selected?.id == original.entry.conversationId && controller.virtualDisplayEntry() === original.entry

    private fun closeVirtualDisplayCleanup(original: VirtualDisplayCleanupVisit) {
        // MUST finish denial of the old published request before any new owner is visible.
        // A lone owner.close() is only an admission fence, not a synchronous adapter close.
        original.adapter.close()
        currentCleanupOwner.compareAndSet(original.owner, null)
        if (virtualDisplayCleanupVisit === original) virtualDisplayCleanupVisit = null
    }

    private fun openVirtualDisplayCleanup(originalEntry: ControllerVirtualDisplayEntry) {
        if (!activityUsable() || retainedPage != "chat" || sidebarVisible ||
            selected?.id != originalEntry.conversationId || controller.virtualDisplayEntry() !== originalEntry) return
        virtualDisplayCleanupVisit?.let(::closeVirtualDisplayCleanup)
        val owner = R08CleanupUiOwner()
        currentCleanupOwner.set(owner)
        val adapter = controller.openVirtualDisplayCleanup(originalEntry, owner) { currentCleanupOwner.get() }
            ?: run { owner.close(); currentCleanupOwner.compareAndSet(owner, null); return }
        val visit = VirtualDisplayCleanupVisit(originalEntry, owner, adapter)
        virtualDisplayCleanupVisit = visit
    }

    private fun prepareVirtualDisplayCleanup(visit: VirtualDisplayCleanupVisit) {
        if (!cleanupVisitCurrent(visit) || visit.busy || visit.request != null) return
        visit.busy = true
        lifecycleScope.launch {
            val request = try {
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { visit.adapter.prepareOnWorker() }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (cleanupVisitCurrent(visit)) {
                visit.busy = false
                if (request != null && visit.adapter.canPublish(request)) {
                    visit.request = request
                    answerVirtualDisplayCleanup(visit, request, true)
                } else visit.status = "暂不可释放：排空未完成、仍持有任务或请求已处理。未发送，不自动重试。"
            } else closeVirtualDisplayCleanup(visit)
        }
    }

    private fun observeVirtualDisplayTasks(visit: VirtualDisplayCleanupVisit) {
        if (!cleanupVisitCurrent(visit) || visit.busy || visit.observationStarted || visit.request != null) return
        visit.busy = true; visit.observationStarted = true
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { visit.adapter.observeTasksOnWorker() }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { ControllerVirtualDisplaySession.TasksResult.Unknown }
            if (cleanupVisitCurrent(visit)) {
                visit.busy = false
                visit.status = when (result) {
                    is ControllerVirtualDisplaySession.TasksResult.Observed -> {
                        if (visit.adapter.canPublishTasks(result.tasks)) {
                            visit.tasks = result.tasks
                            if (result.tasks.candidateTaskIds.isEmpty()) "原 owner 没有可交付的 live owned task。"
                            else "请明确选择原副屏 ${result.tasks.displayId} 的任务；未选任务可能被清理。默认不选。"
                        } else "原访问已失效；不发布任务候选。"
                    }
                    ControllerVirtualDisplaySession.TasksResult.Unknown -> "任务观察 UNKNOWN；保留原身份，不重试、不交付。"
                    ControllerVirtualDisplaySession.TasksResult.Unavailable -> "任务观察未执行或已处理；请检查原运行是否排空。"
                }
            } else closeVirtualDisplayCleanup(visit)
        }
    }

    private fun prepareVirtualDisplayTaskHandoff(visit: VirtualDisplayCleanupVisit, keepNone: Boolean = false) {
        val tasks = visit.tasks ?: return
        if (!cleanupVisitCurrent(visit) || visit.busy || visit.request != null || (!keepNone && visit.selectedTaskIds.isEmpty())) return
        val selected = if (keepNone) emptyList() else tasks.candidateTaskIds.filter { it in visit.selectedTaskIds }
        visit.busy = true
        lifecycleScope.launch {
            val request = try {
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                    visit.adapter.prepareTaskHandoffOnWorker(tasks, selected)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (cleanupVisitCurrent(visit)) {
                visit.busy = false
                if (request != null && visit.adapter.canPublish(request)) {
                    visit.request = request
                    answerVirtualDisplayCleanup(visit, request, true)
                } else visit.status = "交付请求未受理；原状态已改变或选择不可用，不自动重试。"
            } else closeVirtualDisplayCleanup(visit)
        }
    }

    private fun shutdownVirtualDisplayUninitialized(visit: VirtualDisplayCleanupVisit) {
        if (!cleanupVisitCurrent(visit) || visit.busy || visit.shutdownStarted || visit.request != null) return
        visit.shutdownStarted = true
        visit.busy = true
        lifecycleScope.launch {
            val confirmed = try {
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { visit.adapter.shutdownUninitializedOnWorker() }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            if (cleanupVisitCurrent(visit)) {
                visit.busy = false
                visit.status = if (confirmed) "未初始化 owner 的关闭请求已确认；尚未证明物理退出，请单独核实副屏停止。"
                    else "未执行或结果未确认，保留原记录，不自动重试。"
            } else closeVirtualDisplayCleanup(visit)
        }
    }

    private fun answerVirtualDisplayCleanup(visit: VirtualDisplayCleanupVisit,
        request: R08CleanupApprovalRequest, accepted: Boolean): Boolean {
        if (!cleanupVisitCurrent(visit) || visit.request !== request || visit.decided) return false
        val answered = if (accepted) visit.adapter.authorizeWithProductDefaults(request)
            else visit.adapter.answerFromUi(request, false)
        if (!accepted) { closeVirtualDisplayCleanup(visit); return answered }
        if (!answered) return false
        visit.decided = true
        visit.status = "请求已受理；排队 worker 将再次检查原父记录、本地 drain 和当前访问。"
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { visit.adapter.releaseOnWorker(request) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { ControllerVirtualDisplaySession.CleanupResult.UNKNOWN }
            if (cleanupVisitCurrent(visit)) visit.status = when (result) {
                ControllerVirtualDisplaySession.CleanupResult.CONFIRMED ->
                    "Release 已确认；本次不代表交付任务。保留原记录，请单独核实物理退出。"
                ControllerVirtualDisplaySession.CleanupResult.DELIVERED_AND_RELEASED ->
                    "所选非空任务已确认交付，Release 已确认；请单独核实 owner 物理退出。"
                ControllerVirtualDisplaySession.CleanupResult.DELIVERED_RETAINED ->
                    "所选任务已确认交付；Release 未确认或未发送，保留原 owner、journal 和 lease，不补发。"
                ControllerVirtualDisplaySession.CleanupResult.CLEANED_AND_RELEASED ->
                    "未交付任务；清理与 Release 已确认，请单独核实 owner 物理退出。"
                ControllerVirtualDisplaySession.CleanupResult.CLEANED_RETAINED ->
                    "未交付任务；清理已确认，Release 未确认或未发送，保留原记录，不补发。"
                ControllerVirtualDisplaySession.CleanupResult.UNKNOWN ->
                    "收尾 UNKNOWN；保留原身份、journal 和 lease，不重发、不重连。"
                ControllerVirtualDisplaySession.CleanupResult.UNAVAILABLE,
                ControllerVirtualDisplaySession.CleanupResult.NOT_APPROVED ->
                    "Release 未发送或已处理；不自动重试，保留原记录。"
            }
        }
        return true
    }

    @Composable private fun VirtualDisplayR08Surfaces() {
        virtualDisplayPlanVisit?.let { visit -> key(visit.host) {
            app.daiyu.android.ui.components.DaiyuDialog(onDismissRequest = { cancelVirtualDisplayPlan(visit) },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                androidx.compose.material3.Surface {
                    R08PlanEditor(visit.host, onFrozen = { freezeVirtualDisplayPlan(visit, it) },
                        onCancel = { cancelVirtualDisplayPlan(visit) })
                }
            }
        } }
        virtualDisplayCleanupVisit?.let { visit -> key(visit.owner) {
            DisposableEffect(visit.adapter) { onDispose { visit.adapter.close() } }
            app.daiyu.android.ui.components.DaiyuDialog(onDismissRequest = { closeVirtualDisplayCleanup(visit) },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                androidx.compose.material3.Surface {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        if (visit.request == null) {
                            app.daiyu.android.ui.haptics.material.TextButton(onClick = { prepareVirtualDisplayCleanup(visit) },
                                enabled = !visit.busy, modifier = Modifier.testTag("vd-cleanup-release-only")) {
                                androidx.compose.material3.Text("执行一次 Release 收尾（不交付任务）")
                            }
                            app.daiyu.android.ui.haptics.material.TextButton(onClick = { observeVirtualDisplayTasks(visit) },
                                enabled = !visit.busy && !visit.observationStarted,
                                modifier = Modifier.testTag("vd-cleanup-observe-tasks")) {
                                androidx.compose.material3.Text("显式观察原副屏可交付任务（只读）")
                            }
                            app.daiyu.android.ui.haptics.material.TextButton(onClick = { shutdownVirtualDisplayUninitialized(visit) },
                                enabled = !visit.busy && !visit.shutdownStarted,
                                modifier = Modifier.testTag("vd-cleanup-shutdown-uninitialized")) {
                                androidx.compose.material3.Text("查证未初始化并仅关闭原 owner 进程")
                            }
                            androidx.compose.material3.Text("仅适用于从未尝试初始化的原 owner：认证查询后关闭，不初始化、不 Release、不 kill；UNKNOWN 不重发。")
                            visit.tasks?.let { tasks ->
                                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                    tasks.candidateTaskIds.forEach { id ->
                                        app.daiyu.android.ui.haptics.material.Checkbox(checked = id in visit.selectedTaskIds,
                                            onCheckedChange = { checked -> if (!visit.busy) {
                                                visit.selectedTaskIds = if (checked) visit.selectedTaskIds + id else visit.selectedTaskIds - id
                                            } }, enabled = !visit.busy,
                                            modifier = Modifier.testTag("vd-delivery-task-$id"))
                                        androidx.compose.material3.Text("原副屏 ${tasks.displayId} · owned task $id")
                                    }
                                }
                                app.daiyu.android.ui.haptics.material.Button(onClick = { prepareVirtualDisplayTaskHandoff(visit) },
                                    enabled = !visit.busy && visit.selectedTaskIds.isNotEmpty(),
                                    modifier = Modifier.testTag("vd-cleanup-freeze-delivery")) {
                                    androidx.compose.material3.Text("交付所选任务 → 条件 Release")
                                }
                                app.daiyu.android.ui.haptics.material.TextButton(onClick = { prepareVirtualDisplayTaskHandoff(visit, keepNone = true) },
                                    enabled = !visit.busy, modifier = Modifier.testTag("vd-cleanup-freeze-empty")) {
                                    androidx.compose.material3.Text("清理 owned 任务 → 条件 Release（不交付任何任务）")
                                }
                            }
                        }
                        visit.request?.let { request ->
                            val plan = request.review.plan
                            val binding = remember(request) { plan.binding.json() }
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                androidx.compose.material3.Text("已请求本次收尾；实际结果以下方状态为准。")
                                androidx.compose.material3.Text(request.warning)
                                listOf("session", "task", "conversation", "run", "assistantMessage", "runOwner").forEach {
                                    androidx.compose.material3.Text("$it: ${binding.getString(it)}")
                                }
                                androidx.compose.material3.Text("bindingKey: ${plan.bindingKey}")
                                androidx.compose.material3.Text("generation: ${plan.generation}")
                                request.review.rows.forEach { row ->
                                    androidx.compose.material3.Text("WRITE ${row.stepId} · ${row.operation}")
                                    androidx.compose.material3.Text("规范生效参数: ${row.effectiveParameters}")
                                    androidx.compose.material3.Text("namespace: ${row.exactEffect.namespace}")
                                    androidx.compose.material3.Text("operation: ${row.exactEffect.operation}")
                                    androidx.compose.material3.Text("target: ${row.exactEffect.target}")
                                    androidx.compose.material3.Text("change: ${row.exactEffect.change}")
                                }
                            }
                        }
                        androidx.compose.material3.Text(visit.status, Modifier.testTag("vd-cleanup-status"))
                        app.daiyu.android.ui.haptics.material.TextButton(onClick = { closeVirtualDisplayCleanup(visit) },
                            modifier = Modifier.testTag("vd-cleanup-close")) {
                            androidx.compose.material3.Text("关闭此次收尾访问（不撤回在途操作，不释放 lease）")
                        }
                    }
                }
            }
        } }
    }

    private fun virtualDisplayOwnerCurrent(entry: ControllerVirtualDisplayEntry): Boolean = activityUsable() &&
        virtualDisplayEditor === entry && controller.virtualDisplayEntry() === entry && !entry.revoked && !entry.submitted &&
        retainedPage == "chat" && !sidebarVisible && selectionReady && controller.ready && !controller.dataBusy &&
        selected?.id == entry.conversation?.id &&
        entry.editor == ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision) &&
        entry.text == draft && !imageHost.busy && !imageHost.hasAttachments && videoHost.state.items.isEmpty() && !videoSending

    private fun submitVirtualDisplay(entry: ControllerVirtualDisplayEntry) {
        if (entry.taskSurface.ready(TaskSurface.BACKGROUND)?.let { it.selfTarget == null } != true) return
        if (!virtualDisplayOwnerCurrent(entry)) return
        if (videoHost.state.items.isNotEmpty() || videoSending) return
        if (!activityUsable() || virtualDisplayEditor !== entry || retainedPage != "chat" || sidebarVisible ||
            pendingTextSend != null || imageHost.hasAttachments || entry.text != draft ||
            entry.editor != ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision)) return
        guard {
            if (selected == null) controller.database.draft(entry.text)
            else controller.database.draft(checkNotNull(selected).id, entry.text)
            val pending = PendingTextSend(entry.editor, entry.text)
            pendingTextSend = pending
            if (!controller.sendVirtualDisplay(entry, pending.ticket) { result -> completeTextSend(pending, result) }) {
                pendingTextSend = null
                cancelVirtualDisplayEditor(entry)
                toast("副屏运行未受理；保留草稿")
            }
            publishChat()
        }
    }

    // Retain the host callback, not a callback that looks up whichever editor is current later.
    // A Dialog additionally belongs to its exact request (even if its entry has not changed).
    private fun virtualDisplayCancelAction(entry: ControllerVirtualDisplayEntry,
        request: app.daiyu.android.vdapproval.VirtualDisplayApprovalRequest?): () -> Unit = {
        if (request == null || virtualDisplayRequest === request) cancelVirtualDisplayEditor(entry)
    }

    private fun cancelVirtualDisplayEditor(original: ControllerVirtualDisplayEntry) {
        if (virtualDisplayEditor !== original) return
        // Fence BEFORE hiding the approval; disposal of an old screen cannot affect a later run.
        controller.cancelVirtualDisplay(original)
        clearTaskLocation(original)
        virtualDisplayRequest = null
        virtualDisplayEditor = null
    }

    // Navigation intentionally cancels the current editor rather than a retained UI capability.
    private fun cancelVirtualDisplayEditor() {
        virtualDisplayPlanVisit?.let(::cancelVirtualDisplayPlan)
        virtualDisplayCleanupVisit?.let(::closeVirtualDisplayCleanup)
        val original = virtualDisplayEditor
        if (original != null) cancelVirtualDisplayEditor(original)
        else virtualDisplayRequest = null
    }

    private fun mcpVisitCurrent(visit: Any?): Boolean = visit != null && mcpVisit === visit &&
        activityUsable() && voiceForeground && retainedPage == "chat" && !sidebarVisible

    private fun cancelMcpEditor() {
        // Revoke BEFORE hiding UI/rejecting preparation; no callback may resurrect this visit.
        mcpVisit = null
        if (prompt === mcpInput) prompt = null
        mcpInput = null
        mcpRequest = null; mcpPreparing = false
        val pending = mcpApproval
        mcpApproval = null
        pending?.let(controller::rejectMcp)
    }

    private fun approveMcp(pending: ControllerMcpPreparation, visit: Any?) {
        // Both identities come from the rendered preview, never from whichever visit is current later.
        if (!mcpVisitCurrent(visit) || mcpApproval !== pending) return
        val owner = ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision)
        if (!imageHost.hasAttachments && videoHost.state.items.isEmpty() && !videoSending && pendingTextSend == null &&
            owner == pending.editor && draft == pending.text && pending.scope.isCurrent()) {
            val submitted = PendingTextSend(owner, draft)
            pendingTextSend = submitted
            if (!controller.sendMcp(pending, owner, draft, submitted.ticket) { result ->
                if (mcpApproval === pending && mcpVisit === visit) { mcpApproval = null; mcpVisit = null }
                completeTextSend(submitted, result)
            }) {
                pendingTextSend = null; cancelMcpEditor()
                toast("MCP 发送未受理；草稿保留")
            } else if (mcpApproval === pending && mcpVisit === visit) { mcpApproval = null; mcpVisit = null }
        } else { cancelMcpEditor(); toast("MCP 批准或草稿已过期；未发送") }
    }

    private fun openMcpApproval() {
        if (videoHost.state.items.isNotEmpty() || videoSending || imageHost.hasAttachments ||
            !activityUsable() || !voiceForeground || pendingTextSend != null || draft.isBlank() || retainedPage != "chat" || sidebarVisible) return
        // Cancel competing editors BEFORE issuing the token; submission must not revoke/reissue it.
        cancelShizukuEditor(); cancelBrowserEditor()
        val visit = Any(); mcpVisit = visit
        val owner = ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision)
        val original = draft
        val conversation = selected
        val input = InputPrompt("本轮 MCP 服务器、工具与完整参数", "{\"server\":\"my_server\",\"tool\":\"tool_name\",\"arguments\":{}}",
            "先在 MCP 设置保存服务器。填写一个完整 JSON 对象；本次只使用这个服务器/工具/参数，未知工具默认有副作用。提交后按默认许可运行。",
            "运行 MCP 调用", { raw ->
                if (!mcpVisitCurrent(visit) || mcpInput == null ||
                    owner != ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision) ||
                    draft != original || imageHost.hasAttachments || videoHost.state.items.isNotEmpty() || videoSending || pendingTextSend != null) return@InputPrompt
                mcpInput = null // Input is single-use even if its original action is retained.
                val intent = try {
                    app.daiyu.android.mcp.McpJson.objectValue(raw).also {
                        require(it.keys().asSequence().toSet() == setOf("server", "tool", "arguments"))
                        require(it.get("server") is String && it.get("tool") is String && it.get("arguments") is org.json.JSONObject)
                    }
                } catch (_: Exception) { cancelMcpEditor(); toast("MCP JSON 无效；未发送"); return@InputPrompt }
                if (conversation == null) controller.database.draft(original) else controller.database.draft(conversation.id, original)
                val request = Any(); mcpRequest = request; mcpPreparing = true
                lifecycleScope.launch {
                    var prepared: ControllerMcpPreparation? = null
                    var retained = false
                    try {
                        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                            prepared = controller.prepareMcpSend(conversation, owner, original,
                                intent.getString("server"), intent.getString("tool"), intent.getJSONObject("arguments").toString())
                        }
                        if (mcpVisitCurrent(visit) && mcpRequest === request &&
                            owner == ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision) && draft == original) {
                            mcpApproval = prepared; retained = prepared != null
                            prepared?.let { approveMcp(it, visit) }
                            if (prepared == null) toast(controller.problem ?: "MCP 准备失败；草稿保留")
                        }
                    } finally {
                        if (!retained) prepared?.let(controller::rejectMcp)
                        if (mcpRequest === request) {
                            mcpRequest = null; mcpPreparing = false
                            if (!retained && mcpVisit === visit) mcpVisit = null
                        }
                    }
                }
            })
        mcpInput = input; prompt = input
    }

    private fun cancelBrowserEditor(preserveGenerationSelection: Boolean = false, preserveStaticSelection: Boolean = false,
        preserveMediaSelection: Boolean = false, preserveOutputPresentation: Boolean = false) {
        cancelMcpEditor()
        cancelTerminalEditor()
        cancelVirtualDisplayEditor()
        if (!preserveGenerationSelection) cancelGenerationEditor()
        clearGenerationOutputs(preserveStaticSelection, preserveMediaSelection, preservePresentation = preserveOutputPresentation)
        browserRequest = null
        browserPreparing = false
        val pending = browserApproval
        browserApproval = null
        pending?.let(controller::rejectBrowser)
    }

    private fun openBrowserApproval() {
        if (videoHost.state.items.isNotEmpty() || videoSending || !activityUsable() ||
            retainedPage != "chat" || sidebarVisible || pendingTextSend != null ||
            imageHost.hasAttachments || draft.isBlank()) return
        val snapshot = BrowserDraftSnapshot(BrowserDraftOwner(selected?.id, editorGeneration, draftRevision), draft)
        val conversation = selected
        if (snapshot.owner.conversationId == null) controller.database.draft(snapshot.text)
        else controller.database.draft(checkNotNull(snapshot.owner.conversationId), snapshot.text)
        cancelShizukuEditor()
        cancelBrowserEditor()
        val request = Any()
        browserRequest = request
        browserPreparing = true
        lifecycleScope.launch {
            var prepared: ControllerBrowserPreparation? = null
            var retained = false
            try {
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                    prepared = controller.prepareBrowserSend(conversation, snapshot, emptyList())
                }
                val matches = activityUsable() && browserRequest === request && retainedPage == "chat" &&
                    !sidebarVisible && !imageHost.hasAttachments && videoHost.state.items.isEmpty() &&
                    !videoSending && pendingTextSend == null && snapshot == BrowserDraftSnapshot(
                        BrowserDraftOwner(selected?.id, editorGeneration, draftRevision), draft)
                if (matches) {
                    val pending = prepared
                    if (pending == null) toast(controller.problem ?: "浏览器准备未受理；草稿保留")
                    else {
                        browserApproval = pending
                        val owner = ChatController.TextSendOwner(snapshot.owner.conversationId,
                            snapshot.owner.editorGeneration, snapshot.owner.draftRevision)
                        val submitted = PendingTextSend(owner, snapshot.text)
                        pendingTextSend = submitted
                        retained = controller.sendBrowser(pending, snapshot, submitted.ticket) { result ->
                            if (browserApproval === pending) browserApproval = null
                            completeTextSend(submitted, result)
                        }
                        if (!retained) {
                            pendingTextSend = null
                            if (browserApproval === pending) browserApproval = null
                            toast("浏览器任务未受理；草稿保留，不自动重试")
                        }
                    }
                }
            } finally {
                if (!retained) prepared?.let(controller::rejectBrowser)
                if (browserRequest === request) { browserPreparing = false; browserRequest = null }
            }
        }
    }

    private fun trackShizukuPreparation() {
        val pending = controller.shizukuPreparation()
        shizukuEditor?.let { editor ->
            if (pending != null && pending.conversation?.id == editor.conversation?.id && pending.text == editor.text) {
                editor.preview = pending
            }
        }
        if (pending?.isSubmitted() == true) shizukuInFlight = pending
        val settling = shizukuInFlight
        if (settling != null && pending !== settling) {
            // Controller posts its receipt immediately after the change notification. Keep all
            // editors frozen through that receipt, including a different selected conversation.
            window.decorView.post {
                if (shizukuInFlight === settling && controller.shizukuPreparation() !== settling) {
                    // A cancelled prepare may have won the DB gate before this Activity (or
                    // its replacement) left. No writes were admitted while the gate was held:
                    // settle from the authoritative current-owner draft before enabling edits.
                    if (activityUsable() && selectionReady) {
                        val persisted = runCatching {
                            selected?.id?.let { id -> controller.database.conversations().first { it.id == id }.draft }
                                ?: controller.database.draft()
                        }.getOrNull() ?: return@post
                        if (draft != persisted) {
                            draft = persisted; editorGeneration++; draftRevision++
                            selectionStart = draft.length; selectionEnd = draft.length
                        }
                    }
                    shizukuInFlight = null
                    if (activityUsable()) refresh()
                }
            }
        }
    }

    private fun cancelShizukuEditor() {
        trackShizukuPreparation()
        val editor = shizukuEditor
        shizukuEditor = null
        controller.shizukuPreparation()?.takeIf {
            editor != null && it.conversation?.id == editor.conversation?.id && it.text == editor.text
        }?.let(controller::rejectShizuku) // Includes submitted prepare/connect; never stops another run.
    }

    private fun shizukuEditorCurrent(editor: ShizukuEditor): Boolean =
        activityUsable() && retainedPage == "chat" && !sidebarVisible && shizukuEditor === editor &&
            editorGeneration == editor.generation && draftRevision == editor.revision && draft == editor.text &&
            selected?.id == editor.conversation?.id && !imageHost.hasAttachments && videoHost.state.items.isEmpty()

    private fun acceptShizuku(editor: ShizukuEditor, preview: ControllerShizukuPreparation, saved: Conversation) {
        if (!shizukuEditorCurrent(editor) || editor.preview !== preview || !preview.isSubmitted() ||
            preview.conversation?.id != editor.conversation?.id || preview.text != editor.text ||
            saved.id != preview.conversationId) return
        shizukuEditor = null
        selected = saved; editorGeneration++; draftRevision++
        draft = ""; selectionStart = 0; selectionEnd = 0
        // Controller already atomically consumed exactly the submitted DB draft. Never write it again.
        controller.preferences.edit().putString("selected", saved.id.value).apply()
        publishChat(); loadAcceptedHistory(saved)
    }

    @Composable private fun ImageComposerPanel() {
        val session = imageHost.session
        val images by session.attachments.state.collectAsStateWithLifecycle()
        val ownerGeneration = editorGeneration
        val enabled = activityUsable() && retainedPage == "chat" && !sidebarVisible && selectionReady &&
            controller.ready && !controller.dataBusy && controller.active == null && !imageHost.busy
        Column(Modifier.fillMaxWidth()) {
            ImageAttachments(images, imageHost.actions(::launchImagePicker), imageHost.draftLoader,
                enabled = enabled, compact = true)
            val attempt = session.attempt
            if (attempt != null && !session.uncertain) app.daiyu.android.ui.haptics.miuix.TextButton(
                text = "取消图片准备", modifier = Modifier.heightIn(min = 48.dp).testTag("image-host-cancel"),
                onClick = {
                    if (editorGeneration == ownerGeneration && imageHost.session === session)
                        imageHost.cancelPreparation(session, attempt)
                })
            if (imageHost.fenced) {
                top.yukonga.miuix.kmp.basic.Text(
                    "发送结果待核实；保留原草稿，禁止重发", modifier = Modifier.testTag("image-host-unknown"))
                app.daiyu.android.ui.haptics.miuix.TextButton(text = "只读核实原发送结果", enabled = imageHost.canRecover,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("image-host-recover"), onClick = {
                        if (editorGeneration == ownerGeneration && imageHost.session === session) imageHost.recover(session)
                    })
            }
        }
    }

    private fun launchImagePicker() { imagePicker.launch("image/*") }
    private fun pickImages() {
        if (videoHost.state.items.isNotEmpty() || videoSending) return
        if (!selectionReady || sidebarVisible || retainedPage != "chat" || controller.active != null || imageHost.busy) return
        if (shizukuWritesBlocked) return
        cancelShizukuEditor()
        cancelBrowserEditor()
        imageHost.requestSelection(imageHost.session.attachments.state.value.draft, ::launchImagePicker)
    }
    private fun sendImageDraft(text: String) {
        if (videoHost.state.items.isNotEmpty() || videoSending) return
        if (!selectionReady || retainedPage != "chat" || sidebarVisible || controller.active != null || text != draft) return
        val generation = editorGeneration
        val revision = draftRevision
        val owner = imageHost.session
        val conversation = selected
        imageHost.send(conversation, revision, text, stillCurrent = {
            activityUsable() && imageHost.session === owner && editorGeneration == generation &&
                draftRevision == revision && selected?.id == conversation?.id && draft == text
        })
    }

    private fun settleImageReceipt(receipt: ImageSendResult.Accepted, text: String) {
        // Host withholds this callback if the editor was replaced while UNKNOWN (durably,
        // even across numeric revision reuse). Reconcile only the exact unchanged current view.
        if (!activityUsable() || !selectionReady || imageHost.session.owner != receipt.owner ||
            imageHost.session.attachments.state.value.draft.revision != receipt.revision || draftRevision != receipt.revision ||
            (selected != null && selected?.id != receipt.conversation.id) || draft != text) return
        selected = receipt.conversation; editorGeneration++; draftRevision++
        draft = ""; selectionStart = 0; selectionEnd = 0
        controller.preferences.edit().putString("selected", receipt.conversation.id.value).apply()
        publishChat()
        loadAcceptedHistory(receipt.conversation)
    }

    private val streamingHistoryRefresh = app.daiyu.android.ui.screens.chat.conversation.StreamingHistoryRefresh()
    private var refreshHistoryLoading = false
    private var refreshHistoryPending = false
    private var refreshHistoryRequestSerial = 0L
    private var refreshHistoryTailConversation: ConversationId? = null
    private var loadedWindowReader: (ConversationId, Long, Int) -> List<Pair<Long, Message>> = ::readLoadedWindow

    private fun loadedHistoryOwner(id: ConversationId) = controller.active?.takeIf { it.conversationId == id }
        ?.let { Triple(it.conversationId, it.id, it.runId) }

    private fun refreshMessageActionHistory() {
        refreshHistoryTailConversation = selected?.id
        refresh()
    }

    private fun loadRefreshedHistory(conversation: Conversation) {
        refreshHistoryPending = true
        if (refreshHistoryLoading || !activityUsable() || !controller.ready || controller.dataBusy ||
            controller.problem?.startsWith("会话恢复失败") == true) return
        refreshHistoryLoading = true
        refreshHistoryPending = false
        val id = conversation.id
        val generation = editorGeneration
        val request = ++refreshHistoryRequestSerial
        val acceptedRequest = historyRequestSerial
        val capturedWindow = shown.toList()
        val content = chatState.contentRevision
        val data = controller.dataRevision
        val owner = loadedHistoryOwner(id)
        val first = shown.firstOrNull()?.first
        val last = shown.lastOrNull()?.first
        val count = shown.size
        val reloadTail = refreshHistoryTailConversation == id
        val newOwner = owner != null && shown.none {
            Triple(it.second.conversationId, it.second.id, it.second.runId) == owner
        }
        val read = loadedWindowReader
        lifecycleScope.launch {
            try {
                val rows = try {
                    withContext(Dispatchers.IO) {
                        if (reloadTail) readAcceptedWindow(id, first)
                        else read(id, if (newOwner || last == null) Long.MAX_VALUE else last + 1,
                            if (count == 0) 50 else count + if (newOwner) 2 else 0)
                            .filter { first == null || it.first >= first }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
                if (!activityUsable() || !controller.ready || controller.dataBusy ||
                    controller.problem?.startsWith("会话恢复失败") == true || selected?.id != id ||
                    editorGeneration != generation || request != refreshHistoryRequestSerial ||
                    acceptedRequest != historyRequestSerial) return@launch
                val active = controller.active?.takeIf { it.conversationId == id }
                val liveOnlyChange = app.daiyu.android.ui.screens.chat.conversation.canOverlayLiveHistory(capturedWindow, shown, active)
                if (refreshHistoryPending || (content != chatState.contentRevision && !liveOnlyChange) || data != controller.dataRevision ||
                    owner != loadedHistoryOwner(id)) {
                    refreshHistoryPending = true
                    return@launch
                }
                if (rows == null) { streamingHistoryRefresh.invalidate(); toast("历史读取失败；原数据保留"); return@launch }
                if (reloadTail) refreshHistoryTailConversation = null
                val currentOwner = loadedHistoryOwner(id)
                val refreshed = rows.map { entry ->
                    if (active != null && Triple(entry.second.conversationId, entry.second.id, entry.second.runId) == currentOwner)
                        entry.first to active else entry
                }
                if (refreshed != shown) {
                    shown = refreshed.toMutableList()
                    prepareMessageActionSnapshot()
                    publishChat()
                }
            } finally {
                refreshHistoryLoading = false
                if (refreshHistoryPending && activityUsable()) selected?.let(::loadRefreshedHistory)
            }
        }
    }

    private fun loadAcceptedHistory(conversation: Conversation) {
        val generation = editorGeneration
        val request = ++historyRequestSerial
        val content = chatState.contentRevision
        val first = shown.firstOrNull()?.first
        val data = controller.dataRevision
        val owner = loadedHistoryOwner(conversation.id)
        lifecycleScope.launch {
            val rows = try {
                withContext(Dispatchers.IO) { readAcceptedWindow(conversation.id, first) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
            if (!activityUsable() || !controller.ready || controller.dataBusy ||
                selected?.id != conversation.id || editorGeneration != generation || request != historyRequestSerial) return@launch
            if (data != controller.dataRevision || owner != loadedHistoryOwner(conversation.id)) {
                loadAcceptedHistory(conversation)
                return@launch
            }
            applyAcceptedHistory(conversation, generation, request, content, rows)
        }
    }

    private fun readAcceptedWindow(id: ConversationId, first: Long?): List<Pair<Long, Message>> {
        if (first == null) return controller.database.messages(id)
        val rows = mutableListOf<Pair<Long, Message>>()
        var cursor = Long.MAX_VALUE
        while (true) {
            val page = controller.database.messages(id, cursor, 200)
            if (page.isEmpty()) break
            rows.addAll(0, page)
            cursor = page.first().first
            if (cursor <= first) break
        }
        return rows.filter { it.first >= first }
    }

    private fun applyAcceptedHistory(conversation: Conversation, generation: Long, request: Long,
        content: Long, rows: List<Pair<Long, Message>>?) {
        if (!activityUsable() || selected?.id != conversation.id || editorGeneration != generation ||
            request != historyRequestSerial) return
        // Any intervening stream/final refresh, earlier-window expansion or scroll publication
        // invalidates this snapshot. Never replace a newer loaded range with an older IO result.
        if (chatState.contentRevision != content) { loadAcceptedHistory(conversation); return }
        if (rows == null) { toast("消息已保存；历史加载失败"); return }
        val active = controller.active?.takeIf { it.conversationId == conversation.id }
        val owner = loadedHistoryOwner(conversation.id)
        shown = rows.map {
            if (active != null && Triple(it.second.conversationId, it.second.id, it.second.runId) == owner)
                it.first to active else it
        }.toMutableList()
        prepareMessageActionSnapshot(); publishChat(); jumpToBottom()
    }

    @Composable private fun PromptSurface() {
        val current = prompt ?: return
        fun valid() = activityUsable() && prompt === current
        when (current) {
            is InputPrompt -> DaiyuTextInputDialog(current.title, current.initial, current.hint, current.confirm,
                preview = retainedPage == "chat",
                onConfirm = { value -> confirmInputPrompt(current, value) },
                onDismiss = { if (valid()) { if (current === mcpInput) cancelMcpEditor() else prompt = null } })
            is FolderPrompt -> ConversationFolderPicker(current.folders, current.initialFolderId,
                onConfirm = { folderId -> if (valid()) { prompt = null; current.action(folderId) } },
                onDismiss = { if (valid()) prompt = null })
            is ChoicePrompt -> DaiyuChoiceSheet(current.title, current.options, preview = retainedPage == "chat",
                onSelect = { index -> if (valid()) { prompt = null; current.action(index) } }, onDismiss = { if (valid()) prompt = null })
            is ConfirmPrompt -> DaiyuConfirmDialog(current.title, current.message, current.confirm, current.destructive,
                preview = retainedPage == "chat", showCloseButton = current.showCloseButton, secondaryCancel = current.secondaryCancel,
                onConfirm = { confirmPrompt(current) }, onDismiss = { if (valid()) dismissPrompt(current) })
        }
    }

    private fun dismissPrompt(expected: Prompt) {
        if (prompt !== expected) return
        if (deferredOpenedBackup?.waitingPrompt === expected) cancelDeferredOpenedBackup()
        prompt = null
    }
    private fun confirmInputPrompt(expected: InputPrompt, value: String) {
        if (activityUsable() && prompt === expected) { prompt = null; expected.action(value) }
    }
    private fun confirmPrompt(expected: ConfirmPrompt) {
        if (activityUsable() && prompt === expected) { prompt = null; expected.action() }
    }
    private fun openSettingsFromPage(route: FeatureRoute) {
        if (activityUsable() && retainedPage == "settings") guard { openSettingsRoute(route) }
    }

    private fun controlChildTaskFromCard(step: WorkStepUi, request: ToolCardActionRequest) {
        if (!controller.ready || controller.dataBusy) return
        val owner = step.owner ?: return
        val editor = editorGeneration
        val visit = contextManagementEntryEpoch
        fun current() = activityUsable() && voiceForeground && selectionReady && retainedPage == "chat" &&
            !sidebarVisible && editorGeneration == editor && contextManagementEntryEpoch == visit &&
            selected?.id?.value == owner.conversationId && controller.ready && !controller.dataBusy &&
            resolveToolCardAction(chatState.turns, request) != null
        lifecycleScope.launch {
            val records = try {
                withContext(Dispatchers.IO) {
                    app.daiyu.android.storage.SQLiteChildTaskStore(controller.database).forCall(
                        owner.conversationId, owner.runId, owner.messageId, step.toolName, step.toolCallId)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (current()) toast("无法读取任务记录，未执行操作")
                return@launch
            }
            if (!current()) return@launch
            val target = resolveChildTaskControlTarget(step, request, records)
            val live = try { controller.liveChildTasks(app.daiyu.android.domain.ConversationId(owner.conversationId)) }
                catch (_: Exception) { emptyList() }
            val task = target?.let { app.daiyu.android.ui.screens.conversation.controllableChildTask(it, live) }
            if (target == null || task == null) {
                toast("任务已结束或已变化，未执行操作")
                return@launch
            }
            try {
                when (request.action) {
                    ToolCardAction.PAUSE_TASK -> {
                        val receipt = controller.pauseChildTask(target)
                        toast(getString(app.daiyu.android.ui.screens.conversation.childTaskControlMessage(receipt)))
                    }
                    ToolCardAction.RESUME_TASK -> {
                        val receipt = controller.resumeChildTask(target)
                        toast(getString(app.daiyu.android.ui.screens.conversation.childTaskControlMessage(receipt,
                            task.pauseState == app.daiyu.android.domain.delegation.ChildTaskPauseState.PENDING)))
                    }
                    ToolCardAction.STOP_TASK -> {
                        val receipt = controller.stopChildTask(target)
                        toast(if (receipt == app.daiyu.android.domain.delegation.ChildTaskStop.REQUESTED)
                            "已请求停止，等待退出回执" else "任务已结束或已变化")
                    }
                    else -> Unit
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (current()) toast("操作回执未确认，请查看任务状态；未自动重试")
            }
        }
    }

    private fun handleToolCardAction(request: ToolCardActionRequest) {
        if (!activityUsable() || !selectionReady || !voiceForeground || retainedPage != "chat" || sidebarVisible) return
        val step = resolveToolCardAction(chatState.turns, request)
        if (step == null || step.owner?.conversationId != selected?.id?.value) {
            toast("记录已变化，请重新打开")
            return
        }
        if (step.categoryDetails?.rich?.bodyRestricted == true) return
        val owner = step.owner ?: return
        when (request.action) {
            ToolCardAction.READ_TASK_RESULT -> guard { openChildTaskRecords(owner.conversationId) }
            ToolCardAction.PAUSE_TASK, ToolCardAction.RESUME_TASK, ToolCardAction.STOP_TASK ->
                controlChildTaskFromCard(step, request)
            ToolCardAction.OPEN_IMAGE, ToolCardAction.PLAY_VIDEO, ToolCardAction.SAVE_MEDIA -> generationMediaAction(step, request)
            ToolCardAction.VIEW_ASSETS -> openGenerationOutputs()
            else -> toast("操作未完成，请稍后再试")
        }
    }

    @Composable private fun ToolTrustedContent(step: WorkStepUi) {
        val editor = editorGeneration
        val visit = contextManagementEntryEpoch
        val action: (ToolCardActionRequest) -> Unit = remember(editor, visit) {
            { request ->
                if (editorGeneration == editor && contextManagementEntryEpoch == visit) handleToolCardAction(request)
            }
        }
        if ((step.hasScreenScreenshot || step.hasBrowserScreenshot) && step.state == WorkStepState.OK && activityUsable() && voiceForeground &&
            retainedPage == "chat" && step.owner?.conversationId == selected?.id?.value) {
            app.daiyu.android.ui.screens.chat.conversation.ToolScreenshotContent(controller, step)
        }
        if (step.category == "generation") {
            app.daiyu.android.ui.screens.chat.conversation.GenerationToolContent(controller, step, action)
        }
        if (step.toolName in setOf("run_child_models", "start_child_tasks", "direct_child_task")) {
            app.daiyu.android.ui.screens.chat.conversation.ChildTaskToolContent(controller, step, action)
        }
    }

    @Composable private fun DedicatedQuestion(step: WorkStepUi): Boolean {
        if (step.toolName != "ask_user") return false
        val owner = step.owner ?: return false
        val question = questionCards[owner to step.toolCallId] ?: return false
        if (step.dedicatedKey != question.identity.storageKey) return false
        key(question.identity) {
            val binding = question.binding
            if (binding != null) {
                QuestionCard(question.identity, question.record, question.canAnswer, binding)
            } else {
                QuestionCard(question.identity, question.record, canAnswer = false,
                    submit = { _, _, complete ->
                        complete(app.daiyu.android.question.QuestionCardView.Reply.STOPPED)
                    })
            }
        }
        return true
    }

    @Composable private fun QuestionCleanup(id: String) {
        val row = questionRows[id] ?: return
        if (row.retryCleanup) {
            app.daiyu.android.ui.haptics.miuix.TextButton(text = "重试停止清理",
                modifier = Modifier.testTag("question-retry-cleanup"),
                onClick = { guard { controller.stop(row.message) } })
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        cancelDeferredOpenedBackup() // A recreated visit must require a fresh external open.
        state.putInt("browser-save-next", nextBrowserSaveRequestCode)
        val supplementEditor = receiptSupplementEditor()
        if (supplementDraft.pending?.editor == supplementEditor) {
            state.putString("supplement-editor-conversation", supplementEditor.conversationId)
            state.putLong("supplement-editor-generation", supplementEditor.generation)
            state.putLong("supplement-editor-revision", supplementEditor.revision)
            state.putString("supplement-editor-text", supplementEditor.text)
        }

        state.putBoolean("terminal-page", retainedPage == "terminal")
        state.putString("terminal-return-page", terminalReturnPage)
        state.putString("linux-settings-return-page", linuxSettingsReturnPage)
        state.putBoolean("balance-page", retainedPage == "balance-settings") // Route only, no balance credentials.
        state.putInt("pending-backup-request", pendingBackupRequest?.takeUnless { it == 101 || it == 103 } ?: 0)
        state.putStringArrayList("pending-conversation-exports", conversationExport.pendingConversationIds?.let { ArrayList(it) })
        if (retainedPage == "child-tasks") state.putString("task-records-conversation", taskRecordsConversationId)
        super.onSaveInstanceState(state)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        revokeBackupExportPage()
        cancelDeferredOpenedBackup() // Replacement, including invalid VIEW/other actions, revokes.
        if (intent.action == ChatApplication.OPEN_PET_SETTINGS) {
            dismissSidebar(); retainedPage = "pet-settings"
        } else offerOpenedBackup(intent)
        consumeOpenedAttachments()
    }
    override fun onResume() {
        super.onResume(); suspendMessageActionEditor(); voiceForeground = true; speechHost.setForeground(true)
        resumeOrdinaryDialogActions()
        if (retainedPage == "session-gateway" && gatewayReloadPending) loadSessionGateway(restore = true)
        chatReferencePick?.pickerAway = false
        editImagePick?.pickerAway = false
        settleEditImages()
        subAgentSourceRevision++
        refreshUnknownBranchOutcome()
        // Re-render fresh entry callbacks; none captured while backgrounded gains authority here.
        contextManagementEntryEpoch++
        browserVisualEpoch++
        controller.providerBalances.onResume(lifecycleScope, android.os.SystemClock.uptimeMillis())
        if (retainedPage == "chat") refreshConfirmedModelLabel()
        if (selectionReady) publishChat() // Re-arm foreground read confirmation; never acknowledge on background refresh.
        prepareMessageActionSnapshot()
        if (retainedPage == "platform-settings") platformSettingsRevision++
        controller.attachVideoUi(videoUi)
        settleRetainedVideoReceipt()
        controller.recoverVideos { if (activityUsable()) publishChat() }
        (application as ChatApplication).petSettingsNavigator = openPetSettings
        pets.refreshOverlayPermission()
        refreshRateWindowResumed = true; applyPreferredRefreshRate()
        window.decorView.post(refreshRateRequest)
    }
    private fun applyPreferredRefreshRate() {
        if (!refreshRateWindowResumed || refreshRateRequestRelinquished || isFinishing || isDestroyed) return
        val attributes = window.attributes
        if (requestedPreferredRefreshRate != null && attributes.preferredRefreshRate != requestedPreferredRefreshRate) {
            refreshRateRequestRelinquished = true
            return
        }
        val rate = highestSupportedRefreshRate(window.decorView.display?.supportedModes?.map { it.refreshRate }.orEmpty())
        if (rate <= 0f) return
        if (attributes.preferredRefreshRate == rate) {
            requestedPreferredRefreshRate = rate
            return
        }
        if (previousPreferredRefreshRate == null) previousPreferredRefreshRate = attributes.preferredRefreshRate
        requestedPreferredRefreshRate = rate; attributes.preferredRefreshRate = rate; window.attributes = attributes
    }
    private fun releasePreferredRefreshRate() {
        val previous = previousPreferredRefreshRate
        if (previous != null && window.attributes.preferredRefreshRate == requestedPreferredRefreshRate) {
            val attributes = window.attributes; attributes.preferredRefreshRate = previous; window.attributes = attributes
        }
        previousPreferredRefreshRate = null; requestedPreferredRefreshRate = null
    }
    override fun onPostResume() {
        super.onPostResume()
        controller.attachCommandApprovalUi(commandApprovalUi)
        controller.attachToolLocationUi(toolLocationUi)
        // ActivityResult can arrive before RESUMED. Do not open a provider until then.
        val browserTarget = browserResumeTarget
        val browserEpoch = browserVisualEpoch
        window.decorView.post {
            if (browserTarget != null && browserTarget.owner == null) {
                resumeBrowserVisualPage(browserTarget, browserEpoch)
            }
            if (generationResumed() && generationInputsDelegate.isInitialized()) generationInputs.resume()
            presentBackupImportResult()
            settleChatReference()
            settleEditImages()
            consumeOpenedAttachments()
        }
    }
    override fun onPause() {
        suspendMessageActionEditor()
        suspendOrdinaryDialogActions()
        resetRootExitConfirmation()
        cancelPaneConversationSelection()
        controller.detachCommandApprovalUi(commandApprovalUi)
        controller.detachToolLocationUi(toolLocationUi)
        toolLocationQuestion?.answer(null)
        toolLocationDialog?.dismiss()
        revokeCommandApproval()
        if (retainedPage == "session-gateway") {
            gatewayOwner?.revoke()
            gatewayReloadPending = true; gatewayReplacementAuthorized = false
        }
        revokeContextManagementEntry(closeRoute = false)
        historySearchEpoch++
        val mediaPicker = pendingGenerationMediaOriginal
        val preserveMedia = mediaPicker != null && mediaPicker.pickerHopPending && mediaPicker.active.get() &&
            generationMediaVisit === mediaPicker.active && pendingBackupRequest == mediaPicker.requestCode &&
            retainedPage == "chat" && !sidebarVisible && selected?.id?.value == mediaPicker.binding.conversationId
        mediaPicker?.pickerHopPending = false

        closeBrowserVisualVisit(clearTarget = false)
        chatReferencePick?.let { request ->
            val preserve = request.live && request.firstPickerHop && !request.returned &&
                chatReferenceAvailable() && chatReferenceMatches(request)
            request.firstPickerHop = false; request.pickerAway = preserve
            if (!preserve) revokeChatReferencePick(preserveShortcut = true)
        }
        editImagePick?.let { request ->
            val preserve = request.live && request.firstPickerHop && !request.returned &&
                request.visit === inlineMessageEditor?.visit && chatReferenceAvailable()
            request.firstPickerHop = false; request.pickerAway = preserve
            if (!preserve) { request.live = false; request.host.cancelSelection(request.selection) }
        }
        modelSaveOwner?.revoke(); modelSaveOwner = null
        gptSpeedSaveOwner?.revoke(); gptSpeedSaveOwner = null
        subAgentModelSource.clear()
        revokePlatformActionVisit()
        // Pending location UI cannot survive backgrounding. An already handed-off external GUI
        // overlay MUST survive this pause so the user can switch to the actual target app.
        taskLocationVisit?.let(::cancelTaskLocation)
        // The outstanding DocumentsUI needs this pause to return its one-shot result.
        // Once consumed, a copy owns only this visit's atomic flag: backgrounding
        // revokes it, even if a blocked provider has not returned (not a drain).
        val picker = pendingBrowserDownload
        if (picker == null || pendingBackupRequest != picker.requestCode ||
            !browserDownloadVisitCurrent(picker.visit)) cancelBrowserDownloadVisit(preservePresentation = true)
        controller.invalidateVideo(videoUi)
        videoDialog?.dismiss(); videoDialog = null
        (application as ChatApplication).let { app ->
            if (app.petSettingsNavigator === openPetSettings) app.petSettingsNavigator = null
        }
        voiceForeground = false
        voiceConversationDialogOwner = null
        unknownBranchOutcome = null // unmount only; the controller receipt is not consumed
        stopCascadeVoice()
        invalidateConfiguredVoice()
        dictationHost.invalidate()
        offlineHost.invalidate()
        cancelShizukuEditor()
        // Exempt ONLY the exact first external-picker hop. The approval UI is unmounted;
        // all unrelated editors still revoke, and worker/background pauses cannot resume it.
        val selectionOnly = generationInputsDelegate.isInitialized() && generationInputs.pause()
        val staticPicker = pendingStaticOriginal
        val preserveStatic = staticPicker != null && staticPicker.pickerHopPending &&
            pendingBackupRequest == staticPicker.requestCode && staticPicker.active.get() &&
            staticGenerationVisit === staticPicker.active && retainedPage == "chat" && !sidebarVisible &&
            selected?.id?.value == staticPicker.output.destination.conversationId
        // Only the first DocumentsUI hop survives. A later unrelated pause must revoke even
        // when the picker never returned; a consumed save keeps only its original atomic visit.
        staticPicker?.pickerHopPending = false
        cancelBrowserEditor(preserveGenerationSelection = selectionOnly, preserveStaticSelection = preserveStatic,
            preserveMediaSelection = preserveMedia, preserveOutputPresentation = true)
        speechHost.setForeground(false)
        refreshRateWindowResumed = false; window.decorView.removeCallbacks(refreshRateRequest)
        if (selectionReady) selections[selected?.id] = selectionStart to selectionEnd
        // A returned backup URI may show a one-shot confirmation. Unlike the external
        // DocumentsUI hop itself, that approval must not survive a background transition.
        // A fresh foreground visit must choose the file again.
        val preserveExportPicker = pendingBackupRequest in setOf(101, 103) && backupExportPickerHopPending &&
            backupExportPageCurrent(pendingBackupExportOwner)
        backupExportPickerHopPending = false
        if (!preserveExportPicker) revokeBackupExportPage()
        val preserveBackupPicker = pendingBackupRequest == 107 && backupImportPickerHopPending &&
            backupImportVisit != null && retainedPage == "backup"
        backupImportPickerHopPending = false
        if (!preserveBackupPicker) {
            backupImportVisit = null
            pendingBackupImportResult = null
            if (prompt?.backup == true) prompt = null
            cancelRestorePreview()
        }
        super.onPause()
    }
    override fun onStop() {
        closeBrowserVisualVisit(clearTarget = false)
        if (chatReferencePick?.pickerAway != true) revokeChatReferencePick(preserveShortcut = true)
        revokePlatformActionVisit()
        revokeContextManagementEntry(closeRoute = false)
        suspendMessageActionEditor()
        gatewayOwner?.revoke()
        controller.invalidateVideo(videoUi)
        // Idempotent fallback as well as the earlier onPause cancellation.
        voiceForeground = false
        voiceConversationDialogOwner = null
        stopCascadeVoice()
        invalidateConfiguredVoice()
        dictationHost.invalidate()
        offlineHost.invalidate()
        speechHost.setForeground(false)
        super.onStop()
    }
    override fun onDestroy() {
        toolPresentations.clear()
        suspendOrdinaryDialogActions()
        humanBrowsers.values.forEach { it.close() }
        humanBrowsers.clear()
        cancelGenerationMedia()
        revokeBackupExportPage()
        closeBrowserVisualVisit()
        revokeChatReferencePick(); chatReferencePick = null
        modelSaveOwner?.revoke(); modelSaveOwner = null
        gptSpeedSaveOwner?.revoke(); gptSpeedSaveOwner = null
        paneRefreshClosed = true
        paneRefreshSerial++
        paneRefreshPending = false
        revokePlatformActionVisit()
        taskLocationVisit?.let(::cancelTaskLocation)
        if (revokeContextManagementEntry()) editorGeneration++
        cancelDeferredOpenedBackup()
        revokeTerminalJobsVisit()
        cancelBrowserDownloadVisit()

        invalidateMessageActionEditor()
        reconnectSettingsPageOwner?.revoke()
        reconnectSettingsPageOwner = null
        gatewayOwner?.revoke(); gatewayOwner = null
        gatewayEditing = null; gatewayProviderEditor = null; gatewayDraft = null
        cancelGuiEditor()
        fenceVideoRoute()
        controller.detachVideoUi(videoUi)
        pendingTextSend?.let { controller.cancelTextSend(it.ticket) }
        linuxSettingsVisit = null
        linuxSettingsPageOwner?.close()
        linuxSettingsPageOwner = null
        generationSettingsVisit?.active?.set(false)
        generationSettingsVisit = null
        titleSettingsPageOwner?.revoke()
        titleSettingsPageOwner = null
        compressionSettingsPageOwner?.revoke()
        compressionSettingsPageOwner = null
        mcpSettingsPageOwner?.revoke()
        mcpSettingsPageOwner = null
        browserSettingsPageOwner?.revoke()
        browserSettingsPageOwner = null
        hapticsHost?.close(); hapticsHost = null
        voiceForeground = false
        voiceConversationDialogOwner = null
        revokeUniversalVoice()
        if (universalVoiceDelegate.isInitialized()) universalVoice.close()
        universalUnobserve?.invoke(); universalUnobserve = null
        try { cascadeVoice.dispose() } catch (_: Exception) { toast("级联音频释放未确认") }
        invalidateConfiguredVoice()
        dictationHost.close()
        offlineHost.close()
        interactiveModeObservation?.close()
        interactiveModeObservation = null
        cancelShizukuEditor()
        cancelBrowserEditor()
        if (generationInputsDelegate.isInitialized()) generationInputs.close()
        imageHost.close()
        speechHost.close()
        activityCallbacksAllowed = false; refreshRateWindowResumed = false
        window.decorView.removeCallbacks(refreshRateRequest); releasePreferredRefreshRate(); dismissChatModels()
        cancelRestorePreview(); prompt = null
        controller.unobserve(listener); super.onDestroy()
    }
    private fun pageBackPreviewTarget(): String? = when (retainedPage) {
        "chat" -> null
        "backup", "subagents", "appearance", "haptics", "voice-settings", "browser-settings", "mcp-settings",
        "gui-settings", "compression-settings", "reconnect-settings", "pet-settings",
        "vision-settings", "session-gateway", "balance-settings", "title-model-settings", "platform-settings" -> "settings"
        "generation-settings" -> "settings"
        "legacy-library" -> "backup"
        "terminal" -> terminalReturnPage
        "linux-settings", "chat-workspace" -> linuxSettingsReturnPage
        "usage" -> usageReturnPage
        "providers" -> providersReturnPage
        "skills" -> skillsReturnPage
        "offline-speech" -> offlineSpeechReturnPage
        "pet-model-settings" -> "pet-settings"
        "assistant-edit" -> if (assistantEditReturnPage == "chat" && assistantEditReturnToSidebar)
            "chat-sidebar" else assistantEditReturnPage
        "conversation-search", "message-search" -> searchPage?.returnPage
        "history-search" -> null
        else -> "chat"
    }

    private fun returnToChat() {
        dismissSidebar()
        dismissChatModels(); modelDraft = null; editingSettings = null; editingCatalogIdentity = null; modelEditingOwner = null; modelError = null
        cancelRestorePreview(); prompt = null; searchPage = null; retainedPage = "chat"
        taskRecordsConversationId = null
        if (activityUsable()) refresh()
    }
    private fun openSkillManagement() {
        skillsReturnPage = if (retainedPage == "settings") "settings" else "chat"
        retainedPage = "skills"
    }
    private fun closeSkillsPage() {
        if (!activityUsable() || retainedPage != "skills") return
        if (skillsReturnPage == "settings") retainedPage = "settings" else returnToChat()
    }
    private fun openGenerationSettings() {
        requireActivityUsable()
        generationSettingsVisit?.active?.set(false)
        generationSettingsVisit = GenerationSettingsVisit(GenerationSettingsEditor(applicationContext))
        retainedPage = "generation-settings"
    }
    private fun openCompressionSettings() {
        requireActivityUsable()
        compressionSettingsPageOwner?.revoke()
        compressionSettingsPageOwner = app.daiyu.android.context.CompressionSettingsEditor(applicationContext)
        retainedPage = "compression-settings"
    }
    private fun openReconnectSettings() {
        requireActivityUsable()
        reconnectSettingsPageOwner?.revoke()
        reconnectSettingsPageOwner = app.daiyu.android.model.reconnect.ReconnectSettingsSaveOwner()
        retainedPage = "reconnect-settings"
    }
    private fun openBrowserSettings() {
        requireActivityUsable()
        browserSettingsPageOwner?.revoke()
        browserSettingsPageOwner = app.daiyu.android.browser.BrowserSettingsSaveOwner()
        retainedPage = "browser-settings"
    }
    @Composable private fun TerminalLoadingPage(
        state: app.daiyu.android.terminal.TerminalApplicationState,
        onBack: () -> Unit,
        title: String = "终端",
        compactTitle: Boolean = false,
        settingsStyle: Boolean = false,
    ) {
        val content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
            item {
                app.daiyu.android.ui.screens.settings.SettingsNote(
                    if (state == app.daiyu.android.terminal.TerminalApplicationState.Unavailable)
                        "本地 Linux 配置验证失败；未创建替代终端，请核实配置后重新启动应用。"
                    else "正在工作线程验证本地 Linux 配置…",
                    error = state == app.daiyu.android.terminal.TerminalApplicationState.Unavailable)
            }
        }
        if (settingsStyle) app.daiyu.android.ui.screens.settings.SettingsScaffoldPage(
            title = title, onBack = onBack, content = content,
        ) else app.daiyu.android.ui.components.MiuixScaffoldPage(
            title = title, onBack = onBack, compactTitle = compactTitle, content = content,
        )
    }
    private fun openLinuxSettings(filesOnly: Boolean = false) {
        requireActivityUsable()
        val route = if (filesOnly) "chat-workspace" else "linux-settings"
        linuxSettingsReturnPage = app.daiyu.android.ui.navigation.linuxSettingsBackTarget(
            retainedPage, terminalReturnPage, linuxSettingsReturnPage, filesOnly)
        linuxSettingsPageOwner?.close()
        val visit = Any()
        linuxSettingsVisit = visit
        linuxSettingsPageOwner = null
        retainedPage = route
        lifecycleScope.launch {
            val state = terminalProvider.state.first {
                it != app.daiyu.android.terminal.TerminalApplicationState.Loading
            }
            if (activityUsable() && retainedPage == route && linuxSettingsVisit === visit &&
                state is app.daiyu.android.terminal.TerminalApplicationState.Ready) {
                linuxSettingsPageOwner = state.application.settings.enterPage()
            }
        }
    }
    private fun closeLinuxSettingsPage() {
        if (!activityUsable() || retainedPage !in setOf("linux-settings", "chat-workspace")) return
        when (linuxSettingsReturnPage) {
            "terminal" -> { terminalPageOwner = Any(); retainedPage = "terminal" }
            "chat" -> returnToChat()
            else -> retainedPage = "settings"
        }
    }
    private fun terminalPageCurrent(page: Any) = activityUsable() && voiceForeground &&
        retainedPage == "terminal" && terminalPageOwner === page && !sidebarVisible
    private var terminalWorkspaceCreation: Any? = null
    private fun createTerminalSession(page: Any, initialText: String? = null) {
        if (!terminalPageCurrent(page) || terminalWorkspaceCreation != null) return
        val application = (terminalProvider.state.value as? app.daiyu.android.terminal.TerminalApplicationState.Ready)
            ?.application ?: return
        val conversation = selected?.id
        val before = application.terminalSessions.state.value
        val creation = Any()
        terminalWorkspaceCreation = creation
        lifecycleScope.launch {
            val pending = java.util.concurrent.atomic.AtomicReference<app.daiyu.android.workspace.ConversationWorkspaceRun?>()
            try {
                withContext(Dispatchers.IO) { pending.set(controller.freezeConversationWorkspaceOnWorker(conversation)) }
                val frozen = checkNotNull(pending.get())
                val current = application.terminalSessions.state.value
                if (!terminalPageCurrent(page) || selected?.id != conversation || current.simple != before.simple ||
                    current.linuxSelected != before.linuxSelected ||
                    (initialText != null && current.activeSessionId != before.activeSessionId)) return@launch
                val reference = frozen.resolved.rootfs?.let {
                    app.daiyu.android.terminal.LinuxGenerationRef(it.environmentId, it.generation)
                } ?: application.terminalOwner.host.state.value.linux.selected
                val directory = frozen.terminal(reference)
                if (application.terminalSessions.create(conversation, directory, frozen)) {
                    pending.set(null)
                    if (initialText != null) application.terminalSessions.send(
                        application.terminalSessions.state.value.activeSessionId, initialText, conversation)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { if (terminalPageCurrent(page)) toast("会话工作区不可用，未创建终端") }
            finally {
                pending.getAndSet(null)?.close()
                if (terminalWorkspaceCreation === creation) terminalWorkspaceCreation = null
            }
        }
    }
    private fun openTerminal() {
        requireActivityUsable()
        terminalReturnPage = app.daiyu.android.ui.navigation.terminalBackTarget(
            retainedPage, terminalReturnPage, linuxSettingsReturnPage)
        terminalPageOwner = Any()
        retainedPage = "terminal"
    }
    private fun closeTerminalPage() {
        when (terminalReturnPage) {
            "settings" -> retainedPage = "settings"
            "linux-settings" -> openLinuxSettings()
            "chat-workspace" -> openLinuxSettings(filesOnly = true)
            else -> returnToChat()
        }
    }
    private fun openUsagePage(returnPage: String) {
        usageReturnPage = returnPage
        retainedPage = "usage"
    }
    private fun closeUsagePage() {
        if (usageReturnPage == "settings") retainedPage = "settings" else returnToChat()
    }
    private fun openSettingsRoute(route: FeatureRoute) {
        when (route) {
            FeatureRoute.MODEL_SETTINGS -> modelSettings()
            FeatureRoute.BALANCE_SETTINGS -> retainedPage = "balance-settings"
            FeatureRoute.BACKUP -> backupMenu()
            FeatureRoute.SIDEBAR -> { returnToChat(); openSidebar() }
            FeatureRoute.MANAGE_CHATS -> { updatePane(); retainedPage = "manage" }
            FeatureRoute.USAGE_STATS -> openUsagePage("settings")
            FeatureRoute.CONVERSATIONS -> chooseConversation()
            FeatureRoute.CONVERSATION_SEARCH -> searchConversations()
            FeatureRoute.FOLDERS -> browseFolders()
            FeatureRoute.SKILLS -> openSkillManagement()
            FeatureRoute.TERMINAL -> Unit
            FeatureRoute.LINUX_SETTINGS -> openLinuxSettings()
            FeatureRoute.MCP_SETTINGS -> {
                requireActivityUsable()
                mcpSettingsPageOwner?.revoke()
                mcpSettingsPageOwner = app.daiyu.android.mcp.McpSettingsOwner()
                retainedPage = "mcp-settings"
            }
            FeatureRoute.BROWSER_SETTINGS -> openBrowserSettings()
            FeatureRoute.RECONNECT_SETTINGS -> openReconnectSettings()
            FeatureRoute.GUI_SETTINGS -> { requireActivityUsable(); retainedPage = "gui-settings" }
            FeatureRoute.TITLE_MODEL_SETTINGS -> {
                requireActivityUsable()
                titleSettingsPageOwner?.revoke()
                titleSettingsPageOwner = app.daiyu.android.title.TitleModelSettingsEditor { controller.titleSettings }
                retainedPage = "title-model-settings"
            }
            FeatureRoute.COMPRESSION_SETTINGS -> openCompressionSettings()
            FeatureRoute.GENERATION_SETTINGS -> openGenerationSettings()
            FeatureRoute.PET_SETTINGS -> retainedPage = "pet-settings"
            FeatureRoute.AUXILIARY_VISION_SETTINGS -> retainedPage = "vision-settings"
            // Settings entries are navigation, never implicit execution/send/stop operations.
            FeatureRoute.CHAT, FeatureRoute.USER_MESSAGE -> Unit
        }
    }
    private fun openSidebar() { guard {
        cancelPaneConversationSelection()
        dictationHost.invalidate()
        offlineHost.invalidate()
        cancelShizukuEditor()
        cancelBrowserEditor()
        updatePane(); speechHost.setVisible(false); imageHost.setVisible(false, selected?.id?.value)
        sidebarVisible = true; publishChat()
    } }
    private fun dismissSidebar() {
        sidebarVisible = false; speechHost.setVisible(retainedPage == "chat")
        imageHost.setVisible(retainedPage == "chat", selected?.id?.value)
        if (activityUsable()) refresh()
    }
    private fun paneRefreshAllowed() = !paneRefreshClosed && activityUsable() &&
        controller.ready && !controller.dataBusy &&
        controller.problem?.startsWith("会话恢复失败") != true

    private fun updatePane() = requestPaneRefresh(invalidate = true)

    private fun requestPaneRefresh(invalidate: Boolean) {
        if (invalidate) paneRefreshSerial++
        paneRefreshPending = paneRefreshAllowed()
        if (!paneRefreshPending || paneRefreshRunning) return
        paneRefreshRunning = true
        lifecycleScope.launch {
            try {
                while (paneRefreshPending && paneRefreshAllowed()) {
                    paneRefreshPending = false
                    val request = paneRefreshSerial
                    val data = controller.dataRevision
                    val title = controller.titleRevision
                    val active = controller.active?.let { Triple(it.conversationId, it.id, it.runId) }
                    val read = paneSnapshotReader
                    val snapshot = try {
                        withContext(Dispatchers.IO) { read(active?.first) }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (!paneRefreshAllowed()) break
                    val currentActive = controller.active?.let { Triple(it.conversationId, it.id, it.runId) }
                    if (request != paneRefreshSerial || data != controller.dataRevision ||
                        title != controller.titleRevision || active != currentActive) {
                        paneRefreshPending = true
                        continue
                    }
                    if (snapshot == null) {
                        if (!paneReadFailureReported) {
                            paneReadFailureReported = true
                            toast("会话列表读取失败；原数据保留")
                        }
                        continue
                    }
                    paneReadFailureReported = false
                    val currentPane = paneState
                    val all = snapshot.conversations
                    paneState = currentPane.copy(
                        conversations = all.filter { it.folderId == currentPane.selectedFolderId },
                        historyConversations = all,
                        selectedConversationId = pendingPaneConversationId ?: selected?.id?.value,
                        folders = snapshot.folders,
                        conversationsLoaded = true,
                    )
                    paneSnapshotRevision = PaneRevision(data, title, active)
                }
            } finally {
                paneRefreshRunning = false
                paneRefreshPending = false
            }
        }
    }
    private fun cancelPaneConversationSelection() {
        val job = paneSelectionJob
        paneSelectionRequest = null
        pendingPaneConversationId = null
        paneSelectionJob = null
        job?.cancel()
        paneState = paneState.copy(selectedConversationId = selected?.id?.value)
    }

    private fun selectPaneConversation(id: String) { guard {
        check(!selectionReady || (pendingTextSend == null && controller.textSendState() == null))
        check(controller.ready && !controller.dataBusy && controller.problem?.startsWith("会话恢复失败") != true)
        cancelPaneConversationSelection()
        val request = Any()
        val owner = selected?.id
        val editor = editorGeneration
        val read = paneConversationReader
        paneSelectionRequest = request
        pendingPaneConversationId = id
        paneState = paneState.copy(selectedConversationId = id)
        paneSelectionJob = lifecycleScope.launch {
            try {
                while (paneSelectionRequest === request) {
                    val revision = currentPaneRevision()
                    val snapshot = withContext(Dispatchers.IO) { read(id) }
                    if (paneSelectionRequest !== request || !activityUsable() ||
                        retainedPage != "chat" || selected?.id != owner || editorGeneration != editor) return@launch
                    if (revision != currentPaneRevision()) continue
                    select(snapshot.conversation, snapshot)
                    break
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (paneSelectionRequest === request && activityUsable()) toast("操作或保存失败；原数据保留")
            } finally {
                if (paneSelectionRequest === request) {
                    paneSelectionRequest = null
                    pendingPaneConversationId = null
                    paneSelectionJob = null
                    if (activityUsable()) paneState = paneState.copy(selectedConversationId = selected?.id?.value)
                }
            }
        }
    } }

    private fun retainedConversation(id: String) = controller.database.conversations().single { it.id.value == id }
    private fun openRetainedConversation(id: String) { val conversation = retainedConversation(id); dismissSidebar(); returnToChat(); select(conversation) }
    private fun toggleRetainedPin(id: String) {
        val conversation = retainedConversation(id)
        controller.database.organize(conversation.id, conversation.folder, !conversation.pinned); updatePane()
    }
    private fun deleteRetainedConversation(id: String) {
        if (pendingPaneConversationId == id) cancelPaneConversationSelection()
        if (selected?.id?.value == id && revokeContextManagementEntry()) editorGeneration++
        val conversation = retainedConversation(id)
        check(controller.active?.conversationId != conversation.id && !controller.hasLiveChildTasks(conversation.id)) {
            "请先停止此会话的运行和后台子任务"
        }
        controller.mutateGenerationOwners(setOf(id), completed = { success ->
            if (activityUsable() && success) {
                if (selected?.id == conversation.id) select(null)
                updatePane()
            }
        }, mutation = { controller.database.delete(conversation.id) })
    }
    private fun confirmDelete(id: String) {
        prompt = ConfirmPrompt("删除会话", "确认删除此会话及其消息？", "删除", true,
            action = { guard { deleteRetainedConversation(id) } }, showCloseButton = false)
    }
    private fun editRetainedConversation(item: ConversationSummaryUi, folder: Boolean) { guard { editConversation(item.id, folder) } }
    private fun editConversation(id: String, folder: Boolean) {
        val current = retainedConversation(id)
        if (folder) {
            prompt = FolderPrompt(retainedData.folders(), current.folder.takeIf { it.isNotEmpty() }, { folderId -> guard {
                val destination = if (folderId == null) "" else retainedData.folders().single { it.id == folderId }.name
                val latest = retainedConversation(id)
                controller.database.organize(latest.id, destination, latest.pinned)
                if (selected?.id == latest.id) selected = retainedConversation(id)
                updatePane(); refresh()
            } })
        } else {
            prompt = InputPrompt("重命名", current.title, "名称", "保存", { value -> guard {
                val latest = retainedConversation(id)
                controller.database.rename(latest.id, value)
                if (selected?.id == latest.id) selected = retainedConversation(id)
                updatePane(); refresh()
            } })
        }
    }
    private fun searchConversations() = openSearch("conversation-search")

    private fun openSearch(route: String) {
        requireActivityUsable()
        check(controller.ready && !controller.dataBusy && controller.problem?.startsWith("会话恢复失败") != true)
        require(route in setOf("conversation-search", "message-search", "history-search"))
        val origin = if (retainedPage == "manage") "manage" else "chat"
        updatePane()
        prompt = null
        searchPage = SearchPage(route, origin, selected?.id?.value, controller.active, paneState.selectedFolderId)
        // Transition first: dismissSidebar must not briefly make hidden chat speech visible.
        retainedPage = route
        dismissSidebar()
    }

    private fun historySearchHitAction(page: SearchPage): (MessageSearchHit) -> Unit {
        val epoch = historySearchEpoch
        return { hit ->
            if (activityUsable() && epoch == historySearchEpoch && searchPage === page && retainedPage == page.route &&
                selected?.id?.value == page.conversationId && hit.conversationId == page.conversationId &&
                lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                guard { openHistoryHit(hit) }
            }
        }
    }

    private fun closeSearch(page: SearchPage) {
        if (!activityUsable() || searchPage !== page || retainedPage != page.route) return
        searchPage = null
        try {
            if (page.returnPage == "manage") {
                retainedPage = "manage"
                updatePane()
            } else returnToChat()
        } catch (_: Exception) {
            // A failed reader must not trap Back or turn a refresh error into an Activity crash.
            toast("读取失败；原数据保留")
        }
    }
    private fun browseFolders() {
        val folders = controller.database.conversations().map { it.folder }.distinct().sorted()
        prompt = ChoicePrompt("文件夹", folders.map { it.ifEmpty { "未分类" } }, { index -> guard { chooseConversation(folder = folders[index]) } })
    }
    private fun chooseConversation(query: String = "", folder: String? = null) {
        val list = controller.database.conversations(query, folder)
        prompt = ChoicePrompt("会话", list.map { "${if (it.pinned) "★ " else ""}${if (it.folder.isNotEmpty()) "[${it.folder}] " else ""}${it.title}" },
            { index -> guard {
                val id = list[index].id.value
                val conversation = retainedConversation(id)
                prompt = ChoicePrompt(conversation.title, listOf("打开", "重命名", "删除", if (conversation.pinned) "取消置顶" else "置顶", "移动到文件夹"),
                    { action -> guard { when (action) {
                        0 -> openRetainedConversation(id)
                        1 -> editConversation(id, false)
                        2 -> confirmDelete(id)
                        3 -> { toggleRetainedPin(id); chooseConversation(query, folder) }
                        4 -> editConversation(id, true)
                    } } })
            } })
    }
    private fun openHistoryHit(hit: MessageSearchHit) {
        val conversation = retainedConversation(hit.conversationId)
        val sequence = controller.database.readableDatabase.rawQuery("SELECT seq FROM messages WHERE conversation_id=? AND id=?",
            arrayOf(conversation.id.value, hit.ownerMessageId)).use { cursor -> check(cursor.moveToFirst()) { "命中消息已不存在" }; cursor.getLong(0) }
        val window = controller.database.messages(conversation.id, sequence + 1, 50).toMutableList()
        // Resolve the owner/window before leaving search, so a deleted hit keeps its source page.
        openRetainedConversation(hit.conversationId)
        shown = window
        prepareMessageActionSnapshot(); publishChat(); requestScroll(hit.messageId, false, hit.segmentIndex)
    }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    private fun guard(action: () -> Unit) {
        try { requireActivityUsable(); check(!controller.dataBusy); action() }
        catch (_: Exception) { toast("操作或保存失败；原数据保留") }
    }
    private fun updateDraft(value: String) {
        if (!activityUsable() || sidebarVisible || controller.dataBusy || !controller.ready || controller.textSendState() != null || pendingTextSend != null || imageHost.sending || shizukuWritesBlocked) return
        if (value == draft) return
        controller.invalidateVideo(videoUi)
        videoDialog?.dismiss(); videoDialog = null
        if (!applyingDictationDraft) { dictationHost.invalidate(); offlineHost.invalidate() }
        cancelShizukuEditor()
        cancelBrowserEditor()
        cancelGuiEditor()
        guard {
            if (draft != value && !imageHost.beforeTextEdit()) return@guard
            val id = selected?.id
            if (id == null) controller.database.draft(value) else controller.database.draft(id, value)
            if (draft != value) draftRevision++
            draft = value; publishChat(rowsChanged = false)
        }
    }
    internal fun createNewConversation() {
        requireActivityUsable()
        check(pendingTextSend == null && controller.textSendState() == null) { "请等待本次发送结果" }
        check(controller.ready && !controller.dataBusy && controller.problem?.startsWith("会话恢复失败") != true)
        val source = selected?.id
        val reasoning = reasoningPreference.snapshot(source?.value)
        val created = app.daiyu.android.context.DatabaseTransactions { controller.database.writableDatabase }.run {
            val assistant = if (source == null) controller.database.selectedAssistantId()
                else checkNotNull(controller.database.assistantId(source)) { "请先为当前会话绑定助手" }
            controller.database.create(assistant, modelSource = source).also {
                reasoningPreference.retainAccepted(it.id.value, reasoning)
            }
        }
        select(created)
        updatePane()
    }

    internal fun select(conversation: Conversation?) {
        cancelPaneConversationSelection()
        select(conversation, null)
    }

    private fun select(conversation: Conversation?, prepared: PaneConversationSnapshot?) {
        requireActivityUsable()
        acceptedTextPreview = null
        gptSpeedSaveOwner?.revoke(); gptSpeedSaveOwner = null
        revokeCommandApproval()
        check(!selectionReady || (pendingTextSend == null && controller.textSendState() == null)) { "请等待本次发送结果" }
        check(controller.ready && !controller.dataBusy && controller.problem?.startsWith("会话恢复失败") != true)
        if (selectionReady && conversation == null) {
            reasoningPreference.inheritDraft(selected?.id?.value)
            controller.database.inheritModelBinding(selected?.id, null)
        }
        historySearchEpoch++
        if (revokeContextManagementEntry()) editorGeneration++
        voiceConversationDialogOwner = null
        // Replacing the editor retires its visit, not the mounted chat route.
        fenceVideoRoute(detachHost = false)
        val shizukuOwned = shizukuEditor != null || shizukuWritesBlocked
        cancelShizukuEditor()
        cancelBrowserEditor()
        cancelGuiEditor()
        val list = prepared?.conversations ?: controller.database.conversations()
        var targetDraft = if (conversation == null) controller.database.draft() else list.first { it.id == conversation.id }.draft
        if (selectionReady) {
            val id = selected?.id
            // A submitted image revision is already persisted and frozen. Its DB acceptance
            // may precede the UI receipt; writing it again here could resurrect sent text.
            if (!imageHost.sending && !shizukuOwned && !videoSending) {
                if (id == null) controller.database.draft(draft) else if (list.any { it.id == id }) controller.database.draft(id, draft)
            }
            selections[id] = selectionStart to selectionEnd
        }
        if (selectionReady && selected?.id == conversation?.id && !imageHost.sending && !shizukuOwned && !videoSending) targetDraft = draft
        // Commit the owner before allowing any text callbacks to write to it.
        check(controller.preferences.edit().apply {
            if (conversation == null) remove("selected") else putString("selected", conversation.id.value)
        }.commit())
        editorGeneration++
        // Even A -> A re-entry gets a new capability; old click lambdas cannot act on it.
        stopCascadeVoice()
        speechHost.enter(conversation?.id?.value)
        imageHost.enter(conversation?.id?.value)
        videoHost.enter(conversation?.id?.value)
        draftRevision = maxOf(draftRevision + 1, imageHost.session.attachments.state.value.draft.revision)
        val restoringSupplement = !selectionReady
        selected = conversation; selectionReady = true; draft = targetDraft
        dismissChatModels()
        confirmedModelLabel = "模型"; confirmedReasoningModel = ""; confirmedGptSpeed = null
        paneState = paneState.copy(selectedConversationId = conversation?.id?.value)
        if (restoringSupplement) {
            restoredSupplementEditor = restoredSupplementEditor?.takeIf {
                it.conversationId == conversation?.id?.value && it.text == draft
            }
            supplementMountEditor = restoredSupplementEditor?.let { supplementEditor() }
        }
        val range = selections[conversation?.id]
        selectionStart = (range?.first ?: draft.length).coerceIn(0, draft.length)
        selectionEnd = (range?.second ?: draft.length).coerceIn(0, draft.length)
        shown = if (conversation == null) mutableListOf() else (prepared?.messages ?: controller.database.messages(conversation.id)).toMutableList()
        childStatusVisit = null; usageVisit = null; revokeWorkspaceVisit(); collaborationOwner = null; collaborationVisible = false
        publishChat(rowsChanged = true, scrollRequest = ChatScrollRequest(++scrollSerial)); refresh()
        refreshConfirmedModelLabel()
    }
    private fun compressionSendBlocked(conversationId: ConversationId? = selected?.id): Boolean {
        if (!controller.isMainContextCompressing(conversationId)) return false
        toast("正在压缩上下文")
        return true
    }
    private fun sendDraft(text: String, universal: UniversalSend? = null) {
        if (compressionSendBlocked()) {
            universal?.result?.invoke(VoiceControlReceipt.Rejected(universal.turn.requestId)); return
        }
        if (pendingTextSend != null || text != draft || text.isBlank()) {
            universal?.result?.invoke(VoiceControlReceipt.Rejected(universal.turn.requestId)); return
        }
        val owner = ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision)
        // Persist the exact source revision before the worker freezes history or calls a summary model.
        if (owner.conversationId == null) controller.database.draft(text)
        else controller.database.draft(owner.conversationId, text)
        val pending = PendingTextSend(owner, text)
        if (universal != null) {
            check(universal.expected == owner)
            universalTickets[pending.ticket] = universal
        }
        cascadeVoice.reserve(pending.ticket)
        acceptedTextPreview = null
        pendingTextSend = pending
        var unknown = false
        val queued = try {
            controller.sendText(selected, owner.editorGeneration, owner.draftRevision, text, pending.ticket) { result ->
                // The original voice association survives editor loss, Unknown and Activity teardown.
                completeTextSend(pending, result, universal)
            }
        } catch (_: Exception) {
            controller.cancelTextSend(pending.ticket)
            unknown = true
            universal?.result?.invoke(VoiceControlReceipt.Unknown(universal.turn.requestId))
            if (universal != null) revokeUniversalVoice()
            false
        }
        if (!queued && !unknown && universal != null) {
            universalTickets.remove(pending.ticket)
            universal.result(VoiceControlReceipt.Rejected(universal.turn.requestId))
            revokeUniversalVoice()
        }
        if (!queued && pendingTextSend === pending) {
            cascadeVoice.rejected(pending.ticket)
            pendingTextSend = null
            toast("本次发送未受理；草稿保留")
        }
        publishChat()
        if (queued) jumpToBottom()
    }
    private fun cancelPendingTextSend(pending: PendingTextSend) {
        if (activityUsable() && pendingTextSend === pending) {
            controller.cancelTextSend(pending.ticket)
            publishChat()
        }
    }
    private fun completeTextSend(pending: PendingTextSend, result: ChatController.TextSendResult,
        universal: UniversalSend? = universalTickets[pending.ticket]) {
        if (universal == null) { completeTextSendUi(pending, result); return }
        val before = ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision)
        val samePermit = universalVoiceDelegate.isInitialized() && universalVoice.state.value.active &&
            universalVoice.state.value.permitId == universal.turn.permit.id
        val completingOriginal = activityUsable() && pendingTextSend === pending
        val ownsEditor = samePermit && completingOriginal && pending.owner == result.owner &&
            pending.editorOwner == before && universalEditor == before && draft == pending.text
        universalOwnMutation = ownsEditor
        try { completeTextSendUi(pending, result) }
        finally {
            universalOwnMutation = false
            val after = ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision)
            if (ownsEditor && productionUniversalEditorMigration(universal.expected, before, after,
                    result is ChatController.TextSendResult.Accepted, draft.isEmpty())) migrateUniversalEditor(before, after)
            else if (samePermit && completingOriginal) revokeUniversalVoice()
            val receipt = productionUniversalReceipt(universal.turn, universal.expected, result) { owner, exact ->
                if (universal.retained.add(owner)) universalAssistants[owner] = exact
            }
            if (result !is ChatController.TextSendResult.Unknown) universalTickets.remove(pending.ticket)
            if (samePermit && completingOriginal) refreshUniversalAdmission()
            universal.result(receipt) // Even a late durable Accepted must reach the host's exact cleanup fence.
        }
    }
    private fun completeTextSendUi(pending: PendingTextSend, result: ChatController.TextSendResult) {
        if (pendingTextSend !== pending) return
        if (!activityUsable()) return
        pendingTextSend = null
        controller.acknowledgeTextSend(pending.ticket)
        val matches = result.owner == pending.owner && selected?.id == pending.editorOwner.conversationId &&
            editorGeneration == pending.editorOwner.editorGeneration && draftRevision == pending.editorOwner.draftRevision &&
            draft == pending.text
        if (!matches) { cascadeVoice.rejected(pending.ticket); refresh(); return }
        when (result) {
            is ChatController.TextSendResult.Accepted -> {
                val saved = result.conversation
                acceptedTextPreview = result.assistant?.let { assistant ->
                    saved.id to app.daiyu.android.ui.screens.chat.conversation.ChatSubmissionPreview(
                        pending.ticket.previewKey, pending.text, settledAssistantId = assistant.id.value, settledUserId = result.userId)
                }
                editorGeneration++; draftRevision++
                if (selected?.id != saved.id) shown = mutableListOf()
                selected = saved; selectionReady = true
                controlOwner = controller.active?.takeIf { it.conversationId == saved.id && it.status == RunStatus.RUNNING }
                draft = ""; selectionStart = 0; selectionEnd = 0
                try { cascadeVoice.accepted(pending.ticket, result.assistant) }
                catch (_: Exception) { stopCascadeVoice(); toast("级联未启动，聊天发送不受影响") }
                val savedSelection = runCatching {
                    controller.preferences.edit().putString("selected", saved.id.value).commit()
                }.getOrDefault(false)
                publishChat()
                loadAcceptedHistory(saved)
                if (!savedSelection) toast("消息已保存；最后选中会话未保存")
            }
            is ChatController.TextSendResult.Rejected -> {
                cascadeVoice.rejected(pending.ticket)
                toast(controller.problem ?: "本次发送未受理；草稿保留")
                publishChat()
            }
            is ChatController.TextSendResult.Unknown -> {
                cascadeVoice.rejected(pending.ticket)
                toast("发送保存结果未确认；草稿保留，不会自动重发")
                publishChat()
            }
        }
    }
    private fun currentChatControlAvailable() = voiceForeground && retainedPage == "chat" && !sidebarVisible
    private fun supplementEditor() = SupplementEditor(selected?.id?.value, editorGeneration, draftRevision, draft)
    private fun receiptSupplementEditor(): SupplementEditor {
        val current = supplementEditor()
        return restoredSupplementEditor?.takeIf { supplementMountEditor == current } ?: current
    }
    private fun settleSupplementReceipt() {
        if (!selectionReady || !activityUsable()) return
        val (ticket, result) = supplementDraft.receipt ?: return
        val current = receiptSupplementEditor()
        if (ticket.editor == current && (sidebarVisible || controller.dataBusy || !controller.ready ||
                controller.textSendState() != null || pendingTextSend != null || imageHost.sending || shizukuWritesBlocked)) return
        if (result is SupplementAdmission.Accepted && ticket.editor == current) {
            updateDraft("")
            if (draft.isNotEmpty()) return
        }
        val clear = supplementDraft.finish(ticket, result, current)
        restoredSupplementEditor = null; supplementMountEditor = null
        if (clear) {
            selectionStart = 0; selectionEnd = 0; editorGeneration++
        }
        when (result) {
            is SupplementAdmission.Accepted -> toast("追加内容已保存，将在当前任务的安全边界继续")
            is SupplementAdmission.Rejected -> toast("未接受追加，草稿已保留")
            is SupplementAdmission.Unconfirmed -> toast("追加结果未确认，草稿已保留；不会自动重试")
        }
    }
    private fun supplementMediaBlocked() = imageHost.hasAttachments || imageHost.busy ||
        videoHost.state.items.isNotEmpty() || videoSending || videoComposerControls?.blocked == true
    private fun submitSupplement(owner: Message, text: String) {
        if (compressionSendBlocked(owner.conversationId)) return
        if (text != draft) return
        if (supplementMediaBlocked()) {
            toast("当前任务暂不支持追加图片或原视频；文字与附件已保留，请任务结束后发送")
            return
        }
        if (!controller.canSupplement(owner)) {
            toast("当前任务暂不能追加；草稿已保留")
            return
        }
        val ticket = supplementDraft.begin(MessageId(java.util.UUID.randomUUID().toString()), supplementEditor()) ?: return
        publishChat()
        try {
            val queued = controller.admitSupplement(owner, ticket.messageId, text) {
                if (activityUsable()) refresh()
            }
            if (queued && controller.runState(owner)?.phase == ExecutionPhase.WAITING_USER)
                toast("追加已排队，等待当前提问处理；草稿在受理确认前保留")
            if (queued) window.decorView.postDelayed({
                if (activityUsable() && supplementDraft.pending === ticket && supplementDraft.receipt == null)
                    toast("尚未收到追加确认，请先检查会话，草稿保留；不会自动重试")
            }, 15_000L)
        } catch (_: Exception) {
            supplementDraft.receive(SupplementAdmission.Unconfirmed(ticket.messageId))
            settleSupplementReceipt()
            publishChat()
        }
    }
    private fun currentControlOwner(): Message? =
        (if (controlTouchActive) controlTouchOwner else controlOwner)?.takeIf { it.conversationId == selected?.id }
    private fun refresh() {
        if (!activityUsable()) return
        childStopChoice = controller.childStopChoice()
        refreshUnknownBranchOutcome()
        offerDeferredOpenedBackup()
        settleGenerationReceipt()
        virtualDisplayCleanupVisit?.let { visit ->
            if (!cleanupVisitCurrent(visit)) closeVirtualDisplayCleanup(visit)
        }
        virtualDisplayEditor?.let { entry ->
            if (controller.virtualDisplayEntry() !== entry) {
                virtualDisplayEditor = null; virtualDisplayRequest = null
            } else virtualDisplayRequest = controller.virtualDisplayApproval(entry)
        }
        trackShizukuPreparation()
        backupBusy = controller.dataBusy
        backupStatus = controller.dataStatus
        chatModelPicker = chatModelPicker?.let { picker -> picker.copy(enabled = !picker.loading && !picker.switching &&
            !chatModelSelectionFailed && (chatModelProviderEditor == null || chatModelSettings != null) &&
            chatModelProviderChoices.isNotEmpty() && chatModelSession != null &&
            !controller.dataBusy && controller.ready) }
        controlOwner = controller.active?.takeIf { it.conversationId == selected?.id && it.status == RunStatus.RUNNING }
        if (controller.dataBusy) {
            if (prompt?.backup == true) prompt = null
            cancelRestorePreview(); publishChat(); return
        }
        if (!controller.ready || controller.problem?.startsWith("会话恢复失败") == true) { publishChat(); return }
        guard {
            terminalEditor?.let { editor ->
                val pending = editor.preparation
                if (pendingTextSend == null && pending != null &&
                    (controller.terminalPreparation() !== pending || pending.isCancelled ||
                        terminalDraft() != pending.draft || runCatching {
                        controller.terminalConfiguration(editor.conversation)
                    }.getOrNull() != (pending.assistant to pending.provider))) cancelTerminalEditor()
            }
            if (!selectionReady) {
                val id = controller.preferences.getString("selected", null)
                select(controller.database.conversations().firstOrNull { it.id.value == id }); return@guard
            }
            if (modelDeletionRevision != controller.modelDeletionRevision) {
                modelDeletionRevision = controller.modelDeletionRevision
                refreshConfirmedModelLabel()
            }
            if (dataRevision != controller.dataRevision) {
                val initialSupplementRestore = dataRevision == -1L && supplementMountEditor == supplementEditor()
                dataRevision = controller.dataRevision
                cancelShizukuEditor()
                cancelBrowserEditor()
                controller.revokeImageProvider()
                imageHost.enter(selected?.id?.value)
                val latest = selected?.id?.let { id -> controller.database.conversations().firstOrNull { it.id == id } }
                if (selected != null && latest == null) { select(null); return@guard }
                val retainedMessageEdit = inlineMessageEditor?.takeIf { it.target.conversationId == latest?.id }
                if (retainedMessageEdit != null) inlineMessageEditor = null
                editorGeneration++
                selected = latest
                refreshConfirmedModelLabel()
                inlineMessageEditor = retainedMessageEdit?.copy(visit = Any())
                draft = latest?.draft ?: controller.database.draft()
                if (initialSupplementRestore && restoredSupplementEditor?.let {
                        it.conversationId == selected?.id?.value && it.text == draft } == true)
                    supplementMountEditor = supplementEditor()
                selectionStart = selectionStart.coerceIn(0, draft.length); selectionEnd = selectionEnd.coerceIn(0, draft.length)
            }
            if (titleRevision != controller.titleRevision) {
                titleRevision = controller.titleRevision
                // Title-only refresh must not replace a live draft or revoke its editor owner.
                selected?.let { current ->
                    controller.database.readableDatabase.rawQuery("SELECT title FROM conversations WHERE id=?",
                        arrayOf(current.id.value)).use { row ->
                        if (row.moveToFirst()) selected = current.copy(title = row.getString(0))
                    }
                }
            }
            settleSupplementReceipt()
            val beforeVideoReceipt = editorGeneration
            settleRetainedVideoReceipt()
            if (editorGeneration != beforeVideoReceipt) return@guard
            // Capture the replacement editor only after its initial data revision has been restored.
            val sending = controller.textSendState()
            if (sending != null) {
                if (sending.owner.conversationId == selected?.id) {
                    var pending = pendingTextSend
                    if (pending == null) {
                        // Adopt the same application-owned submission after Activity recreation.
                        // An accepted transaction has already cleared its DB draft; never write it back.
                        if (sending.result is ChatController.TextSendResult.Accepted && draft.isEmpty()) draft = sending.text
                        pending = PendingTextSend(sending.owner, sending.text, sending.ticket,
                            ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision))
                        pendingTextSend = pending
                    }
                    sending.result?.let { completeTextSend(pending, it); return@guard }
                } else if (sending.result != null) {
                    // Another editor may observe completion, but may not consume its text or selection.
                    controller.acknowledgeTextSend(sending.ticket)
                }
            }
            controller.consumeDataNotice()?.let { toast(it) }
            controller.consumeTitleNotice()?.let { toast(it) }
            // Keep the already-loaded streaming owner live while the single IO reader is outstanding.
            val active = controller.active?.takeIf { it.conversationId == selected?.id }
            if (active != null) {
                val owner = Triple(active.conversationId, active.id, active.runId)
                shown = shown.map { entry ->
                    if (Triple(entry.second.conversationId, entry.second.id, entry.second.runId) == owner)
                        entry.first to active else entry
                }.toMutableList()
            }
            prepareMessageActionSnapshot()
            publishChat()
            selected?.let { conversation ->
                val loaded = active != null && shown.any { (_, message) ->
                    message.conversationId == active.conversationId && message.id == active.id && message.runId == active.runId
                }
                if (streamingHistoryRefresh.needsRead(active, loaded, controller.dataRevision, editorGeneration,
                        force = refreshHistoryTailConversation == conversation.id)) loadRefreshedHistory(conversation)
            }
            if (sidebarVisible || retainedPage == "manage" || paneSnapshotRevision != currentPaneRevision())
                requestPaneRefresh(invalidate = false)
        }
        consumeOpenedAttachments()
    }
    private fun consumeOpenedAttachments() {
        if (!app.daiyu.android.attachments.OpenedAttachmentInbox.hasPending() || !generationResumed()) return
        if ((!controller.ready || !selectionReady) && controller.problem?.startsWith("会话恢复失败") != true) return
        val batch = app.daiyu.android.attachments.OpenedAttachmentInbox.take() ?: return
        val store = app.daiyu.android.attachments.OpenedFileStore(applicationContext)
        var accepted = false
        try {
            if (!controller.ready || !selectionReady || batch.conversation != selected?.id?.value ||
                controller.dataBusy || controller.active != null || pendingTextSend != null ||
                controller.textSendState() != null || imageHost.busy || imageHost.hasAttachments ||
                videoHost.state.items.isNotEmpty() || videoSending || shizukuWritesBlocked ||
                controller.problem?.startsWith("会话恢复失败") == true) {
                toast("当前会话已变化或正在运行；未添加附件，请稍后重新打开")
                return
            }
            val owner = ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision)
            val original = draft
            returnToChat(); dismissSidebar()
            if (ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision) != owner || draft != original ||
                controller.dataBusy || pendingTextSend != null || controller.textSendState() != null) {
                toast("当前会话已变化；未添加附件，请重新打开")
                return
            }
            val references = batch.documents.joinToString("\n") { it.reference() }
            val next = if (draft.isEmpty()) references else "$draft\n$references"
            updateDraft(next)
            accepted = draft == next
            if (accepted) { selectionStart = next.length; selectionEnd = next.length; publishChat(rowsChanged = false) }
            else toast("附件未加入；原草稿保留，请重新打开")
        } finally {
            if (!accepted) lifecycleScope.launch(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { batch.documents.forEach(store::remove) }
        }
    }

    private fun currentAssistantLabel(): String = try {
        val binding=selected?.id?.let(controller.database::assistantId) ?: if(selected==null) assistantStore.selectedId.value else null
        if(binding==null) "未绑定助手" else assistantStore.read(binding)?.name ?: "助手已删除，请重新绑定"
    } catch (_:Exception) { "助手不可用" }
    private fun savedModelLabel(): String = controller.retainedDeletedMainModel(selected?.id?.value) ?: confirmedModelLabel
    private fun readLoadedWindow(id: ConversationId, before: Long, count: Int): List<Pair<Long, Message>> {
        val result = mutableListOf<Pair<Long, Message>>()
        var cursor = before
        var remaining = count
        while (remaining > 0) {
            val page = controller.database.messages(id, cursor, remaining.coerceAtMost(200))
            if (page.isEmpty()) break
            result.addAll(0, page); remaining -= page.size; cursor = page.first().first
        }
        return result
    }
    private var timelineConversation: ConversationId? = null
    private var timelineEntries: List<app.daiyu.android.timeline.ChatTimelineEntry> = emptyList()
    private var timelineRead: kotlinx.coroutines.Job? = null
    private var timelineReadRevision = -1L
    private fun refreshTimeline() {
        val id = selected?.id
        if (timelineConversation != id) {
            timelineRead?.cancel(); timelineRead = null
            timelineConversation = id; timelineEntries = emptyList(); timelineReadRevision = -1L
        }
        if (id == null || timelineRead?.isActive == true) return
        val revision = controller.timelineVersion
        if (timelineReadRevision == revision) return
        val generation = editorGeneration
        timelineRead = lifecycleScope.launch {
            try {
                val entries = try { withContext(Dispatchers.IO) { controller.timelineHistory(id) } }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { return@launch }
                if (!activityUsable() || selected?.id != id || editorGeneration != generation) return@launch
                timelineEntries = entries; timelineReadRevision = revision
                publishChat(rowsChanged = false)
            } finally {
                if (timelineRead === kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]) {
                    timelineRead = null
                    if (activityUsable() && selected?.id == id &&
                        (controller.timelineVersion != revision || editorGeneration != generation)) refreshTimeline()
                }
            }
        }
    }
    private data class ToolReceiptKey(val conversation: ConversationId?, val data: Long, val generation: Long,
        val sources: List<app.daiyu.android.ui.RetainedToolOutcomes.Source>, val execution: app.daiyu.android.domain.runtime.ExecutionState?)
    private val toolPresentations = app.daiyu.android.ui.screens.chat.conversation.ToolPresentationCache()
    private var toolReceiptKey: ToolReceiptKey? = null
    private var toolReceiptSnapshot: app.daiyu.android.ui.RetainedToolOutcomes.Snapshot? = null
    private var toolReceiptRead: kotlinx.coroutines.Job? = null
    private var toolReceiptRequested: ToolReceiptKey? = null
    private var toolReceiptRequest: Any? = null
    private fun currentToolReceiptKey() = ToolReceiptKey(selected?.id, controller.dataRevision, editorGeneration,
        app.daiyu.android.ui.RetainedToolOutcomes.sources(shown.map { it.second }),
        controller.active?.takeIf { it.conversationId == selected?.id }?.let(controller::runState))

    private fun prepareToolReceipts(): Boolean {
        val key = currentToolReceiptKey()
        if (toolReceiptKey == key) return true
        if (key.sources.isEmpty()) {
            toolReceiptRead?.cancel(); toolReceiptRead = null; toolReceiptRequested = null; toolReceiptRequest = null
            toolReceiptKey = key; toolReceiptSnapshot = null
            return true
        }
        if (toolReceiptRead?.isActive == true && toolReceiptRequested == key) return false
        toolReceiptRead?.cancel()
        toolReceiptRequested = key
        val request = Any()
        toolReceiptRequest = request
        val rows = shown.map { it.second }
        toolReceiptRead = lifecycleScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    app.daiyu.android.ui.RetainedToolOutcomes.Snapshot.read(controller.database, rows)
                }
                if (!activityUsable() || toolReceiptRequest !== request || toolReceiptRequested != key ||
                    currentToolReceiptKey() != key) return@launch
                toolReceiptKey = key; toolReceiptSnapshot = snapshot
                publishChat()
            } finally {
                if (toolReceiptRequest === request) {
                    toolReceiptRead = null; toolReceiptRequested = null; toolReceiptRequest = null
                }
            }
        }
        return false
    }

    private fun publishChat() = publishChat(rowsChanged = true)

    private fun publishChat(rowsChanged: Boolean, scrollRequest: ChatScrollRequest? = chatState.scrollRequest) {
        if (rowsChanged) refreshTimeline()
        contextReceiptRevision = controller.contextUsageRevision
        conversationUsageRevision = controller.usageRevision
        taskLocationVisit?.takeIf { !taskLocationCurrent(it) }?.let(::cancelTaskLocation)
        guiEditor?.let { entry ->
            if (controller.guiEntry() !== entry) guiEditor = null
            else if (!entry.submitted && !guiOwnerCurrent(entry)) cancelGuiEditor(entry)
        }
        refreshUniversalAdmission()
        cascadeVoice.update(controller.active, shown.map { it.second }, selected?.id?.value)
        syncHaptics()
        // Also catches new-chat -> persisted conversation after sending, without auto-playing.
        speechHost.syncConversation(selected?.id?.value)
        imageHost.syncConversation(selected?.id?.value)
        if (rowsChanged) refreshReplyIdentities()
        if (rowsChanged) questionRows = shown.mapNotNull { (_, message) ->
            val retryCleanup = controller.canRetryQuestionCleanup(message)
            if (!retryCleanup) null else message.id.value to QuestionRowUi(message, retryCleanup)
        }.toMap()
        val state = controlOwner?.let { controller.runState(it) }
        val phase = composerPhaseForRun(state?.pauseRequested)
        val composerAvailable = !sidebarVisible && controller.ready && controller.problem?.startsWith("会话恢复失败") != true &&
            controller.textSendState() == null && pendingTextSend == null && !imageHost.sending &&
            !shizukuWritesBlocked && !mcpPreparing && mcpApproval == null
        val allowed = composerAvailable && !controller.dataBusy
        val messageEditBlocked = inlineMessageEditor?.let { !messageEditTargetCurrent(it) } == true
        val visibleTimeline = app.daiyu.android.timeline.withStoppedRuns(
            shown.map { it.second }, timelineEntries.takeIf { timelineConversation == selected?.id }.orEmpty())
        val visibleCompressions = controller.compressionActivities.filter { it.conversationId == selected?.id?.value }
        val workflowBoundaries = visibleTimeline.filterNot { it is app.daiyu.android.timeline.ChatTimelineEntry.Stopped }
            .map { it.anchor.listKey }.toSet() +
            app.daiyu.android.timeline.compressionActivitiesByAnchor(visibleCompressions, visibleTimeline).keys.filterNotNull()
        val oldBoundaries = chatState.timeline.filterNot { it is app.daiyu.android.timeline.ChatTimelineEntry.Stopped }
            .map { it.anchor.listKey }.toSet() +
            app.daiyu.android.timeline.compressionActivitiesByAnchor(chatState.compressionActivities, chatState.timeline).keys.filterNotNull()
        val sendPreview = pendingTextSend?.takeIf {
            it.editorOwner == ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision)
        }?.let { pending ->
            val receipt = controller.textSendState()?.takeIf { it.ticket === pending.ticket }?.result
                as? ChatController.TextSendResult.Accepted
            app.daiyu.android.ui.screens.chat.conversation.ChatSubmissionPreview(pending.ticket.previewKey,
                pending.text, settledAssistantId = receipt?.assistant?.id?.value, settledUserId = receipt?.userId)
        } ?: acceptedTextPreview?.takeIf { it.first == selected?.id }?.second
        val regeneration = controller.regenerationPreview()?.takeIf { it.target.conversationId == selected?.id }
        val submissionPreview = regeneration?.let { preview ->
            val target = preview.target
            val key = app.daiyu.android.timeline.TimelineAnchor(target.conversationId.value, target.runId.value,
                target.messageId.value, target.segmentIndex).listKey
            app.daiyu.android.ui.screens.chat.conversation.ChatSubmissionPreview("pending-regeneration:$key",
                replaceFromKey = key, settledAssistantId = preview.assistantId)
        } ?: sendPreview
        toolPresentations.scope(selected?.id?.value)
        val receiptsReady = prepareToolReceipts()
        if (receiptsReady) questionCards = buildMap {
            MessageHistory.expand(shown.map { it.second }).forEach { message ->
                val owner = WorkStepOwnerUi(message.conversationId.value, message.runId.value, message.id.value)
                message.blocks.filterIsInstance<MessageBlock.Tool>().forEach { block ->
                    controller.questionProjection(message, block.invocation, toolReceiptSnapshot?.questionEvidence(message))?.let { question ->
                        put(owner to block.invocation.id.value, question)
                    }
                }
            }
        }
        else questionCards = if (toolReceiptKey?.conversation != selected?.id) emptyMap()
            else questionCards.mapValues { (_, question) -> question.copy(binding = null, canAnswer = false) }
        val remapTurns = receiptsReady && (rowsChanged || workflowBoundaries != oldBoundaries)
        chatState = chatState.copy(title = selected?.title ?: "代鱼", conversationKey = selected?.id?.value,
            scrollRequest = scrollRequest,
            contentRevision = chatState.contentRevision + if (rowsChanged) 1 else 0,
            timeline = visibleTimeline,
            compressionActivity = null,
            compressionActivities = visibleCompressions,
            turns = ChatTurnMapper.withStreamingOwner(if (!remapTurns) chatState.turns.takeIf { chatState.conversationKey == selected?.id?.value }.orEmpty() else ChatTurnMapper.map(shown.map { it.second },
                boundariesAfter = workflowBoundaries, assistantIdentity = { _, owner -> replyIdentities[owner] }, dedicatedTool = { message, block ->
                val owner = WorkStepOwnerUi(message.conversationId.value, message.runId.value, message.id.value)
                questionCards[owner to block.invocation.id.value]?.identity?.storageKey
            }, timestamp = { message ->
                MessageTimestampFormatter.timestamp(message, enabled = appearance.messageTimestampsEnabled)
            }, presentations = toolPresentations, toolReceipt = toolReceiptSnapshot?.projectionReader(controller::runState, controller::runStopping) ?: { _, _ -> null }),
                controlOwner, phase = state?.phase ?: ExecutionPhase.IDLE, pauseRequested = state?.pauseRequested == true,
                stopRequested = controlOwner?.let(controller::runStopping) == true),
            submissionPreview = submissionPreview,
            statusText = controller.problem ?: when {
                regeneration != null && regeneration.assistantId == null -> "正在准备重新生成…"
                controller.dataBusy -> controller.dataStatus; !controller.ready -> "恢复会话中";
                messageEditBlocked -> if (messageActionLoading != null) "正在核对消息；未保存编辑已保留"
                    else "消息已变化或暂不可核对；编辑已保留，请复制后重新编辑";
                imageHost.sending -> "正在核实图片发送结果"; else -> null },
            composer = ComposerUi(text = inlineMessageEditor?.text ?: draft,
                selectionStart = inlineMessageEditor?.selectionStart ?: selectionStart,
                selectionEnd = inlineMessageEditor?.selectionEnd ?: selectionEnd,
                editingMessageCanSubmit = inlineMessageEditor?.let { it.canSubmit && !editReferencePending(it) } == true,
                editingMessageBlocked = messageEditBlocked,
                mainContextCompressing = controller.isMainContextCompressing(selected?.id),
                preparingSendKey = pendingTextSend?.takeIf { pending ->
                    controller.textSendState()?.let { it.ticket === pending.ticket && it.result == null } == true
                }?.ticket?.previewKey,
                editorGeneration = editorGeneration,
                phase = phase, controlOwnerKey = controlOwner?.id?.value,
                imageSendAvailable = inlineMessageEditor == null && imageHost.session.attachments.state.value.canSubmit && !imageHost.busy &&
                    videoHost.state.items.isEmpty() && !videoSending && controller.active == null,
                imageSendBlocked = inlineMessageEditor?.let { it.uncertain || it.additions.blocked || editReferencePending(it) } ?: (imageHost.busy || imageHost.hasAttachments &&
                    (!imageHost.session.attachments.state.value.canSubmit || state != null)),
                modelLabel = savedModelLabel(),
                reasoningLabel = try { ReasoningEffort.textPreference(reasoningPreference.snapshot(selected?.id?.value), model = confirmedReasoningModel).displayName } catch (_: Exception) { "配置无效，需重存" },
                gptSpeedMode = confirmedGptSpeed?.mode,
                gptSpeedSaving = composerAvailable && gptSpeedSaveOwner != null,
                gptSpeedEnabled = allowed && controller.active == null && !controller.hasLiveChildTasks() &&
                    gptSpeedSaveOwner == null && pendingTextSend == null &&
                    !imageHost.sending && !videoSending && inlineMessageEditor == null,
                assistantLabel = if (!rowsChanged || controller.dataBusy) chatState.composer.assistantLabel else currentAssistantLabel(), enabled = allowed,
                supplementAvailable = controlOwner?.let(controller::canSupplement) == true &&
                    supplementDraft.pending == null && !supplementMediaBlocked(),
                supplementPending = supplementDraft.pending?.editor?.conversationId == selected?.id?.value &&
                    supplementDraft.pending != null,
                executionPhase = state?.phase,
            ))
        regeneration?.takeIf { preview -> preview.assistantId?.let { id -> chatState.turns.any { it.id == id } } == true }
            ?.let(controller::acknowledgeRegenerationPreview)
        acceptedTextPreview?.takeIf { preview ->
            preview.first == selected?.id && (preview.second.settledUserId ?: preview.second.settledAssistantId)
                ?.let { id -> chatState.turns.any { it.id == id } } == true
        }?.let { acceptedTextPreview = null }
    }
    private fun requestScroll(id: String? = null, follow: Boolean = true, segmentIndex: Int? = null) {
        chatState = chatState.copy(scrollRequest = ChatScrollRequest(++scrollSerial, id, follow, segmentIndex))
    }
    private fun jumpToBottom() { requestScroll() }
    internal fun jumpToMessage(id: MessageId) {
        check(shown.any { it.second.id == id && it.second.role == "user" })
        requestScroll(id.value, false)
    }
    private fun chooseUserMessage() {
        val messages = shown.map { it.second }.filter { it.role == "user" }
        if (messages.isEmpty()) return
        prompt = ChoicePrompt("定位用户消息", messages.map { it.blocks.filterIsInstance<MessageBlock.Text>().joinToString(" ") { block -> block.text }.take(100) },
            { index -> guard { jumpToMessage(messages[index].id) } })
    }
    private fun loadEarlier() {
        val id = selected?.id ?: return
        val earlier = controller.database.messages(id, shown.firstOrNull()?.first ?: Long.MAX_VALUE)
        if (earlier.isEmpty()) { toast("没有更早消息"); return }
        requestScroll(follow = false)
        shown.addAll(0, earlier); prepareMessageActionSnapshot(); publishChat()
    }
    private fun copyMessage(id: String) {
        val turns = chatState.turns
        val turn = turns.firstOrNull { it.matchesKey(id) } ?: return
        val sources = ChatTurnMapper.sourceMessages(turn, shown.map { it.second })
        val first = sources.firstOrNull() ?: return
        val text = copyableImageHostText(first.copy(blocks = sources.flatMap { it.blocks })) ?: return
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("代鱼消息", text))
    }

    private fun cycleChatGptSpeed(expected: ChatGptSpeedSelection?, conversationId: String?) {
        if (expected == null || expected !== confirmedGptSpeed || expected.mode == null ||
            !activityUsable() || retainedPage != "chat" || sidebarVisible || selected?.id?.value != conversationId ||
            !chatState.composer.enabled || !chatState.composer.gptSpeedEnabled || gptSpeedSaveOwner != null) return
        val next = try { expected.next() } catch (failure: IllegalStateException) {
            toast(failure.message ?: "当前模型不支持此速度"); return
        }
        val owner = ModelSettingsSaveOwner()
        gptSpeedSaveOwner = owner
        publishChat(rowsChanged = false)
        val value = expected.editing.settings.copy(requestOptions = expected.editing.settings.requestOptions.copy(speedMode = next))
        try { controller.saveConversationGptSpeed(conversationId?.let(::ConversationId), expected.editor, expected.editing, value, owner) { success ->
            if (gptSpeedSaveOwner !== owner) return@saveConversationGptSpeed
            gptSpeedSaveOwner = null
            if (!activityUsable() || retainedPage != "chat" || selected?.id?.value != conversationId ||
                confirmedGptSpeed?.editor != expected.editor || confirmedGptSpeed?.editing?.settings?.model != value.model) return@saveConversationGptSpeed
            if (success) {
                confirmedGptSpeed = expected.copy(editing = expected.editing.copy(settings = value))
                toast(chatGptSpeedToast(next))
            } else toast("速度切换未保存，请重新尝试")
            publishChat(rowsChanged = false)
            refreshConfirmedModelLabel()
        } } catch (_: Exception) {
            owner.revoke()
            if (gptSpeedSaveOwner === owner) gptSpeedSaveOwner = null
            toast("速度切换未受理，请稍后重试")
            publishChat(rowsChanged = false)
        }
    }

    private fun refreshConfirmedModelLabel() {
        val request = Any()
        val conversation = selected?.id
        val generation = editorGeneration
        modelLabelRead = request
        balanceRuntime = null
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val (editor, editing) = controller.conversationModelEditing(conversation)
                    val runtime = controller.providerBalances.get(editor.slotId)
                    Triple(editor, editing, runtime)
                }.getOrNull()
            }
            val saved = loaded
            if (activityUsable() && modelLabelRead === request && selected?.id == conversation && editorGeneration == generation) {
                confirmedModelLabel = saved?.second?.settings?.model?.ifBlank { "模型" } ?: "模型"
                confirmedReasoningModel = saved?.second?.settings?.let {
                    runCatching { app.daiyu.android.model.ReasoningRequestPolicy.requestModel(it.model, it.requestOptions) }.getOrNull()
                }.orEmpty()
                confirmedGptSpeed = saved?.let { ChatGptSpeedSelection(it.first, it.second) }
                publishChat(rowsChanged = false)
                contextModelRevision++
                balanceRuntime = saved?.third
            }
        }
    }

    private fun refreshAutomaticCompression() {
        if (automaticCompressionPending) return
        automaticCompressionPending = true
        lifecycleScope.launch {
            val value = withContext(Dispatchers.IO) { runCatching {
                app.daiyu.android.context.ProductionCompressionSettingsStore(applicationContext).snapshot().automatic
            } }
            automaticCompression = value.getOrNull()
            automaticCompressionPending = false
        }
    }
    private fun toggleAutomaticCompression() {
        val expected = automaticCompression ?: return toast("自动压缩配置不可用；请先核对摘要配置")
        if (automaticCompressionPending) return
        automaticCompressionPending = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val store = app.daiyu.android.context.ProductionCompressionSettingsStore(applicationContext)
                store.changeAutomatic(expected).automatic
            } }
            automaticCompressionPending = false
            automaticCompression = result.getOrNull()
            if (result.isFailure && activityUsable()) toast("自动压缩未确认；请在摘要配置中核对，不会自动发起压缩")
        }
    }

    private fun openChatModels() {
        requireActivityUsable()
        dismissChatModels()
        controller.providerBalances.requestRefresh(lifecycleScope)
        val owner = Any(); chatModelOwner = owner
        reloadChatModels(owner)
    }
    private fun dismissChatModels() {
        chatModelCancelled.set(true)
        chatModelProviderEditor = null; chatModelPicker = null; chatModelSettings = null; chatModelSession = null; chatModelOwner = null
        chatModelSelectionFailed = false; chatModelProviderChoices = emptyMap()
    }
    private fun reloadChatModels(owner: Any, retainPresentation: Boolean = false) {
        if (!activityUsable() || retainedPage != "chat" || chatModelOwner !== owner) return
        chatModelCancelled.set(true)
        val conversation = selected?.id
        val generation = editorGeneration
        val cancelled = AtomicBoolean(false); chatModelCancelled = cancelled
        if (!retainPresentation) {
            chatModelProviderEditor = null; chatModelSession = null; chatModelSettings = null
            chatModelProviderChoices = emptyMap()
        }
        chatModelSelectionFailed = false
        chatModelPicker = chatModelPicker?.takeIf { retainPresentation }?.copy(loading = true, enabled = false, switching = false)
            ?: ChatModelPickerState("", emptyList(), loading = true, enabled = false)
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching {
                val registry = controller.providers.snapshot()
                val binding = controller.database.modelBinding(conversation)
                val choices = registry.providers.filter { it.enabled }.mapNotNull { provider ->
                    val editor = ProviderEditorSlot(provider.slotId, registry.revision)
                    runCatching { controller.providers.withEditor(editor) { store ->
                        val endpoint = store.nonSecretConfiguration()["endpoint"] as? String ?: ""
                        if (app.daiyu.android.voice.providers.DoubaoSpeech.isOpenspeech(endpoint)) null
                        else ChatModelProviderChoice(editor, store.load(),
                            store.localModelCatalogSnapshot())
                    } }.getOrNull()?.let { provider.slotId to it }
                }.toMap()
                check(controller.providers.snapshot().revision == registry.revision) { "提供商列表已变化" }
                val groups = registry.providers.filter { it.enabled && it.slotId in choices }.map { provider ->
                    ChatModelProviderUi(provider.slotId, provider.name,
                        choices[provider.slotId]?.catalog?.models.orEmpty().map { ModelCatalogEntryUi(it.id, it.displayName) },
                        balance = controller.providerBalances.get(provider.slotId).state.value)
                }
                ChatModelCatalogLoad(binding?.providerSlotId?.let { id -> choices[id]?.editor }, choices, groups)
            } }
            if (!activityUsable() || retainedPage != "chat" || chatModelOwner !== owner ||
                cancelled.get() || chatModelCancelled !== cancelled || selected?.id != conversation || editorGeneration != generation) return@launch
            val result = loaded.getOrNull()
            chatModelProviderChoices = result?.choices.orEmpty()
            chatModelProviderEditor = result?.activeEditor
            val active = result?.let { loaded -> loaded.activeEditor?.let { loaded.choices[it.slotId] } }
            val binding = controller.database.modelBinding(conversation)
            val record = active?.catalog?.models?.singleOrNull { it.settings.localIdentity == binding?.localModelIdentity }
            val saved = if (record != null && active.settings != null) record.settings.applyTo(active.settings, record.id) else null
            chatModelSettings = saved
            confirmedModelLabel = saved?.model?.ifBlank { "模型" } ?: "模型"
            confirmedReasoningModel = saved?.let {
                runCatching { app.daiyu.android.model.ReasoningRequestPolicy.requestModel(it.model, it.requestOptions) }.getOrNull()
            }.orEmpty()
            contextModelRevision++
            val session = result?.let {
                LocalCatalogChatModelPickerSession(active?.catalog, saved?.model.orEmpty(),
                    providerId = it.activeEditor?.slotId.orEmpty(), providers = it.groups,
                    providerCatalogs = it.choices.mapNotNull { (id, choice) -> choice.catalog?.let { catalog -> id to catalog } }.toMap())
            }
            chatModelSession = session
            val message = when {
                loaded.isFailure -> "本地模型目录读取失败，请关闭后重新打开或到模型设置核对"
                result?.activeEditor != null && saved == null -> "当前提供商凭据或配置不可用，请到模型设置修复"
                result?.choices.isNullOrEmpty() -> "尚无可用提供商，请到模型设置保存或启用"
                else -> null
            }
            chatModelPicker = (session?.state ?: ChatModelPickerState("", emptyList()))
                .copy(loading = false, enabled = result != null && result.choices.isNotEmpty() && !controller.dataBusy && controller.ready,
                    message = message)
        }
    }
    private fun selectChatModel(
        ticket: app.daiyu.android.model.catalog.CatalogModelSelection<app.daiyu.android.model.catalog.CatalogModelParameters>,
        owner: Any?,
        session: LocalCatalogChatModelPickerSession,
        providerId: String = session.state.providerId,
    ) {
        if (owner == null || chatModelOwner !== owner || chatModelSession !== session) return
        val picker = chatModelPicker ?: return
        if (picker.switching) return
        val conversation = selected?.id
        if (!allowTaskConfigurationChange(conversation)) return
        val saved = chatModelSettings
        val target = chatModelProviderChoices[providerId] ?: return
        val cancelled = chatModelCancelled
        val lease = ordinaryDialogLease ?: return
        try {
            requireActivityUsable()
            check(!chatModelSelectionFailed && picker.enabled && !picker.loading && retainedPage == "chat" && controller.ready &&
                !controller.taskConfigurationBusy(conversation) && !controller.dataBusy)
            chatModelPicker = picker.copy(switching = true)
            controller.selectActiveProviderCatalogModel(chatModelProviderEditor, saved, target.editor, target.settings, ticket,
                cancelled = { cancelled.get() || !lease.isActive }, conversation = conversation, completed = { success ->
                if (activityUsable()) {
                    if (success) {
                        controller.revokeImageProvider()
                        refreshConfirmedModelLabel()
                    }
                    if (dialogActionsCurrent(lease) && chatModelOwner === owner && chatModelSession === session && retainedPage == "chat") {
                        if (success) { reloadChatModels(owner, retainPresentation = true); refresh() }
                        else {
                            chatModelSelectionFailed = true
                            chatModelPicker = chatModelPicker?.copy(enabled = false, switching = false,
                                message = "切换未保存，请关闭后重新打开选择模型")
                        }
                    }
                }
            })
        } catch (_: Exception) {
            if (chatModelOwner === owner && chatModelSession === session) {
                chatModelSelectionFailed = true
                chatModelPicker = picker.copy(enabled = false, switching = false, message = "切换未保存，请关闭后重新打开选择模型")
            }
        }
    }

    private fun gatewayCurrent(owner: ModelSettingsSaveOwner) = activityUsable() &&
        retainedPage == "session-gateway" && gatewayOwner === owner && !gatewayReloadPending

    private fun openSessionGateway() {
        requireActivityUsable()
        check(controller.ready && controller.active == null && !controller.dataBusy)
        retainedPage = "session-gateway"
        loadSessionGateway(restore = false)
    }

    private fun loadSessionGateway(restore: Boolean) {
        val oldEditor = gatewayProviderEditor
        val oldEditing = gatewayEditing
        val oldDraft = gatewayDraft
        gatewayOwner?.revoke()
        val owner = ModelSettingsSaveOwner()
        gatewayOwner = owner
        gatewayReloadPending = true; gatewayReplacementAuthorized = false; gatewayError = null
        if (!restore) { gatewayEditing = null; gatewayProviderEditor = null; gatewayDraft = null }
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching {
                val editor = controller.providers.activeEditor()
                editor to controller.providers.editing(editor)
            } }
            if (!activityUsable() || !voiceForeground || retainedPage != "session-gateway" || gatewayOwner !== owner) return@launch
            loaded.fold(onSuccess = { (editor, editing) ->
                val sameConfiguration = restore && oldEditor == editor && oldEditing == editing && oldDraft != null
                gatewayProviderEditor = editor
                gatewayEditing = editing
                gatewayDraft = if (sameConfiguration) oldDraft else editing.settings.requestOptions.sessionGateway
                gatewayReloadPending = false
                if (restore && !sameConfiguration) gatewayError = "提供商配置已变化，已重新读取会话网关设置"
                if (editing.credentialUnavailable && !editing.requestOptionsUnavailable ||
                    editing.settings.protocol == app.daiyu.android.model.ProviderProtocol.UNKNOWN) {
                    gatewayError = "凭据或协议不可用；请先到模型设置修复，未更改网关策略"
                }
            }, onFailure = {
                gatewayEditing = null; gatewayProviderEditor = null; gatewayReloadPending = false
                gatewayError = "设置无法读取；未更改配置，请返回后重试"
            })
        }
    }

    private fun saveSessionGateway(owner: ModelSettingsSaveOwner,
        value: app.daiyu.android.model.sessiongateway.SessionGatewayRule) {
        try {
            check(gatewayCurrent(owner) && gatewayDraft === value)
            check(controller.ready && controller.active == null && !controller.dataBusy)
            val editing = checkNotNull(gatewayEditing)
            // This page cannot authorize a protocol/key rebind. The model editor owns that flow.
            require(editing.settings.protocol != app.daiyu.android.model.ProviderProtocol.UNKNOWN)
            require(!editing.credentialUnavailable || editing.requestOptionsUnavailable)
            require(editing.settings.oauth != null || editing.settings.apiKey.isNotBlank())
            require(!editing.requestOptionsUnavailable || gatewayReplacementAuthorized)
            value.validate() // Unknown retention/keySource may be saved, but never executed.
            val next = editing.settings.copy(requestOptions = editing.settings.requestOptions.copy(sessionGateway = value))
            controller.saveProviderSettings(checkNotNull(gatewayProviderEditor), next, editing, false, gatewayReplacementAuthorized, owner) { success ->
                if (success && activityUsable()) controller.revokeImageProvider()
                if (gatewayCurrent(owner)) {
                    if (success) retainedPage = "settings"
                    else gatewayError = "设置未保存；配置或页面可能已变化，请返回后重试"
                }
            }
        } catch (_: Exception) {
            if (gatewayCurrent(owner)) gatewayError = "设置未保存；请检查策略或先到模型设置修复凭据/协议"
        }
    }

    private fun modelSettings() {
        requireActivityUsable()
        providersReturnPage = if (retainedPage == "settings") "settings" else "chat"
        retainedPage = "providers"
    }
    private fun closeProvidersPage() {
        if (!activityUsable() || retainedPage != "providers") return
        if (providersReturnPage == "settings") retainedPage = "settings" else returnToChat()
    }
    private fun changeModelDraft(value: ModelSettingsDraftUi) {
        if (activityUsable() && retainedPage == "models" && !controller.dataBusy) modelDraft = value
    }
    private fun ModelSettingsDraftUi.toSettings(base: ModelSettings) = base.copy(endpoint = endpoint.trim(), model = model.trim(),
        apiKey = apiKey, protocol = protocol, responsesPath = responsesPath.trim(), batteryEnabled = batteryEnabled,
        deviceStatusEnabled = deviceStatusEnabled, requestOptions = base.requestOptions.copy(
            customBody = providerRequestDraft().customBody, speedMode = speedMode, codex = codex))
    private fun saveModelSettings(value: ModelSettingsDraftUi) {
        try {
            requireActivityUsable(); check(retainedPage == "models" && modelDraft === value)
            check(controller.active == null && !controller.dataBusy)
            val editing = checkNotNull(editingSettings)
            require(value.savedProtocol == editing.settings.protocol)
            require(!editing.requestOptionsUnavailable || value.requestOptionsReplacementAuthorized)
            val owner = checkNotNull(modelEditingOwner)
            val saveOwner = modelSaveOwner ?: ModelSettingsSaveOwner().also { modelSaveOwner = it }
            controller.saveModelSettings(value.toSettings(editing.settings), value.protocol != editing.settings.protocol,
                value.requestOptionsReplacementAuthorized, editing, saveOwner) { success ->
                if (activityUsable()) {
                    if (success) controller.revokeImageProvider()
                    if (retainedPage == "models" && modelEditingOwner === owner && editingSettings === editing) {
                        if (success) returnToChat() else modelError = "设置未保存"
                    }
                }
            }
        } catch (_: Exception) { modelError = "设置未保存" }
    }
    private fun requestModelConnectionTest(owner: Any, value: ModelSettingsDraftUi, cancelled: () -> Boolean,
        deliver: (app.daiyu.android.model.catalog.ModelConnectionResult) -> Unit): Boolean {
        if (!activityUsable() || retainedPage != "models" || modelEditingOwner !== owner ||
            modelDraft != value || cancelled()) return false
        val editing = editingSettings ?: return false
        return controller.testModelConnection(value.toSettings(editing.settings), cancelled) { result ->
            if (activityUsable() && retainedPage == "models" && modelEditingOwner === owner &&
                editingSettings === editing && modelDraft == value && !cancelled()) deliver(result)
        }
    }

    private fun requestSettingsCatalog(
        value: ModelSettingsDraftUi,
        cancelled: () -> Boolean,
        deliver: (List<app.daiyu.android.model.catalog.RemoteModel>?) -> Unit,
    ): Boolean {
        if (!activityUsable() || retainedPage != "models" || modelDraft != value ||
            controller.dataBusy || cancelled()) return false
        val editing = editingSettings ?: return false
        return try {
            controller.fetchModelCatalog(value.toSettings(editing.settings), cancelled) { models ->
                if (activityUsable() && retainedPage == "models" && editingSettings === editing &&
                    modelDraft == value && !cancelled()) deliver(models)
            }
        } catch (_: Exception) { false }
    }

    private fun checkBackupAvailable() {
        requireActivityUsable()
        check(controller.ready && controller.active == null && !controller.dataBusy && controller.problem?.startsWith("会话恢复失败") != true) {
            "会话尚未就绪、运行中、备份任务进行中或恢复失败；未打开备份，请稍后重新选择文件"
        }
    }
    private fun backupGuard(action: () -> Unit) {
        try { checkBackupAvailable(); action() }
        catch (failure: Exception) { toast(if (failure is IllegalStateException || failure is IllegalArgumentException)
            failure.message ?: "备份操作失败；原数据保留" else "备份操作失败；原数据保留，请重新选择文件") }
    }
    private fun openedBackupUri(open: Intent): Uri {
        val uri = open.data
        require(uri != null && uri.scheme == "content" && !uri.authority.isNullOrBlank() &&
            uri.toString().length <= 8192 && open.type in setOf("application/json", "application/zip")) { "请选择content类型的JSON或ZIP备份文件（URI不超过8192字符）" }
        require(open.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "备份文件未授予读取权限；请重新选择文件" }
        return uri
    }
    private fun showOpenedBackup(uri: Uri) {
        if (lastOpenedBackupUri == uri.toString() && prompt?.backup == true) return
        lastOpenedBackupUri = uri.toString(); retainedPage = "backup"
        confirmBackupImportAuto(uri)
    }
    private fun cancelDeferredOpenedBackup() {
        val deferred = deferredOpenedBackup ?: return
        deferredOpenedBackup = null
        if (prompt === deferred.waitingPrompt) prompt = null
    }
    private fun offerDeferredOpenedBackup() {
        val deferred = deferredOpenedBackup ?: return
        if (controller.problem != null || controller.active != null || controller.dataBusy ||
            retainedPage != "backup" || prompt !== deferred.waitingPrompt) {
            cancelDeferredOpenedBackup(); return
        }
        if (!controller.ready) return
        cancelDeferredOpenedBackup()
        // Reuse the existing choice + second confirmation. No resolver/service call here.
        backupGuard { showOpenedBackup(deferred.uri) }
    }
    private fun offerOpenedBackup(open: Intent?) {
        if (open?.action != Intent.ACTION_VIEW) return
        if (!controller.ready && controller.problem == null && controller.active == null && !controller.dataBusy) {
            try {
                requireActivityUsable()
                val uri = openedBackupUri(open)
                cancelDeferredOpenedBackup()
                retainedPage = "backup"
                val waiting = ConfirmPrompt("等待会话恢复", "恢复完成后确认导入，尚未读取文件。", "取消打开",
                    action = { cancelDeferredOpenedBackup() }, backup = true)
                deferredOpenedBackup = DeferredOpenedBackup(uri, waiting)
                prompt = waiting
            } catch (failure: Exception) { toast(failure.message ?: "文件打开失败；请重新选择") }
            return
        }
        backupGuard { showOpenedBackup(openedBackupUri(open)) }
    }
    private fun backupMenu() { checkBackupAvailable(); lastOpenedBackupUri = null; retainedPage = "backup" }
    private fun openChildTaskRecords(id: String) {
        retainedConversation(id)
        dismissSidebar(); taskRecordsConversationId = id; retainedPage = "child-tasks"
    }
    @Suppress("DEPRECATION") private fun pickConversationExports(ids: List<String>) {
        checkBackupAvailable()
        check(!controller.hasLiveChildTasks()) { "子任务运行中，暂不能导出" }
        check(pendingBackupRequest == null) { "文件选择尚未返回；请先完成或取消选择" }
        val targets = ids.toList()
        targets.forEach { retainedConversation(it) }
        conversationExport.begin(targets); pendingBackupRequest = 105; exportPickerPending = true
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                putExtra(Intent.EXTRA_TITLE, if (targets.size == 1) "daiyu-conversation.json" else "daiyu-conversations.json")
            }, 105)
        } catch (failure: Exception) {
            pendingBackupRequest = null; exportPickerPending = false; conversationExport.take(); throw failure
        }
    }
    @Suppress("DEPRECATION") private fun pickBackupAuto() {
        checkBackupAvailable(); check(pendingBackupRequest == null)
        cancelRestorePreview(); backupImportVisit = Any(); pendingBackupRequest = 107
        backupImportPickerHopPending = true
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "application/zip", "application/octet-stream", "text/plain"))
            }, 107)
        } catch (failure: Exception) { pendingBackupRequest = null; backupImportVisit = null; backupImportPickerHopPending = false; throw failure }
    }
    private fun confirmBackupImportAuto(uri: Uri, visit: Any = Any()) {
        checkBackupAvailable(); cancelRestorePreview()
        backupImportVisit = visit
        prompt = ConfirmPrompt("导入备份",
            "将识别所选文件。代鱼备份会添加聊天记录，不覆盖现有记录；" +
                "若包含 Linux 环境，将恢复到独立目录，不覆盖当前环境，也不自动授权运行；chroot 恢复需要 Root。" +
                "若包含可恢复的模型配置，将另行确认。" +
                "仅支持当前应用的备份格式，不支持的格式不会导入。",
            "导入", action = {
            backupGuard {
                check(backupImportVisit === visit && retainedPage == "backup" &&
                    lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
                controller.importUserBackupAuto(uri) { outcome ->
                    if (backupImportVisit !== visit || !activityUsable() || retainedPage != "backup") return@importUserBackupAuto
                    if (outcome == null) { backupImportVisit = null; return@importUserBackupAuto }
                    pendingBackupImportResult = BackupImportResult(visit, uri, outcome)
                    presentBackupImportResult()
            settleChatReference()
                }
            }
        }, backup = true)
    }
    private fun presentBackupImportResult() {
        val result = pendingBackupImportResult ?: return
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        pendingBackupImportResult = null
        if (backupImportVisit !== result.visit || !activityUsable() || retainedPage != "backup") return
        backupImportVisit = null
        backupGuard {
                val candidate = result.outcome.linux
                if (candidate == null) offerImportedConfiguration(result.outcome.hasConfiguration)
                else {
                    val installation = candidate.installation
                    val owner = backupExportPageOwner
                    prompt = ConfirmPrompt("批准恢复的 Linux 环境？",
                        "发行版：${installation.distribution}\n运行方式：${installation.executionMode.wire}\n" +
                            "恢复目录：${installation.rootfs.path}\n" +
                            "只使用本机安装包内的运行组件，不恢复外部共享目录授权。批准后，下次启动可选择此环境；不会切换或运行当前环境。",
                        "批准", action = {
                            if (backupExportPageCurrent(owner)) backupGuard {
                                controller.approveRestoredLinux(candidate) { success ->
                                    if (success && backupExportPageCurrent(owner))
                                        backupGuard { offerImportedConfiguration(result.outcome.hasConfiguration) }
                                }
                            }
                        }, backup = true)
                }
        }
    }
    private fun offerImportedConfiguration(hasConfiguration: Boolean) {
        if (hasConfiguration) {
            previewConfigurationRestore()
            if (configurationRestorePreview?.plan?.isNoOp == false) confirmConfigurationRestore()
        }
    }
    @Suppress("DEPRECATION") private fun pickBackup(exporting: Boolean, complete: Boolean, includeLinux: Boolean = false) {
        checkBackupAvailable(); check(pendingBackupRequest == null) { "文件选择尚未返回；请先完成或取消选择" }
        val owner = if (exporting) backupExportPageOwner.also {
            check(backupExportPageCurrent(it) && generationResumed()) { "备份页面已变化；请重新选择导出" }
        } else null
        val code = if (complete) { if (exporting) 103 else 104 } else { if (exporting) 101 else 102 }
        require(!includeLinux || exporting && complete)
        pendingBackupRequest = code
        pendingBackupIncludeLinux = includeLinux
        pendingBackupExportOwner = owner
        backupExportPickerHopPending = exporting
        try {
            startActivityForResult(Intent(if (exporting) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = if (includeLinux) "application/zip" else "application/json"
                if (exporting) putExtra(Intent.EXTRA_TITLE, if (includeLinux) "daiyu-linux-backup.zip"
                    else if (complete) "daiyu-complete.json" else "daiyu-text.json")
            }, code)
        } catch (failure: Exception) {
            pendingBackupRequest = null; pendingBackupExportOwner = null; pendingBackupIncludeLinux = false; backupExportPickerHopPending = false
            throw failure
        }
    }
    @Suppress("DEPRECATION") override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val browserSelection = pendingBrowserDownload
        if (browserSelection?.requestCode == requestCode && pendingBackupRequest == requestCode) {
            // Consume once, even on cancellation/invalid URI. Distinct request codes ensure a
            // late/duplicate callback cannot target a newer selection. No reference is restored.
            pendingBrowserDownload = null; pendingBackupRequest = null
            if (resultCode != RESULT_OK) return
            backupGuard {
                assertBrowserSaveOwner(browserSelection)
                val uri = data?.data
                require(uri?.scheme == "content") { "未返回文档目标；未保存原件" }
                // Capture the original visit, not the current UI owner or a replacement
                // token. The callback above consumed the request before any worker starts.
                val visit = browserSelection.visit
                controller.saveBrowserDownload(visit.conversation, browserSelection.reference,
                    checkNotNull(uri), assertUiOwner = {
                        check(visit.active.get()) { "原会话已变化；本次保存已失效" }
                    })
            }
            return
        }
        val mediaSelection = pendingGenerationMediaOriginal
        if (mediaSelection?.requestCode == requestCode && pendingBackupRequest == requestCode) {
            pendingGenerationMediaOriginal = null; pendingBackupRequest = null
            mediaSelection.pickerHopPending = false
            if (resultCode != RESULT_OK) { cancelGenerationMedia(); return }
            backupGuard {
                val uri = data?.data
                require(mediaSelection.active.get() && generationMediaVisit === mediaSelection.active &&
                    activityUsable() && retainedPage == "chat" && !sidebarVisible && uri?.scheme == "content" &&
                    selected?.id?.value == mediaSelection.binding.conversationId) { "原件选择已失效；未保存或重试" }
                controller.saveGenerationMediaOriginal(mediaSelection.binding, mediaSelection.assetKey,
                    checkNotNull(uri), mediaSelection.active)
            }
            return
        }
        val staticSelection = pendingStaticOriginal
        if (staticSelection?.requestCode == requestCode && pendingBackupRequest == requestCode) {
            // Consume before checking result/URI or starting work. Late, duplicate and cancelled
            // callbacks cannot borrow a later selection, including across Activity recreation.
            pendingStaticOriginal = null; pendingBackupRequest = null
            staticSelection.pickerHopPending = false
            if (resultCode != RESULT_OK) return
            backupGuard {
                val uri = data?.data
                require(staticSelection.active.get() && staticGenerationVisit === staticSelection.active &&
                    activityUsable() && retainedPage == "chat" && !sidebarVisible && uri?.scheme == "content" &&
                    selected?.id?.value == staticSelection.output.destination.conversationId) {
                    "静态原件选择已失效；未保存或重试"
                }
                controller.saveStaticGenerationOriginal(staticSelection.output, checkNotNull(uri), staticSelection.active)
            }
            return
        }
        if (requestCode !in setOf(101, 102, 103, 104, 105, 106, 107) || pendingBackupRequest != requestCode) return
        val includeLinux = pendingBackupIncludeLinux.also { pendingBackupIncludeLinux = false }
        val exportOwner = if (requestCode == 101 || requestCode == 103) pendingBackupExportOwner.also {
            pendingBackupExportOwner = null; backupExportPickerHopPending = false
        } else null
        pendingBackupRequest = null
        if (requestCode == 107) backupImportPickerHopPending = false
        if (requestCode == 106) {
            // Reference is deliberately memory-only: activity/process recreation fails closed
            // and requires a fresh history selection rather than guessing a different asset.
            val history = pendingHistoricalOriginal.also { pendingHistoricalOriginal = null }
            if (resultCode != RESULT_OK) return
            backupGuard {
                val uri = data?.data
                require(history != null && uri?.scheme == "content" && selected?.id?.value == history.conversationId) {
                    "历史原件选择已失效；请重新打开原会话并选择保存"
                }
                controller.saveHistoricalGenerationOriginal(checkNotNull(history), checkNotNull(uri))
            }
            return
        }
        if (requestCode == 105) {
            exportPickerPending = false
            val originalIds = conversationExport.take()
            if (resultCode != RESULT_OK) return
            val uri = data?.data
            backupGuard {
                require(!originalIds.isNullOrEmpty() && uri?.scheme == "content") { "导出目标已失效或文件未返回；请重新选择" }
                check(!controller.hasLiveChildTasks()) { "子任务运行中，暂不能导出" }
                controller.exportConversationsBackup(checkNotNull(uri), originalIds.map(::ConversationId))
            }
            return
        }
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: run { toast("文件选择未返回URI；原数据保留，请重新选择"); return }
        val complete = requestCode == 103 || requestCode == 104
        backupGuard {
            require(uri.scheme == "content") { "文件选择未返回content URI；原数据保留，请重新选择" }
            if (requestCode == 101 || requestCode == 103) {
                check(backupExportPageCurrent(exportOwner)) { "备份页面已变化；本次导出已失效，请重新选择" }
                controller.exportUserBackup(uri, complete, includeLinux = includeLinux)
            }
            else if (requestCode == 107) {
                val visit = backupImportVisit
                if (visit != null && retainedPage == "backup") confirmBackupImportAuto(uri, visit)
            }
            else confirmBackupImport(uri, complete)
        }
    }
    private fun confirmBackupImport(uri: Uri, complete: Boolean) {
        checkBackupAvailable()
        val visit = Any()
        backupImportVisit = visit
        prompt = ConfirmPrompt(if (complete) "确认导入完整中立备份" else "确认导入文本备份", "", "导入",
            action = { backupGuard {
                check(backupImportVisit === visit && retainedPage == "backup" && generationResumed())
                backupImportVisit = null
                controller.importUserBackup(uri, complete)
            } }, backup = true)
    }
    private fun cancelRestorePreview() {
        configurationRestorePreview?.let { controller.cancelConfigurationRestore(it) }
        configurationRestorePreview = null; restorePreviewText = ""
    }
    private fun previewConfigurationRestore() {
        checkBackupAvailable(); cancelRestorePreview()
        val preview = controller.previewImportedConfiguration()
        configurationRestorePreview = preview
        val plan = preview.plan
        val labels = listOf("protocol" to "协议", "endpoint" to "服务地址", "responses-path" to "Responses 路径",
            "model" to "模型", "battery" to "只读电量工具", "device-status" to "只读设备状态工具")
        fun value(values: Map<String, Any?>, name: String): String = when {
            name == "protocol" -> when (values[name]) { null, "responses" -> "Responses"; "messages" -> "Messages"; "chat_completions" -> "Chat Completions"; "gemini" -> "Gemini"; else -> "无效" }
            !values.containsKey(name) -> "（未设置）"
            values[name] == "" -> "（空）"
            values[name] is Boolean -> if (values[name] == true) "允许" else "关闭"
            else -> values[name]?.toString() ?: "（空）"
        }
        restorePreviewText = if (plan.isNoOp) "暂无配置" else labels.joinToString("\n") { (name, label) ->
            "$label：${value(plan.before, name)} → ${value(plan.after, name)}"
        } + if (plan.credentialRebindRequired) "\n密钥：重新输入" else ""
    }
    private fun confirmConfigurationRestore() {
        backupGuard {
            val preview = checkNotNull(configurationRestorePreview)
            check(!preview.plan.isNoOp)
            prompt = ConfirmPrompt("恢复模型配置", restorePreviewText, "确认恢复", action = {
                backupGuard {
                    check(configurationRestorePreview === preview)
                    try { controller.restoreImportedConfiguration(preview, authorized = true) { success ->
                        if (success && activityUsable()) controller.revokeImageProvider()
                    } }
                    finally { cancelRestorePreview() }
                }
            }, backup = true)
        }
    }
    private fun loadAppearance(): AppearanceSettings = appearanceStore.load()
    private fun saveAppearance(value: AppearanceSettings): Boolean {
        var saved = false
        guard {
            appearance = appearanceStore.save(value)
            publishChat()
            saved = true
        }
        return saved
    }

}

/** Activity-owned, main-thread-only policy. The factory never runs on enter/render/resume. */
internal interface VoiceHostPlayer : AutoCloseable {
    val state: StateFlow<SpeechPlaybackState>
    fun bindOwner(owner: String)
    fun play(owner: String, requestId: String, text: String): Boolean
    fun stop(owner: String): Boolean
    /** Must confirm release or throw; close() alone is only lifecycle best effort. */
    fun closeStrict()
}

internal class AndroidVoiceHostPlayer(private val player: AndroidSpeechPlayer) : VoiceHostPlayer {
    override val state get() = player.state
    override fun bindOwner(owner: String) = player.bindOwner(owner)
    override fun play(owner: String, requestId: String, text: String) = player.play(owner, requestId, text)
    override fun stop(owner: String) = player.stop(owner)
    override fun close() = player.close()
    override fun closeStrict() = player.closeStrict()
}

/** P32-INTEGRATION option 2: retain playback UI, but own every player through a strict gate lease.
 * Production must inject the same gate as dictation; the default exists for isolated legacy tests.
 */
internal class VoiceHost(
    private val scope: CoroutineScope,
    private val gate: AudioOwnerGate = AudioOwnerGate(),
    private val factory: () -> VoiceHostPlayer,
) : AutoCloseable {
    // UI capability is not the engine identity. Each click gets fresh audio and player tokens.
    var owner by mutableStateOf(java.util.UUID.randomUUID().toString())
        private set
    private class Request(val uiOwner: String, val messageId: String, conversation: String?) {
        val audioOwner = VoiceOwner(conversation ?: "unsaved-draft", java.util.UUID.randomUUID().toString())
        val playerOwner = java.util.UUID.randomUUID().toString()
        val requestId = java.util.UUID.randomUUID().toString()
        lateinit var lease: AudioOwnerGate.Lease
        var player: VoiceHostPlayer? = null
        var observation: Job? = null
    }
    private var conversation: String? = null
    private var foreground = false
    private var visible = true
    private var closed = false
    private var active: Request? = null
    private var requestId: String? = null
    private val mutableState = MutableStateFlow(SpeechPlaybackState(owner = owner))
    val state: StateFlow<SpeechPlaybackState> = mutableState.asStateFlow()

    fun enter(conversationId: String?) {
        if (closed) return
        stop(owner)
        conversation = conversationId
        renewOwner()
    }
    fun syncConversation(conversationId: String?) {
        if (conversation != conversationId) enter(conversationId)
    }
    fun setForeground(value: Boolean) {
        if (closed || foreground == value) return
        if (!value) stop(owner)
        foreground = value
        if (value) renewOwner()
    }
    fun setVisible(value: Boolean) {
        if (closed || visible == value) return
        if (!value) stop(owner)
        visible = value
        if (value) renewOwner()
    }
    private fun renewOwner() {
        owner = java.util.UUID.randomUUID().toString()
        requestId = null
        mutableState.value = SpeechPlaybackState(owner = owner)
    }

    fun speak(expectedOwner: String?, id: String, text: String): Boolean {
        if (closed || !foreground || !visible || owner != expectedOwner || id.isBlank()) return false
        if (text.isBlank()) {
            if (!stop(owner)) return false
            requestId = id
            mutableState.value = SpeechPlaybackState(owner, id, SpeechPhase.ERROR, SpeechError.EMPTY_TEXT)
            return false
        }
        val request = Request(owner, id, conversation)
        try {
            // Previous TTS OR dictation must finish strict teardown before factory/play can run.
            request.lease = gate.acquire(request.audioOwner, {
                !closed && foreground && visible && owner == expectedOwner
            }) { teardown(request, stopped = true) }
            active = request
            requestId = id
            val player = factory()
            request.player = player // Install before bind: a throwing bind still owns resources.
            player.bindOwner(request.playerOwner)
            request.observation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                player.state.collect { accept(request, player, it) }
            }
            val accepted = player.play(request.playerOwner, request.requestId, text)
            accept(request, player, player.state.value)
            if (!accepted && active === request) {
                finish(request, SpeechPlaybackState(owner, id, SpeechPhase.ERROR, SpeechError.PLAYBACK_FAILED))
            }
            return accepted
        } catch (_: Exception) {
            // A failure before acquisition is already recorded by the shared gate. If acquired,
            // close through releaseAfter, never best-effort close followed by bare release.
            if (active === request) {
                try { gate.releaseAfter(request.lease) { teardown(request, stopped = false) } }
                catch (_: Exception) { /* poisoned gate remains closed to all subsequent owners */ }
            }
            if (!closed && owner == request.uiOwner) {
                mutableState.value = SpeechPlaybackState(owner, id, SpeechPhase.ERROR, SpeechError.STOP_FAILED)
            }
            return false
        }
    }

    fun stop(expectedOwner: String?, expectedRequestId: String? = null): Boolean {
        if (closed || owner != expectedOwner ||
            (expectedRequestId != null && expectedRequestId != requestId)) return false
        val request = active ?: return true
        return try { gate.cancel(request.audioOwner); true } catch (_: Exception) {
            mutableState.value = SpeechPlaybackState(owner, request.messageId, SpeechPhase.ERROR, SpeechError.STOP_FAILED)
            false
        }
    }

    private fun teardown(request: Request, stopped: Boolean) {
        if (active === request) active = null // Fence callbacks before touching Android.
        request.observation?.cancel(); request.observation = null
        val player = request.player; request.player = null
        try {
            player?.closeStrict()
        } catch (failure: Exception) {
            if (!closed && owner == request.uiOwner) {
                mutableState.value = SpeechPlaybackState(owner, request.messageId, SpeechPhase.ERROR, SpeechError.STOP_FAILED)
            }
            throw failure
        }
        if (stopped && !closed && owner == request.uiOwner) {
            mutableState.value = SpeechPlaybackState(owner, request.messageId, SpeechPhase.STOPPED)
        }
    }
    private fun finish(request: Request, state: SpeechPlaybackState) {
        try {
            if (gate.releaseAfter(request.lease) { teardown(request, stopped = false) }) mutableState.value = state
        } catch (_: Exception) {
            mutableState.value = state.copy(phase = SpeechPhase.ERROR, error = SpeechError.STOP_FAILED)
        }
    }
    private fun accept(request: Request, source: VoiceHostPlayer, value: SpeechPlaybackState) {
        if (closed || active !== request || request.player !== source || !gate.current(request.lease) ||
            owner != request.uiOwner || value.owner != request.playerOwner || value.requestId != request.requestId) return
        val display = value.copy(owner = request.uiOwner, requestId = request.messageId)
        when (value.phase) {
            SpeechPhase.COMPLETED, SpeechPhase.ERROR, SpeechPhase.UNAVAILABLE, SpeechPhase.STOPPED, SpeechPhase.CLOSED -> finish(request, display)
            else -> mutableState.value = display
        }
    }
    override fun close() {
        if (closed) return
        stop(owner)
        closed = true
        mutableState.value = SpeechPlaybackState(owner = owner, phase = SpeechPhase.CLOSED)
    }
}

/** Image references are not text: no private path, hash, ID or metadata enters the clipboard. */
internal fun copyableImageHostText(message: Message): String? {
    if (message.blocks.none { it is MessageBlock.Tool || it is MessageBlock.Text && it.text.isNotBlank() }) return null
    return message.blocks.joinToString("\n") { block -> when (block) {
        is MessageBlock.Text -> block.text
        is MessageBlock.Tool -> "${block.invocation.name}: ${block.result ?: "未完成"}"
        is MessageBlock.Image -> "[图片]"
    } }
}

/** Only the current assistant's visible answer; never flatten work-process/tool payloads. */
internal fun voiceHostText(turns: List<ChatTurnUi>, id: String): String? =
    turns.filterIsInstance<ChatTurnUi.Assistant>().firstOrNull { it.matchesKey(id) }?.finalText

internal fun voiceHostIsActive(state: SpeechPlaybackState) = state.phase in setOf(
    SpeechPhase.INITIALIZING, SpeechPhase.QUEUED, SpeechPhase.SPEAKING,
)

internal fun voiceHostStatusResource(state: SpeechPlaybackState): Int? = when (state.phase) {
    SpeechPhase.INITIALIZING -> R.string.voice_host_initializing
    SpeechPhase.QUEUED -> R.string.voice_host_queued
    SpeechPhase.SPEAKING -> R.string.voice_host_speaking
    SpeechPhase.COMPLETED -> R.string.voice_host_completed
    SpeechPhase.STOPPED -> R.string.voice_host_stopped
    SpeechPhase.UNAVAILABLE, SpeechPhase.ERROR -> when (state.error) {
        SpeechError.ENGINE_UNAVAILABLE -> R.string.voice_host_engine_unavailable
        SpeechError.INITIALIZATION_FAILED -> R.string.voice_host_initialization_failed
        SpeechError.LANGUAGE_UNSUPPORTED -> R.string.voice_host_language_unsupported
        SpeechError.LANGUAGE_DATA_MISSING -> R.string.voice_host_language_data_missing
        SpeechError.OFFLINE_VOICE_UNAVAILABLE -> R.string.voice_host_offline_voice_unavailable
        SpeechError.EMPTY_TEXT -> R.string.voice_host_empty_text
        SpeechError.TEXT_TOO_LONG -> R.string.voice_host_text_too_long
        SpeechError.STOP_FAILED -> R.string.voice_host_stop_failed
        SpeechError.PLAYBACK_FAILED, null -> R.string.voice_host_playback_failed
    }
    SpeechPhase.IDLE, SpeechPhase.READY, SpeechPhase.CLOSED -> null
}
