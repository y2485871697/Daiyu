package app.daiyu.android.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import app.daiyu.android.ui.screens.chat.conversation.ChatAnchoredMenu
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuLayer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.textureBlur
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.daiyu.android.R
import app.daiyu.android.ui.model.ConversationFolderUi
import app.daiyu.android.ui.model.searchResults
import app.daiyu.android.ui.model.ConversationPaneUiState
import app.daiyu.android.ui.haptics.TouchHaptics
import app.daiyu.android.ui.model.ConversationSummaryUi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.material3.DropdownMenuItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private val DrawerMenuItemModifier = Modifier.height(40.dp)
private val DrawerMenuItemPadding = PaddingValues(horizontal = 12.dp)

private object DrawerMetrics {
    val PaneMaxWidth @Composable get() = 320.dp * conversationPanePreviewScale()
    const val PaneWidthFraction = 0.78f
    val DrawerCornerRadius @Composable get() = 28.dp * conversationPanePreviewScale()
    const val OpenDurationMs = 220
    const val CloseDurationMs = 200
    val PaneHorizontalPadding @Composable get() = 14.dp * conversationPanePreviewScale()
    val AfterSearch @Composable get() = 12.dp * conversationPanePreviewScale()
    val SearchIconSize @Composable get() = 20.dp * conversationPanePreviewScale()
    val SearchCornerRadius @Composable get() = 13.dp * conversationPanePreviewScale()
    val ChipGap @Composable get() = 6.dp * conversationPanePreviewScale()
    val AfterChips @Composable get() = 12.dp * conversationPanePreviewScale()
    val ListBottomPadding @Composable get() = 8.dp * conversationPanePreviewScale()
    val BottomInset @Composable get() = 14.dp * conversationPanePreviewScale()
    val ActionIconSize @Composable get() = 20.dp * conversationPanePreviewScale()
    val SectionTopPadding @Composable get() = 12.dp * conversationPanePreviewScale()
    val SectionBottomPadding @Composable get() = 6.dp * conversationPanePreviewScale()
    val RowMinHeight @Composable get() = 44.dp * conversationPanePreviewScale()
    val RowCornerRadius @Composable get() = 18.dp * conversationPanePreviewScale()
    val RowHorizontalPadding @Composable get() = 12.dp * conversationPanePreviewScale()
    val RowVerticalPadding @Composable get() = 10.dp * conversationPanePreviewScale()
    val ActiveDotSize @Composable get() = 6.dp * conversationPanePreviewScale()
    val ActiveDotGap @Composable get() = 8.dp * conversationPanePreviewScale()
    val EmptyVerticalPadding @Composable get() = 30.dp * conversationPanePreviewScale()
    val DockTopGap @Composable get() = 8.dp * conversationPanePreviewScale()
    val AssistantBarHeight @Composable get() = 48.dp * conversationPanePreviewScale()
}

