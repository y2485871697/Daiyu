package app.daiyu.android.ui.components.subagent

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.delegation.ReasoningEffort
import app.daiyu.android.ui.haptics.TouchHaptics
import kotlin.math.roundToInt

@Composable internal fun ConversationThinkingMark(modifier: Modifier = Modifier) {
    androidx.compose.material3.Icon(app.daiyu.android.ui.icons.ChatPreviewIcons.Thought,
        contentDescription = null, modifier = modifier, tint = conversationAgentColors().accent)
}

@Composable internal fun ConversationThinkingPicker(show: Boolean, effort: ReasoningEffort, options: List<ReasoningEffort>,
    onDismiss: () -> Unit, onEffortChange: (ReasoningEffort) -> Unit, description: String?) {
    if (!show) return
    val colors = conversationAgentColors()
    val subagent = LocalConversationAgentStyle.current
    var selected by remember(show, effort, options) { mutableIntStateOf(options.indexOf(effort).coerceAtLeast(0)) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp - 50).coerceAtLeast(160).dp
    if (subagent) {
        SubAgentConfigurationWindow("思考深度", onDismiss) {
            Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 25.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ConversationThinkingMark(Modifier.size(34.dp))
                Text(options[selected].displayName, fontSize = 19.8.sp, fontWeight = FontWeight.Medium, color = colors.text, modifier = Modifier.padding(top = 14.dp))
            }
            if (options.size > 1) ConversationDepthSlider(selected, options.lastIndex, { selected = it }, { index ->
                if (options[index] != effort) onEffortChange(options[index])
            })
            if (description != null) Text(description, fontSize = 8.1.sp, lineHeight = 13.5.sp, color = colors.muted, modifier = Modifier.padding(top = 15.dp))
            androidx.compose.material3.HorizontalDivider(Modifier.padding(top = 10.dp), color = colors.line)
            ConversationAgentButton("关闭", modifier = Modifier.fillMaxWidth().padding(top = 10.dp), onClick = onDismiss)
        }
        return
    }
    ConversationAgentTypography {
        ConversationAgentSheet(onDismiss, radius = if (subagent) 25 else 28, modifier = Modifier.heightIn(max = maxHeight)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 22.dp, bottom = 24.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("调整思考深度", fontSize = 14.4.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 19.dp), color = colors.text)
                ConversationThinkingMark(Modifier.size(if (subagent) 34.dp else 30.dp))
                Text(options[selected].displayName, fontSize = if (subagent) 19.8.sp else 18.sp,
                    lineHeight = 25.2.sp, fontWeight = FontWeight.Normal, color = colors.text,
                    modifier = Modifier.padding(top = if (subagent) 14.dp else 8.dp, bottom = if (subagent) 25.dp else 18.dp))
                if (options.size > 1) ConversationDepthSlider(selected, options.lastIndex, { selected = it }, { index ->
                    if (options[index] != effort) onEffortChange(options[index])
                })
                // Real capability/permission explanation is retained; no preview endpoint claims.
                if (description != null) Text(description, fontSize = 8.1.sp, lineHeight = 13.5.sp, color = colors.muted,
                    modifier = Modifier.padding(top = 12.dp))
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
    Canvas(Modifier.fillMaxWidth().height(28.dp).semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(selected.toFloat(), 0f..maximum.toFloat(), (maximum - 1).coerceAtLeast(0))
        setProgress { value -> val index = value.roundToInt().coerceIn(0, maximum); preview(index); commit(index); true }
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
        val thumbX = radius + (size.width - 2 * radius) * selected / maximum.coerceAtLeast(1)
        val active = Color(if (colors.dark) 0xffb2a0c9 else 0xffa38db9)
        drawRoundRect(Color(if (colors.dark) 0xff332f3d else 0xffeee9f3), cornerRadius = CornerRadius(radius))
        drawRoundRect(active, size = Size((thumbX + radius).coerceAtMost(size.width), size.height), cornerRadius = CornerRadius(radius))
        // CSS stardust is decorative and deterministic, never a task or capability signal.
        listOf(.08f to .22f,.18f to .78f,.28f to .19f,.40f to .76f,.56f to .25f,.68f to .72f,.81f to .21f,.93f to .69f).forEach { (x,y) ->
            drawCircle(Color.White.copy(alpha = .6f), .7.dp.toPx(), Offset(size.width*x,size.height*y))
        }
        for (i in 0..maximum) {
            drawCircle(if (i <= selected) Color(0xe6ffffff) else Color(if (colors.dark) 0xff8b8199 else 0xffb6adbf), 3.5.dp.toPx(),
                Offset(radius + (size.width - 2 * radius)*i/maximum.coerceAtLeast(1), size.height/2))
        }
        drawCircle(Color(if (colors.dark) 0xfffcfaff else 0xffffffff), 11.dp.toPx(), Offset(thumbX,size.height/2))
        drawCircle(Color(if (colors.dark) 0x40ffffff else 0x40a99aba), 11.dp.toPx(), Offset(thumbX,size.height/2), style = Stroke(1.dp.toPx()))
    }
}
