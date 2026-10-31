package app.daiyu.android.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import app.daiyu.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme


internal data class AssistantDisplay(val id: String, val name: String, val avatar: Bitmap? = null)

@Composable
internal fun AssistantAvatar(assistant: AssistantDisplay?, size: Dp, modifier: Modifier = Modifier) {
    AssistantAvatar(bitmap = assistant?.avatar, size = size, modifier = modifier)
}

@Composable
internal fun AssistantAvatar(
    bitmap: Bitmap?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val custom = remember(bitmap) { bitmap?.takeUnless { it.isRecycled }?.asImageBitmap() }
    val fallback by DefaultAssistantAvatar.image.collectAsState()
    if (custom == null) LaunchedEffect(context) { DefaultAssistantAvatar.load(context.applicationContext) }
    val imageBitmap = custom ?: fallback
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

internal object DefaultAssistantAvatar {
    private val prepared = MutableStateFlow<ImageBitmap?>(null)
    val image = prepared.asStateFlow()
    private val mutex = Mutex()

    suspend fun load(context: Context): ImageBitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            prepared.value?.let { return@withLock it }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(context.resources, R.drawable.ic_assistant_default, options)
            options.inJustDecodeBounds = false
            while (options.outWidth / (options.inSampleSize.coerceAtLeast(1) * 2) >= 256 &&
                options.outHeight / (options.inSampleSize.coerceAtLeast(1) * 2) >= 256) {
                options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
            }
            BitmapFactory.decodeResource(context.resources, R.drawable.ic_assistant_default, options)
                ?.asImageBitmap()?.also { prepared.value = it }
        }
    }
}
