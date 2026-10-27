package app.daiyu.android.ui.components.subagent

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import app.daiyu.android.ui.haptics.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import app.daiyu.android.ui.layout.appWindowSize
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.delegation.ReasoningEffort
import app.daiyu.android.ui.haptics.TouchHaptics
import kotlin.math.roundToInt

@Composable internal fun ConversationThinkingMark(modifier: Modifier = Modifier, tint: Color = conversationAgentColors().accent) {
    androidx.compose.material3.Icon(app.daiyu.android.ui.icons.ChatPreviewIcons.Thought,
        contentDescription = null, modifier = modifier, tint = tint)
}

@Composable internal fun ConversationThinkingPicker(show: Boolean, effort: ReasoningEffort, options: List<ReasoningEffort>,
    onDismiss: () -> Unit, onEffortChange: (ReasoningEffort) -> Unit, description: String?,
    speedMode: app.daiyu.android.model.GptSpeedMode? = null, onCycleSpeed: (() -> Unit)? = null, speedEnabled: Boolean = true) {
    if (!show) return
    val colors = conversationAgentColors()
    val subagent = LocalConversationAgentStyle.current
    val choices = if (subagent) options else options.filter { it != ReasoningEffort.DEFAULT }
    if (choices.isEmpty()) return
    var selected by remember(show, effort, choices) {
        mutableIntStateOf(choices.indexOf(effort))
    }
    val maxHeight = (appWindowSize().height - 50.dp).coerceAtLeast(0.dp)
    if (subagent) {
        SubAgentConfigurationWindow("思考深度", onDismiss) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 25.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ConversationThinkingMark(Modifier.size(34.dp))
                Text(choices.getOrNull(selected)?.displayName ?: effort.displayName, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = colors.text, modifier = Modifier.testTag("subagent-thinking-value").padding(top = 14.dp))
            }
            if (choices.size > 1) ConversationDepthSlider(selected, choices.lastIndex, { selected = it }, { index ->
                if (choices[index] != effort) onEffortChange(choices[index])
            })
            if (description != null) Text(description, fontSize = 9.sp, lineHeight = 15.sp, color = colors.muted, modifier = Modifier.padding(top = 15.dp))
            }
        }
        return
    }
    ConversationAgentTypography {
        ConversationAgentSheet(onDismiss, radius = 28, modifier = Modifier.heightIn(max = maxHeight)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 22.dp, bottom = 24.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("调整思考深度", fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth().testTag("chat-thinking-title").padding(bottom = 19.dp),
                    textAlign = TextAlign.Center, color = colors.text)
                val speedClick = onCycleSpeed?.takeIf { speedMode != null }
                Box(Modifier.size(48.dp).testTag("chat-thinking-speed")
                    .then(if (speedClick != null) Modifier.clickable(enabled = speedEnabled, role = Role.Button,
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClickLabel = "切换 GPT 速度", onClick = speedClick) else Modifier),
                    contentAlignment = Alignment.Center) {
                    app.daiyu.android.ui.components.ChatThinkingSpeedIcon(speedMode,
                        tint = colors.strong, modifier = Modifier.size(36.dp).testTag("chat-thinking-speed-icon"))
                }
                Text(choices.getOrNull(selected)?.displayName ?: effort.displayName, fontSize = 22.sp,
                    lineHeight = 30.sp, fontWeight = FontWeight(450), color = colors.text,
                    modifier = Modifier.testTag("chat-thinking-value").padding(top = 8.dp, bottom = 13.dp))
                if (choices.size > 1) ConversationDepthSlider(selected, choices.lastIndex, { index ->
                    selected = index
                    if (choices[index] != effort) onEffortChange(choices[index])
                }, {})
            }
        }
    }
}

/** .depth-slider final cascade: 28px pill, 7px stops, 22px inset white thumb. */
@Composable internal fun ConversationDepthSlider(selected: Int, maximum: Int, onPreview: (Int) -> Unit, onCommit: (Int) -> Unit) {
    val colors = conversationAgentColors()
    val view = LocalView.current
    val preview by rememberUpdatedState(onPreview)
    val commit by rememberUpdatedState(onCommit)
    Canvas(Modifier.testTag("thinking-depth-slider").fillMaxWidth().height(28.dp).semantics {
        contentDescription = "思考深度"
        if (selected >= 0) progressBarRangeInfo = ProgressBarRangeInfo(selected.toFloat(), 0f..maximum.toFloat(), (maximum - 1).coerceAtLeast(0))
        else stateDescription = "尚未选择"
        setProgress { value ->
            val index = value.roundToInt().coerceIn(0, maximum)
            if (index == selected) false else {
                TouchHaptics.action(view) { preview(index); commit(index) }; true
            }
        }
    }.pointerInput(maximum) {
        awaitEachGesture {
            val down = awaitFirstDown(); down.consume()
            fun index(x: Float) = (((x - 14.dp.toPx()) / (size.width - 28.dp.toPx()).coerceAtLeast(1f)).coerceIn(0f, 1f) * maximum).roundToInt()
            var current = index(down.position.x)
            preview(current); TouchHaptics.click(view)
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: break
                val next = index(change.position.x)
                if (next != current) { current = next; preview(current); TouchHaptics.click(view) }
                change.consume()
                if (!event.changes.any { it.pressed }) break
            }
            commit(current)
        }
    }) {
        val radius = 14.dp.toPx()
        val thumbX = radius + (size.width - 2 * radius) * selected.coerceAtLeast(0) / maximum.coerceAtLeast(1)
        val active = Color(if (colors.dark) 0xffb2a0c9 else 0xffa38db9)
        drawRoundRect(Color(if (colors.dark) 0xff332f3d else 0xffeee9f3), cornerRadius = CornerRadius(radius))
        if (selected >= 0) drawRoundRect(active, size = Size((thumbX + radius).coerceAtMost(size.width), size.height), cornerRadius = CornerRadius(radius))
        // CSS stardust is decorative and deterministic, never a task or capability signal.
        listOf(.08f to .22f,.18f to .78f,.28f to .19f,.40f to .76f,.56f to .25f,.68f to .72f,.81f to .21f,.93f to .69f).forEach { (x,y) ->
            drawCircle(Color.White.copy(alpha = .6f), .7.dp.toPx(), Offset(size.width*x,size.height*y))
        }
        for (i in 0..maximum) {
            drawCircle(if (i <= selected) Color(0xe6ffffff) else Color(if (colors.dark) 0xff8b8199 else 0xffb6adbf), 3.5.dp.toPx(),
                Offset(radius + (size.width - 2 * radius)*i/maximum.coerceAtLeast(1), size.height/2))
        }
        if (selected >= 0) drawCircle(Color(if (colors.dark) 0xfffcfaff else 0xffffffff), 11.dp.toPx(), Offset(thumbX,size.height/2))
        if (selected >= 0) drawCircle(Color(if (colors.dark) 0x40ffffff else 0x40a99aba), 11.dp.toPx(), Offset(thumbX,size.height/2), style = Stroke(1.dp.toPx()))
    }
}