@Composable
internal fun ConversationSidePaneScaffold(
    state: ConversationPaneUiState,
    visible: Boolean,
    backHandlerEnabled: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onSearchChange: (String) -> Unit,
    onConversationSelected: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onMoveConversationToFolder: (ConversationSummaryUi) -> Unit = {},
    onConversationTogglePin: (ConversationSummaryUi) -> Unit = {},
    onConversationExport: (ConversationSummaryUi) -> Unit = {},
    onConversationTasks: ((ConversationSummaryUi) -> Unit)? = null,
    onNewConversation: () -> Unit,
    onSelectFolder: (String?) -> Unit = {},
    onCreateFolder: (String) -> Unit = {},
    onRenameFolder: (String, String) -> Unit = { _, _ -> },
    onDeleteFolder: (String) -> Unit = {},
    assistants: List<AssistantDisplay> = emptyList(),
    selectedAssistantId: String = "",
    onSelectAssistant: (String) -> Unit,
    onEditAssistant: (String) -> Unit,
    onOpenAssistants: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenUsageStats: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val backdrop = rememberTopBarBackdrop()
    val sceneLifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val navigationEventState = rememberNavigationEventState(NavigationEventInfo.None)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val drawerState = rememberDrawerState(
        initialValue = if (visible) DrawerValue.Open else DrawerValue.Closed,
    )
    remember(drawerState) {
        drawerState.applyEtaDrawerMotion()
        true
    }
    val drawerScope = rememberCoroutineScope()
    val currentVisible by rememberUpdatedState(visible)
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    var drawerBlocksIme by remember { mutableStateOf(visible) }
    var managing by remember { mutableStateOf(false) }

    fun launchAfterDrawerClosed(action: () -> Unit) {
        drawerScope.launch {
            if (drawerState.targetValue != DrawerValue.Closed ||
                drawerState.currentValue != DrawerValue.Closed
            ) {
                drawerState.snapTo(DrawerValue.Closed)
            }
            action()
        }
    }

    LaunchedEffect(visible) {
        val target = if (visible) DrawerValue.Open else DrawerValue.Closed
        if (drawerState.targetValue == target) return@LaunchedEffect
        if (!visible && drawerState.currentValue == DrawerValue.Open) {
            // 从底部按钮切页：抽屉是完全打开的，瞬时合上再让新页入场。
            // 手势收回时 currentValue 会先变成 Closed，这里不会走到。
            drawerState.snapTo(target)
            return@LaunchedEffect
        }
        @Suppress("DEPRECATION")
        drawerState.animateTo(
            targetValue = target,
            anim = if (visible) DrawerOpenMotion else DrawerCloseMotion,
        )
    }

    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.currentValue to drawerState.isAnimationRunning }
            .collectLatest { (value, running) ->
                // 快滑时 currentValue 会在 settle 开始就切换。立刻改 visible 会让
                // 聊天页在惯性动画中间整页重组，看起来就是顿一下。
                if (running) return@collectLatest
                if (value == DrawerValue.Open) {
                    if (!currentVisible) currentOnOpen()
                } else if (currentVisible) {
                    currentOnDismiss()
                }
            }
    }

    LaunchedEffect(visible) {
        drawerBlocksIme = visible
    }

    LaunchedEffect(drawerState, visible, focusManager, keyboard, view) {
        snapshotFlow {
            visible ||
                drawerState.targetValue == DrawerValue.Open ||
                drawerState.currentValue == DrawerValue.Open
        }.collect { shouldHide ->
            if (shouldHide) hideChatInputIme(focusManager, keyboard, view)
        }
    }

    NavigationBackHandler(
        state = navigationEventState,
        isBackEnabled = visible &&
            backHandlerEnabled &&
            sceneLifecycleState == Lifecycle.State.RESUMED,
        onBackCompleted = { currentOnDismiss() },
    )

    ChatMenuLayer(modifier.fillMaxSize()) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val paneWidth = minOf(maxWidth * DrawerMetrics.PaneWidthFraction, DrawerMetrics.PaneMaxWidth)
        val drawerShape = AbsoluteRoundedCornerShape(
            topLeft = 0.dp,
            topRight = DrawerMetrics.DrawerCornerRadius,
            bottomRight = DrawerMetrics.DrawerCornerRadius,
            bottomLeft = 0.dp,
        )
        CompositionLocalProvider(LocalConversationDrawerBlocksIme provides drawerBlocksIme) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = backHandlerEnabled,
            scrimColor = conversationPanePreviewPalette().scrim,
            modifier = Modifier.fillMaxSize(),
            drawerContent = {
                ConversationPanePanel(
                    state = state,
                    width = paneWidth,
                    drawerShape = drawerShape,
                    backdrop = backdrop,
                    // Only the search row observes open/settle state. The panel and its
                    // lazy item provider must stay reusable when the drawer opens.
                    enableSearchFocus = {
                        currentVisible && drawerState.currentValue == DrawerValue.Open &&
                            !drawerState.isAnimationRunning
                    },
                    onSearchChange = onSearchChange,
                    onConversationSelected = onConversationSelected,
                    onConversationRename = onConversationRename,
                    onConversationDelete = onConversationDelete,
                    onMoveConversationToFolder = onMoveConversationToFolder,
                    onNewConversation = onNewConversation,
                    onConversationTogglePin = onConversationTogglePin,
                    onConversationExport = onConversationExport,
                    onConversationTasks = onConversationTasks,
                    managing = managing,
                    onManagingChange = { managing = it },
                    onSelectFolder = onSelectFolder,
                    onCreateFolder = onCreateFolder,
                    onRenameFolder = onRenameFolder,
                    onDeleteFolder = onDeleteFolder,
                    assistants = assistants,
                    selectedAssistantId = selectedAssistantId,
                    onSelectAssistant = onSelectAssistant,
                    onEditAssistant = { id -> launchAfterDrawerClosed { onEditAssistant(id) } },
                    onOpenAssistants = { launchAfterDrawerClosed(onOpenAssistants) },
                    onOpenSettings = { launchAfterDrawerClosed(onOpenSettings) },
                    onOpenModelProviders = { launchAfterDrawerClosed(onOpenModelProviders) },
                    onOpenUsageStats = { launchAfterDrawerClosed(onOpenUsageStats) },
                    onOpenSkills = { launchAfterDrawerClosed(onOpenSkills) },
                    onOpenPermissions = { launchAfterDrawerClosed(onOpenPermissions) },
                    modifier = Modifier.fillMaxHeight(),
                )
            },
        ) {
            Box(Modifier.fillMaxSize()
                .captureForTopBar(backdrop)) { content() }
        }
        }
    }
}
}

