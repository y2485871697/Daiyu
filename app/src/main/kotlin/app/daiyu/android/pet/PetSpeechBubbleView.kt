package app.daiyu.android.pet

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text

/** The user's original maid bubble, shared by the in-app and global presentations. */
internal class PetSpeechBubbleView(context: Context) : AbstractComposeView(context) {
    private var speech by mutableStateOf<String?>(null)
    private var scale by mutableStateOf(1f)
    var onDismiss: () -> Unit = {}

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        visibility = GONE
    }

    fun present(text: String?, scale: Float) {
        this.speech = text
        this.scale = scale
        visibility = if (text == null) GONE else VISIBLE
    }

    @Composable
    override fun Content() {
        val text = speech
        val alpha = remember { Animatable(1f) }
        LaunchedEffect(text) {
            alpha.snapTo(1f)
            if (text != null) {
                delay(2600)
                alpha.animateTo(0f, tween(durationMillis = 500))
            }
        }
        if (text != null) AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            Text(
                text = text,
                color = Color(0xFF1E293B),
                fontSize = (13f * scale).sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .graphicsLayer { this.alpha = alpha.value }
                    .padding(bottom = 6.dp)
                    .widthIn(max = 220.dp * scale)
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .border(2.dp, Color(0xFF3B82F6), RoundedCornerShape(12.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}
