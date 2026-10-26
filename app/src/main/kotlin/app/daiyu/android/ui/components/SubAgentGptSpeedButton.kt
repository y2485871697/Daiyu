package app.daiyu.android.ui.components

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.haptics.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.daiyu.android.R
import app.daiyu.android.delegation.*
import app.daiyu.android.model.GptSpeedMode
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private val SubAgentGptSpeedIconSize = 18.dp
private val SubAgentGptSpeedMinTouchSize = 48.dp
internal fun subAgentGptSpeedDescription(name: String, mode: GptSpeedMode): String = "$name GPT 速度：${subAgentGptSpeedDisplayName(mode)}，点击切换速度"

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SubAgentGptSpeedButton(profile: SubAgentProfile, providers: List<SubAgentProvider>, enabled: Boolean, modifier: Modifier = Modifier) {
    val editor = LocalConversationSubAgentEditor.current
    val usable = enabled && editor?.enabled == true
    val boundModel = remember(profile.providerId, profile.modelId, providers) {
        val provider = providers.firstOrNull { it.id == profile.providerId && it.isEnabled }
        val model = provider?.models?.firstOrNull { it.id == profile.modelId && it.isEnabled }
        model?.takeIf { supportsGptSpeedBinding(provider, it) }
    } ?: return
    val mode = profile.gptSpeedForModel(fallback = boundModel.defaultGptSpeed)
    key(editor, profile.id, profile.providerId, profile.modelId) { BoundSubAgentGptSpeedButton(profile, editor, usable, mode, modifier) }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoundSubAgentGptSpeedButton(profile: SubAgentProfile, editor: ConversationSubAgentEditor?, usable: Boolean, mode: GptSpeedMode, modifier: Modifier) {
    val gate = remember { SubAgentGptSpeedAnimationGate() }
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val normalColor = MaterialTheme.colorScheme.onSurface
    val contentColor by animateColorAsState(targetValue = subAgentGptSpeedChipColor(mode, normalColor,
        MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimaryContainer),
        animationSpec = tween(durationMillis = SubAgentGptSpeedAnimationDurationMillis), label = "sub_agent_gpt_speed_color")
    val displayedColor = if (usable) contentColor else contentColor.copy(alpha = 0.38f)
    val chatStyle = app.daiyu.android.ui.components.subagent.LocalConversationAgentStyle.current
    val chatColors = app.daiyu.android.ui.components.subagent.conversationAgentColors()
    val chipShape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    Row(modifier = (if (chatStyle) modifier.heightIn(min = 24.dp).testTag("subagent-gpt-speed-${profile.id}").clip(chipShape).background(chatColors.soft).border(1.dp, chatColors.line, chipShape)
        else modifier.defaultMinSize(minWidth = SubAgentGptSpeedMinTouchSize, minHeight = SubAgentGptSpeedMinTouchSize))
        .semantics(mergeDescendants = true) { contentDescription = subAgentGptSpeedDescription(profile.name, mode) }
        .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = usable,
            role = Role.Button, onLongClick = null, onClick = {
                val token = gate.request()
                if (token != 0) scope.launch {
                    try {
                        val result = editor?.cycleGptSpeed(profile.id, profile.providerId, profile.modelId, expected = profile)
                        coroutineContext.ensureActive()
                        if (result !is ConversationSubAgentPreferences.WriteResult.Saved) return@launch
                        val savedProfile = result.config.profiles.singleOrNull { it.id == profile.id && it.providerId == profile.providerId && it.modelId == profile.modelId } ?: return@launch
                        val savedMode = savedProfile.gptSpeedForModel(profile.providerId, profile.modelId)

                        Toast.makeText(context, subAgentGptSpeedToastMessage(savedMode), Toast.LENGTH_SHORT).show()
                        progress.snapTo(0f)
                        progress.animateTo(targetValue = 1f, animationSpec = tween(durationMillis = SubAgentGptSpeedAnimationDurationMillis, easing = LinearEasing))
                    } finally { gate.finish(token) }
                }
            })
        .then(if (chatStyle) Modifier.padding(horizontal = 8.dp, vertical = 3.dp) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
        if (!chatStyle) Icon(imageVector = ImageVector.vectorResource(R.drawable.ic_atom), contentDescription = null,
            modifier = Modifier.size(SubAgentGptSpeedIconSize).graphicsLayer {
                rotationZ = subAgentGptSpeedRotationDegrees(progress.value)
                val scale = subAgentGptSpeedScale(progress.value); scaleX = scale; scaleY = scale
            }, tint = displayedColor)
        Text(text = (if (chatStyle) "GPT 速度 · " else "") + subAgentGptSpeedDisplayName(mode),
            modifier = if (chatStyle) Modifier.width(IntrinsicSize.Max) else Modifier,
            style = MaterialTheme.typography.labelMedium,
            fontSize = if (chatStyle) 10.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
            lineHeight = if (chatStyle) 16.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
            color = if (chatStyle) chatColors.accent.copy(alpha = if (usable) 1f else .38f) else displayedColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
