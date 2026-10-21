package app.daiyu.android.ui.components.subagent

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import app.daiyu.android.R
import app.daiyu.android.delegation.ReasoningEffort
import app.daiyu.android.ui.haptics.TouchHaptics
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun SubAgentThinkingEffortPickerDialog(
    show: Boolean,
    effort: ReasoningEffort,
    options: List<ReasoningEffort>,
    onDismiss: () -> Unit,
    onEffortChange: (ReasoningEffort) -> Unit,
    description: String? = null,
    chatStyle: Boolean = LocalConversationAgentStyle.current,
) {
    if (options.isEmpty()) return
    if (chatStyle) {
        ConversationThinkingPicker(show, effort, options, onDismiss, onEffortChange, description)
        return
    }
    val view = LocalView.current
    val selectedIndex = options.indexOf(effort).coerceAtLeast(0)
    var sliderValue by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    var lastHapticIndex by remember { mutableIntStateOf(selectedIndex) }
    val latestEffort by rememberUpdatedState(effort)
    val latestOptions by rememberUpdatedState(options)
    val latestOnEffortChange by rememberUpdatedState(onEffortChange)
    LaunchedEffect(show, selectedIndex, options) {
        if (show) {
            sliderValue = selectedIndex.toFloat()
            lastHapticIndex = selectedIndex
        }
    }
    val previewIndex = sliderValue.roundToInt().coerceIn(0, options.lastIndex)
    val preview = options[previewIndex]
    val maxIndex = (options.size - 1).toFloat().coerceAtLeast(0f)
    WindowDialog(show = show, title = stringResource(R.string.subagent_reasoning_picker_title), onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (description != null) {
                Text(text = description, style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(bottom = 8.dp))
            }
            Icon(imageVector = ImageVector.vectorResource(R.drawable.ic_atom), contentDescription = null,
                modifier = Modifier.padding(top = 6.dp).size(32.dp),
                tint = if (preview != ReasoningEffort.OFF) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface)
            Text(text = preview.displayName, style = MiuixTheme.textStyles.title3, color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
            if (options.size > 1) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Slider(value = sliderValue.coerceIn(0f, maxIndex), onValueChange = { value ->
                        val index = value.roundToInt().coerceIn(0, latestOptions.lastIndex)
                        val next = latestOptions[index]
                        if (next != latestEffort) TouchHaptics.action(view) {
                            sliderValue = index.toFloat()
                            latestOnEffortChange(next)
                        }
                    }, modifier = Modifier.fillMaxWidth(),
                        valueRange = 0f..maxIndex, steps = (options.size - 2).coerceAtLeast(0), showKeyPoints = true,
                        keyPoints = options.indices.map { it.toFloat() }, magnetThreshold = 0.18f,
                        hapticEffect = SliderDefaults.SliderHapticEffect.None)
                    Box(modifier = Modifier.matchParentSize().pointerInput(options.size) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            val width = size.width.toFloat().coerceAtLeast(1f)
                            var current = subAgentDiscreteSliderIndexForTap(down.position.x, width, latestOptions.size)
                            sliderValue = current.toFloat(); lastHapticIndex = current; TouchHaptics.click(view)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                val index = subAgentDiscreteSliderIndexForTap(change.position.x, width, latestOptions.size)
                                if (index != current) {
                                    current = index; sliderValue = current.toFloat()
                                    if (current != lastHapticIndex) { lastHapticIndex = current; TouchHaptics.click(view) }
                                }
                                change.consume()
                                if (!event.changes.any { it.pressed }) break
                            }
                            val coerced = current.coerceIn(0, latestOptions.lastIndex)
                            sliderValue = coerced.toFloat(); lastHapticIndex = coerced
                            val next = latestOptions[coerced]
                            if (next != latestEffort) latestOnEffortChange(next)
                        }
                    })
                }
            }
        }
    }
}
private fun subAgentDiscreteSliderIndexForTap(x: Float, width: Float, count: Int): Int {
    if (count <= 1 || width <= 0f) return 0
    val fraction = (x / width).coerceIn(0f, 1f)
    return (fraction * (count - 1)).roundToInt().coerceIn(0, count - 1)
}
