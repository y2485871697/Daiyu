package app.daiyu.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.screens.chat.attachments.ImageAttachments
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
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
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.delegation.ConversationSubAgentPreferences
import app.daiyu.android.delegation.ReasoningEffort
import app.daiyu.android.domain.*
import app.daiyu.android.domain.appearance.IconNotificationPort
import app.daiyu.android.domain.appearance.IconSyncResult
import app.daiyu.android.domain.runtime.ExecutionPhase
import app.daiyu.android.domain.runtime.SupplementAdmission
import app.daiyu.android.ui.actions.ConversationExportSelection
import app.daiyu.android.ui.actions.PausedSupplementDraft
import app.daiyu.android.ui.actions.SupplementEditor
import app.daiyu.android.ui.screens.conversation.ChildTaskRecordsScreen
import app.daiyu.android.ui.screens.conversation.ConversationExportScreen
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
import app.daiyu.android.ui.app.DaiyuPalette
import app.daiyu.android.ui.components.*
import app.daiyu.android.ui.components.subagent.SubAgentThinkingEffortPickerDialog
import app.daiyu.android.ui.model.ConversationPaneUiState
import app.daiyu.android.ui.model.ConversationReasoningPreference
import app.daiyu.android.ui.model.ConversationSummaryUi
import app.daiyu.android.ui.model.MessageSearchHit
import app.daiyu.android.ui.screens.assistants.AssistantEditScreen
import app.daiyu.android.ui.screens.assistants.AssistantPickerDialog
import app.daiyu.android.ui.screens.chat.ManageChatsScreen
import app.daiyu.android.ui.screens.chat.conversation.*
import app.daiyu.android.ui.screens.settings.*
import app.daiyu.android.ui.screens.search.ConversationSearchScreen
import app.daiyu.android.ui.screens.search.MessageSearchScreen
import app.daiyu.android.ui.screens.stats.UsageStatsScreen
import app.daiyu.android.vdapproval.R08PlanEditorHost
import app.daiyu.android.vdapproval.R08PlanEditor
import app.daiyu.android.vdapproval.R08FrozenPlan
import app.daiyu.android.vdapproval.R08CleanupUiOwner
import app.daiyu.android.vdapproval.R08CleanupApprovalRequest
import app.daiyu.android.vdapproval.R08CleanupApprovalScreen
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.ui.graphics.asImageBitmap