@Composable
private fun ConversationPanePanel(
    state: ConversationPaneUiState,
    managing: Boolean,
    onManagingChange: (Boolean) -> Unit,
    width: androidx.compose.ui.unit.Dp,
    drawerShape: AbsoluteRoundedCornerShape,
    backdrop: LayerBackdrop?,
    enableSearchFocus: () -> Boolean,
    onSearchChange: (String) -> Unit,
    onConversationSelected: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onMoveConversationToFolder: (ConversationSummaryUi) -> Unit = {},
    onConversationTogglePin: (ConversationSummaryUi) -> Unit = {},
    onConversationExport: (ConversationSummaryUi) -> Unit = {},
    onConversationTasks: ((ConversationSummaryUi) -> Unit)? = null,
    onNewConversation: () -> Unit,
    onSelectFolder: (String?) -> Unit = {},
    onCreateFolder: (String) -> Unit = {},
    onRenameFolder: (String, String) -> Unit = { _, _ -> },
    onDeleteFolder: (String) -> Unit = {},
    assistants: List<AssistantDisplay> = emptyList(),
    selectedAssistantId: String = "",
    onSelectAssistant: (String) -> Unit,
    onEditAssistant: (String) -> Unit,
    onOpenAssistants: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenUsageStats: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedIds by remember(managing) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(state.historyConversations) {
        selectedIds = selectedIds.intersect(state.historyConversations.map { it.id }.toSet())
    }
    val query = state.searchQuery.trim()
    val visibleConversations = remember(state.conversations, query) {
        state.searchResults()
    }
    val groups = remember(visibleConversations) { visibleConversations.groupForDrawer() }
    val profiles = assistants
    val activeId = selectedAssistantId
    val activeAssistant = remember(profiles, activeId) {
        profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
    }
    val colors = conversationPanePreviewPalette()
    val scale = conversationPanePreviewScale()
    val density = LocalDensity.current
    val safeTop = with(density) { WindowInsets.safeDrawing.getTop(density).toDp() }
    Surface(
        modifier = modifier
            .width(width).testTag("conversation-drawer")
            .fillMaxHeight()
            .dropShadow(drawerShape, Shadow(radius = 32.dp, offset = DpOffset(8.dp, 0.dp),
                color = if (colors.dark) Color(0x55000000) else Color(0x16372444)))
            .graphicsLayer { clip = true; shape = drawerShape }
            .then(if (backdrop != null) Modifier.textureBlur(backdrop = backdrop, shape = drawerShape,
                blurRadius = 28f * scale, colors = BlurColors(blendColors = emptyList(), saturation = 1.3f))
                else Modifier.background(if (colors.dark) Color(0xFF24202F) else Color(0xFFF8F4FC)))
            .drawWithCache {
                // CSS linear-gradient(145deg), including its explicit 62% stop.
                val length = size.width * .573576f + size.height * .819152f
                val delta = Offset(length * .573576f / 2, length * .819152f / 2)
                val center = Offset(size.width / 2, size.height / 2)
                val brush = Brush.linearGradient(0f to colors.gradient[0], .62f to colors.gradient[1], 1f to colors.gradient[2],
                    start = center - delta, end = center + delta)
                onDrawBehind { drawRect(brush) }
            }
            .drawWithCache {
                val border = drawerShape.createOutline(size, layoutDirection, this)
                // The outer clip leaves a 1dp border; CSS omits its left edge.
                val stroke = 2.dp.toPx()
                onDrawWithContent {
                    drawContent()
                    clipRect(left = 1.dp.toPx()) {
                        drawOutline(border, colors.border, style = Stroke(stroke))
                    }
                }
            },
        shape = drawerShape,
        color = Color.Transparent,
        contentColor = colors.text,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                // 搜索框在顶部，键盘不必把底部 dock 和会话输入一起顶上来。
                .windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime))
                .consumeWindowInsets(WindowInsets.ime)
                .padding(start = DrawerMetrics.PaneHorizontalPadding, end = DrawerMetrics.PaneHorizontalPadding + 1.dp),
        ) {
            // CSS: top border 1 + padding 30 + .drawer-top margin 1, including the status area.
            Spacer(modifier = Modifier.height((32.dp * scale - safeTop).coerceAtLeast(0.dp)))
            PaneSearchRow(
                query = state.searchQuery,
                onSearchChange = onSearchChange,
                enableSearchFocus = enableSearchFocus,
            )
            Spacer(modifier = Modifier.height(DrawerMetrics.AfterSearch))
            PaneDrawerActions(
                onNewConversation = onNewConversation,
                managing = managing,
                onOpenManageChats = { onManagingChange(!managing) },
            )
            Spacer(modifier = Modifier.height(DrawerMetrics.AfterSearch))
            PaneFolderBar(
                folders = state.folders,
                selectedFolderId = state.selectedFolderId,
                onSelectFolder = onSelectFolder,
                onCreateFolder = onCreateFolder,
                onRenameFolder = onRenameFolder,
                onDeleteFolder = onDeleteFolder,
            )
            Spacer(modifier = Modifier.height(DrawerMetrics.AfterChips))
            if (managing) Text("已选择 ${selectedIds.size} 项", color = colors.accent,
                fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.testTag("drawer-selection-count").padding(start = 8.dp, end = 8.dp, bottom = 8.dp))
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .testTag("drawer-list")
                    .scrollEndHaptic()
                    .overScrollVertical(),
                // CSS adjoining chat margins collapse to 2dp; headers keep their own margins.
                contentPadding = PaddingValues(bottom = DrawerMetrics.ListBottomPadding + if (visibleConversations.isEmpty()) 0.dp else 2.dp),
                overscrollEffect = null,
            ) {
                if (visibleConversations.isEmpty()) {
                    if (state.conversationsLoaded) item(contentType = "drawer-empty") {
                        EmptyConversations(isSearching = query.isNotBlank())
                    }
                } else {
                    groups.forEachIndexed { groupIndex, group ->
                        item(key = group.key, contentType = "drawer-section") {
                            ConversationSectionHeader(group = group, first = groupIndex == 0)
                        }
                        itemsIndexed(
                            items = group.items,
                            key = { _, conversation -> conversation.id },
                            contentType = { _, _ -> "drawer-conversation" },
                        ) { index, conversation ->
                            ConversationTextRow(
                                modifier = Modifier.padding(top = if (index == 0) 0.dp else 2.dp),
                                conversation = conversation,
                                selected = conversation.id == state.selectedConversationId,
                                managing = managing,
                                checked = conversation.id in selectedIds,
                                onClick = {
                                    if (managing) selectedIds = if (conversation.id in selectedIds)
                                        selectedIds - conversation.id else selectedIds + conversation.id
                                    else onConversationSelected(conversation.id)
                                },
                                onRename = { onConversationRename(conversation) },
                                onDelete = { onConversationDelete(conversation) },
                                onMoveToFolder = { onMoveConversationToFolder(conversation) },
                                onTogglePin = { onConversationTogglePin(conversation) },
                                onExport = { onConversationExport(conversation) },
                                onTasks = onConversationTasks?.let { action -> { action(conversation) } },
                            )
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.footerBorder).testTag("drawer-footer-border"))
            Spacer(Modifier.height(11.dp * scale))
            PaneAssistantBar(
                name = activeAssistant?.name?.ifBlank { stringResource(R.string.app_name) }
                    ?: stringResource(R.string.app_name),
                onClick = onOpenAssistants,
                avatar = {
                    AssistantAvatar(
                        assistant = activeAssistant,
                        size = 32.dp * scale,
                        modifier = Modifier.testTag("drawer-assistant-avatar"),
                    )
                },
            )
            Spacer(modifier = Modifier.height(DrawerMetrics.DockTopGap))
            PaneDock(
                onOpenPermissions = onOpenPermissions,
                onOpenSettings = onOpenSettings,
                onOpenModelProviders = onOpenModelProviders,
                onOpenUsageStats = onOpenUsageStats,
                onOpenSkills = onOpenSkills,
            )
            Spacer(modifier = Modifier.height(DrawerMetrics.BottomInset + 1.dp))
        }
    }

}

