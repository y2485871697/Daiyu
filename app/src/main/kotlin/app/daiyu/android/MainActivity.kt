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
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import app.daiyu.android.assistants.AssistantSkill
import app.daiyu.android.assistants.FileAssistantAssets
import app.daiyu.android.assistants.SharedPreferencesAssistantStore
import app.daiyu.android.context.ContextService
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.delegation.ConversationSubAgentPreferences
import app.daiyu.android.delegation.ReasoningEffort
import app.daiyu.android.domain.*
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.storage.EditingSettings
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.ui.FeatureRoute
import app.daiyu.android.ui.RetainedConversationData
import app.daiyu.android.ui.SubAgentSettingsScreen
import app.daiyu.android.ui.app.AgentAppTheme
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
import app.daiyu.android.ui.screens.stats.UsageStatsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private val controller get() = (application as ChatApplication).controller
    private val retainedData by lazy { RetainedConversationData(controller.database, ContextService(this, controller.database)) }
    private val assistantStore by lazy { SharedPreferencesAssistantStore(applicationContext) }
    private val assistantAssets by lazy { FileAssistantAssets(applicationContext) }
    private val reasoningPreference by lazy {
        ConversationReasoningPreference(getSharedPreferences("conversation-ui", MODE_PRIVATE))
    }
    private val subAgentRepository by lazy {
        ConversationSubAgentPreferences(getSharedPreferences("subagent_configuration", MODE_PRIVATE))
    }
    private var sidebarVisible by mutableStateOf(false)
    private var retainedPage by mutableStateOf("chat")
    private var paneState by mutableStateOf(ConversationPaneUiState(emptyList(), null, ""))
    private var chatState by mutableStateOf(ChatScreenState("代鱼"))
    private var selected: Conversation? = null
    private var draft by mutableStateOf("")
    private var selectionStart = 0
    private var selectionEnd = 0
    private var selectionReady = false
    private var editorGeneration = 0L
    private val selections get() = controller.selections
    private var shown = mutableListOf<Pair<Long, Message>>()
    private data class QuestionRowUi(val message: Message,
        val projections: List<ChatController.QuestionProjection>, val retryCleanup: Boolean)
    private var questionRows by mutableStateOf<Map<String, QuestionRowUi>>(emptyMap())
    private var dataRevision = -1L
    private var scrollSerial = 0L
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
    private var pendingBackupRequest: Int? = null
    private var configurationRestorePreview by mutableStateOf<ChatController.ConfigurationRestorePreview?>(null)
    private var restorePreviewText by mutableStateOf("")
    private var backupBusy by mutableStateOf(false)
    private var editingSettings: EditingSettings? = null
    private var modelDraft by mutableStateOf<ModelSettingsDraftUi?>(null)
    private var modelError by mutableStateOf<String?>(null)
    private var catalog by mutableStateOf(emptyList<ModelCatalogEntryUi>())
    private var catalogForDraft by mutableStateOf<ModelSettingsDraftUi?>(null)
    private var catalogBusy by mutableStateOf(false)
    private var catalogStatus by mutableStateOf<String?>(null)
    private var catalogGeneration = 0L
    private var catalogCancelled = AtomicBoolean(false)
    private var assistantPicker by mutableStateOf(false)
    private var assistantEditingId by mutableStateOf<String?>(null)
    private var collaborationOwner by mutableStateOf<String?>(null)
    private var collaborationVisible by mutableStateOf(false)
    private var thinkingPicker by mutableStateOf(false)
    private var appearance by mutableStateOf(AppearanceSettings())

    private fun activityUsable() = activityCallbacksAllowed && !isFinishing && !isDestroyed
    private fun requireActivityUsable() { check(activityUsable()) { "页面已关闭；未执行操作" } }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        appearance = loadAppearance()
        activityCallbacksAllowed = true
        pendingBackupRequest = state?.getInt("pending-backup-request", 0)?.takeIf { it != 0 }
        setContent {
            AgentAppTheme(appearance, applyInterfaceScale = true) {
                val focus = LocalFocusManager.current
                val chatPageState = rememberSaveableStateHolder()
                val assistantProfiles by assistantStore.assistants.collectAsState()
                val activeAssistant by assistantStore.selectedId.collectAsState()
                LaunchedEffect(assistantProfiles, activeAssistant) { if (activityUsable()) publishChat() }
                LaunchedEffect(sidebarVisible, retainedPage) {
                    if (sidebarVisible || retainedPage != "chat") focus.clearFocus()
                }
                BackHandler(enabled = retainedPage != "chat") { returnToChat() }
                when (retainedPage) {
                    "manage" -> ManageChatsScreen(
                        conversations = paneState.historyConversations,
                        onBack = { returnToChat() },
                        onOpenConversation = { id -> guard { openRetainedConversation(id) } },
                        onTogglePin = { id -> guard { toggleRetainedPin(id) } },
                        onDeleteConversation = { item -> guard { deleteRetainedConversation(item.id) } },
                        onDeleteAll = { guard {
                            check(controller.active == null) { "请先停止当前运行" }
                            retainedData.deleteAll(); select(null); updatePane()
                        } },
                        onSearchHistory = { query -> withContext(Dispatchers.IO) { retainedData.search(query) } },
                        onOpenHistoryHit = { hit -> guard { openHistoryHit(hit) } },
                    )
                    "usage" -> UsageStatsScreen(onBack = { returnToChat() }, load = { retainedData.stats() })
                    "skills" -> SkillManagementScreen(
                        managementFactory = { controller.skillManagement() },
                        onBack = { returnToChat() },
                    )
                    "settings" -> DaiyuSettingsScreen(
                        onBack = { returnToChat() }, onRoute = { route -> openSettingsFromPage(route) },
                        onOpenEntry = { entry ->
                            if (activityUsable() && retainedPage == "settings" && entry.id in setOf("P24", "P29", "P30", "P31", "P35", "P36", "P40")) retainedPage = "appearance"
                        },
                        onOpenSubAgents = { retainedPage = "subagents" },
                        onOpenAssistants = { assistantPicker = true },
                    )
                    "models" -> modelDraft?.let { value ->
                        ModelSettingsScreen(
                            draft = value, onDraftChange = { changeModelDraft(it) },
                            onSave = { saveModelSettings(it) }, onFetchCatalog = { fetchCatalog(it) },
                            onBack = { returnToChat() }, errorText = modelError,
                            credentialUnavailable = editingSettings?.credentialUnavailable == true,
                            requestOptionsUnavailable = editingSettings?.requestOptionsUnavailable == true,
                            catalog = catalog, isFetchingCatalog = catalogBusy,
                            catalogStatusText = catalogStatus, catalogForDraft = catalogForDraft,
                        )
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
                        state = AppearanceSettingsUi(SettingsThemeMode.valueOf(appearance.themeMode.name),
                            appearance.interfaceScale, appearance.messageTimestampsEnabled, appearance.monetEnabled),
                        onThemeModeChange = { saveAppearance(appearance.copy(themeMode = AppearanceThemeMode.valueOf(it.name))) },
                        onInterfaceScaleChange = { saveAppearance(appearance.copy(interfaceScale = it)) },
                        onWallpaperColorsChange = { saveAppearance(appearance.copy(monetEnabled = it)) },
                        onMessageTimestampsChange = { saveAppearance(appearance.copy(messageTimestampsEnabled = it)) },
                        onOpenAppIcon = {}, onOpenIconAppearance = {}, onOpenPet = {}, onOpenVibration = {},
                        onOpenRefreshRate = {}, onBack = { returnToChat() },
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
                            onSaved = { refresh() })
                    }
                    else -> ConversationSidePaneScaffold(
                        state = paneState, visible = sidebarVisible, backHandlerEnabled = true,
                        onOpen = { openSidebar() }, onDismiss = { dismissSidebar() },
                        onSearchChange = { paneState = paneState.copy(searchQuery = it) },
                        onConversationSelected = { id -> guard { openRetainedConversation(id) } },
                        onConversationRename = { item -> editRetainedConversation(item, false) },
                        onConversationDelete = { item -> confirmDelete(item.id) },
                        onMoveConversationToFolder = { item -> editRetainedConversation(item, true) },
                        onConversationTogglePin = { item -> guard { toggleRetainedPin(item.id) } },
                        onConversationExport = {},
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
                        onSelectAssistant = {}, onEditAssistant = { assistantPicker = true },
                        onOpenAssistants = { dismissSidebar(); assistantPicker = true },
                        onOpenSettings = { dismissSidebar(); retainedPage = "settings" },
                        onOpenModelProviders = { dismissSidebar(); guard { modelSettings() } },
                        onOpenUsageStats = { dismissSidebar(); retainedPage = "usage" },
                        onOpenSkills = { dismissSidebar(); guard { openSkillManagement() } },
                        onOpenPermissions = { dismissSidebar(); retainedPage = "settings" },
                    ) {
                        chatPageState.SaveableStateProvider(chatState.conversationKey ?: "new-chat") {
                            DaiyuChatScreen(chatState, chatActions(), turnExtras = { id -> QuestionExtras(id) })
                        }
                    }
                }
                PromptSurface()
                AssistantPickerDialog(show = assistantPicker, store = assistantStore, assets = assistantAssets,
                    onSelect = { id -> guard { assistantStore.select(id); refresh() } },
                    onEdit = { id -> assistantPicker = false; assistantEditingId = id; retainedPage = "assistant-edit" },
                    onDismiss = { assistantPicker = false })
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
        controller.observe(listener)
        refresh()
        // Recreating an Activity never replays an external VIEW/import confirmation.
        if (state == null) offerOpenedBackup(intent)
    }

    private fun chatActions(): ChatScreenActions {
        val renderedOwner = controlOwner
        val renderedConversation = chatState.conversationKey
        val renderedEditorGeneration = editorGeneration
        fun editorMatches() = activityUsable() && selectionReady &&
            selected?.id?.value == renderedConversation && editorGeneration == renderedEditorGeneration
        fun gestureOwner() = currentControlOwner()?.takeIf {
            renderedOwner != null && it.id == renderedOwner.id && it.runId == renderedOwner.runId && it.conversationId == renderedOwner.conversationId
        }
        return ChatScreenActions(
        onOpenSidebar = { openSidebar() }, onTextChange = { if (editorMatches()) updateDraft(it) },
        onSelectionChange = { start, end ->
            if (editorMatches()) { selectionStart = start; selectionEnd = end; publishChat() }
        },
        onSend = { text -> guard { sendDraft(text) } },
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
        onModelClick = { guard { modelSettings() } }, onReasoningClick = { thinkingPicker = true },
        onAssistantClick = { assistantPicker = true }, onCopy = { id -> copyMessage(id) },
        onMenuAction = { action -> guard { when (action) {
            ChatMenuAction.NEW_CHAT -> select(null)
            ChatMenuAction.RENAME -> selected?.let { conversation ->
                editConversation(conversation.id.value, false)
            }
            ChatMenuAction.SUB_AGENT -> { collaborationOwner = selected?.id?.value; collaborationVisible = true }
            ChatMenuAction.LOAD_EARLIER -> loadEarlier()
            ChatMenuAction.LOCATE_USER -> chooseUserMessage()
            ChatMenuAction.BOTTOM -> jumpToBottom()
            ChatMenuAction.SEARCH -> searchConversations()
            ChatMenuAction.SETTINGS -> retainedPage = "settings"
            ChatMenuAction.CONVERSATION_ID -> selected?.let {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("会话 ID", it.id.value))
            }
            else -> Unit
        } } },
        )
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
                onConfirm = { confirmPrompt(current) }, onDismiss = { if (valid()) prompt = null })
        }
    }

    private fun confirmPrompt(expected: ConfirmPrompt) {
        if (activityUsable() && prompt === expected) { prompt = null; expected.action() }
    }
    private fun openSettingsFromPage(route: FeatureRoute) {
        if (activityUsable() && retainedPage == "settings") guard { openSettingsRoute(route) }
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
        state.putInt("pending-backup-request", pendingBackupRequest ?: 0)
        super.onSaveInstanceState(state)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); offerOpenedBackup(intent) }
    override fun onResume() {
        super.onResume(); refreshRateWindowResumed = true; applyPreferredRefreshRate()
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
        refreshRateWindowResumed = false; window.decorView.removeCallbacks(refreshRateRequest)
        val previous = previousPreferredRefreshRate
        if (previous != null && window.attributes.preferredRefreshRate == requestedPreferredRefreshRate) {
            val attributes = window.attributes; attributes.preferredRefreshRate = previous; window.attributes = attributes
        }
        previousPreferredRefreshRate = null; requestedPreferredRefreshRate = null
        if (selectionReady) selections[selected?.id] = selectionStart to selectionEnd
        super.onPause()
    }
    override fun onDestroy() {
        activityCallbacksAllowed = false; refreshRateWindowResumed = false
        window.decorView.removeCallbacks(refreshRateRequest); invalidateCatalog()
        cancelRestorePreview(); prompt = null
        controller.unobserve(listener); super.onDestroy()
    }
    private fun returnToChat() {
        invalidateCatalog(); modelDraft = null; editingSettings = null; modelError = null
        cancelRestorePreview(); prompt = null; retainedPage = "chat"
        if (activityUsable()) refresh()
    }
    private fun openSkillManagement() {
        retainedPage = "skills"
    }
    private fun openSettingsRoute(route: FeatureRoute) {
        when (route) {
            FeatureRoute.MODEL_SETTINGS -> modelSettings()
            FeatureRoute.BACKUP -> backupMenu()
            FeatureRoute.SIDEBAR -> { returnToChat(); openSidebar() }
            FeatureRoute.MANAGE_CHATS -> { updatePane(); retainedPage = "manage" }
            FeatureRoute.USAGE_STATS -> retainedPage = "usage"
            FeatureRoute.CONVERSATIONS -> chooseConversation()
            FeatureRoute.CONVERSATION_SEARCH -> searchConversations()
            FeatureRoute.FOLDERS -> browseFolders()
            FeatureRoute.SKILLS -> openSkillManagement()
            // Settings entries are navigation, never implicit execution/send/stop operations.
            FeatureRoute.CHAT, FeatureRoute.USER_MESSAGE -> Unit
        }
    }
    private fun openSidebar() { guard { updatePane(); sidebarVisible = true; publishChat() } }
    private fun dismissSidebar() { sidebarVisible = false; if (activityUsable()) refresh() }
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
        val conversation = retainedConversation(id)
        check(controller.active?.conversationId != conversation.id) { "请先停止此会话的运行" }
        controller.database.delete(conversation.id)
        if (selected?.id == conversation.id) select(null)
        updatePane()
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
    private fun searchConversations() {
        prompt = InputPrompt("搜索会话", "", "标题、草稿或消息关键词", "搜索", { query -> guard { chooseConversation(query) } })
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
        openRetainedConversation(hit.conversationId)
        val sequence = controller.database.readableDatabase.rawQuery("SELECT seq FROM messages WHERE conversation_id=? AND id=?",
            arrayOf(conversation.id.value, hit.ownerMessageId)).use { cursor -> check(cursor.moveToFirst()) { "命中消息已不存在" }; cursor.getLong(0) }
        shown = controller.database.messages(conversation.id, sequence + 1, 50).toMutableList()
        publishChat(); requestScroll(hit.ownerMessageId, false)
    }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    private fun guard(action: () -> Unit) {
        try { requireActivityUsable(); check(!controller.dataBusy); action() }
        catch (_: Exception) { toast("操作或保存失败；原数据保留") }
    }
    private fun updateDraft(value: String) {
        if (!activityUsable() || sidebarVisible || controller.dataBusy || !controller.ready) return
        guard {
            val id = selected?.id
            if (id == null) controller.database.draft(value) else controller.database.draft(id, value)
            draft = value; publishChat()
        }
    }
    internal fun select(conversation: Conversation?) {
        requireActivityUsable()
        check(controller.ready && !controller.dataBusy && controller.problem?.startsWith("会话恢复失败") != true)
        val list = controller.database.conversations()
        var targetDraft = if (conversation == null) controller.database.draft() else list.first { it.id == conversation.id }.draft
        if (selectionReady) {
            val id = selected?.id
            if (id == null) controller.database.draft(draft) else if (list.any { it.id == id }) controller.database.draft(id, draft)
            selections[id] = selectionStart to selectionEnd
        }
        if (selectionReady && selected?.id == conversation?.id) targetDraft = draft
        // Commit the owner before allowing any text callbacks to write to it.
        check(controller.preferences.edit().apply {
            if (conversation == null) remove("selected") else putString("selected", conversation.id.value)
        }.commit())
        editorGeneration++
        selected = conversation; selectionReady = true; draft = targetDraft
        val range = selections[conversation?.id]
        selectionStart = (range?.first ?: draft.length).coerceIn(0, draft.length)
        selectionEnd = (range?.second ?: draft.length).coerceIn(0, draft.length)
        shown = if (conversation == null) mutableListOf() else controller.database.messages(conversation.id).toMutableList()
        collaborationOwner = null; collaborationVisible = false; publishChat(); jumpToBottom(); refresh()
    }
    private fun sendDraft(text: String) {
        val saved = controller.send(selected, text) ?: return
        editorGeneration++
        selected = saved; selectionReady = true
        val savedSelection = controller.preferences.edit().putString("selected", saved.id.value).commit()
        draft = ""; selectionStart = 0; selectionEnd = 0
        shown = controller.database.messages(saved.id).toMutableList()
        publishChat(); jumpToBottom(); refresh()
        if (!savedSelection) toast("消息已保存；最后选中会话未保存")
    }
    private fun currentControlOwner(): Message? =
        (if (controlTouchActive) controlTouchOwner else controlOwner)?.takeIf { it.conversationId == selected?.id }
    private fun refresh() {
        if (!activityUsable()) return
        backupBusy = controller.dataBusy
        controlOwner = controller.active?.takeIf { it.conversationId == selected?.id && it.status == RunStatus.RUNNING }
        if (controller.dataBusy) {
            if (prompt?.backup == true) prompt = null
            cancelRestorePreview(); publishChat(); return
        }
        if (!controller.ready || controller.problem?.startsWith("会话恢复失败") == true) { publishChat(); return }
        guard {
            if (!selectionReady) {
                val id = controller.preferences.getString("selected", null)
                select(controller.database.conversations().firstOrNull { it.id.value == id }); return@guard
            }
            if (dataRevision != controller.dataRevision) {
                dataRevision = controller.dataRevision
                val latest = selected?.id?.let { id -> controller.database.conversations().firstOrNull { it.id == id } }
                if (selected != null && latest == null) { select(null); return@guard }
                editorGeneration++
                selected = latest
                draft = latest?.draft ?: controller.database.draft()
                selectionStart = selectionStart.coerceIn(0, draft.length); selectionEnd = selectionEnd.coerceIn(0, draft.length)
            }
            controller.consumeDataNotice()?.let { toast(it) }
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
            publishChat()
            if (sidebarVisible || retainedPage == "manage") updatePane()
        }
    }
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
            turns = ChatTurnMapper.map(shown.map { it.second }), canJumpToPreviousUser = shown.any { it.second.role == "user" },
            balanceText = controller.problem ?: when { controller.dataBusy -> "处理备份中"; !controller.ready -> "恢复会话中"; else -> null },
            composer = ComposerUi(text = draft, selectionStart = selectionStart, selectionEnd = selectionEnd,
                phase = phase, controlOwnerKey = controlOwner?.id?.value, modelLabel = savedModelLabel(),
                reasoningLabel = reasoningPreference.get(selected?.id?.value).displayName,
                assistantLabel = assistantStore.read(assistantStore.selectedId.value)?.name ?: "助手", enabled = allowed))
    }
    private fun requestScroll(id: String? = null, follow: Boolean = true) {
        chatState = chatState.copy(scrollRequest = ChatScrollRequest(++scrollSerial, id, follow))
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
        shown.addAll(0, earlier); publishChat()
    }
    private fun copyMessage(id: String) {
        val message = shown.firstOrNull { it.second.id.value == id }?.second ?: return
        val text = message.blocks.joinToString("\n") { block -> when (block) {
            is MessageBlock.Text -> block.text
            is MessageBlock.Tool -> "${block.invocation.name}: ${block.result ?: "未完成"}"
        } }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("代鱼消息", text))
    }

    private fun modelSettings() {
        requireActivityUsable(); check(!controller.dataBusy)
        if (controller.active != null) { toast("当前运行使用冻结配置，请结束后修改"); return }
        invalidateCatalog()
        val editing = controller.settings.loadForEditing(); editingSettings = editing
        val saved = editing.settings
        modelDraft = ModelSettingsDraftUi(protocol = saved.protocol, endpoint = saved.endpoint, responsesPath = saved.responsesPath,
            apiKey = saved.apiKey, model = saved.model, batteryEnabled = saved.batteryEnabled, deviceStatusEnabled = saved.deviceStatusEnabled,
            customBody = saved.requestOptions.customBody, speedMode = saved.requestOptions.speedMode, codex = saved.requestOptions.codex)
        modelError = null; retainedPage = "models"
    }
    private fun invalidateCatalog() {
        catalogCancelled.set(true); catalogGeneration++; catalogBusy = false
        catalog = emptyList(); catalogForDraft = null; catalogStatus = null
    }
    private fun changeModelDraft(value: ModelSettingsDraftUi) { invalidateCatalog(); modelDraft = value }
    private fun ModelSettingsDraftUi.toSettings(base: ModelSettings) = base.copy(endpoint = endpoint.trim(), model = model.trim(),
        apiKey = apiKey, protocol = protocol, responsesPath = responsesPath.trim(), batteryEnabled = batteryEnabled,
        deviceStatusEnabled = deviceStatusEnabled, requestOptions = ProviderRequestOptions(customBody, speedMode, codex))
    private fun saveModelSettings(value: ModelSettingsDraftUi) {
        try {
            requireActivityUsable(); check(retainedPage == "models" && modelDraft === value)
            check(controller.active == null && !controller.dataBusy)
            invalidateCatalog()
            val editing = checkNotNull(editingSettings)
            require(value.savedProtocol == editing.settings.protocol)
            require(value.protocol == editing.settings.protocol || value.protocolChangeAuthorized)
            require(!editing.requestOptionsUnavailable || value.requestOptionsReplacementAuthorized)
            controller.settings.save(value.toSettings(editing.settings), protocolChangeAuthorized = value.protocolChangeAuthorized,
                requestOptionsReplacementAuthorized = value.requestOptionsReplacementAuthorized)
            returnToChat(); toast("模型设置已保存")
        } catch (_: Exception) { modelError = "设置未保存" }
    }
    private fun fetchCatalog(value: ModelSettingsDraftUi) {
        try {
            requireActivityUsable(); check(retainedPage == "models" && modelDraft == value && !controller.dataBusy)
            val editing = checkNotNull(editingSettings)
            invalidateCatalog(); catalogCancelled = AtomicBoolean(false)
            val cancelled = catalogCancelled; val generation = ++catalogGeneration
            val draft = value.toSettings(editing.settings)
            catalogBusy = true
            val accepted = controller.fetchModelCatalog(draft, { cancelled.get() }) { models ->
                if (activityUsable() && retainedPage == "models" && !cancelled.get() && generation == catalogGeneration && modelDraft == value) {
                    catalogBusy = false; catalogForDraft = value
                    catalog = models.orEmpty().map { ModelCatalogEntryUi(it.id, it.displayName) }
                    catalogStatus = when { models == null -> "获取失败"; models.isEmpty() -> "模型目录为空"; else -> null }
                }
            }
            if (!accepted) { catalogBusy = false; catalogForDraft = value; catalogStatus = "当前不可查询" }
        } catch (_: Exception) { catalogBusy = false; catalogForDraft = value; catalogStatus = "获取失败" }
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
    private fun offerOpenedBackup(open: Intent?) {
        if (open?.action != Intent.ACTION_VIEW) return
        backupGuard {
            val uri = open.data
            require(uri != null && uri.scheme == "content" && open.type == "application/json") { "请选择content类型的JSON备份文件" }
            require(open.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "备份文件未授予读取权限；请重新选择文件" }
            if (lastOpenedBackupUri == uri.toString() && prompt?.backup == true) return@backupGuard
            lastOpenedBackupUri = uri.toString(); retainedPage = "backup"
            prompt = ChoicePrompt("打开备份", listOf("完整中立备份（推荐）", "纯文本兼容备份"),
                { index -> backupGuard { confirmBackupImport(uri, index == 0) } }, backup = true)
        }
    }
    private fun backupMenu() { checkBackupAvailable(); lastOpenedBackupUri = null; retainedPage = "backup" }
    @Suppress("DEPRECATION") private fun pickBackup(exporting: Boolean, complete: Boolean) {
        checkBackupAvailable(); check(pendingBackupRequest == null) { "文件选择尚未返回；请先完成或取消选择" }
        val code = if (complete) { if (exporting) 103 else 104 } else { if (exporting) 101 else 102 }
        pendingBackupRequest = code
        try {
            startActivityForResult(Intent(if (exporting) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                if (exporting) putExtra(Intent.EXTRA_TITLE, if (complete) "daiyu-complete-v1.json" else "daiyu-text-v1.json")
            }, code)
        } catch (failure: Exception) { pendingBackupRequest = null; throw failure }
    }
    @Suppress("DEPRECATION") override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode !in setOf(101, 102, 103, 104) || pendingBackupRequest != requestCode) return
        pendingBackupRequest = null
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
                    try { controller.restoreImportedConfiguration(preview, authorized = true); toast("配置已恢复") }
                    finally { cancelRestorePreview() }
                }
            }, backup = true)
        }
    }
    private fun loadAppearance(): AppearanceSettings {
        val preferences = getSharedPreferences("appearance-settings", MODE_PRIVATE)
        return AppearanceSettings(themeMode = AppearanceThemeMode.fromPersistedValue(preferences.getString("theme", null)),
            interfaceScale = preferences.getFloat("scale", 1f), messageTimestampsEnabled = preferences.getBoolean("timestamps", false),
            monetEnabled = preferences.getBoolean("monet", false)).normalized()
    }
    private fun saveAppearance(value: AppearanceSettings) { guard {
        val normalized = value.normalized()
        check(getSharedPreferences("appearance-settings", MODE_PRIVATE).edit().putString("theme", normalized.themeMode.persistedValue)
            .putFloat("scale", normalized.interfaceScale).putBoolean("timestamps", normalized.messageTimestampsEnabled)
            .putBoolean("monet", normalized.monetEnabled).commit())
        appearance = normalized
    } }
}