class MainActivity : ComponentActivity() {
    private val controller get() = (application as ChatApplication).controller
    private var balanceRuntime by mutableStateOf<app.daiyu.android.balance.BalanceRuntime?>(null)
    private var balanceLifecycle: app.daiyu.android.balance.BalanceLifecycleBinding? = null
    private val pets get() = (application as ChatApplication).pets
    private val openPetSettings: () -> Unit = {
        if (activityUsable()) { dismissSidebar(); retainedPage = "pet-settings" }
    }
    private val terminalProvider get() = (application as ChatApplication).terminalProvider
    private var linuxSettingsVisit: Any? = null
    private var linuxSettingsPageOwner by mutableStateOf<app.daiyu.android.terminal.LinuxSettingsPageOwner?>(null)
    private var terminalPageOwner: Any? = null
    private val browserSettings by lazy { app.daiyu.android.browser.BrowserSettingsStore(applicationContext) }
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
        if (tool == app.daiyu.android.platform.PlatformReadTool.PERMISSIONS && permission.isEmpty()) {
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
    private val retainedData by lazy { RetainedConversationData(controller.database, ContextService(this, controller.database)) }
    private val assistantStore get() = controller.assistantStore
    private val assistantAssets get() = controller.assistantAssets
    private val reasoningPreference by lazy {
        ConversationReasoningPreference(getSharedPreferences("conversation-ui", MODE_PRIVATE))
    }
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
    private val universalVoiceDelegate = lazy {
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
                    (!universalOffline || (app.daiyu.android.voice.offline.OfflineSpeechPack.state.value.enabled &&
                        app.daiyu.android.voice.offline.OfflineSpeechPack.state.value.ready))
            },
            recognitionConfiguration = {
                val cfg = checkNotNull(universalFrozen).configuration
                when {
                    universalOffline -> UniversalRecognitionConfiguration.Offline
                    cfg.dictation == "DOUBAO" -> UniversalRecognitionConfiguration.Doubao(cfg.asrKey, cfg.asrResource)
                    cfg.dictation == "SYSTEM" -> UniversalRecognitionConfiguration.System("zh-CN")
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
                    universalOwnMutation = true
                    try { updateDraft(text) } finally { universalOwnMutation = false }
                    if (draft != text || selected?.id?.value != turn.permit.conversationId) {
                        revokeUniversalVoice(); result(VoiceControlReceipt.Rejected(turn.requestId))
                    } else {
                        val expected = ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision)
                        universalEditor = expected
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
    private val universalVoice get() = universalVoiceDelegate.value
    private fun microphoneGranted() = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun universalQuestionWaiting(message: Message): Boolean = message.blocks.filterIsInstance<MessageBlock.Tool>()
        .mapNotNull { controller.questionProjection(message, it.invocation) }
        .any { it.record.answer == null && !it.record.interrupted }
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
            browserApproval != null || browserPreparing || generationApproval != null ||
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
    private fun startUniversalVoice(profile: VoiceControlProfile, limits: VoiceControlLimits) {
        if (!activityUsable() || !voiceForeground || retainedPage != "chat" || sidebarVisible) return
        stopCascadeVoice(); invalidateConfiguredVoice(); dictationHost.invalidate(); offlineHost.invalidate()
        speechHost.stop(speechHost.owner)
        try { universalVoice.start(profile, limits) }
        catch (_: Exception) { revokeUniversalVoice(); toast("持续语音未启动：检查会话独立配置、离线包或音频释放状态") }
    }
    private var preparedSpeech: FrozenVoiceSpeech? = null
    private var voiceConsent: android.app.AlertDialog? = null
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
            target.editorGeneration == editorGeneration && target.draftRevision == draftRevision
    private val remoteVoice by lazy {
        ActivityRemoteVoiceHost(applicationContext, lifecycleScope, voiceRuntime, ::voiceEditorCurrent,
            applyDraft = { target, text ->
                if (voiceEditorCurrent(target)) {
                    updateDraft(text); check(draft == text) { "remote_dictation_draft_not_saved" }
                    selectionStart = text.length; selectionEnd = text.length; publishChat()
                }
            }, requestPermission = { microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO) })
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
        voiceConsent = android.app.AlertDialog.Builder(this)
            .setTitle("本次发送参考录音？")
            .setMessage("将向 ${config.provider}（${config.endpoint}）发送参考录音「${reference.name}」及本条回答文本用于合成，可能收费。仅授权本次请求，不长期授权。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认本次发送") { _, _ ->
                if (current()) lifecycleScope.launch {
                    try { play(voiceRuntime.authorizeReference(frozen)) }
                    catch (_: Exception) { if (current()) toast("参考录音或配置已失效；未发送") }
                }
            }.create().also { it.show() }
    }
    private var voiceForeground = false
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        dictationHost.permissionResult(granted)
        // A permission sheet may pause/invalidate its editor token. Report only the app-level
        // permission outcome in that case; never resurrect that token or start a recognizer.
        if (activityUsable()) toast(if (granted) "麦克风权限已授予，请再次点击语音开始听写"
            else "麦克风权限被拒绝；草稿未更改")
    }
    private val dictationHost by lazy {
        ComposerDictationHost(applicationContext, audioGate,
            isEditorCurrent = { target ->
                activityUsable() && voiceForeground && retainedPage == "chat" && !sidebarVisible &&
                    selectionReady && controller.ready && !controller.dataBusy &&
                    !imageHost.sending && !shizukuWritesBlocked && terminalEditor == null &&
                    virtualDisplayEditor == null && virtualDisplayPlanVisit == null && !videoSending && pendingTextSend == null &&
                    controller.textSendState() == null &&
                    target.conversationId == selected?.id?.value &&
                    target.editorGeneration == editorGeneration && target.draftRevision == draftRevision
            },
            requestPermission = { microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO) },
            applyDraft = { _, text ->
                updateDraft(text)
                check(draft == text) { "dictation_draft_not_saved" }
                selectionStart = text.length; selectionEnd = text.length
                publishChat()
            })
    }
    private val offlineMicrophonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (activityUsable()) toast(if (granted) "麦克风权限已授予，请再次点击离线语音" else "麦克风权限被拒绝；草稿未更改")
    }
    private val offlineHost by lazy {
        app.daiyu.android.voice.offline.OfflineComposerHost(applicationContext,
            requestPermission = { offlineMicrophonePermission.launch(android.Manifest.permission.RECORD_AUDIO) },
            current = { target ->
                activityUsable() && voiceForeground && retainedPage == "chat" && !sidebarVisible &&
                    selectionReady && controller.ready && !controller.dataBusy && pendingTextSend == null &&
                    controller.textSendState() == null && !imageHost.sending && !shizukuWritesBlocked &&
                    terminalEditor == null && virtualDisplayEditor == null && virtualDisplayPlanVisit == null && !videoSending &&
                    target.conversationId == selected?.id?.value &&
                    target.editorGeneration == editorGeneration && target.draftRevision == draftRevision
            },
            currentText = { draft },
            applyDraft = { _, text, cursor ->
                updateDraft(text)
                check(draft == text) { "offline_dictation_draft_not_saved" }
                selectionStart = cursor; selectionEnd = cursor
                publishChat()
            })
    }
    private fun startOfflineDictation() {
        invalidateConfiguredVoice()
        dictationHost.invalidate()
        offlineHost.start(DictationDraftTarget(selected?.id?.value, editorGeneration, draftRevision),
            draft, selectionStart, selectionEnd)
    }
    private val imageHost by lazy { ImageChatHost.obtain(controller, { if (activityUsable()) publishChat() },
        { if (activityUsable()) toast(it) }, ::settleImageReceipt) }
    private var imagePanelVisible by mutableStateOf(false)
    private var videoPanelVisible by mutableStateOf(false)
    private var videoSending by mutableStateOf(false)
    private var videoReceiptRequest: VideoDraftRequest? = null
    private var videoReceiptEditor: ControllerVideoEditor? = null
    private var videoDialog: android.app.AlertDialog? = null
    private val videoHost get() = controller.videoHost
    private val videoUi = object : ControllerVideoUi {
        override fun editor() = ControllerVideoEditor(selected?.id?.value, editorGeneration, draftRevision,
            draft, activityUsable() && voiceForeground && retainedPage == "chat" && !sidebarVisible)
        override fun showRun(prompt: app.daiyu.android.model.videos.VideoApprovalGate.Prompt,
            text: String, decision: (Boolean) -> Unit) {
            showVideoPermission("批准本次原视频运行", prompt.disclosure +
                "\n模型：${prompt.request.provider.model}\n端点：${prompt.request.provider.asModelSettings().requestEndpoint()}" +
                "\n\n原文：\n" + text +
                "\n\n" + prompt.inventory.joinToString("\n") { "${it.mime} · ${it.bytes} 字节 · SHA-256 ${it.sha256}" }, decision)
        }
        override fun showPost(prompt: ControllerVideoPostPrompt, decision: (Boolean) -> Unit) {
            showVideoPermission("许可本次 POST #${prompt.postNumber}", prompt.disclosure +
                "\n正文 SHA-256：${prompt.requestSha256}\n\n" + prompt.references.joinToString("\n") {
                    "${it.mime} · ${it.bytes} 字节 · SHA-256 ${it.sha256}"
                }, decision)
        }
    }
    private fun showVideoPermission(title: String, message: String, decision: (Boolean) -> Unit) {
        videoDialog?.dismiss()
        val decided = AtomicBoolean(false)
        fun answer(value: Boolean) { if (decided.compareAndSet(false, true)) decision(value) }
        videoDialog = android.app.AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("明确允许") { _, _ -> answer(true) }
            .setNegativeButton("拒绝") { _, _ -> answer(false) }
            .setOnCancelListener { answer(false) }.create().also { dialog ->
                dialog.setOnDismissListener { answer(false); if (videoDialog === dialog) videoDialog = null }
                dialog.show()
            }
    }
    private fun fenceVideoRoute() {
        controller.invalidateVideo(videoUi) // Gate and real session first; dismiss is not revocation.
        videoDialog?.dismiss(); videoDialog = null
        if (controller.ownsVideoUi(videoUi)) videoHost.detach()
    }
    private fun videoComposerAvailable() = terminalEditor == null && virtualDisplayEditor == null && virtualDisplayPlanVisit == null &&
        shizukuEditor == null && browserApproval == null && generationApproval == null &&
        pendingTextSend == null && controller.textSendState() == null &&
        !imageHost.hasAttachments && !imageHost.busy && !shizukuWritesBlocked
    @Composable private fun VideoComposerPanel() {
        app.daiyu.android.ui.screens.chat.attachments.VideoAttachments(videoHost, selected?.id?.value,
            app.daiyu.android.ui.screens.chat.attachments.VideoDraftText(draft, editorGeneration, draftRevision),
            onHandoff = { request, text, settle ->
                handoffVideo(request, ControllerVideoEditor(request.conversation, text.editorGeneration,
                    text.revision, text.text, true), settle)
            }, enabled = videoComposerAvailable(),
            parentBusy = videoSending || controller.active != null || !controller.ready || controller.dataBusy)
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
    private var sidebarVisibleState by mutableStateOf(false)
    private var sidebarVisible: Boolean
        get() = sidebarVisibleState
        set(value) {
            // Opening revokes even without a visit; closing cannot revive clicks rendered behind it.
            if (value != sidebarVisibleState) revokeContextManagementEntry()
            if (value && !sidebarVisibleState) { stopCascadeVoice(); invalidateConfiguredVoice(); fenceVideoRoute(); cancelGenerationOutputs(); cancelTerminalEditor(); cancelVirtualDisplayEditor(); cancelGuiEditor() }
            sidebarVisibleState = value
        }
    private class ContextManagementVisit(val owner: ContextManagementOwner,
        val adapter: ContextManagementControllerAdapter)
    private var contextManagementVisit by mutableStateOf<ContextManagementVisit?>(null)
    // Entry authority is separate from the shared editor generation and is never restored/saved.
    private var contextManagementEntryEpoch by mutableStateOf(0L)
    // Revoke BEFORE navigation/lifecycle changes, including when no route has been opened yet.
    private fun revokeContextManagementEntry(): Boolean {
        contextManagementEntryEpoch++
        val visit = contextManagementVisit ?: return false
        controller.invalidateContextManagement(visit.adapter)
        contextManagementVisit = null
        return true
    }
    private var retainedPageState by mutableStateOf("chat")
    private var retainedPage: String
        get() = retainedPageState
        set(value) {
            // Revoke before any navigation work, not a later Compose disposal or IO callback.
            if (value != retainedPageState && revokeContextManagementEntry()) editorGeneration++
            if (value != retainedPageState) cancelDeferredOpenedBackup()
            if (value != "reconnect-settings") {
                reconnectSettingsPageOwner?.revoke()
                reconnectSettingsPageOwner = null
            }
            if (value != retainedPageState) { stopCascadeVoice(); invalidateConfiguredVoice(); fenceVideoRoute(); cancelGenerationOutputs(); cancelVirtualDisplayEditor(); cancelGuiEditor() }
            if (value != "session-gateway") {
                gatewayOwner?.revoke(); gatewayOwner = null
                gatewayEditing = null; gatewayDraft = null
                gatewayReplacementAuthorized = false; gatewayError = null
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
            if (value != "terminal") terminalPageOwner = null
            if (value != "linux-settings") {
                linuxSettingsVisit = null
                linuxSettingsPageOwner?.close()
                linuxSettingsPageOwner = null
            }
            if (value != "browser-settings") {
                browserSettingsPageOwner?.revoke()
                browserSettingsPageOwner = null
            }
            if (value != "chat") { dictationHost.invalidate(); offlineHost.invalidate(); cancelShizukuEditor(); cancelBrowserEditor() }
            speechHost.setVisible(value == "chat" && !sidebarVisible)
            imageHost.setVisible(value == "chat" && !sidebarVisible, selected?.id?.value)
            retainedPageState = value
            syncHaptics()
        }
    private var paneState by mutableStateOf(ConversationPaneUiState(emptyList(), null, ""))
    private var chatState by mutableStateOf(ChatScreenState("代鱼"))
    private var interactiveModeEnabled by mutableStateOf(false)
    private var interactiveModeObservation: AutoCloseable? = null
    private var selected: Conversation? = null
        set(value) {
            if (field?.id != value?.id && revokeContextManagementEntry()) editorGeneration++
            field = value
        }
    private var draft by mutableStateOf("")
    private var selectionStart = 0
    private var selectionEnd = 0
    private var selectionReady = false
    private val messageActionHost by lazy { ControllerMessageActionHost(controller) }
    private var messageActionVisit = Any()
    private var messageActionSnapshot: app.daiyu.android.storage.MessageActionSnapshot? = null
    private data class MessageActionRenderKey(val conversation: ConversationId, val editor: Long,
        val rows: String, val data: Long)
    private var messageActionKey: MessageActionRenderKey? = null
    private var messageActionLoading: MessageActionRenderKey? = null
    private var messageActionAttempted: MessageActionRenderKey? = null
    private fun invalidateMessageActionEditor() {
        messageActionHost.invalidate()
        messageActionVisit = Any()
        messageActionSnapshot = null; messageActionKey = null
        messageActionLoading = null; messageActionAttempted = null
    }
    private fun prepareMessageActionSnapshot() {
        val owner = selected?.id ?: run { invalidateMessageActionEditor(); return }
        if (!activityUsable() || !voiceForeground || !selectionReady || !controller.ready || controller.dataBusy || controller.active != null) return
        val key = MessageActionRenderKey(owner, editorGeneration,
            app.daiyu.android.domain.context.ContextRevision.digest(shown.map { it.second }), controller.dataRevision)
        if (messageActionKey == key || messageActionLoading == key || messageActionAttempted == key) return
        messageActionSnapshot = null; messageActionKey = null
        // Failed, mismatched and rejected reads consume this key too; refresh is not retry authority.
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
        if (!admitted) messageActionLoading = null
    }
    private var editorGeneration = 0L
        set(value) {
            if (field != value) { revokeContextManagementEntry(); invalidateMessageActionEditor(); invalidateConfiguredVoice(); dictationHost.invalidate(); offlineHost.invalidate() }
            field = value
        }
    private var draftRevision = 0L
        set(value) {
            if (field != value) { messageActionHost.invalidate(); invalidateConfiguredVoice(); dictationHost.invalidate(); offlineHost.invalidate() }
            field = value
        }
    private data class PendingTextSend(val owner: ChatController.TextSendOwner, val text: String,
        val ticket: ChatController.TextSendTicket = ChatController.TextSendTicket(),
        val editorOwner: ChatController.TextSendOwner = owner)
    private var pendingTextSend by mutableStateOf<PendingTextSend?>(null)
    private class TerminalEditor(val conversation: Conversation?, val draft: TerminalDraftSnapshot) {
        var command by mutableStateOf("")
        var linux by mutableStateOf<app.daiyu.android.terminal.LinuxGenerationRef?>(null)
        var preparing by mutableStateOf(false)
        var choosing by mutableStateOf(false)
        var confirmed by mutableStateOf(false)
        var preparation by mutableStateOf<ControllerTerminalPreparation?>(null)
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
        var status by mutableStateOf("正在检查原运行排空状态；不观察物理退出、不发送 Release。")
    }
    // Workers read only this atomic owner, never Compose state or the current text editor.
    private val currentCleanupOwner = AtomicReference<R08CleanupUiOwner?>(null)
    private var virtualDisplayCleanupVisit by mutableStateOf<VirtualDisplayCleanupVisit?>(null)
    private var guiEditor by mutableStateOf<ControllerGuiEntry?>(null)
    private var virtualDisplayEditor by mutableStateOf<ControllerVirtualDisplayEntry?>(null)
    private var virtualDisplayRequest by mutableStateOf<app.daiyu.android.vdapproval.VirtualDisplayApprovalRequest?>(null)
    private var browserApproval by mutableStateOf<ControllerBrowserPreparation?>(null)
    private var browserPreparing by mutableStateOf(false)
    private var browserRequest: Any? = null
    private var generationApproval by mutableStateOf<ChatController.GenerationEntry?>(null)
    private var generationForm by mutableStateOf(app.daiyu.android.ui.GenerationApprovalForm())
    private var generationFormRevision by mutableStateOf(0L)
    private var generationRequest: Any? = null
    private var generationOutputs by mutableStateOf<List<ChatController.GenerationAssetDisplay>?>(null)
    private var historicalGenerationOutputs by mutableStateOf<List<ChatController.HistoricalGenerationAssetDisplay>?>(null)
    private var pendingHistoricalOriginal: app.daiyu.android.backup.HistoricalGenerationOutput? = null
    private var generationOutputsRequest: Any? = null
    private var generationReceiptNotice by mutableStateOf<ChatController.GenerationReceiptNotice?>(null)
    private val supplementDraft = PausedSupplementDraft()
    private val selections get() = controller.selections
    private var shown = mutableListOf<Pair<Long, Message>>()
    private data class QuestionRowUi(val message: Message,
        val projections: List<ChatController.QuestionProjection>, val retryCleanup: Boolean)
    private var questionRows by mutableStateOf<Map<String, QuestionRowUi>>(emptyMap())
    private var titleRevision = -1L
    private var dataRevision = -1L
    private var scrollSerial = 0L
    private var historyRequestSerial = 0L
    private var controlOwner: Message? = null
    private var controlTouchOwner: Message? = null
    private var controlTouchActive = false
    private var controlTouchGeneration = 0L
    private val listener: () -> Unit = { refresh() }
    private var activityCallbacksAllowed = false
    private var refreshRateWindowResumed = false
    private var previousPreferredRefreshRate: Float? = null
    private var requestedPreferredRefreshRate: Float? = null
    private val refreshRateRequest = Runnable { applyPreferredRefreshRate() }

    // Prompts are one-shot capabilities, not saved state. A replaced/dismissed prompt cannot commit.
    private sealed interface Prompt { val backup: Boolean }
    private class InputPrompt(val title: String, val initial: String, val hint: String,
        val confirm: String, val action: (String) -> Unit, override val backup: Boolean = false) : Prompt
    private class ChoicePrompt(val title: String, val options: List<String>, val action: (Int) -> Unit,
        override val backup: Boolean = false) : Prompt
    private class ConfirmPrompt(val title: String, val message: String, val confirm: String,
        val destructive: Boolean = false, val action: () -> Unit, override val backup: Boolean = false) : Prompt
    private var prompt by mutableStateOf<Prompt?>(null)
    private var lastOpenedBackupUri: String? = null
    // One validated, bounded URI only: never an Intent/ClipData, descriptor, saved state or grant.
    // Recovery completion may offer the normal manual choice, but must not read/import a file.
    private class DeferredOpenedBackup(val uri: Uri, val waitingPrompt: Prompt)
    private var deferredOpenedBackup: DeferredOpenedBackup? = null
    private var pendingBackupRequest: Int? = null
    private var exportPickerPending by mutableStateOf(false)
    private var conversationExport = ConversationExportSelection()
    private var exportConversationId by mutableStateOf<String?>(null)
    private var exportConversationTitle by mutableStateOf("")
    private var taskRecordsConversationId by mutableStateOf<String?>(null)
    // Frozen entry context. Readers run on IO and must not inspect mutable Compose/host state.
    private class SearchPage(
        val route: String, val returnPage: String, val conversationId: String?,
        val active: Message?, val folderId: String?,
    )
    private var searchPage by mutableStateOf<SearchPage?>(null)
    private var configurationRestorePreview by mutableStateOf<ChatController.ConfigurationRestorePreview?>(null)
    private var restorePreviewText by mutableStateOf("")
    private var backupBusy by mutableStateOf(false)
    private var editingSettings: EditingSettings? = null
    private var modelDraft by mutableStateOf<ModelSettingsDraftUi?>(null)
    private var modelError by mutableStateOf<String?>(null)
    private var editingCatalogIdentity by mutableStateOf<String?>(null)
    private var modelEditingOwner by mutableStateOf<Any?>(null)
    private var gatewayOwner by mutableStateOf<ModelSettingsSaveOwner?>(null)
    private var gatewayEditing: EditingSettings? = null
    private var gatewayDraft by mutableStateOf<app.daiyu.android.model.sessiongateway.SessionGatewayRule?>(null)
    private var gatewayError by mutableStateOf<String?>(null)
    private var gatewayReplacementAuthorized by mutableStateOf(false)
    private var chatModelPicker by mutableStateOf<ChatModelPickerState?>(null)
    private var chatModelSettings: ModelSettings? = null
    private var chatModelSession: LocalCatalogChatModelPickerSession? = null
    private var chatModelSelectionFailed = false
    private var chatModelCancelled = AtomicBoolean(false)
    private var chatModelOwner: Any? = null
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
    private fun assistantOwnerMatches(owner: AssistantSelectionOwner): Boolean = activityUsable() &&
        owner.accepts(assistantSelectionOwner,selected?.id?.value,editorGeneration,assistantPicker)
    private var assistantEditingId by mutableStateOf<String?>(null)
    private var assistantAvatarRevision by mutableStateOf(0L)
    private var collaborationOwner by mutableStateOf<String?>(null)
    private var collaborationVisible by mutableStateOf(false)
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
        lifecycleScope.launch {
            // Application-retained deferred: first Keystore/disk read runs on IO, once per process.
            val runtime = (application as ChatApplication).balanceHost.get()
            balanceRuntime = runtime
            balanceLifecycle = app.daiyu.android.balance.BalanceLifecycleBinding(
                lifecycle, lifecycleScope, runtime, poll = true)
        }
        if (state?.getBoolean("balance-page", false) == true) retainedPageState = "balance-settings"
        // An Activity mount never inherits an approval capability from an older mount.
        controller.virtualDisplayEntry()?.let(controller::cancelVirtualDisplay)
        controller.guiEntry()?.let(controller::cancelGui)
        app.daiyu.android.voice.offline.OfflineSpeechPack.initialize(applicationContext)
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { controller.voiceAccounts.voices.load() } }
            catch (_: Exception) { /* Personal account voices fail closed; settings reports local recovery. */ }
            try { voiceRuntime.load() } catch (_: Exception) { /* Settings page offers explicit repair. */ }
        }
        if (state?.getBoolean("terminal-page", false) == true) {
            terminalPageOwner = Any()
            retainedPageState = "terminal"
        }
        hapticsHost = app.daiyu.android.haptics.HapticsActivityHost(
            (application as ChatApplication).hapticsRuntime, window.decorView, this, controller)
        interactiveModeObservation = controller.interactiveMode.observe { interactiveModeEnabled = it }
        appearance = loadAppearance()
        activityCallbacksAllowed = true
        pendingBackupRequest = state?.getInt("pending-backup-request", 0)?.takeIf { it != 0 }
        conversationExport = ConversationExportSelection(
            state?.getString("pending-conversation-export").takeIf { pendingBackupRequest == 105 })
        exportPickerPending = pendingBackupRequest == 105
        exportConversationId = state?.getString("export-page-conversation")
        exportConversationTitle = state?.getString("export-page-title").orEmpty()
        taskRecordsConversationId = state?.getString("task-records-conversation")
        if (exportConversationId != null) retainedPage = "conversation-export"
        else if (taskRecordsConversationId != null) retainedPage = "child-tasks"
        if (intent?.action == ChatApplication.OPEN_PET_SETTINGS) retainedPage = "pet-settings"
        setContent {
            app.daiyu.android.ui.screens.settings.ApplicationPetMount(pets, this@MainActivity, Modifier.fillMaxSize()) {
            AgentAppTheme(appearance, applyInterfaceScale = true, onResolvedDarkModeChange = ::updateSystemBars) {
                val iconCoordinator = rememberIconAppearanceCoordinator(iconNotifications, ::reportAutomaticIconSync)
                val focus = LocalFocusManager.current
                val balanceState = balanceRuntime?.state?.collectAsStateWithLifecycle()?.value
                val chatPageState = rememberSaveableStateHolder()
                val assistantProfiles by assistantStore.assistants.collectAsState()
                val activeAssistant by assistantStore.selectedId.collectAsState()
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
                LaunchedEffect(sidebarVisible, retainedPage) {
                    if (sidebarVisible || retainedPage != "chat") focus.clearFocus()
                }
                BackHandler(enabled = retainedPage != "chat") {
                    when (retainedPage) {
                        "appearance", "haptics", "offline-speech", "voice-settings", "terminal", "browser-settings", "compression-settings", "reconnect-settings", "linux-settings", "pet-settings", "vision-settings", "session-gateway", "balance-settings", "title-model-settings", "platform-settings" -> retainedPage = "settings"
                        "pet-model-settings" -> retainedPage = "pet-settings"
                        "app-icon" -> retainedPage = "appearance"
                        "conversation-search", "message-search" -> searchPage?.let(::closeSearch)
                        else -> returnToChat()
                    }
                }
                when (retainedPage) {
                    "context-management" -> {
                        val visit = contextManagementVisit
                        if (visit != null) key(visit) {
                            ContextManagementRoute(visit.adapter, onClose = {
                                if (contextManagementVisit === visit) returnToChat()
                            })
                        } else Column(Modifier.fillMaxSize().padding(16.dp)) {
                            top.yukonga.miuix.kmp.basic.Text("上下文页面已失效；请返回原会话重新打开")
                            top.yukonga.miuix.kmp.basic.TextButton(text = "返回", onClick = { returnToChat() })
                        }
                    }
                    "balance-settings" -> {
                        val runtime = balanceRuntime
                        if (runtime != null) app.daiyu.android.balance.ui.BalanceSettingsScreen(
                            runtime = runtime, onBack = { retainedPage = "settings" },
                            modifier = Modifier.fillMaxSize())
                        else Column(Modifier.fillMaxSize().padding(16.dp)) {
                            top.yukonga.miuix.kmp.basic.TextButton(text = "返回", onClick = { retainedPage = "settings" })
                            top.yukonga.miuix.kmp.basic.Text("正在读取独立余额配置")
                        }
                    }
                    "pet-settings" -> Column(Modifier.fillMaxSize()) {
                        top.yukonga.miuix.kmp.basic.TextButton(text = "返回", onClick = { retainedPage = "settings" })
                        top.yukonga.miuix.kmp.basic.TextButton(text = "配置独立助手 / 服务商 / 模型", onClick = { retainedPage = "pet-model-settings" })
                        top.yukonga.miuix.kmp.basic.TextButton(text = "允许本次进程全局悬浮", onClick = { pets.setOverlayAllowed(true) })
                        top.yukonga.miuix.kmp.basic.TextButton(text = "禁止本次进程全局悬浮", onClick = { pets.setOverlayAllowed(false) })
                        top.yukonga.miuix.kmp.basic.TextButton(text = "打开系统悬浮窗授权（仍需上方明确允许）", onClick = {
                            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")))
                        })
                        app.daiyu.android.ui.screens.settings.PetSettingsScreen(pets.host, Modifier.weight(1f))
                    }
                    "pet-model-settings", "vision-settings" -> key(retainedPage) {
                        val petModel = retainedPage == "pet-model-settings"
                        app.daiyu.android.ui.screens.settings.IndependentModelScreen(controller.independentModels,
                            if (petModel) app.daiyu.android.storage.IndependentModelSettings.Slot.PET
                            else app.daiyu.android.storage.IndependentModelSettings.Slot.VISION,
                            assistantProfiles, pets,
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
                                onSearch = { query -> retainedData.search(query) },
                                currentConversationId = page.conversationId,
                                onSearchCurrentConversation = { query, id ->
                                    check(id == page.conversationId) { "搜索范围已改变" }
                                    retainedData.searchCurrentConversation(query, id)
                                },
                                resultLimit = RetainedConversationData.SEARCH_RESULT_LIMIT,
                                onOpenHit = { hit ->
                                    if (searchPage === page && retainedPage == page.route) guard { openHistoryHit(hit) }
                                },
                                onBack = { closeSearch(page) },
                            )
                        }
                    }
                    "usage" -> UsageStatsScreen(onBack = { returnToChat() }, load = { retainedData.stats() })
                    "conversation-export" -> exportConversationId?.let { id ->
                        ConversationExportScreen(
                            conversationTitle = exportConversationTitle,
                            available = controller.ready && controller.active == null && !controller.hasLiveChildTasks() &&
                                controller.problem?.startsWith("会话恢复失败") != true,
                            pending = exportPickerPending || pendingBackupRequest != null, busy = backupBusy,
                            onExport = { if (exportConversationId == id) backupGuard { pickConversationExport(id) } },
                            onBack = { returnToChat() },
                        )
                    }
                    "child-tasks" -> taskRecordsConversationId?.let { id ->
                        ChildTaskRecordsScreen(id, load = { target -> controller.childTasks(ConversationId(target)) },
                            loadLive = { target -> controller.liveChildTasks(ConversationId(target)) },
                            onPause = { record -> controller.pauseChildTask(record) },
                            onResume = { record -> controller.resumeChildTask(record) },
                            onCommand = { command -> controller.directChildTask(command) },
                            onStop = { record -> controller.stopChildTask(record) },
                            onBack = { returnToChat() })
                    }
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
                                retainedPage = "settings"
                        }) else {
                            val owner = ready.application.terminalOwner
                            val caller by owner.caller.collectAsStateWithLifecycle()
                            app.daiyu.android.ui.screens.terminal.TerminalScreen(
                                host = owner.host, caller = caller,
                                isCurrent = { activityUsable() && retainedPage == "terminal" && terminalPageOwner === page },
                                onOpenAndroid = { openTerminalAndroid(page) },
                                onOpenLinux = { reference -> openTerminalLinux(page, reference) },
                                onBack = {
                                    if (activityUsable() && retainedPage == "terminal" && terminalPageOwner === page)
                                        retainedPage = "settings"
                                },
                            )
                        }
                    }
                    "linux-settings" -> {
                        val state by terminalProvider.state.collectAsStateWithLifecycle()
                        val ready = state as? app.daiyu.android.terminal.TerminalApplicationState.Ready
                        val page = linuxSettingsPageOwner
                        val visit = linuxSettingsVisit
                        if (ready == null || page == null) TerminalLoadingPage(state, onBack = {
                            if (activityUsable() && retainedPage == "linux-settings" && linuxSettingsVisit === visit)
                                retainedPage = "settings"
                        }) else {
                            LinuxSettingsScreen(ready.application.settings, page, onBack = {
                                if (activityUsable() && retainedPage == "linux-settings" && linuxSettingsPageOwner === page)
                                    retainedPage = "settings"
                            })
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
                        app.daiyu.android.title.TitleModelSettingsScreen(page, ownerCurrent = {
                            activityUsable() && titleSettingsPageOwner === page && retainedPage == "title-model-settings"
                        }, onBack = { retainedPage = "settings" })
                    } }
                    "compression-settings" -> compressionSettingsPageOwner?.let { page -> key(page) {
                        CompressionSettingsScreen(page, onBack = {
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
                    "skills" -> SkillManagementScreen(
                        managementFactory = { controller.skillManagement() },
                        onBack = { returnToChat() },
                    )
                    "voice-settings" -> VoiceSettingsPanel(voiceRuntime, remoteVoice, controller.voiceAccounts,
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
                        if (owner != null && value != null) key(owner) {
                            SessionGatewaySettingsScreen(
                                draft = value,
                                onDraftChange = { if (gatewayCurrent(owner) && !controller.dataBusy) gatewayDraft = it },
                                onSave = { saveSessionGateway(owner, it) },
                                onBack = { if (gatewayCurrent(owner)) retainedPage = "settings" },
                                errorText = gatewayError,
                                enabled = controller.ready && controller.active == null && !controller.dataBusy,
                                requestOptionsUnavailable = gatewayEditing?.requestOptionsUnavailable == true,
                                replacementAuthorized = gatewayReplacementAuthorized,
                                onReplacementAuthorization = {
                                    if (gatewayCurrent(owner) && !controller.dataBusy) gatewayReplacementAuthorized = it
                                },
                            )
                        } else Column {
                            top.yukonga.miuix.kmp.basic.TextButton(text = "返回", onClick = { retainedPage = "settings" })
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
                    "settings" -> Column(Modifier.fillMaxSize()) {
                        top.yukonga.miuix.kmp.basic.TextButton(text = "保留会话网关",
                            modifier = Modifier.testTag("open-session-gateway"), onClick = { guard { openSessionGateway() } })
                        top.yukonga.miuix.kmp.basic.TextButton(text = "语音设置 / 实时通话",
                            modifier = Modifier.testTag("open-voice-settings"), onClick = {
                                if (activityUsable() && retainedPage == "settings") retainedPage = "voice-settings"
                            })
                        Box(Modifier.weight(1f)) { DaiyuSettingsScreen(
                        onBack = { returnToChat() }, onRoute = { route -> openSettingsFromPage(route) },
                        onOpenSubAgents = { retainedPage = "subagents" },
                        onOpenAssistants = { openAssistantPicker(global = true) },
                        onOpenAppearance = { retainedPage = "appearance" },
                        onOpenHaptics = {
                            if (activityUsable() && retainedPage == "settings") retainedPage = "haptics"
                        },
                        onOpenPlatformTools = {
                            if (activityUsable() && retainedPage == "settings") retainedPage = "platform-settings"
                        },
                    ) } }
                    "offline-speech" -> app.daiyu.android.ui.screens.settings.OfflineSpeechSettingsScreen(
                        applicationContext, onBack = { retainedPage = "settings" },
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
                    "models" -> modelDraft?.let { value ->
                        val identity = editingCatalogIdentity
                        if (identity == null) ModelSettingsScreen(
                            draft = value, onDraftChange = { changeModelDraft(it) },
                            onSave = { saveModelSettings(it) }, onFetchCatalog = {},
                            onBack = { returnToChat() }, errorText = modelError,
                            credentialUnavailable = editingSettings?.credentialUnavailable == true,
                            requestOptionsUnavailable = editingSettings?.requestOptionsUnavailable == true,
                            catalogEnabled = false, enabled = !controller.dataBusy,
                        ) else key(modelEditingOwner) { ProviderCatalogSettingsScreen(
                            draft = value, onDraftChange = { changeModelDraft(it) },
                            onSave = { saveModelSettings(it) }, onBack = { returnToChat() },
                            providerIdentity = identity, persistence = controller.settings.modelCatalog,
                            createModel = { CatalogModelParameters.newModel(it) },
                            fetchCatalog = ::requestSettingsCatalog,
                            enabled = controller.ready && controller.active == null && !controller.dataBusy,
                            errorText = modelError,
                            credentialUnavailable = editingSettings?.credentialUnavailable == true,
                            requestOptionsUnavailable = editingSettings?.requestOptionsUnavailable == true,
                        ) }
                    }
                    "backup" -> BackupScreen(
                        onBack = { returnToChat() },
                        onExportCompact = { backupGuard { pickBackup(true, false) } },
                        onExportComplete = { backupGuard { pickBackup(true, true) } },
                        onImportCompact = { backupGuard { pickBackup(false, false) } },
                        onImportComplete = { backupGuard { pickBackup(false, true) } },
                        restorePreviewText = restorePreviewText,
                        onPreviewRestore = { backupGuard { previewConfigurationRestore() } },
                        onRestoreConfiguration = { confirmConfigurationRestore() },
                        canRestoreConfiguration = configurationRestorePreview?.plan?.isNoOp == false,
                        busy = backupBusy,
                    )
                    "appearance" -> AppearanceSettingsScreen(
                        state = appearance,
                        onChange = { saveAppearance(it) },
                        onBack = { retainedPage = "settings" },
                        onOpenIcon = { if (activityUsable() && retainedPage == "appearance") retainedPage = "app-icon" },
                    )
                    "app-icon" -> IconAppearanceScreen(
                        coordinator = iconCoordinator,
                        onBack = { if (activityUsable() && retainedPage == "app-icon") retainedPage = "appearance" },
                    )
                    "subagents" -> SubAgentSettingsScreen(onBack = { returnToChat() },
                        repositoryFactory = { subAgentRepository }, isCurrentRoute = retainedPage == "subagents")
                    "assistant-edit" -> assistantEditingId?.let { id ->
                        var skills by remember(id) { mutableStateOf(emptyList<AssistantSkill>()) }
                        LaunchedEffect(id) {
                            skills = withContext(Dispatchers.IO) {
                                try { controller.skillManagement().snapshot().entries.map { entry ->
                                    AssistantSkill(entry.id, entry.skill.name, entry.skill.description)
                                } } catch (_: Exception) { emptyList() }
                            }
                        }
                        AssistantEditScreen(assistantId = id, onBack = { returnToChat() },
                            store = assistantStore, assets = assistantAssets, installedSkills = skills,
                            onSaved = { savedId ->
                                if (activityUsable()) {
                                    assistantAvatarRevision++
                                    if (retainedPage == "assistant-edit" && assistantEditingId == savedId) refresh()
                                }
                            })
                    }
                    else -> ConversationSidePaneScaffold(
                        state = paneState, visible = sidebarVisible, backHandlerEnabled = true,
                        assistants = projectedAssistants, selectedAssistantId = activeAssistant,
                        onOpen = { openSidebar() }, onDismiss = { dismissSidebar() },
                        onSearchChange = { paneState = paneState.copy(searchQuery = it) },
                        onConversationSelected = { id -> guard { openRetainedConversation(id) } },
                        onConversationRename = { item -> editRetainedConversation(item, false) },
                        onConversationDelete = { item -> confirmDelete(item.id) },
                        onMoveConversationToFolder = { item -> editRetainedConversation(item, true) },
                        onConversationTogglePin = { item -> guard { toggleRetainedPin(item.id) } },
                        onConversationExport = { item -> backupGuard { openConversationExport(item.id) } },
                        onConversationTasks = { item -> guard { openChildTaskRecords(item.id) } },
                        onNewConversation = { guard { dismissSidebar(); select(null) } },
                        onOpenManageChats = { guard { dismissSidebar(); updatePane(); retainedPage = "manage" } },
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
                        onSelectAssistant = { id -> guard { assistantStore.select(id) } },
                        onEditAssistant = { id -> guard {
                            checkNotNull(assistantStore.read(id)) { "助手已不存在" }
                            dismissSidebar(); assistantEditingId = id; retainedPage = "assistant-edit"
                        } },
                        onOpenAssistants = { dismissSidebar(); openAssistantPicker(global = true) },
                        onOpenSettings = { dismissSidebar(); retainedPage = "settings" },
                        onOpenModelProviders = { dismissSidebar(); guard { modelSettings() } },
                        onOpenUsageStats = { dismissSidebar(); retainedPage = "usage" },
                        onOpenSkills = { dismissSidebar(); guard { openSkillManagement() } },
                        onOpenPermissions = { dismissSidebar(); retainedPage = "settings" },
                    ) {
                        chatPageState.SaveableStateProvider(chatState.conversationKey ?: "new-chat") {
                            Column(Modifier.fillMaxSize().imePadding()) {
                                DaiyuChatScreen(chatState.copy(interactiveModeEnabled = interactiveModeEnabled, balanceState = balanceState), chatActions(), modifier = Modifier.weight(1f), turnExtras = { id ->
                                    QuestionExtras(id)
                                    VoiceExtras(id)
                                }, imageLoader = imageHost.historyLoader)
                                pendingTextSend?.let { pending ->
                                    top.yukonga.miuix.kmp.basic.TextButton(
                                        text = "取消发送准备",
                                        modifier = Modifier.testTag("text-send-cancel"),
                                        onClick = { cancelPendingTextSend(pending) }
                                    )
                                }
                                if (imagePanelVisible) ImageComposerPanel()
                                if (videoPanelVisible && !sidebarVisible) VideoComposerPanel()
                                DictationComposerEntry()
                                ShizukuComposerEntry()
                            }
                        }
                    }
                }
                PromptSurface()
                VirtualDisplayR08Surfaces()
                val vdEntry = virtualDisplayEditor
                val vdRequest = virtualDisplayRequest
                if (vdEntry != null && vdRequest != null) {
                    androidx.compose.ui.window.Dialog(onDismissRequest = virtualDisplayCancelAction(vdEntry, vdRequest),
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
                        androidx.compose.material3.Surface(tonalElevation = 8.dp) {
                            androidx.compose.material3.TextButton(
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
                    val owner = chatModelOwner
                    val session = chatModelSession
                    val reload = { if (chatModelOwner === owner && owner != null) reloadChatModels(owner) }
                    val settings = { if (chatModelOwner === owner && owner != null) {
                        dismissChatModels(); guard { modelSettings() }
                    } }
                    val dismiss = { if (chatModelOwner === owner) dismissChatModels() }
                    if (session == null) ChatModelPicker(picker, onSelect = {}, onRefresh = reload,
                        onSettings = settings, onDismiss = dismiss)
                    else LocalCatalogChatModelPicker(session, enabled = picker.enabled,
                        loading = picker.loading, message = picker.message,
                        onSelect = { selectChatModel(it, owner, session) }, onReload = reload,
                        onSettings = settings, onDismiss = dismiss)
                }
                assistantSelectionOwner?.let { owner -> key(owner) {
                    AssistantPickerDialog(show = assistantPicker, store = assistantStore, assets = assistantAssets,
                        selectedId = owner.expectedAssistantId, useGlobalSelection = false,
                        canMutate = { assistantOwnerMatches(owner) && !controller.dataBusy },
                        onSelect = { id -> guard {
                            check(assistantOwnerMatches(owner)) { "选择器所属页面已变化；未改变绑定" }
                            if(owner.global) assistantStore.select(id)
                            else {
                                val conversationId=ConversationId(checkNotNull(owner.conversationId))
                                check(controller.bindConversationAssistant(conversationId,owner.expectedAssistantId,id)) { "当前正在准备或运行，或绑定已变化；未改变绑定" }
                                selected=selected?.copy(assistantId=id)
                            }
                            refresh()
                        } },
                        onEdit = { id -> if(assistantOwnerMatches(owner)) {
                            assistantPicker = false; assistantEditingId = id; retainedPage = "assistant-edit"
                        } },
                        onDismiss = { if(assistantSelectionOwner===owner) assistantPicker = false })
                } }
                val owner = collaborationOwner
                ConversationCollaborationDialog(show = collaborationVisible, conversationId = owner,
                    onDismiss = { collaborationVisible = false; collaborationOwner = null }, taskRunning = controller.active != null || backupBusy,
                    ownerMatches = { activityUsable() && selected?.id?.value == owner && retainedPage == "chat" && !controller.dataBusy },
                    repositoryFactory = { subAgentRepository })
                // UI preference only: no runtime reasoning-capability contract exists yet.
                val thinkingOwner = chatState.conversationKey
                SubAgentThinkingEffortPickerDialog(show = thinkingPicker, effort = reasoningPreference.get(thinkingOwner),
                    options = ReasoningEffort.entries, onDismiss = { thinkingPicker = false }, onEffortChange = { effort ->
                        if (activityUsable() && selected?.id?.value == thinkingOwner) {
                            reasoningPreference.set(thinkingOwner, effort)
                            publishChat()
                        }
                        thinkingPicker = false
                    })
            }
        }
        }
        controller.observe(listener)
        refresh()
        // Recreating an Activity never replays an external VIEW/import confirmation.
        if (state == null) offerOpenedBackup(intent)
    }

    @Suppress("DEPRECATION")
    private fun updateSystemBars(isDark: Boolean) {
        val background = if (isDark) DaiyuPalette.dark.background else DaiyuPalette.light.background
        window.statusBarColor = background.toArgb()
        window.navigationBarColor = background.toArgb()
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !isDark
        controller.isAppearanceLightNavigationBars = !isDark
    }

    private fun chatActions(): ChatScreenActions {
        val renderedOwner = controlOwner
        val renderedConversation = chatState.conversationKey
        val renderedEditorGeneration = editorGeneration
        val renderedContextEntryEpoch = contextManagementEntryEpoch
        val renderedDraftRevision = draftRevision
        val renderedSpeechOwner = speechHost.owner
        fun editorMatches() = activityUsable() && selectionReady && pendingTextSend == null && controller.textSendState() == null &&
            selected?.id?.value == renderedConversation && editorGeneration == renderedEditorGeneration
        fun gestureOwner() = currentControlOwner()?.takeIf {
            renderedOwner != null && it.id == renderedOwner.id && it.runId == renderedOwner.runId && it.conversationId == renderedOwner.conversationId
        }
        fun pendingFeature() { Toast.makeText(this, "该功能界面已保留，后端尚未接入", Toast.LENGTH_SHORT).show() }
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
                        updateDraft(outcome.text)
                        selectionStart = draft.length; selectionEnd = draft.length; publishChat()
                    }
                    is MessageActionResult.Branch -> select(outcome.conversation)
                    MessageActionResult.Deleted -> { shown.clear(); refresh() }
                    is MessageActionResult.Regenerated -> {
                        select(outcome.conversation)
                        when (outcome.outcome) {
                            RegenerationSendOutcome.ACCEPTED -> Unit
                            RegenerationSendOutcome.NOT_SENT -> toast("新分支和原用户草稿已保留；本次未发送")
                            RegenerationSendOutcome.UNKNOWN -> {
                                shown.clear(); refresh()
                                toast("新分支发送保存结果 UNKNOWN；请核实历史和服务端结果，不会自动重发或重新准备")
                            }
                        }
                    }
                    is MessageActionResult.Rejected -> {
                        if (actionSnapshot == null) {
                            prompt = ChoicePrompt("消息操作快照不可用", listOf("重新获取消息操作", "取消"), { choice ->
                                if (choice == 0 && editorMatches() && draftRevision == renderedDraftRevision &&
                                    retainedPage == "chat" && !sidebarVisible && messageActionLoading == null) guard {
                                    messageActionSnapshot = null; messageActionKey = null; messageActionAttempted = null
                                    prepareMessageActionSnapshot(); publishChat()
                                }
                            })
                        } else toast(outcome.reason)
                    }
                    is MessageActionResult.Unknown -> { shown.clear(); refresh(); toast(outcome.reason) }
                }
            } })
        return ChatScreenActions(
        onInteractiveModeChange = { enabled -> if (activityUsable()) controller.interactiveMode.setEnabled(enabled) },
        onVoice = {
            if (editorMatches() && terminalEditor == null && virtualDisplayEditor == null && virtualDisplayPlanVisit == null && !videoSending &&
                draftRevision == renderedDraftRevision && voiceForeground &&
                retainedPage == "chat" && !sidebarVisible &&
                controller.ready && !controller.dataBusy && !imageHost.sending && !shizukuWritesBlocked) {
                if (app.daiyu.android.voice.offline.OfflineSpeechPack.state.value.enabled) startOfflineDictation()
                else {
                    val target = DictationDraftTarget(selected?.id?.value, editorGeneration, draftRevision)
                    val config = try { voiceRuntime.snapshot() } catch (_: Exception) { null }
                    when (config?.dictation) {
                        "SYSTEM" -> { invalidateConfiguredVoice(); dictationHost.start(target) }
                        "DOUBAO" -> { revokeUniversalVoice(); invalidatePreparedSpeech(); dictationHost.invalidate(); offlineHost.invalidate(); remoteVoice.dictate(target) }
                        else -> toast("听写未配置或凭据不可用，请打开语音设置")
                    }
                }
            }
        }, onContextClick = {
            val conversation = selected
            if (editorMatches() && voiceForeground && retainedPage == "chat" && !sidebarVisible &&
                renderedContextEntryEpoch == contextManagementEntryEpoch && conversation != null &&
                !renderedConversation.isNullOrBlank() && conversation.id.value == renderedConversation &&
                editorGeneration == renderedEditorGeneration) {
                val owner = ContextManagementOwner(conversation.id, renderedEditorGeneration)
                val adapter = controller.openContextManagement(owner)
                if (adapter == null) toast("上下文页面未打开；当前正在准备、运行或执行任务，请稍后再试")
                else {
                    retainedPage = "context-management"
                    contextManagementVisit = ContextManagementVisit(owner, adapter)
                }
            }
        },
        onRegenerate = messageCallbacks.onRegenerate, onSpeak = { id ->
            if (editorMatches() && controller.ready && !controller.dataBusy &&
                speechHost.owner == renderedSpeechOwner && selected?.id?.value == renderedConversation) {
                // Resolve the CURRENT displayed answer, never a captured stream/tool payload.
                voiceHostText(chatState.turns, id)?.let { text ->
                    requestConfiguredSpeech(renderedSpeechOwner, id, text)
                }
            }
        },
        onBranch = messageCallbacks.onBranch, onEditUser = messageCallbacks.onEditUser,
        onDeleteTurn = messageCallbacks.onDeleteTurn, onRetryStep = messageCallbacks.onRetryStep,
        onOpenSidebar = { openSidebar() }, onTextChange = { if (editorMatches()) updateDraft(it) },
        onSelectionChange = { start, end ->
            if (editorMatches()) {
                if (selectionStart != start || selectionEnd != end) offlineHost.invalidate()
                selectionStart = start; selectionEnd = end; publishChat()
            }
        },
        onSend = { text -> if (editorMatches() && terminalEditor == null && text == draft && pendingTextSend == null && !shizukuWritesBlocked) guard {
            invalidateConfiguredVoice()
            dictationHost.invalidate()
            offlineHost.invalidate()
            cancelShizukuEditor()
            cancelBrowserEditor()
            if (videoHost.state.items.isNotEmpty()) { stopCascadeVoice(); videoPanelVisible = true }
            else if (imageHost.hasAttachments) { stopCascadeVoice(); sendImageDraft(text) }
            else if (!imageHost.busy && !videoSending) sendDraft(text)
        } },
        onSupplement = { text -> if (editorMatches() && text == draft && !imageHost.sending && !shizukuWritesBlocked) guard {
            offlineHost.invalidate()
            gestureOwner()?.let { submitPausedText(it, text) }
        } },
        onPrimaryTap = { guard {
            gestureOwner()?.let { owner -> controller.runState(owner)?.let { state ->
                val accepted = if (state.pauseRequested) controller.resume(owner) else controller.pause(owner)
                if (accepted) refresh()
            } }
        } },
        onPrimaryLongPress = { guard { gestureOwner()?.let { owner -> controller.stop(owner); refresh() } } },
        onControlPress = {
            controlTouchGeneration++; controlTouchActive = true; controlTouchOwner = controlOwner
        },
        onControlRelease = {
            val generation = controlTouchGeneration
            window.decorView.post { if (controlTouchGeneration == generation) { controlTouchActive = false; controlTouchOwner = null } }
        },
        onModelClick = { guard { openChatModels() } }, onReasoningClick = { thinkingPicker = true },
        onAssistantClick = { openAssistantPicker() }, onCopy = { id -> copyMessage(id) },
        onMenuAction = { action -> guard { when (action) {
            ChatMenuAction.ATTACH_IMAGE -> {
                if (editorMatches() && videoHost.state.items.isEmpty() && !videoSending) {
                    videoPanelVisible = false; imagePanelVisible = true; pickImages()
                }
            }
            ChatMenuAction.ATTACH_VIDEO -> {
                if (editorMatches() && !imageHost.hasAttachments && !imageHost.busy && !shizukuWritesBlocked) {
                    cancelShizukuEditor(); cancelBrowserEditor(); cancelGenerationEditor()
                    imagePanelVisible = false; videoPanelVisible = true
                }
            }
            ChatMenuAction.NEW_CHAT -> select(null)
            ChatMenuAction.RENAME -> selected?.let { conversation ->
                editConversation(conversation.id.value, false)
            }
            ChatMenuAction.SUB_AGENT -> { collaborationOwner = selected?.id?.value; collaborationVisible = true }
            ChatMenuAction.LOAD_EARLIER -> loadEarlier()
            ChatMenuAction.LOCATE_USER -> chooseUserMessage()
            ChatMenuAction.BOTTOM -> jumpToBottom()
            ChatMenuAction.SEARCH -> openSearch("message-search")
            ChatMenuAction.SETTINGS -> retainedPage = "settings"
            ChatMenuAction.OPEN_TERMINAL -> openTerminal()
            ChatMenuAction.CONVERSATION_ID -> selected?.let {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("会话 ID", it.id.value))
            }
            else -> pendingFeature()
        } } },
        )
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
        top.yukonga.miuix.kmp.basic.TextButton(text = "离线语音设置", modifier = Modifier.testTag("offline-speech-open"),
            onClick = { if (activityUsable() && retainedPage == "chat" && !sidebarVisible) retainedPage = "offline-speech" })
        if (pack.enabled) top.yukonga.miuix.kmp.basic.TextButton(
            text = if (offline.listening) "取消离线听写" else "离线听写（本机麦克风）",
            modifier = Modifier.testTag("offline-speech-microphone"),
            onClick = {
                if (activityUsable() && retainedPage == "chat" && !sidebarVisible && voiceForeground &&
                    editorGeneration == capturedGeneration && draftRevision == capturedRevision &&
                    selected?.id?.value == capturedConversation) startOfflineDictation()
            })
        if (offline.status.isNotEmpty()) top.yukonga.miuix.kmp.basic.Text(text = offline.status,
            modifier = Modifier.testTag("offline-speech-status").semantics { liveRegion = LiveRegionMode.Polite })
        if (offline.preview.isNotEmpty()) top.yukonga.miuix.kmp.basic.Text(text = "离线识别预览（尚未写入草稿）：${offline.preview}",
            modifier = Modifier.testTag("offline-speech-preview"))
        val cascadeStatus by cascadeVoice.status.collectAsStateWithLifecycle()
        val voiceConfig by voiceRuntime.configuration.collectAsStateWithLifecycle()
        val conversationVoiceConfig by voiceRuntime.conversationConfiguration.collectAsStateWithLifecycle()
        ProductionUniversalVoicePanel(universalVoice, conversationVoiceConfig,
            configure = { if (currentEditor()) retainedPage = "voice-settings" }, start = ::startUniversalVoice)
        top.yukonga.miuix.kmp.basic.Text("听写：${if (voiceConfig?.dictation == "DOUBAO") "豆包联网" else "系统 / 未配置"}；最终结果只填草稿")
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
        if (cascadeStatus.isNotEmpty()) {
            top.yukonga.miuix.kmp.basic.Text(cascadeStatus,
                modifier = Modifier.testTag("voice-cascade-status").semantics { liveRegion = LiveRegionMode.Polite })
            top.yukonga.miuix.kmp.basic.TextButton(text = "停止级联", modifier = Modifier.testTag("voice-cascade-stop"), onClick = {
                if (currentVoiceControls()) {
                    stopCascadeVoice(); invalidateConfiguredVoice(); dictationHost.invalidate(); offlineHost.invalidate()
                }
            })
        }
        val remoteStatus by remoteVoice.status.collectAsStateWithLifecycle()
        if (remoteStatus.isNotEmpty()) {
            top.yukonga.miuix.kmp.basic.Text(remoteStatus,
                modifier = Modifier.testTag("remote-voice-status").semantics { liveRegion = LiveRegionMode.Polite })
            top.yukonga.miuix.kmp.basic.TextButton(text = "停止云端语音", modifier = Modifier.testTag("remote-voice-stop"), onClick = {
                if (currentVoiceControls()) {
                    stopCascadeVoice(); invalidateConfiguredVoice(); speechHost.stop(capturedSpeechOwner)
                }
            })
        }
        val state by dictationHost.state.collectAsStateWithLifecycle()
        if (state.status.isNotEmpty()) {
            top.yukonga.miuix.kmp.basic.Text(text = state.status,
                modifier = Modifier.testTag("dictation-status").semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (state.preview.isNotEmpty()) {
            top.yukonga.miuix.kmp.basic.Text(text = "识别预览（非最终草稿）：${state.preview}",
                modifier = Modifier.testTag("dictation-partial"))
        }
        if (state.listening) {
            val target = state.target
            top.yukonga.miuix.kmp.basic.TextButton(text = "停止听写", modifier = Modifier.testTag("dictation-stop"),
                onClick = { if (dictationHost.state.value.target == target) dictationHost.invalidate() })
        }
    }

    @Composable private fun ShizukuComposerEntry() {
        val images by imageHost.session.attachments.state.collectAsStateWithLifecycle()
        val hasImages = images.items.isNotEmpty() || videoHost.state.items.isNotEmpty() || videoSending
        val generation = editorGeneration
        val revision = draftRevision
        val available = activityUsable() && retainedPage == "chat" && !sidebarVisible && selectionReady &&
            controller.ready && !controller.dataBusy && controller.active == null &&
            !imageHost.busy && !shizukuWritesBlocked && !hasImages && draft.isNotBlank() && terminalEditor == null
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                top.yukonga.miuix.kmp.basic.TextButton(
                    text = if (hasImages) "Shizuku 仅限无附件文本" else "Shizuku 文本审批",
                    enabled = available && shizukuEditor == null,
                    modifier = Modifier.weight(1f).testTag("shizuku-text-entry").heightIn(min = 48.dp),
                    onClick = {
                        if (available && generation == editorGeneration && revision == draftRevision &&
                            !imageHost.hasAttachments && controller.shizukuPreparation() == null) {
                            shizukuEditor = ShizukuEditor(selected, generation, revision, draft)
                        }
                    })
                top.yukonga.miuix.kmp.basic.TextButton(text = "Shizuku 权限设置",
                    modifier = Modifier.weight(1f).testTag("shizuku-settings-entry").heightIn(min = 48.dp),
                    onClick = { if (activityUsable()) retainedPage = "shizuku-settings" })
            }
            top.yukonga.miuix.kmp.basic.TextButton(
                text = if (browserPreparing) "正在准备浏览器审批" else "浏览器操作审批",
                enabled = available && !browserPreparing && browserApproval == null && pendingTextSend == null,
                modifier = Modifier.testTag("browser-approval-entry").heightIn(min = 48.dp),
                onClick = ::openBrowserApproval)
            top.yukonga.miuix.kmp.basic.TextButton(
                text = "图片 / 视频生成审批",
                enabled = available && pendingTextSend == null && generationApproval == null &&
                    !controller.generationSubmissionPending(),
                modifier = Modifier.testTag("generation-approval-entry").heightIn(min = 48.dp),
                onClick = ::openGenerationApproval)
            top.yukonga.miuix.kmp.basic.TextButton(text = "GUI 精确动作审批（前台屏幕）",
                enabled = available && pendingTextSend == null && guiEditor == null,
                modifier = Modifier.testTag("gui-prepare-entry").heightIn(min = 48.dp),
                onClick = ::openGuiPreparation)
            top.yukonga.miuix.kmp.basic.TextButton(text = "GUI 系统无障碍设置",
                modifier = Modifier.testTag("gui-settings-entry").heightIn(min = 48.dp),
                onClick = { if (activityUsable()) app.daiyu.android.gui.GuiSystemSettings.openFromUser(this@MainActivity) })
            guiEditor?.let { entry ->
                androidx.compose.material3.Text(if (entry.submitted) "GUI 已交给本次任务；Unknown 不重试，资源释放前不可再次批准。"
                    else "请切换到目标应用，在无障碍浮层读取并确认唯一真实目标。返回聊天或取消可撤销。")
                top.yukonga.miuix.kmp.basic.TextButton(text = "撤销 GUI 准备 / 停止原任务",
                    modifier = Modifier.testTag("gui-cancel"), onClick = { cancelGuiEditor(entry); publishChat() })
            }
            top.yukonga.miuix.kmp.basic.TextButton(text = "副屏运行准备（需单独批准）",
                enabled = available && pendingTextSend == null && virtualDisplayEditor == null && virtualDisplayPlanVisit == null &&
                    controller.virtualDisplayEntry() == null,
                modifier = Modifier.testTag("vd-prepare-entry").heightIn(min = 48.dp),
                onClick = ::openVirtualDisplayPreparation)
            virtualDisplayEditor?.let { entry ->
                androidx.compose.material3.Text("已冻结 ${entry.writes.size} 项写步骤、${entry.reads.size} 项读取；消息受理后仍须审阅完整副屏授权。")
                top.yukonga.miuix.kmp.basic.TextButton(text = "接受消息并审阅完整副屏授权",
                    enabled = !entry.submitted,
                    modifier = Modifier.testTag("vd-submit"),
                    onClick = { submitVirtualDisplay(entry) })
                top.yukonga.miuix.kmp.basic.TextButton(text = "停止副屏运行 / 取消准备",
                    modifier = Modifier.testTag("vd-cancel"), onClick = virtualDisplayCancelAction(entry, null))
            }
            controller.virtualDisplayEntry()?.let { originalEntry ->
                top.yukonga.miuix.kmp.basic.TextButton(text = "为原副屏申请额外 Release 收尾",
                    enabled = originalEntry.revoked && selected?.id == originalEntry.conversationId,
                    modifier = Modifier.testTag("vd-cleanup-entry"),
                    onClick = { openVirtualDisplayCleanup(originalEntry) })
                top.yukonga.miuix.kmp.basic.TextButton(text = "核实副屏停止（UNKNOWN 继续保留）",
                    modifier = Modifier.testTag("vd-observe-close"),
                    onClick = { controller.observeVirtualDisplayClose(originalEntry) })
            }
            top.yukonga.miuix.kmp.basic.TextButton(text = "读取本会话生成资产",
                modifier = Modifier.testTag("generation-output-entry").heightIn(min = 48.dp),
                onClick = ::openGenerationOutputs)
            TerminalComposerEntry()
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
                top.yukonga.miuix.kmp.basic.TextButton(text = "停止工具型浏览器子任务（UNKNOWN 保留）",
                    modifier = Modifier.testTag("child-browser-stop"), onClick = controller::stopBrowserChildren)
            }
            val editor = shizukuEditor
            if (editor != null && !hasImages) key(editor) {
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())
                    .testTag("shizuku-text-panel")) {
                    ShizukuApprovalHost(controller, editor.conversation, editor.text,
                        onAccepted = { preview, saved -> acceptShizuku(editor, preview, saved) })
                    top.yukonga.miuix.kmp.basic.TextButton(text = "取消文本审批",
                        modifier = Modifier.testTag("shizuku-text-cancel"), onClick = ::cancelShizukuEditor)
                }
            }
        }
    }

    private fun terminalDraft() = TerminalDraftSnapshot(
        TerminalDraftOwner(selected?.id, editorGeneration, draftRevision), draft)

    private fun terminalEditorCurrent(editor: TerminalEditor): Boolean = activityUsable() &&
        terminalEditor === editor && retainedPage == "chat" && !sidebarVisible && selectionReady &&
        terminalDraft() == editor.draft && !imageHost.hasAttachments &&
        videoHost.state.items.isEmpty() && !videoSending && pendingTextSend == null &&
        shizukuEditor == null && browserApproval == null && generationApproval == null

    private fun terminalInputEditable(editor: TerminalEditor) = terminalEditorCurrent(editor) &&
        !editor.preparing && !editor.choosing && editor.preparation == null

    private fun cancelTerminalEditor() {
        val editor = terminalEditor
        terminalEditor = null
        editor?.preparation?.let(controller::rejectTerminal)
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
        top.yukonga.miuix.kmp.basic.TextButton(text = "终端精确命令审批",
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
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                .testTag("terminal-chat-editor")) {
                androidx.compose.material3.Text("聊天草稿不是 shell。仅发送下面独立输入区的 UTF-8 字节；不补回车。需要执行时请在输入中明确换行。准备和确认都不发送、不启动。")
                androidx.compose.material3.OutlinedTextField(value = editor.command,
                    onValueChange = { if (terminalInputEditable(editor)) editor.command = it },
                    enabled = terminalInputEditable(editor),
                    label = { androidx.compose.material3.Text("精确终端命令（包含所需换行）") },
                    modifier = Modifier.fillMaxWidth().testTag("terminal-exact-command"))
                val canChoose = terminalInputEditable(editor)
                androidx.compose.material3.TextButton(enabled = canChoose, onClick = {
                    if (terminalInputEditable(editor)) editor.linux = null
                }, modifier = Modifier.testTag("terminal-chat-android")) {
                    androidx.compose.material3.Text(if (editor.linux == null) "已选 Android（应用 UID）" else "选择 Android（应用 UID）")
                }
                if (application != null) {
                    val host = application.terminalOwner.host
                    val status by host.state.collectAsStateWithLifecycle()
                    LaunchedEffect(application) { host.refreshLinux() }
                    status.linux.choices.forEach { choice ->
                        androidx.compose.material3.TextButton(enabled = canChoose, onClick = {
                            if (terminalInputEditable(editor)) {
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
                if (pending == null) {
                    androidx.compose.material3.Button(enabled = terminalInputEditable(editor) && editor.command.isNotEmpty(),
                        modifier = Modifier.testTag("terminal-prepare"), onClick = { prepareTerminal(editor) }) {
                        androidx.compose.material3.Text(if (editor.preparing) "准备中（不启动）" else "准备终端审批（不启动）")
                    }
                } else {
                    val state = if (editor.confirmed) ControllerTerminalPreparation.State.CONFIRMED else pending.state
                    app.daiyu.android.ui.TerminalApprovalHost(
                        draft = terminalDraft(), assistant = pending.assistant, provider = pending.provider,
                        preparation = pending, state = state, onPrepare = { false },
                        onConfirm = { preparation, displayed, current ->
                            if (!terminalEditorCurrent(editor) || editor.preparation !== preparation) false
                            else {
                                val configuration = runCatching { controller.terminalConfiguration(editor.conversation) }.getOrNull()
                                val confirmed = configuration != null && controller.confirmTerminal(preparation, displayed,
                                    current, configuration.first, configuration.second)
                                if (confirmed) { editor.confirmed = true; publishChat() } else cancelTerminalEditor()
                                confirmed
                            }
                        }, onCancel = { if (terminalEditor === editor && editor.preparation === it) cancelTerminalEditor() }, problem = controller.problem)
                    if (state == ControllerTerminalPreparation.State.CONFIRMED) {
                        androidx.compose.material3.Text("已确认；尚未发送或执行。仅授权 2 个步骤及 2 个有界读页。")
                        androidx.compose.material3.Button(modifier = Modifier.testTag("terminal-send-approved"),
                            onClick = { sendTerminal(editor, pending) }) {
                            androidx.compose.material3.Text("发送已批准任务")
                        }
                    }
                }
                androidx.compose.material3.TextButton(onClick = { if (terminalEditor === editor) cancelTerminalEditor() },
                    modifier = Modifier.testTag("terminal-chat-cancel")) { androidx.compose.material3.Text("取消终端审批") }
            }
        }
    }

    private fun prepareTerminal(editor: TerminalEditor) {
        if (!terminalInputEditable(editor)) return
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
        generationRequest = null
        val entry = generationApproval
        generationApproval = null
        if (entry != null && !entry.submitted) controller.abandonGeneration(entry)
    }

    private fun openGenerationApproval() {
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
                    generationApproval = entry
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

    @Composable private fun GenerationSurfaces() {
        generationApproval?.let { entry ->
            androidx.compose.ui.window.Dialog(onDismissRequest = ::cancelGenerationEditor) {
                androidx.compose.material3.Surface {
                    Column(Modifier.fillMaxWidth().heightIn(max = 620.dp)
                        .verticalScroll(rememberScrollState()).padding(16.dp)) {
                        androidx.compose.material3.Text(ControllerGenerationResources.DOWNLOAD_STATUS)
                        androidx.compose.material3.Text("当前入口仅支持文本生成；原图和 mask 的安全导入尚未接线。生成配置保存不会启动请求。")
                        app.daiyu.android.ui.GenerationApprovalHost(
                            identity = entry.identity,
                            editor = GenerationEditorSnapshot(generationFormRevision, draft),
                            profiles = entry.settings.profiles,
                            downloads = entry.settings.downloads,
                            form = generationForm,
                            confirmationHandoff = entry.handoff,
                            onFormChange = { value ->
                                if (!entry.submitted) { generationForm = value; generationFormRevision++ }
                            },
                            onPickReference = { toast("此入口尚不支持安全导入原图；未修改草稿或发送请求") },
                            onPickMask = { toast("此入口尚不支持安全导入 mask；未修改草稿或发送请求") },
                            onConfirmed = {
                                val valid = activityUsable() && generationApproval === entry &&
                                    selected?.id == entry.conversation?.id && editorGeneration == entry.editorGeneration &&
                                    draftRevision == entry.initialEditor.revision && draft == entry.initialEditor.text &&
                                    !imageHost.hasAttachments && videoHost.state.items.isEmpty()
                                val accepted = valid && controller.sendGeneration(entry,
                                    GenerationEditorSnapshot(generationFormRevision, draft))
                                generationApproval = null
                                if (accepted) publishChat()
                                else toast("本次生成未受理；草稿保留，请等待清理后重新预览")
                                accepted
                            },
                            onCancelled = ::cancelGenerationEditor)
                        androidx.compose.material3.TextButton(onClick = ::cancelGenerationEditor) {
                            androidx.compose.material3.Text("关闭生成表单")
                        }
                    }
                }
            }
        }
        generationOutputs?.let { outputs ->
            androidx.compose.material3.AlertDialog(onDismissRequest = ::cancelGenerationOutputs,
                title = { androidx.compose.material3.Text("已登记生成资产") },
                text = {
                    Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                        if (outputs.isEmpty()) androidx.compose.material3.Text("暂无已登记资产；不会解析模型结果 URL，也不会重试生成。")
                        outputs.forEach { output ->
                            val meta = output.metadata
                            androidx.compose.material3.Text("${meta.kind} · ${meta.mimeType} · ${meta.bytes} bytes · ${meta.width}×${meta.height}")
                            output.thumbnail?.let { bitmap ->
                                androidx.compose.foundation.Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "已校验的生成图片", modifier = Modifier.fillMaxWidth())
                            }
                            if (meta.kind == app.daiyu.android.model.generation.GenerationKind.VIDEO)
                                androidx.compose.material3.Text("原视频已通过私有读取校验；当前入口不提供视频播放或导出。")
                        }
                        androidx.compose.material3.Text("备份导入的历史原件（独立只读存储，最多展示32项；完整备份仍包含全部记录）")
                        val historical = historicalGenerationOutputs
                        if (historical == null) androidx.compose.material3.Text("历史原件正在读取或不可用；不会重发请求。")
                        else if (historical.isEmpty()) androidx.compose.material3.Text("暂无导入的历史生成原件。")
                        historical?.forEach { display ->
                            val history = display.history
                            androidx.compose.material3.Text("历史 ${history.kind} · ${history.mimeType} · ${history.bytes} bytes · ${history.width}×${history.height}")
                            display.thumbnail?.let { bitmap ->
                                androidx.compose.foundation.Image(bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "备份中的历史生成图片", modifier = Modifier.fillMaxWidth())
                            }
                            if (history.kind == app.daiyu.android.model.generation.GenerationKind.VIDEO)
                                androidx.compose.material3.Text("历史原视频已校验；可保存原文件，不恢复生成授权。")
                            androidx.compose.material3.TextButton(onClick = {
                                backupGuard { pickHistoricalGenerationOriginal(history) }
                            }) { androidx.compose.material3.Text("保存历史原件") }
                        }
                    }
                }, confirmButton = { androidx.compose.material3.TextButton(onClick = ::cancelGenerationOutputs) {
                    androidx.compose.material3.Text("关闭")
                } })
        }
    }

    private fun cancelGenerationOutputs() {
        generationOutputsRequest = null
        generationOutputs = null
        historicalGenerationOutputs = null
    }

    private fun openGenerationOutputs() {
        val conversation = selected ?: return
        if (!activityUsable() || !selectionReady || retainedPage != "chat" || sidebarVisible) return
        cancelGenerationOutputs()
        val request = Any().also { generationOutputsRequest = it }
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

    private fun guiOwnerCurrent(entry: ControllerGuiEntry): Boolean = activityUsable() &&
        guiEditor === entry && controller.guiEntry() === entry && !entry.revoked &&
        retainedPage == "chat" && !sidebarVisible && selectionReady &&
        entry.editor == ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision) &&
        entry.text == draft && !imageHost.hasAttachments && videoHost.state.items.isEmpty() && !videoSending

    private fun openGuiPreparation() {
        if (!activityUsable() || retainedPage != "chat" || sidebarVisible || !selectionReady ||
            pendingTextSend != null || imageHost.busy || imageHost.hasAttachments ||
            videoHost.state.items.isNotEmpty() || videoSending || shizukuWritesBlocked || draft.isBlank()) return
        val editor = ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision)
        val text = draft
        prompt = InputPrompt("本次 GUI 精确动作", "{\"operation\":\"tap\"}",
            "operation：tap、long_press、scroll_forward、scroll_backward、input、back、home、recents、notifications。" +
                "仅 input 必须带完整 text（最多 4096 字符）；不接受坐标。随后在真实目标应用浮层选择节点并最终确认。",
            "打开真实目标确认", { raw ->
                if (!activityUsable() || retainedPage != "chat" || sidebarVisible || !selectionReady ||
                    editor != ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision) ||
                    text != draft || shizukuWritesBlocked || pendingTextSend != null || imageHost.hasAttachments ||
                    videoHost.state.items.isNotEmpty() || videoSending) return@InputPrompt
                val parsed = try { app.daiyu.android.gui.GuiUserActionRequest.parse(raw) }
                catch (_: Exception) { toast("GUI 参数无效；未打开授权、未执行动作"); return@InputPrompt }
                cancelBrowserEditor(); cancelShizukuEditor(); dictationHost.invalidate()
                val entry = controller.prepareGuiSend(selected, editor, text, parsed.operation, parsed.text)
                if (entry == null) { toast("GUI 不可用或仍待释放；请手动启用系统服务，草稿保留"); return@InputPrompt }
                guiEditor = entry // retain before overlay attachment
                val result = try { checkNotNull(entry.session).prepareExactActionFromUser(entry.operation, entry.input,
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
                    cancelGuiEditor(entry); toast("GUI 确认浮层不可用；未执行，草稿保留")
                }
                publishChat()
            })
    }

    private fun cancelGuiEditor(original: ControllerGuiEntry) {
        if (guiEditor !== original) return
        controller.cancelGui(original) // fence before hiding; retain Controller resource until real release
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
        if (virtualDisplayPlanVisit === original) virtualDisplayPlanVisit = null
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
        if (virtualDisplayEditor == null) toast("副屏计划未受理；保留草稿，不自动重发")
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
        lifecycleScope.launch {
            val request = withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { adapter.prepareOnWorker() }
            if (cleanupVisitCurrent(visit) && request != null && adapter.canPublish(request)) {
                visit.request = request
                visit.status = "请核对原 owner 的独立一次 Release 授权；不是原文本权限恢复。"
            } else if (cleanupVisitCurrent(visit)) {
                visit.status = "暂不可收尾：排空未完成、请求已回答或结果 UNKNOWN。未发送，不自动重试。"
            } else closeVirtualDisplayCleanup(visit)
        }
    }

    private fun answerVirtualDisplayCleanup(visit: VirtualDisplayCleanupVisit,
        request: R08CleanupApprovalRequest, accepted: Boolean): Boolean {
        if (!cleanupVisitCurrent(visit) || visit.request !== request || visit.decided) return false
        val answered = visit.adapter.answerFromUi(request, accepted)
        if (!accepted) { closeVirtualDisplayCleanup(visit); return answered }
        if (!answered) return false
        visit.decided = true
        visit.status = "已显式批准；排队 worker 将再次检查原父记录、本地 drain 和 UI admission。"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                visit.adapter.releaseOnWorker(request)
            }
            if (cleanupVisitCurrent(visit)) visit.status = when (result) {
                ControllerVirtualDisplaySession.CleanupResult.CONFIRMED ->
                    "Release 已确认；不是物理退出证明。保留原记录，请单独核实停止。"
                ControllerVirtualDisplaySession.CleanupResult.UNKNOWN ->
                    "Release UNKNOWN；保留原身份、journal 和 lease，不重发、不重连。"
                ControllerVirtualDisplaySession.CleanupResult.UNAVAILABLE,
                ControllerVirtualDisplaySession.CleanupResult.NOT_APPROVED ->
                    "Release 未发送或已处理；不自动重试，保留原记录。"
            }
        }
        return true
    }

    @Composable private fun VirtualDisplayR08Surfaces() {
        virtualDisplayPlanVisit?.let { visit -> key(visit.host) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { cancelVirtualDisplayPlan(visit) },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                androidx.compose.material3.Surface {
                    R08PlanEditor(visit.host, onFrozen = { freezeVirtualDisplayPlan(visit, it) },
                        onCancel = { cancelVirtualDisplayPlan(visit) })
                }
            }
        } }
        virtualDisplayCleanupVisit?.let { visit -> key(visit.owner) {
            DisposableEffect(visit.adapter) { onDispose { visit.adapter.close() } }
            androidx.compose.ui.window.Dialog(onDismissRequest = { closeVirtualDisplayCleanup(visit) },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                androidx.compose.material3.Surface {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        visit.request?.let { request ->
                            R08CleanupApprovalScreen(request,
                                onDecision = { req, accepted -> answerVirtualDisplayCleanup(visit, req, accepted) },
                                modifier = Modifier.weight(1f))
                        }
                        androidx.compose.material3.Text(visit.status, Modifier.testTag("vd-cleanup-status"))
                        androidx.compose.material3.TextButton(onClick = { closeVirtualDisplayCleanup(visit) },
                            modifier = Modifier.testTag("vd-cleanup-close")) {
                            androidx.compose.material3.Text("关闭此次收尾访问（不释放资源）")
                        }
                    }
                }
            }
        } }
    }

    private fun submitVirtualDisplay(entry: ControllerVirtualDisplayEntry) {
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

    private fun cancelBrowserEditor() {
        cancelTerminalEditor()
        cancelVirtualDisplayEditor()
        cancelGenerationEditor()
        cancelGenerationOutputs()
        browserRequest = null
        browserPreparing = false
        val pending = browserApproval
        browserApproval = null
        pending?.let(controller::rejectBrowser)
    }

    private fun openBrowserApproval() {
        if (videoHost.state.items.isNotEmpty() || videoSending) return
        if (!activityUsable() || pendingTextSend != null || imageHost.hasAttachments || draft.isBlank()) return
        val snapshot = BrowserDraftSnapshot(BrowserDraftOwner(selected?.id, editorGeneration, draftRevision), draft)
        val conversation = selected
        prompt = InputPrompt("本次浏览器写操作", "",
            "每行一个完整 JSON 操作；仅批准这些精确参数。留空只开放读取，不自动建标签、跳转或下载。",
            "预览审批", { raw ->
                val current = BrowserDraftSnapshot(BrowserDraftOwner(selected?.id, editorGeneration, draftRevision), draft)
                if (current != snapshot || !activityUsable() || imageHost.hasAttachments || pendingTextSend != null) return@InputPrompt
                val writes = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
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
                            prepared = controller.prepareBrowserSend(conversation, snapshot, writes)
                        }
                        val matches = activityUsable() && browserRequest === request && retainedPage == "chat" &&
                            !sidebarVisible && snapshot == BrowserDraftSnapshot(
                                BrowserDraftOwner(selected?.id, editorGeneration, draftRevision), draft)
                        if (matches) {
                            browserApproval = prepared
                            retained = prepared != null
                            if (prepared == null) toast(controller.problem ?: "浏览器准备未受理；草稿保留")
                        }
                    } finally {
                        if (!retained) prepared?.let(controller::rejectBrowser)
                        if (browserRequest === request) { browserPreparing = false; browserRequest = null }
                    }
                }
            })
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

    private fun acceptShizuku(editor: ShizukuEditor, preview: ControllerShizukuPreparation, saved: Conversation) {
        if (!activityUsable() || retainedPage != "chat" || sidebarVisible || shizukuEditor !== editor ||
            editor.preview !== preview || !preview.isSubmitted() || editorGeneration != editor.generation || draftRevision != editor.revision || draft != editor.text ||
            selected?.id != editor.conversation?.id || preview.conversation?.id != editor.conversation?.id ||
            preview.text != editor.text || saved.id != preview.conversationId || imageHost.hasAttachments) return
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
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp)) {
            ImageAttachments(images, imageHost.actions(::launchImagePicker), imageHost.draftLoader, enabled = enabled)
            Row(Modifier.fillMaxWidth()) {
                top.yukonga.miuix.kmp.basic.TextButton(text = imageHost.authorizationLabel,
                    enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("image-host-authorize"),
                    onClick = { if (imageHost.session === session && editorGeneration == ownerGeneration) declareImageSupport() })
                top.yukonga.miuix.kmp.basic.TextButton(text = "发送图片", enabled = enabled && images.canSubmit,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("image-host-send"), onClick = {
                        if (imageHost.session === session && editorGeneration == ownerGeneration) guard { sendImageDraft(draft) }
                    })
                top.yukonga.miuix.kmp.basic.TextButton(text = "收起", modifier = Modifier.heightIn(min = 48.dp),
                    onClick = { imagePanelVisible = false })
            }
            if (imageHost.fenced) top.yukonga.miuix.kmp.basic.Text(
                "发送结果待核实；图片和文字已保留，请勿重发", modifier = Modifier.testTag("image-host-unknown"))
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
    private fun declareImageSupport() {
        val generation = editorGeneration
        imageHost.readSettings { saved ->
            if (!activityUsable() || retainedPage != "chat" || generation != editorGeneration) return@readSettings
            if (saved == null) { toast("请先配置模型提供商"); return@readSettings }
            prompt = ConfirmPrompt("声明图片能力", "仅当你已确认当前提供商的精确模型“${saved.model}”支持图片输入时继续。" +
                "此声明不通过模型名称推测能力，配置变更后失效。", "确认支持", action = {
                if (activityUsable() && retainedPage == "chat" && generation == editorGeneration) {
                    imageHost.authorize(saved) { ok -> toast(if (ok) "已授权当前模型的图片输入及图片历史续发" else "授权未生效；请检查配置后重试") }
                }
            })
        }
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
        // No text writes were admitted while any image receipt was outstanding. Reconcile the
        // current view even after A -> B -> A or recreation; never navigate a different owner.
        if (!activityUsable() || !selectionReady || imageHost.session.owner != receipt.owner ||
            imageHost.session.attachments.state.value.draft.revision != receipt.revision ||
            (selected != null && selected?.id != receipt.conversation.id) || draft != text) return
        selected = receipt.conversation; editorGeneration++; draftRevision++
        draft = ""; selectionStart = 0; selectionEnd = 0
        controller.preferences.edit().putString("selected", receipt.conversation.id.value).apply()
        publishChat()
        loadAcceptedHistory(receipt.conversation)
    }

    private fun loadAcceptedHistory(conversation: Conversation) {
        val generation = editorGeneration
        val request = ++historyRequestSerial
        val content = chatState.contentRevision
        val first = shown.firstOrNull()?.first
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                try { readAcceptedWindow(conversation.id, first) } catch (_: Exception) { null }
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
        shown = rows.map { if (active != null && it.second.id == active.id) it.first to active else it }.toMutableList()
        prepareMessageActionSnapshot(); publishChat(); jumpToBottom()
    }

    @Composable private fun PromptSurface() {
        val current = prompt ?: return
        fun valid() = activityUsable() && prompt === current
        when (current) {
            is InputPrompt -> DaiyuTextInputDialog(current.title, current.initial, current.hint, current.confirm,
                onConfirm = { value -> if (valid()) { prompt = null; current.action(value) } }, onDismiss = { if (valid()) prompt = null })
            is ChoicePrompt -> DaiyuChoiceSheet(current.title, current.options,
                onSelect = { index -> if (valid()) { prompt = null; current.action(index) } }, onDismiss = { if (valid()) prompt = null })
            is ConfirmPrompt -> DaiyuConfirmDialog(current.title, current.message, current.confirm, current.destructive,
                onConfirm = { confirmPrompt(current) }, onDismiss = { if (valid()) dismissPrompt(current) })
        }
    }

    private fun dismissPrompt(expected: Prompt) {
        if (prompt !== expected) return
        if (deferredOpenedBackup?.waitingPrompt === expected) cancelDeferredOpenedBackup()
        prompt = null
    }
    private fun confirmPrompt(expected: ConfirmPrompt) {
        if (activityUsable() && prompt === expected) { prompt = null; expected.action() }
    }
    private fun openSettingsFromPage(route: FeatureRoute) {
        if (activityUsable() && retainedPage == "settings") guard { openSettingsRoute(route) }
    }

    @Composable private fun VoiceExtras(id: String) {
        val state by speechHost.state.collectAsStateWithLifecycle()
        if (state.owner != speechHost.owner || state.requestId != id) return
        val status = voiceHostStatusResource(state) ?: return
        val renderedOwner = state.owner
        top.yukonga.miuix.kmp.basic.Text(
            text = stringResource(status),
            modifier = Modifier.testTag("voice-host-status-$id")
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (voiceHostIsActive(state)) {
            top.yukonga.miuix.kmp.basic.TextButton(
                text = stringResource(R.string.voice_host_stop),
                modifier = Modifier.testTag("voice-host-stop-$id"),
                onClick = { speechHost.stop(renderedOwner, id) },
            )
        }
    }

    @Composable private fun QuestionExtras(id: String) {
        val row = questionRows[id] ?: return
        row.projections.forEach { question ->
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
        }
        if (row.retryCleanup) {
            top.yukonga.miuix.kmp.basic.TextButton(text = "重试停止清理",
                modifier = Modifier.testTag("question-retry-cleanup"),
                onClick = { guard { controller.stop(row.message) } })
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        cancelDeferredOpenedBackup() // A recreated visit must require a fresh external open.
        state.putBoolean("terminal-page", retainedPage == "terminal")
        state.putBoolean("balance-page", retainedPage == "balance-settings") // Route only, no balance credentials.
        state.putInt("pending-backup-request", pendingBackupRequest ?: 0)
        state.putString("pending-conversation-export", conversationExport.pendingConversationId)
        if (retainedPage == "conversation-export") {
            state.putString("export-page-conversation", exportConversationId)
            state.putString("export-page-title", exportConversationTitle)
        }
        if (retainedPage == "child-tasks") state.putString("task-records-conversation", taskRecordsConversationId)
        super.onSaveInstanceState(state)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        cancelDeferredOpenedBackup() // Replacement, including invalid VIEW/other actions, revokes.
        if (intent.action == ChatApplication.OPEN_PET_SETTINGS) {
            dismissSidebar(); retainedPage = "pet-settings"
        } else offerOpenedBackup(intent)
    }
    override fun onResume() {
        super.onResume(); voiceForeground = true; speechHost.setForeground(true)
        // Re-render fresh entry callbacks; none captured while backgrounded gains authority here.
        contextManagementEntryEpoch++
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
        if (!refreshRateWindowResumed || isFinishing || isDestroyed) return
        val rate = highestSupportedRefreshRate(window.decorView.display?.supportedModes?.map { it.refreshRate }.orEmpty())
        if (rate <= 0f) return
        val attributes = window.attributes
        if (attributes.preferredRefreshRate == rate) return
        if (previousPreferredRefreshRate == null) previousPreferredRefreshRate = attributes.preferredRefreshRate
        requestedPreferredRefreshRate = rate; attributes.preferredRefreshRate = rate; window.attributes = attributes
    }
    override fun onPause() {
        controller.invalidateVideo(videoUi)
        videoDialog?.dismiss(); videoDialog = null
        (application as ChatApplication).let { app ->
            if (app.petSettingsNavigator === openPetSettings) app.petSettingsNavigator = null
        }
        voiceForeground = false
        stopCascadeVoice()
        invalidateConfiguredVoice()
        dictationHost.invalidate()
        offlineHost.invalidate()
        cancelShizukuEditor()
        cancelBrowserEditor()
        speechHost.setForeground(false)
        refreshRateWindowResumed = false; window.decorView.removeCallbacks(refreshRateRequest)
        val previous = previousPreferredRefreshRate
        if (previous != null && window.attributes.preferredRefreshRate == requestedPreferredRefreshRate) {
            val attributes = window.attributes; attributes.preferredRefreshRate = previous; window.attributes = attributes
        }
        previousPreferredRefreshRate = null; requestedPreferredRefreshRate = null
        if (selectionReady) selections[selected?.id] = selectionStart to selectionEnd
        super.onPause()
    }
    override fun onStop() {
        revokeContextManagementEntry()
        invalidateMessageActionEditor()
        gatewayOwner?.revoke()
        if (retainedPage == "session-gateway") retainedPage = "settings"
        controller.invalidateVideo(videoUi)
        // Idempotent fallback as well as the earlier onPause cancellation.
        voiceForeground = false
        stopCascadeVoice()
        invalidateConfiguredVoice()
        dictationHost.invalidate()
        offlineHost.invalidate()
        speechHost.setForeground(false)
        super.onStop()
    }
    override fun onDestroy() {
        if (revokeContextManagementEntry()) editorGeneration++
        cancelDeferredOpenedBackup()
        invalidateMessageActionEditor()
        reconnectSettingsPageOwner?.revoke()
        reconnectSettingsPageOwner = null
        gatewayOwner?.revoke(); gatewayOwner = null
        gatewayEditing = null; gatewayDraft = null
        balanceLifecycle?.close(); balanceLifecycle = null
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
        browserSettingsPageOwner?.revoke()
        browserSettingsPageOwner = null
        hapticsHost?.close(); hapticsHost = null
        voiceForeground = false
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
        imageHost.close()
        speechHost.close()
        activityCallbacksAllowed = false; refreshRateWindowResumed = false
        window.decorView.removeCallbacks(refreshRateRequest); dismissChatModels()
        cancelRestorePreview(); prompt = null
        controller.unobserve(listener); super.onDestroy()
    }
    private fun returnToChat() {
        dismissChatModels(); modelDraft = null; editingSettings = null; editingCatalogIdentity = null; modelEditingOwner = null; modelError = null
        cancelRestorePreview(); prompt = null; searchPage = null; retainedPage = "chat"
        exportConversationId = null; exportConversationTitle = ""; taskRecordsConversationId = null
        if (activityUsable()) refresh()
    }
    private fun openSkillManagement() {
        retainedPage = "skills"
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
    ) {
        app.daiyu.android.ui.components.MiuixScaffoldPage(title = "终端", onBack = onBack) {
            item {
                app.daiyu.android.ui.screens.settings.SettingsNote(
                    if (state == app.daiyu.android.terminal.TerminalApplicationState.Unavailable)
                        "本地 Linux 配置验证失败；未创建替代终端，请核实配置后重新启动应用。"
                    else "正在工作线程验证本地 Linux 配置…",
                    error = state == app.daiyu.android.terminal.TerminalApplicationState.Unavailable)
            }
        }
    }
    private fun openLinuxSettings() {
        requireActivityUsable()
        linuxSettingsPageOwner?.close()
        val visit = Any()
        linuxSettingsVisit = visit
        linuxSettingsPageOwner = null
        retainedPage = "linux-settings"
        lifecycleScope.launch {
            val state = terminalProvider.state.first {
                it != app.daiyu.android.terminal.TerminalApplicationState.Loading
            }
            if (activityUsable() && retainedPage == "linux-settings" && linuxSettingsVisit === visit &&
                state is app.daiyu.android.terminal.TerminalApplicationState.Ready) {
                linuxSettingsPageOwner = state.application.settings.enterPage()
            }
        }
    }
    private fun openTerminalAndroid(page: Any) {
        if (activityUsable() && retainedPage == "terminal" && terminalPageOwner === page)
            (terminalProvider.state.value as? app.daiyu.android.terminal.TerminalApplicationState.Ready)
                ?.application?.terminalOwner?.openAndroid(selected?.id)
    }
    private fun openTerminalLinux(page: Any, reference: app.daiyu.android.terminal.LinuxGenerationRef) {
        if (activityUsable() && retainedPage == "terminal" && terminalPageOwner === page)
            (terminalProvider.state.value as? app.daiyu.android.terminal.TerminalApplicationState.Ready)
                ?.application?.openLinux(reference, selected?.id)
    }
    private fun openTerminal() {
        requireActivityUsable()
        terminalPageOwner = Any()
        retainedPage = "terminal"
    }
    private fun openSettingsRoute(route: FeatureRoute) {
        when (route) {
            FeatureRoute.MODEL_SETTINGS -> modelSettings()
            FeatureRoute.BALANCE_SETTINGS -> retainedPage = "balance-settings"
            FeatureRoute.BACKUP -> backupMenu()
            FeatureRoute.SIDEBAR -> { returnToChat(); openSidebar() }
            FeatureRoute.MANAGE_CHATS -> { updatePane(); retainedPage = "manage" }
            FeatureRoute.USAGE_STATS -> retainedPage = "usage"
            FeatureRoute.CONVERSATIONS -> chooseConversation()
            FeatureRoute.CONVERSATION_SEARCH -> searchConversations()
            FeatureRoute.FOLDERS -> browseFolders()
            FeatureRoute.SKILLS -> openSkillManagement()
            FeatureRoute.TERMINAL -> openTerminal()
            FeatureRoute.LINUX_SETTINGS -> openLinuxSettings()
            FeatureRoute.BROWSER_SETTINGS -> openBrowserSettings()
            FeatureRoute.RECONNECT_SETTINGS -> openReconnectSettings()
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
    private fun updatePane() {
        if (!controller.ready || controller.dataBusy) return
        val all = retainedData.summaries(controller.active)
        paneState = paneState.copy(conversations = all.filter { it.folderId == paneState.selectedFolderId },
            historyConversations = all, selectedConversationId = selected?.id?.value, folders = retainedData.folders())
    }
    private fun retainedConversation(id: String) = controller.database.conversations().single { it.id.value == id }
    private fun openRetainedConversation(id: String) { val conversation = retainedConversation(id); dismissSidebar(); returnToChat(); select(conversation) }
    private fun toggleRetainedPin(id: String) {
        val conversation = retainedConversation(id)
        controller.database.organize(conversation.id, conversation.folder, !conversation.pinned); updatePane()
    }
    private fun deleteRetainedConversation(id: String) {
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
            action = { guard { deleteRetainedConversation(id) } })
    }
    private fun editRetainedConversation(item: ConversationSummaryUi, folder: Boolean) { guard { editConversation(item.id, folder) } }
    private fun editConversation(id: String, folder: Boolean) {
        val current = retainedConversation(id)
        prompt = InputPrompt(if (folder) "移动到文件夹" else "重命名", if (folder) current.folder else current.title,
            if (folder) "文件夹" else "名称", "保存", { value -> guard {
                val latest = retainedConversation(id)
                if (folder) controller.database.organize(latest.id, value, latest.pinned) else controller.database.rename(latest.id, value)
                if (selected?.id == latest.id) selected = retainedConversation(id)
                updatePane(); refresh()
            } })
    }
    private fun searchConversations() = openSearch("conversation-search")

    private fun openSearch(route: String) {
        requireActivityUsable()
        check(controller.ready && !controller.dataBusy && controller.problem?.startsWith("会话恢复失败") != true)
        require(route == "conversation-search" || route == "message-search")
        val origin = if (retainedPage == "manage") "manage" else "chat"
        updatePane()
        prompt = null
        searchPage = SearchPage(route, origin, selected?.id?.value, controller.active, paneState.selectedFolderId)
        // Transition first: dismissSidebar must not briefly make hidden chat speech visible.
        retainedPage = route
        dismissSidebar()
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
        controller.invalidateVideo(videoUi)
        videoDialog?.dismiss(); videoDialog = null
        dictationHost.invalidate()
        offlineHost.invalidate()
        cancelShizukuEditor()
        cancelBrowserEditor()
        guard {
            val id = selected?.id
            if (id == null) controller.database.draft(value) else controller.database.draft(id, value)
            if (draft != value) draftRevision++
            draft = value; publishChat()
        }
    }
    internal fun select(conversation: Conversation?) {
        requireActivityUsable()
        if (revokeContextManagementEntry()) editorGeneration++
        fenceVideoRoute()
        check(!selectionReady || (pendingTextSend == null && controller.textSendState() == null)) { "请等待本次发送结果" }
        check(controller.ready && !controller.dataBusy && controller.problem?.startsWith("会话恢复失败") != true)
        val shizukuOwned = shizukuEditor != null || shizukuWritesBlocked
        cancelShizukuEditor()
        cancelBrowserEditor()
        val list = controller.database.conversations()
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
        videoPanelVisible = videoHost.state.items.isNotEmpty() || videoHost.state.fenced
        draftRevision = maxOf(draftRevision + 1, imageHost.session.attachments.state.value.draft.revision)
        imagePanelVisible = false
        selected = conversation; selectionReady = true; draft = targetDraft
        val range = selections[conversation?.id]
        selectionStart = (range?.first ?: draft.length).coerceIn(0, draft.length)
        selectionEnd = (range?.second ?: draft.length).coerceIn(0, draft.length)
        shown = if (conversation == null) mutableListOf() else controller.database.messages(conversation.id).toMutableList()
        imagePanelVisible = imageHost.hasAttachments || imageHost.fenced ||
            MessageHistory.expand(shown.map { it.second }).any { row -> row.blocks.any { it is MessageBlock.Image } }
        if (videoPanelVisible) imagePanelVisible = false
        collaborationOwner = null; collaborationVisible = false; publishChat(); jumpToBottom(); refresh()
    }
    private fun sendDraft(text: String, universal: UniversalSend? = null) {
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
                    result is ChatController.TextSendResult.Accepted, draft.isEmpty())) universalEditor = after
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
    private fun supplementEditor() = SupplementEditor(selected?.id?.value, editorGeneration, draftRevision, draft)
    private fun submitPausedText(owner: Message, text: String) {
        if (text != draft || controller.runState(owner)?.phase != ExecutionPhase.PAUSED) return
        val ticket = supplementDraft.begin(MessageId(java.util.UUID.randomUUID().toString()), supplementEditor()) ?: return
        publishChat()
        try {
            controller.admitSupplement(owner, ticket.messageId, text) { result ->
                if (activityUsable() && supplementDraft.pending === ticket) {
                    val clear = supplementDraft.finish(ticket, result, supplementEditor())
                    if (clear) {
                        updateDraft("")
                        selectionStart = 0; selectionEnd = 0; editorGeneration++
                    }
                    when (result) {
                        is SupplementAdmission.Accepted -> toast("追加内容已保存，点击继续后发送")
                        is SupplementAdmission.Rejected -> toast("未接受追加，草稿已保留")
                        is SupplementAdmission.Unconfirmed -> toast("追加结果未确认，草稿已保留；不会自动重试")
                    }
                    refresh()
                }
            }
        } catch (_: Exception) {
            supplementDraft.finish(ticket, SupplementAdmission.Unconfirmed(ticket.messageId), supplementEditor())
            toast("追加结果未确认，草稿已保留；不会自动重试")
            publishChat()
        }
    }
    private fun currentControlOwner(): Message? =
        (if (controlTouchActive) controlTouchOwner else controlOwner)?.takeIf { it.conversationId == selected?.id }
    private fun refresh() {
        if (!activityUsable()) return
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
        chatModelPicker = chatModelPicker?.let { picker -> picker.copy(enabled = !picker.loading &&
            !chatModelSelectionFailed && chatModelSettings != null && chatModelSession != null && controller.active == null &&
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
            if (dataRevision != controller.dataRevision) {
                dataRevision = controller.dataRevision
                cancelShizukuEditor()
                cancelBrowserEditor()
                imageHost.configurationChanged()
                imageHost.enter(selected?.id?.value)
                val latest = selected?.id?.let { id -> controller.database.conversations().firstOrNull { it.id == id } }
                if (selected != null && latest == null) { select(null); return@guard }
                editorGeneration++
                selected = latest
                draft = latest?.draft ?: controller.database.draft()
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
            selected?.let { conversation ->
                if (shown.isEmpty()) shown = controller.database.messages(conversation.id).toMutableList()
                else {
                    // Refresh the loaded range, retaining the early-history window and history-hit owner.
                    val first = shown.first().first
                    val last = shown.last().first
                    val active = controller.active?.takeIf { it.conversationId == conversation.id }
                    val newOwner = active != null && shown.none { it.second.id == active.id }
                    val persisted = readLoadedWindow(conversation.id, if (newOwner) Long.MAX_VALUE else last + 1,
                        shown.size + if (newOwner) 2 else 0).filter { it.first >= first }
                    shown = persisted.map { entry -> if (active != null && entry.second.id == active.id) entry.first to active else entry }.toMutableList()
                }
            }
            prepareMessageActionSnapshot()
            publishChat()
            if (sidebarVisible || retainedPage == "manage") updatePane()
        }
    }
    private fun currentAssistantLabel(): String = try {
        val binding=selected?.id?.let(controller.database::assistantId) ?: if(selected==null) assistantStore.selectedId.value else null
        if(binding==null) "未绑定助手" else assistantStore.read(binding)?.name ?: "助手已删除，请重新绑定"
    } catch (_:Exception) { "助手不可用" }
    private fun savedModelLabel(): String = try {
        (controller.settings.nonSecretConfiguration()["model"] as? String)?.ifBlank { "模型" } ?: "模型"
    } catch (_: Exception) { "模型" }
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
    private fun publishChat() {
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
        questionRows = shown.mapNotNull { (_, message) ->
            val projections = message.blocks.filterIsInstance<MessageBlock.Tool>()
                .mapNotNull { controller.questionProjection(message, it.invocation) }
            val retryCleanup = controller.canRetryQuestionCleanup(message)
            if (projections.isEmpty() && !retryCleanup) null
            else message.id.value to QuestionRowUi(message, projections, retryCleanup)
        }.toMap()
        val state = controlOwner?.let { controller.runState(it) }
        val phase = composerPhaseForRun(state?.pauseRequested)
        val allowed = !sidebarVisible && controller.ready && !controller.dataBusy && controller.problem?.startsWith("会话恢复失败") != true
        chatState = chatState.copy(title = selected?.title ?: "代鱼", conversationKey = selected?.id?.value,
            contentRevision = chatState.contentRevision + 1,
            turns = ChatTurnMapper.map(shown.map { it.second }, timestamp = { message ->
                MessageTimestampFormatter.timestamp(message, enabled = appearance.messageTimestampsEnabled)
            }), canJumpToPreviousUser = shown.any { it.second.role == "user" },
            statusText = controller.problem ?: when { controller.dataBusy -> "处理备份中"; !controller.ready -> "恢复会话中";
                pendingTextSend != null -> "正在准备上下文并核实发送结果"; imageHost.sending -> "正在核实图片发送结果"; else -> null },
            composer = ComposerUi(text = draft, selectionStart = selectionStart, selectionEnd = selectionEnd,
                phase = phase, controlOwnerKey = controlOwner?.id?.value, modelLabel = if (controller.dataBusy) chatState.composer.modelLabel else savedModelLabel(),
                reasoningLabel = reasoningPreference.get(selected?.id?.value).displayName,
                assistantLabel = if (controller.dataBusy) chatState.composer.assistantLabel else currentAssistantLabel(), enabled = allowed && controller.textSendState() == null && pendingTextSend == null && !imageHost.sending && !shizukuWritesBlocked,
                supplementAvailable = state?.phase == ExecutionPhase.PAUSED && supplementDraft.pending == null,
                supplementPending = supplementDraft.pending?.editor?.conversationId == selected?.id?.value &&
                    supplementDraft.pending != null,
                executionPhase = state?.phase,
                hasAssistantOutput = controlOwner?.let { owner ->
                    controller.active?.takeIf { it.id == owner.id && it.runId == owner.runId &&
                        it.conversationId == owner.conversationId }?.let(::assistantOutputExists)
                },
                morphLoadingIndicator = appearance.morphLoadingIndicator,
                morphLoadingBeforeResponseOnly = appearance.morphLoadingBeforeResponseOnly))
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
        val message = shown.firstOrNull { it.second.id.value == id }?.second ?: return
        val text = copyableImageHostText(message) ?: return
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("代鱼消息", text))
    }

    private fun openChatModels() {
        requireActivityUsable()
        dismissChatModels()
        val owner = Any(); chatModelOwner = owner
        reloadChatModels(owner)
    }
    private fun dismissChatModels() {
        chatModelCancelled.set(true)
        chatModelPicker = null; chatModelSettings = null; chatModelSession = null; chatModelOwner = null
        chatModelSelectionFailed = false
    }
    private fun reloadChatModels(owner: Any) {
        if (!activityUsable() || retainedPage != "chat" || chatModelOwner !== owner) return
        chatModelCancelled.set(true)
        val cancelled = AtomicBoolean(false); chatModelCancelled = cancelled
        chatModelSession = null; chatModelSettings = null; chatModelSelectionFailed = false
        chatModelPicker = ChatModelPickerState("", emptyList(), loading = true, enabled = false)
        lifecycleScope.launch {
            val (catalog, settings) = withContext(Dispatchers.IO) {
                runCatching { controller.settings.localModelCatalogSnapshot() } to
                    runCatching { controller.settings.load() }
            }
            if (!activityUsable() || retainedPage != "chat" || chatModelOwner !== owner ||
                cancelled.get() || chatModelCancelled !== cancelled) return@launch
            val saved = settings.getOrNull()
            chatModelSettings = saved
            val snapshot = catalog.getOrNull()
            val session = snapshot?.let { LocalCatalogChatModelPickerSession(it, saved?.model.orEmpty()) }
            chatModelSession = session
            val message = when {
                catalog.isFailure -> "本地模型目录读取失败，请刷新或到模型设置核对"
                snapshot == null -> "尚无本地模型目录，请到模型设置保存或导入"
                saved == null -> "模型凭据或配置不可用；目录仅供查看，请到模型设置修复"
                else -> session?.state?.message
            }
            chatModelPicker = (session?.state ?: ChatModelPickerState(saved?.model.orEmpty(), emptyList()))
                .copy(loading = false, enabled = saved != null && session != null &&
                    controller.active == null && !controller.dataBusy && controller.ready, message = message)
        }
    }
    private fun selectChatModel(
        ticket: app.daiyu.android.model.catalog.CatalogModelSelection<app.daiyu.android.model.catalog.CatalogModelParameters>,
        owner: Any?,
        session: LocalCatalogChatModelPickerSession,
    ) {
        if (owner == null || chatModelOwner !== owner || chatModelSession !== session) return
        val picker = chatModelPicker ?: return
        val saved = chatModelSettings ?: return
        try {
            requireActivityUsable()
            check(!chatModelSelectionFailed && picker.enabled && !picker.loading && retainedPage == "chat" && controller.ready &&
                controller.active == null && !controller.dataBusy)
            controller.selectConfiguredCatalogModel(saved, ticket) { success ->
                if (activityUsable()) {
                    if (success) imageHost.configurationChanged()
                    if (chatModelOwner === owner && chatModelSession === session && retainedPage == "chat") {
                        if (success) { dismissChatModels(); refresh() }
                        else {
                            chatModelSelectionFailed = true
                            chatModelPicker = chatModelPicker?.copy(enabled = false,
                                message = "切换未保存，请刷新本地目录后重新选择")
                        }
                    }
                }
            }
        } catch (_: Exception) {
            if (chatModelOwner === owner && chatModelSession === session) {
                chatModelSelectionFailed = true
                chatModelPicker = picker.copy(enabled = false, message = "切换未保存，请刷新本地目录后重新选择")
            }
        }
    }

    private fun gatewayCurrent(owner: ModelSettingsSaveOwner) = activityUsable() &&
        retainedPage == "session-gateway" && gatewayOwner === owner

    private fun openSessionGateway() {
        requireActivityUsable()
        check(controller.ready && controller.active == null && !controller.dataBusy)
        gatewayOwner?.revoke()
        val owner = ModelSettingsSaveOwner()
        gatewayOwner = owner; gatewayEditing = null; gatewayDraft = null
        gatewayReplacementAuthorized = false; gatewayError = null
        retainedPage = "session-gateway"
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { controller.settings.loadForEditing() } }
            if (!gatewayCurrent(owner)) return@launch
            loaded.fold(onSuccess = { editing ->
                gatewayEditing = editing
                gatewayDraft = editing.settings.requestOptions.sessionGateway
                if (editing.credentialUnavailable && !editing.requestOptionsUnavailable ||
                    editing.settings.protocol == app.daiyu.android.model.ProviderProtocol.UNKNOWN) {
                    gatewayError = "凭据或协议不可用；请先到模型设置修复，未更改网关策略"
                }
            }, onFailure = { gatewayError = "设置无法读取；未更改配置，请返回后重试" })
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
            require(editing.settings.apiKey.isNotBlank())
            require(!editing.requestOptionsUnavailable || gatewayReplacementAuthorized)
            value.validate() // Unknown retention/keySource may be saved, but never executed.
            val next = editing.settings.copy(requestOptions = editing.settings.requestOptions.copy(sessionGateway = value))
            controller.saveModelSettings(next, false, gatewayReplacementAuthorized, editing, owner) { success ->
                if (success && activityUsable()) imageHost.configurationChanged()
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
        requireActivityUsable(); check(!controller.dataBusy)
        if (controller.active != null) { toast("当前运行使用冻结配置，请结束后修改"); return }
        editingCatalogIdentity = null
        val owner = Any(); modelEditingOwner = owner
        val editing = controller.settings.loadForEditing(); editingSettings = editing
        val saved = editing.settings
        modelDraft = ModelSettingsDraftUi(protocol = saved.protocol, endpoint = saved.endpoint, responsesPath = saved.responsesPath,
            apiKey = saved.apiKey, model = saved.model, batteryEnabled = saved.batteryEnabled, deviceStatusEnabled = saved.deviceStatusEnabled,
            customBody = saved.requestOptions.customBody, speedMode = saved.requestOptions.speedMode, codex = saved.requestOptions.codex)
        modelError = null; retainedPage = "models"
        if (saved.endpoint.isNotBlank() && saved.model.isNotBlank()) lifecycleScope.launch {
            val catalogResult = withContext(Dispatchers.IO) {
                runCatching { controller.settings.modelCatalogSnapshot() }
            }
            if (activityUsable() && retainedPage == "models" && modelEditingOwner === owner && editingSettings === editing) {
                catalogResult.fold(
                    onSuccess = { editingCatalogIdentity = it.providerIdentity },
                    onFailure = { modelError = "模型目录不可用；未启动导入，请检查配置或目录存储" },
                )
            }
        }
    }
    private fun changeModelDraft(value: ModelSettingsDraftUi) {
        if (activityUsable() && retainedPage == "models" && !controller.dataBusy) modelDraft = value
    }
    private fun ModelSettingsDraftUi.toSettings(base: ModelSettings) = base.copy(endpoint = endpoint.trim(), model = model.trim(),
        apiKey = apiKey, protocol = protocol, responsesPath = responsesPath.trim(), batteryEnabled = batteryEnabled,
        deviceStatusEnabled = deviceStatusEnabled, requestOptions = base.requestOptions.copy(
            customBody = customBody, speedMode = speedMode, codex = codex))
    private fun saveModelSettings(value: ModelSettingsDraftUi) {
        try {
            requireActivityUsable(); check(retainedPage == "models" && modelDraft === value)
            check(controller.active == null && !controller.dataBusy)
            val editing = checkNotNull(editingSettings)
            require(value.savedProtocol == editing.settings.protocol)
            require(value.protocol == editing.settings.protocol || value.protocolChangeAuthorized)
            require(!editing.requestOptionsUnavailable || value.requestOptionsReplacementAuthorized)
            val owner = checkNotNull(modelEditingOwner)
            controller.saveModelSettings(value.toSettings(editing.settings), value.protocolChangeAuthorized,
                value.requestOptionsReplacementAuthorized) { success ->
                if (activityUsable()) {
                    if (success) imageHost.configurationChanged()
                    if (retainedPage == "models" && modelEditingOwner === owner && editingSettings === editing) {
                        if (success) returnToChat() else modelError = "设置未保存"
                    }
                }
            }
        } catch (_: Exception) { modelError = "设置未保存" }
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
            uri.toString().length <= 8192 && open.type == "application/json") { "请选择content类型的JSON备份文件（URI不超过8192字符）" }
        require(open.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "备份文件未授予读取权限；请重新选择文件" }
        return uri
    }
    private fun showOpenedBackup(uri: Uri) {
        if (lastOpenedBackupUri == uri.toString() && prompt?.backup == true) return
        lastOpenedBackupUri = uri.toString(); retainedPage = "backup"
        prompt = ChoicePrompt("打开备份", listOf("完整中立备份（推荐）", "纯文本兼容备份"),
            { index -> backupGuard { confirmBackupImport(uri, index == 0) } }, backup = true)
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
                val waiting = ConfirmPrompt("等待会话恢复", "恢复完成后再选择备份类型并确认导入；尚未读取文件。", "取消打开",
                    action = { cancelDeferredOpenedBackup() }, backup = true)
                deferredOpenedBackup = DeferredOpenedBackup(uri, waiting)
                prompt = waiting
            } catch (failure: Exception) { toast(failure.message ?: "文件打开失败；请重新选择") }
            return
        }
        backupGuard { showOpenedBackup(openedBackupUri(open)) }
    }
    private fun backupMenu() { checkBackupAvailable(); lastOpenedBackupUri = null; retainedPage = "backup" }
    private fun openConversationExport(id: String) {
        val target = retainedConversation(id)
        dismissSidebar(); exportConversationId = id; exportConversationTitle = target.title
        retainedPage = "conversation-export"
    }
    private fun openChildTaskRecords(id: String) {
        retainedConversation(id)
        dismissSidebar(); taskRecordsConversationId = id; retainedPage = "child-tasks"
    }
    @Suppress("DEPRECATION") private fun pickConversationExport(id: String) {
        checkBackupAvailable()
        check(pendingBackupRequest == null) { "文件选择尚未返回；请先完成或取消选择" }
        retainedConversation(id)
        conversationExport.begin(id); pendingBackupRequest = 105; exportPickerPending = true
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "daiyu-conversation.json")
            }, 105)
        } catch (failure: Exception) {
            pendingBackupRequest = null; exportPickerPending = false; conversationExport.take(); throw failure
        }
    }
    @Suppress("DEPRECATION") private fun pickBackup(exporting: Boolean, complete: Boolean) {
        checkBackupAvailable(); check(pendingBackupRequest == null) { "文件选择尚未返回；请先完成或取消选择" }
        val code = if (complete) { if (exporting) 103 else 104 } else { if (exporting) 101 else 102 }
        pendingBackupRequest = code
        try {
            startActivityForResult(Intent(if (exporting) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                if (exporting) putExtra(Intent.EXTRA_TITLE, if (complete) "daiyu-complete.json" else "daiyu-text.json")
            }, code)
        } catch (failure: Exception) { pendingBackupRequest = null; throw failure }
    }
    @Suppress("DEPRECATION") override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode !in setOf(101, 102, 103, 104, 105, 106) || pendingBackupRequest != requestCode) return
        pendingBackupRequest = null
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
            val originalId = conversationExport.take()
            if (resultCode != RESULT_OK) return
            val uri = data?.data
            backupGuard {
                require(originalId != null && uri?.scheme == "content") { "导出目标已失效或文件未返回；请重新选择" }
                controller.exportConversationBackup(checkNotNull(uri), ConversationId(originalId))
            }
            return
        }
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: run { toast("文件选择未返回URI；原数据保留，请重新选择"); return }
        val complete = requestCode == 103 || requestCode == 104
        backupGuard {
            require(uri.scheme == "content") { "文件选择未返回content URI；原数据保留，请重新选择" }
            if (requestCode == 101 || requestCode == 103) controller.exportUserBackup(uri, complete)
            else confirmBackupImport(uri, complete)
        }
    }
    private fun confirmBackupImport(uri: Uri, complete: Boolean) {
        checkBackupAvailable()
        prompt = ConfirmPrompt(if (complete) "确认导入完整中立备份" else "确认导入文本备份", "", "导入",
            action = { backupGuard { controller.importUserBackup(uri, complete) } }, backup = true)
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
            name == "protocol" -> when (values[name]) { null, "responses" -> "Responses"; "messages" -> "Messages"; else -> "无效" }
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
            prompt = ConfirmPrompt("恢复模型配置", "", "确认恢复", action = {
                backupGuard {
                    check(configurationRestorePreview === preview)
                    try { controller.restoreImportedConfiguration(preview, authorized = true) { success ->
                        if (success && activityUsable()) imageHost.configurationChanged()
                    } }
                    finally { cancelRestorePreview() }
                }
            }, backup = true)
        }
    }
    private fun loadAppearance(): AppearanceSettings = appearanceStore.load()
    private fun saveAppearance(value: AppearanceSettings) { guard {
        appearance = appearanceStore.save(value)
        publishChat()
    } }

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

private class AndroidVoiceHostPlayer(private val player: AndroidSpeechPlayer) : VoiceHostPlayer {
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
    turns.filterIsInstance<ChatTurnUi.Assistant>().firstOrNull { it.id == id }?.finalText

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