@Composable
private fun PaneSearchRow(
    query: String,
    onSearchChange: (String) -> Unit,
    enableSearchFocus: () -> Boolean,
) {
    val s = conversationPanePreviewScale()
    val colors = conversationPanePreviewPalette()
    val searchFocusEnabled = enableSearchFocus()
    val textStyle = MiuixTheme.textStyles.body2.merge(
        TextStyle(
            color = conversationPanePreviewPalette().text,
            fontSize = (14f * s).sp,
            lineHeight = 20.sp,
        ),
    )
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    var searchActive by remember { mutableStateOf(false) }
    LaunchedEffect(searchFocusEnabled) {
        if (!searchFocusEnabled) {
            searchActive = false
            focusManager.clearFocus(force = true)
        }
    }
    LaunchedEffect(searchActive, searchFocusEnabled) {
        if (searchActive && searchFocusEnabled) {
            showChatInputIme(searchFocusRequester, keyboard, view)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp * s)
            .testTag("drawer-search")
            .clip(RoundedCornerShape(DrawerMetrics.SearchCornerRadius))
            .background(colors.action)
            .border(1.dp, colors.actionBorder, RoundedCornerShape(DrawerMetrics.SearchCornerRadius))
            .clickable(
                enabled = searchFocusEnabled && !searchActive,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                searchActive = true
            }
            .padding(1.dp)
            .padding(horizontal = 12.dp * s, vertical = 8.dp * s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp * s),
    ) {
        Icon(
            imageVector = ConversationPanePreviewIcons.Search,
            contentDescription = null,
            modifier = Modifier.size(DrawerMetrics.SearchIconSize).testTag("drawer-search-icon"),
            tint = colors.muted,
        )
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.conversation_search_hint),
                    color = colors.muted,
                    style = textStyle,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onSearchChange,
                enabled = searchFocusEnabled && searchActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("drawer-search-input")
                    .focusRequester(searchFocusRequester)
                    .focusProperties { canFocus = searchFocusEnabled && searchActive },
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(conversationPanePreviewPalette().accent),
            )
        }
    }
}

