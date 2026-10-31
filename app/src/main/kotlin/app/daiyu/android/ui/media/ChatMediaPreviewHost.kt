package app.daiyu.android.ui.media

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Adapted from the user's original media preview; byte access remains with its authorized owner. */
@Composable
internal fun ChatMediaPreviewHost(
    gallery: List<ChatMediaPreviewItem>,
    content: @Composable () -> Unit,
) {
    var selection by remember { mutableStateOf<ChatMediaPreviewSelection?>(null) }
    val currentGallery by rememberUpdatedState(gallery)
    CompositionLocalProvider(LocalOpenChatMediaPreview provides { requestedKey ->
        selection = chatMediaPreviewSelection(currentGallery, requestedKey)
    }) {
        // Deliberately no layout wrapper: the caller retains its parent layout scope.
        content()
    }
    selection?.let { opened ->
        key(opened) {
            ChatMediaPreviewDialog(opened) { selection = null }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatMediaPreviewDialog(selection: ChatMediaPreviewSelection, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources = selection.gallery
    val pagerState = rememberPagerState(initialPage = selection.initialIndex, pageCount = { sources.size })
    val currentPage = pagerState.currentPage
    val item = sources[currentPage]
    var transform by remember(currentPage) { mutableStateOf(ChatMediaPreviewTransform()) }
    var transforming by remember(currentPage) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var documentItem by remember { mutableStateOf<ChatMediaPreviewItem?>(null) }

    fun reportSaved(success: Boolean) {
        Toast.makeText(context, if (success) "已保存原件" else "保存失败，请重试", Toast.LENGTH_SHORT).show()
    }

    val documentLauncher = rememberLauncherForActivityResult(
        contract = remember(item.mimeType) { ActivityResultContracts.CreateDocument(item.mimeType) },
    ) { uri ->
        val originalItem = documentItem
        documentItem = null
        if (uri == null || originalItem == null) {
            saving = false
        } else {
            scope.launch {
                try {
                    reportSaved(saveChatMediaPreviewDocument(context, originalItem, uri))
                } finally {
                    saving = false
                }
            }
        }
    }

    BackHandler(onBack = onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        ChatMediaPreviewHideStatusBars()
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { sources[it].key },
                userScrollEnabled = chatMediaPreviewPagerEnabled(transform.scale, transforming, sources.size),
            ) { page ->
                val active = page == currentPage
                ChatMediaPreviewPage(
                    item = sources[page],
                    active = active,
                    transform = if (active) transform else ChatMediaPreviewTransform(),
                    onTransform = { if (active) transform = it },
                    onTransforming = { if (active) transforming = it },
                )
            }
            IconButton(
                enabled = !saving,
                onClick = {
                    // Capture the page before any asynchronous work or picker navigation.
                    val requested = item
                    saving = true
                    if (Build.VERSION.SDK_INT < 29) {
                        documentItem = requested
                        try {
                            documentLauncher.launch(chatMediaPreviewFileName(requested.displayName, requested.mimeType))
                        } catch (_: Exception) {
                            documentItem = null
                            saving = false
                            reportSaved(false)
                        }
                    } else {
                        scope.launch {
                            try {
                                reportSaved(saveChatMediaPreviewOriginal(context, requested))
                            } finally {
                                saving = false
                            }
                        }
                    }
                },
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
            ) {
                Icon(Icons.Rounded.Download, contentDescription = "下载原件", tint = Color.White)
            }
            if (saving) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp),
                    size = 20.dp,
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

@Composable
private fun ChatMediaPreviewPage(
    item: ChatMediaPreviewItem,
    active: Boolean,
    transform: ChatMediaPreviewTransform,
    onTransform: (ChatMediaPreviewTransform) -> Unit,
    onTransforming: (Boolean) -> Unit,
) {
    val currentTransform by rememberUpdatedState(transform)
    val updateTransform by rememberUpdatedState(onTransform)
    val updateTransforming by rememberUpdatedState(onTransforming)
    var bitmap by remember(item) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(item) { mutableStateOf(false) }
    val video = chatMediaPreviewIsVideo(item.mimeType)
    LaunchedEffect(item) {
        if (!video) {
            bitmap = try {
                withContext(Dispatchers.IO) { item.loadImage(CHAT_MEDIA_PREVIEW_DISPLAY_EDGE) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            failed = bitmap == null
        }
    }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        val image = bitmap
        when {
            video -> ChatMediaPreviewVideo(item, active, Modifier.fillMaxSize())
            image != null -> Image(
                bitmap = image,
                contentDescription = item.displayName,
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High,
                modifier = Modifier.align(Alignment.Center).fillMaxSize()
                    .pointerInput(item.key, active) {
                        if (active) detectChatMediaPreviewGestures(
                            transform = { currentTransform },
                            onTransform = { updateTransform(it) },
                            onTransforming = { updateTransforming(it) },
                        )
                    }
                    .graphicsLayer(
                        scaleX = transform.scale,
                        scaleY = transform.scale,
                        translationX = transform.offset.x,
                        translationY = transform.offset.y,
                    ),
            )
            failed -> Text(
                text = "图片加载失败",
                color = Color.White,
                style = MiuixTheme.textStyles.body1,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center), size = 28.dp, strokeWidth = 2.5.dp,
            )
        }
    }
}

private suspend fun PointerInputScope.detectChatMediaPreviewGestures(
    transform: () -> ChatMediaPreviewTransform,
    onTransform: (ChatMediaPreviewTransform) -> Unit,
    onTransforming: (Boolean) -> Unit,
) {
    try {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                val previous = transform()
                val pointerCount = event.changes.count { it.pressed }
                if (chatMediaPreviewConsumesGesture(previous.scale, pointerCount)) {
                    onTransforming(true)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                    onTransform(chatMediaPreviewTransform(previous, event.calculateZoom(), event.calculatePan()))
                }
            } while (event.changes.any { it.pressed })
            onTransforming(false)
        }
    } finally {
        onTransforming(false)
    }
}
