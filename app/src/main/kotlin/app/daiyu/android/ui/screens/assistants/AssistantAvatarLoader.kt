package app.daiyu.android.ui.screens.assistants

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import app.daiyu.android.assistants.AssistantAssets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun rememberAssistantAvatar(assets: AssistantAssets, fileName: String?): Bitmap? = key(assets, fileName) {
    val bitmap by produceState<Bitmap?>(null, assets, fileName) {
        value = withContext(Dispatchers.IO) { assets.avatarBitmap(fileName) }
    }
    bitmap
}