@Composable
private fun PaneDrawerActions(
    managing: Boolean,
    onNewConversation: () -> Unit,
    onOpenManageChats: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp * conversationPanePreviewScale()),
    ) {
        DrawerActionRow(
            tag = "drawer-new",
            label = stringResource(R.string.drawer_new_conversation),
            icon = ConversationPanePreviewIcons.Chat,
            onClick = onNewConversation,
        )
        DrawerActionRow(
            tag = "drawer-manage",
            label = if (managing) "完成管理" else stringResource(R.string.drawer_manage_chats),
            icon = ConversationPanePreviewIcons.History,
            onClick = onOpenManageChats,
        )
    }
}

@Composable
private fun DrawerActionRow(
    tag: String,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    val colors = conversationPanePreviewPalette()
    val s = conversationPanePreviewScale()
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp * s).testTag(tag)
        .clip(RoundedCornerShape(13.dp * s)).background(colors.action)
        .border(1.dp, colors.actionBorder, RoundedCornerShape(13.dp * s))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
            TouchHaptics.click(view); onClick()
        }.padding(1.dp).padding(horizontal = 12.dp * s, vertical = 10.dp * s),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp * s)) {
        Icon(icon, null, Modifier.size(20.dp * s).testTag("$tag-icon"), tint = colors.toolbar)
        Text(label, modifier = Modifier.weight(1f), color = colors.accent,
            fontSize = (14f * s).sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PaneFolderBar(
    folders: List<ConversationFolderUi>,
    selectedFolderId: String?,
    onSelectFolder: (String?) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
) {
    var createFolder by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<ConversationFolderUi?>(null) }
    var folderToDelete by remember { mutableStateOf<ConversationFolderUi?>(null) }

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DrawerMetrics.ChipGap),
    ) {
        item(key = "unfiled") {
            DrawerChip(
                tag = "drawer-folder-unfiled",
                label = stringResource(R.string.drawer_chats_chip),
                selected = selectedFolderId == null,
                icon = null,
                onClick = { onSelectFolder(null) },
            )
        }
        items(folders, key = { it.id }) { folder ->
            var showMenu by remember(folder.id) { mutableStateOf(false) }
            Box {
                DrawerChip(
                    tag = "drawer-folder:${folder.id}",
                    label = folder.name,
                    selected = selectedFolderId == folder.id,
                    icon = ConversationPanePreviewIcons.Folder,
                    onClick = { onSelectFolder(folder.id) },
                    onLongClick = { showMenu = true },
                )
                DrawerGlassMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        modifier = DrawerMenuItemModifier,
                        contentPadding = DrawerMenuItemPadding,
                        text = { Text(stringResource(R.string.action_rename)) },
                        leadingIcon = {
                            Icon(
                                imageVector = ConversationPanePreviewIcons.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                            )
                        },
                        onClick = {
                            showMenu = false
                            folderToRename = folder
                        },
                    )
                    DropdownMenuItem(
                        modifier = DrawerMenuItemModifier,
                        contentPadding = DrawerMenuItemPadding,
                        text = {
                            Text(
                                text = stringResource(R.string.action_delete),
                                color = conversationPanePreviewPalette().danger,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = ConversationPanePreviewIcons.Trash,
                                contentDescription = null,
                                modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                                tint = conversationPanePreviewPalette().danger,
                            )
                        },
                        onClick = {
                            showMenu = false
                            folderToDelete = folder
                        },
                    )
                }
            }
        }
        item(key = "new-folder") {
            DrawerChip(
                tag = "drawer-folder-new",
                label = stringResource(R.string.drawer_new_folder),
                selected = false,
                icon = ConversationPanePreviewIcons.FolderAdd,
                onClick = { createFolder = true },
            )
        }
    }

    if (createFolder) {
        FolderNameDialog(
            title = stringResource(R.string.drawer_create_folder_title),
            initialName = "",
            onDismiss = { createFolder = false },
            onConfirm = { name ->
                onCreateFolder(name)
                createFolder = false
            },
        )
    }
    folderToRename?.let { folder ->
        FolderNameDialog(
            title = stringResource(R.string.drawer_rename_folder_title),
            initialName = folder.name,
            onDismiss = { folderToRename = null },
            onConfirm = { name ->
                onRenameFolder(folder.id, name)
                folderToRename = null
            },
        )
    }
    folderToDelete?.let { folder ->
        DaiyuConfirmDialog(stringResource(R.string.drawer_delete_folder_title),
            stringResource(R.string.drawer_delete_folder_message), stringResource(R.string.action_delete), true,
            onConfirm = { onDeleteFolder(folder.id); folderToDelete = null },
            onDismiss = { folderToDelete = null }, preview = true)
    }
}

@Composable
private fun FolderNameDialog(title: String, initialName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    app.daiyu.android.ui.screens.chat.conversation.ChatSelectionSheet(title, onDismiss, footer = {
        app.daiyu.android.ui.screens.assistants.AssistantPreviewButton("取消", onDismiss, Modifier.weight(1f).testTag("folder-name-cancel"))
        app.daiyu.android.ui.screens.assistants.AssistantPreviewButton(stringResource(R.string.action_save),
            { onConfirm(name) }, Modifier.weight(1f).testTag("folder-name-save"), primary = true, enabled = name.isNotBlank())
    }) {
        CompactDialogTextField(name, { name = it }, stringResource(R.string.drawer_folder_name),
            Modifier.testTag("folder-name-input"))
    }
}

@Composable
private fun DrawerChip(
    tag: String,
    label: String,
    selected: Boolean,
    icon: ImageVector?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val s = conversationPanePreviewScale()
    val background = if (selected) {
        conversationPanePreviewPalette().folder
    } else {
        conversationPanePreviewPalette().folderIdle
    }
    val contentColor = if (selected) {
        conversationPanePreviewPalette().folderText
    } else {
        conversationPanePreviewPalette().toolbar
    }
    val view = LocalView.current
    Row(
        modifier = Modifier
            .testTag(tag)
            .heightIn(min = 36.dp * s)
            .clip(RoundedCornerShape(18.dp * s))
            .background(background)
            .border(1.dp, if (selected) conversationPanePreviewPalette().folderBorder else Color.Transparent, RoundedCornerShape(18.dp * s))
            .pointerInput(onClick, onLongClick) {
                detectTapGestures(
                    onTap = {
                        TouchHaptics.click(view)
                        onClick()
                    },
                    onLongPress = {
                        if (onLongClick != null) {
                            TouchHaptics.longPress(view)
                            onLongClick()
                        }
                    },
                )
            }
            .padding(1.dp)
            .padding(horizontal = 11.dp * s, vertical = 6.dp * s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp * s),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(15.dp * s).testTag("$tag-icon"),
                tint = contentColor,
            )
        }
        Text(
            text = label,
            color = contentColor,
            fontSize = (12f * s).sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ConversationSectionHeader(
    group: ConversationDrawerGroup,
    first: Boolean,
) {
    Text(
        text = group.localizedLabel(),
        color = conversationPanePreviewPalette().muted,
        fontSize = (12f * conversationPanePreviewScale()).sp,
        lineHeight = 17.sp,
        letterSpacing = (.2f * conversationPanePreviewScale()).sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = DrawerMetrics.RowHorizontalPadding,
                top = if (first) 5.dp else DrawerMetrics.SectionTopPadding,
                end = DrawerMetrics.RowHorizontalPadding,
                bottom = DrawerMetrics.SectionBottomPadding,
            )
            .testTag("drawer-heading:${group.key}"),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationTextRow(
    managing: Boolean,
    checked: Boolean,
    conversation: ConversationSummaryUi,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMoveToFolder: () -> Unit,
    onTogglePin: () -> Unit,
    onExport: () -> Unit,
    onTasks: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var showActionMenu by remember(managing) { mutableStateOf(false) }
    val view = LocalView.current

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DrawerMetrics.RowMinHeight)
                .clip(RoundedCornerShape(DrawerMetrics.RowCornerRadius))
                .background(
                    if (selected) {
                        conversationPanePreviewPalette().bubble
                    } else {
                        Color.Transparent
                    },
                )
                .border(1.dp, if (selected) conversationPanePreviewPalette().line else Color.Transparent,
                    RoundedCornerShape(DrawerMetrics.RowCornerRadius))
                .testTag("drawer-conversation:${conversation.id}")
                .semantics { if (managing) { role = Role.Checkbox; this.selected = checked } }
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    hapticFeedbackEnabled = false,
                    onClick = {
                        TouchHaptics.click(view)
                        onClick()
                    },
                    onLongClick = {
                        TouchHaptics.longPress(view)
                        if (!managing) showActionMenu = true
                    },
                )
                .padding(1.dp)
                .padding(
                    horizontal = DrawerMetrics.RowHorizontalPadding,
                    vertical = DrawerMetrics.RowVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (managing) {
                Box(Modifier.padding(end = 8.dp).size(13.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (checked) conversationPanePreviewPalette().accent else Color.Transparent)
                    .border(1.dp, if (checked) conversationPanePreviewPalette().accent else conversationPanePreviewPalette().line, RoundedCornerShape(4.dp))
                    .testTag("drawer-select:${conversation.id}"),
                    contentAlignment = Alignment.Center) {
                    if (checked) Box(Modifier.size(5.dp).background(conversationPanePreviewPalette().surface, RoundedCornerShape(1.dp)))
                }
            }
            Text(
                text = conversation.title.ifBlank { conversation.preview },
                color = conversationPanePreviewPalette().text,
                fontSize = (14f * conversationPanePreviewScale()).sp,
                lineHeight = (20f * conversationPanePreviewScale()).sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("drawer-title:${conversation.id}"),
            )
            if (conversation.isActiveRun || conversation.hasCompletionMarker) {
                Box(
                    modifier = Modifier
                        .padding(start = DrawerMetrics.ActiveDotGap)
                        .size(DrawerMetrics.ActiveDotSize)
                        .testTag("drawer-status:${conversation.id}")
                        .clip(CircleShape)
                        .then(
                            if (conversation.isActiveRun) {
                                Modifier.background(conversationPanePreviewPalette().accent)
                            } else {
                                Modifier.border(1.dp, conversationPanePreviewPalette().accent, CircleShape)
                            },
                        ),
                )
            }
            if (conversation.isPinned) {
                Icon(
                    imageVector = ConversationPanePreviewIcons.Pin,
                    contentDescription = null,
                    modifier = Modifier.padding(
                        start = DrawerMetrics.ActiveDotGap,
                    ).size(11.dp * conversationPanePreviewScale()).testTag("drawer-pin:${conversation.id}"),
                    tint = conversationPanePreviewPalette().accent.copy(alpha = .8f),
                )
            }
        }

        DrawerGlassMenu(
            expanded = showActionMenu,
            onDismissRequest = { showActionMenu = false },
        ) {
            DropdownMenuItem(
                modifier = DrawerMenuItemModifier,
                contentPadding = DrawerMenuItemPadding,
                text = { Text(stringResource(R.string.action_rename)) },
                leadingIcon = {
                    Icon(
                        imageVector = ConversationPanePreviewIcons.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                    )
                },
                onClick = {
                    showActionMenu = false
                    onRename()
                },
            )
            DropdownMenuItem(
                modifier = DrawerMenuItemModifier,
                contentPadding = DrawerMenuItemPadding,
                text = {
                    Text(
                        stringResource(
                            if (conversation.isPinned) R.string.conversation_unpin
                            else R.string.conversation_pin,
                        )
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = ConversationPanePreviewIcons.Pin,
                        contentDescription = null,
                        modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                    )
                },
                onClick = {
                    showActionMenu = false
                    onTogglePin()
                },
            )
            DropdownMenuItem(
                modifier = DrawerMenuItemModifier,
                contentPadding = DrawerMenuItemPadding,
                text = { Text(stringResource(R.string.conversation_export)) },
                leadingIcon = {
                    Icon(
                        imageVector = ConversationPanePreviewIcons.Export,
                        contentDescription = null,
                        modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                    )
                },
                onClick = {
                    showActionMenu = false
                    onExport()
                },
            )
            if (onTasks != null) {
                DropdownMenuItem(
                    modifier = DrawerMenuItemModifier,
                    contentPadding = DrawerMenuItemPadding,
                    text = { Text(stringResource(R.string.business_task_records)) },
                    onClick = { showActionMenu = false; onTasks() },
                )
            }
            DropdownMenuItem(
                modifier = DrawerMenuItemModifier,
                contentPadding = DrawerMenuItemPadding,
                text = { Text(stringResource(R.string.drawer_move_to_folder)) },
                leadingIcon = {
                    Icon(
                        imageVector = ConversationPanePreviewIcons.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                    )
                },
                onClick = {
                    showActionMenu = false
                    onMoveToFolder()
                },
            )
            DropdownMenuItem(
                modifier = DrawerMenuItemModifier,
                contentPadding = DrawerMenuItemPadding,
                text = { Text(text = stringResource(R.string.action_delete), color = conversationPanePreviewPalette().danger) },
                leadingIcon = {
                    Icon(
                        imageVector = ConversationPanePreviewIcons.Trash,
                        contentDescription = null,
                        modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                        tint = conversationPanePreviewPalette().danger,
                    )
                },
                onClick = {
                    showActionMenu = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun EmptyConversations(isSearching: Boolean) {
    Text(
        text = stringResource(
            if (isSearching) R.string.conversation_no_results else R.string.conversation_empty,
        ),
        color = conversationPanePreviewPalette().muted,
        fontSize = (13f * conversationPanePreviewScale()).sp,
        lineHeight = 18.sp,
        textAlign = TextAlign.Center,
        fontWeight = FontWeight.Normal,
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = 10.dp,
            vertical = DrawerMetrics.EmptyVerticalPadding,
        ),
    )
}

@Composable
private fun PaneAssistantBar(
    name: String,
    onClick: () -> Unit,
    avatar: @Composable () -> Unit,
) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("drawer-assistant")
            .heightIn(min = DrawerMetrics.AssistantBarHeight)
            .clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                TouchHaptics.click(view)
                onClick()
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            color = conversationPanePreviewPalette().text,
            fontSize = (14f * conversationPanePreviewScale()).sp,
            lineHeight = (20f * conversationPanePreviewScale()).sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp),
        )
        avatar()
    }
}

@Composable
private fun PaneDock(
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenUsageStats: () -> Unit,
    onOpenSkills: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("drawer-dock")) {
        val buttonSize = maxWidth * .15625f
        val gap = maxWidth * .0390625f
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DrawerCircleButton(
                tag = "drawer-dock-permissions",
                size = buttonSize,
                icon = ConversationPanePreviewIcons.Shield,
                label = stringResource(R.string.ui_permission_health_3048bb),
                onClick = onOpenPermissions,
            )
            Spacer(modifier = Modifier.width(gap))
            DrawerCircleButton(
                tag = "drawer-dock-models",
                size = buttonSize,
                icon = ConversationPanePreviewIcons.Memory,
                label = stringResource(R.string.conversation_dock_models),
                onClick = onOpenModelProviders,
            )
            Spacer(modifier = Modifier.width(gap))
            DrawerCircleButton(
                tag = "drawer-dock-skills",
                size = buttonSize,
                icon = ConversationPanePreviewIcons.Puzzle,
                label = stringResource(R.string.route_skills),
                onClick = onOpenSkills,
            )
            Spacer(modifier = Modifier.width(gap))
            DrawerCircleButton(
                tag = "drawer-dock-usage",
                size = buttonSize,
                icon = ConversationPanePreviewIcons.Bars,
                label = stringResource(R.string.stats_page_title),
                onClick = onOpenUsageStats,
            )
            Spacer(modifier = Modifier.weight(1f))
            DrawerCircleButton(
                tag = "drawer-dock-settings",
                size = buttonSize,
                icon = ConversationPanePreviewIcons.Settings,
                label = stringResource(R.string.route_settings),
                onClick = onOpenSettings,
            )
        }
    }
}

@Composable
private fun DrawerCircleButton(
    tag: String,
    size: androidx.compose.ui.unit.Dp,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    Box(
        modifier = Modifier
            .size(size)
            .testTag(tag)
            .clip(CircleShape)
            .background(conversationPanePreviewPalette().bubble)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                TouchHaptics.click(view)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(size * .5f).testTag("$tag-icon"),
            tint = conversationPanePreviewPalette().toolbar,
        )
    }
}

private val DrawerMotionEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

private val DrawerOpenMotion: FiniteAnimationSpec<Float> = tween(
    durationMillis = DrawerMetrics.OpenDurationMs,
    easing = DrawerMotionEasing,
)

private val DrawerCloseMotion: FiniteAnimationSpec<Float> = tween(
    durationMillis = DrawerMetrics.CloseDurationMs,
    easing = DrawerMotionEasing,
)

private object DrawerMotionSetters {
    private val drawerStateClass = DrawerState::class.java
    private val openSetter = method("setOpenDrawerMotionSpec\$material3")
    private val closeSetter = method("setCloseDrawerMotionSpec\$material3")

    private fun method(name: String) = runCatching {
        drawerStateClass.getMethod(name, FiniteAnimationSpec::class.java)
    }.getOrNull()

    fun apply(state: DrawerState) {
        // 只改菜单按钮触发的开合。手势松手后的 settle 沿用 Material3 默认弹簧，
        // 才能吃进快滑速度；短 tween 会把惯性掐成匀速，看起来就是顿一下。
        openSetter?.invoke(state, DrawerOpenMotion)
        closeSetter?.invoke(state, DrawerCloseMotion)
    }
}

private fun DrawerState.applyEtaDrawerMotion() {
    DrawerMotionSetters.apply(this)
}

internal data class ConversationDrawerGroup(
    val section: ConversationDrawerSection,
    val items: List<ConversationSummaryUi>,
) {
    val key: String get() = "section-$section"
}

internal sealed interface ConversationDrawerSection {
    data object Pinned : ConversationDrawerSection
    data object Today : ConversationDrawerSection
    data class Dated(val label: String) : ConversationDrawerSection
}

@Composable
private fun ConversationDrawerGroup.localizedLabel(): String = when (val value = section) {
    ConversationDrawerSection.Pinned -> stringResource(R.string.conversation_section_pinned)
    ConversationDrawerSection.Today -> stringResource(R.string.conversation_section_today)
    is ConversationDrawerSection.Dated -> value.label
}

internal fun List<ConversationSummaryUi>.groupForDrawer(): List<ConversationDrawerGroup> {
    // A cached label or a differently ordered input can put the same section in disjoint
    // runs. Merge all of them, keeping the first section occurrence and row order stable.
    val groups = linkedMapOf<ConversationDrawerSection, MutableList<ConversationSummaryUi>>()
    for (conversation in this) {
        groups.getOrPut(conversation.drawerSection()) { mutableListOf() } += conversation
    }
    return groups.map { (section, items) -> ConversationDrawerGroup(section, items.toList()) }
}

private fun ConversationSummaryUi.drawerSection(): ConversationDrawerSection = when {
    isPinned -> ConversationDrawerSection.Pinned
    isToday -> ConversationDrawerSection.Today
    else -> ConversationDrawerSection.Dated(timeLabel)
}

@Composable
private fun DrawerGlassMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ChatAnchoredMenu(expanded = expanded, onDismiss = onDismissRequest) {
        Column(content = content)
    }
}
