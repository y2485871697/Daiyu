package app.daiyu.android.ui.screens.settings

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import app.daiyu.android.data.model.AppearanceAccentColor
import app.daiyu.android.data.model.AppearancePaletteStyle
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.UserBubbleMode
import app.daiyu.android.ui.app.LocalAppearanceDark
import app.daiyu.android.ui.app.resolveAppearanceColors
import app.daiyu.android.ui.app.resolveAppearanceActions
import app.daiyu.android.ui.app.resolveUserBubbleColors
import app.daiyu.android.ui.app.colorContentColor
import app.daiyu.android.ui.app.resolvedPrimaryAccent
import app.daiyu.android.ui.components.UsagePalette
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.haptics.material.Slider
import app.daiyu.android.ui.haptics.selectable
import app.daiyu.android.ui.screens.assistants.AssistantPreviewButton
import app.daiyu.android.ui.screens.assistants.AssistantPreviewDialog
import app.daiyu.android.ui.screens.assistants.AssistantPreviewPalette
import app.daiyu.android.ui.screens.assistants.assistantPreviewPalette
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class AppearanceColorEditor {
    STYLE, ACCENTS, BUBBLES;
    fun merge(current: AppearanceSettings, source: AppearanceSettings): AppearanceSettings = when (this) {
        STYLE -> current.withColorStyle(source)
        ACCENTS -> current.withAccentColors(source)
        BUBBLES -> current.withUserBubbleColors(source)
    }
}

@Composable
internal fun AppearanceColorSheet(
    editor: AppearanceColorEditor,
    state: AppearanceSettings,
    onChange: (AppearanceSettings) -> Boolean,
    onDismiss: () -> Unit,
) {
    var draft by remember(editor) { mutableStateOf(state) }
    var saveFailed by remember(editor) { mutableStateOf(false) }
    val preview = editor.merge(state, draft)
    val accents = editor == AppearanceColorEditor.ACCENTS
    val buttons = assistantPreviewPalette()
    val defaults = AssistantPreviewPalette(buttons.dark)
    AssistantPreviewDialog(show = true, title = when (editor) { AppearanceColorEditor.STYLE -> "色彩风格"; AppearanceColorEditor.ACCENTS -> "强调色"; AppearanceColorEditor.BUBBLES -> "聊天气泡" },
        onDismiss = onDismiss, showCloseButton = false,
        footer = {
            AssistantPreviewButton("默认", {
                draft = editor.merge(draft, AppearanceSettings())
            }, Modifier.weight(1f).testTag("appearance-color-default"), secondary = true)
            AssistantPreviewButton("确定", {
                val saved = onChange(editor.merge(state, draft))
                saveFailed = !saved
                if (saved) onDismiss()
            }, Modifier.weight(1f).testTag("appearance-color-confirm"), primary = true)
        },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .testTag("appearance-color-content"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (saveFailed) Text("保存失败，草稿已保留，请重试。", color = MiuixTheme.colorScheme.error,
                modifier = Modifier.testTag("appearance-color-save-error"))
            AppearanceDraftPreview(preview)
            if (editor == AppearanceColorEditor.BUBBLES) {
                val selection = UsagePalette(buttons.dark, preview)
                val shape = RoundedCornerShape(12.dp)
                Column(Modifier.fillMaxWidth().selectableGroup().testTag("appearance-bubble-options"),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    UserBubbleMode.entries.forEach { mode ->
                        val selected = draft.userBubbleMode == mode
                        val label = when (mode) {
                            UserBubbleMode.OFF -> "无气泡"
                            UserBubbleMode.THEME -> "跟随主题"
                            UserBubbleMode.CUSTOM -> "自定义颜色"
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape)
                            .background(if (selected) selection.selectionContainer else Color.Transparent)
                            .testTag("settings-radio:$label")
                            .selectable(selected, role = Role.RadioButton, onClick = {
                                draft = draft.copy(userBubbleMode = mode)
                            }).padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f),
                                style = MiuixTheme.textStyles.body1.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                                color = if (selected) selection.selectionContent else buttons.text)
                            if (selected) DaiyuIcon(DaiyuIcons.Check, contentDescription = "已选择",
                                tint = selection.selectionContent, size = 20.dp)
                        }
                    }
                }
                if (draft.userBubbleMode == UserBubbleMode.CUSTOM) {
                    AppearanceColorPicker("用户气泡", draft.userBubbleColor?.let(::Color)
                        ?: resolveUserBubbleColors(draft, buttons.dark).container) {
                        draft = draft.copy(userBubbleColor = it.toArgb())
                    }
                }
            } else if (accents) {
                AppearanceColorPicker("主强调色", draft.resolvedPrimaryAccent ?: defaults.primary) {
                    draft = draft.copy(accentColor = AppearanceAccentColor.SYSTEM, primaryAccentColor = it.toArgb())
                }
                AppearanceColorPicker("次强调色", draft.secondaryAccentColor?.let(::Color) ?: defaults.secondary) {
                    draft = draft.copy(secondaryAccentColor = it.toArgb())
                }
            } else {
                AppearanceColorPicker("界面色彩", Color(draft.paletteSeedColor ?: 0xFF8E7C9E.toInt())) {
                    draft = draft.copy(paletteSeedColor = it.toArgb())
                }
                AppearancePaletteStyle.entries.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { style ->
                            val selected = draft.paletteStyle == style
                            val label = when (style) {
                                AppearancePaletteStyle.TONAL_SPOT -> "柔和"
                                AppearancePaletteStyle.NEUTRAL -> "中性"
                                AppearancePaletteStyle.VIBRANT -> "鲜艳"
                                AppearancePaletteStyle.EXPRESSIVE -> "灵动"
                                AppearancePaletteStyle.RAINBOW -> "彩虹"
                                AppearancePaletteStyle.FRUIT_SALAD -> "缤纷"
                                AppearancePaletteStyle.MONOCHROME -> "灰阶"
                                AppearancePaletteStyle.FIDELITY -> "原色"
                                AppearancePaletteStyle.CONTENT -> "自然"
                            }
                            Box(Modifier.weight(1f).height(44.dp)
                                .background(if (selected) buttons.soft else Color.Transparent, CircleShape)
                                .border(1.dp, if (selected) buttons.primary else buttons.line, CircleShape)
                                .testTag("appearance-style:${style.persistedValue}")
                                .selectable(selected, role = Role.RadioButton, onClick = {
                                    draft = draft.copy(paletteStyle = style,
                                        paletteSeedColor = draft.paletteSeedColor ?: 0xFF8E7C9E.toInt())
                                }), contentAlignment = Alignment.Center) { Text(label, color = buttons.text) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceDraftPreview(draft: AppearanceSettings) {
    val dark = LocalAppearanceDark.current
    val colors = resolveAppearanceColors(draft, dark)
    val actions = resolveAppearanceActions(draft, dark)
    val bubble = resolveUserBubbleColors(draft, dark)
    val selection = UsagePalette(dark, draft)
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().background(colors.surface, shape)
        .border(1.dp, colors.outline, shape).padding(12.dp).testTag("appearance-draft-preview"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("预览", color = colors.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("选中项目", Modifier.weight(1f).testTag("appearance-preview-selection")
                .background(selection.selectionContainer, shape).padding(10.dp),
                color = selection.selectionContent)
            Text("普通容器", Modifier.weight(1f).background(colors.surfaceContainer, shape).padding(10.dp),
                color = colors.onSurface)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("主操作", Modifier.background(actions.primary, CircleShape).padding(10.dp), color = actions.onPrimary)
            Text("次操作", Modifier.background(actions.secondaryContainer, CircleShape).padding(10.dp), color = actions.onSecondary)
            val track = draft.resolvedPrimaryAccent ?: Color(0xFF82728F)
            Box(Modifier.size(38.dp, 24.dp).background(track, CircleShape).padding(3.dp), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.size(18.dp).background(draft.resolvedPrimaryAccent?.let(::colorContentColor) ?: Color.White, CircleShape))
            }
        }
        Text("我的消息", Modifier.align(Alignment.End).background(bubble.container, shape).padding(10.dp), color = bubble.content)
    }
}

@Composable
private fun AppearanceColorPicker(label: String, color: Color, onChange: (Color) -> Unit) {
    val colors = MiuixTheme.colorScheme
    var selection by remember(label) { mutableStateOf(AppearanceColorSelection.fromColor(color)) }
    val synced = selection.synchronize(color)
    if (synced != selection) selection = synced
    val changeColor by rememberUpdatedState(onChange)
    fun update(next: AppearanceColorSelection) {
        selection = next
        changeColor(next.color)
    }
    fun select(saturation: Float, value: Float) {
        update(selection.selectSaturationValue(saturation, value))
    }
    val swatches = listOf(0xFF8E7C9E, 0xFF407FCE, 0xFFBA668F, 0xFFBD5964,
        0xFFC68046, 0xFFC1A340, 0xFF56896C, 0xFF448D91)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = colors.onSurface)
            Box(Modifier.size(26.dp).background(color, CircleShape).border(1.dp, colors.outline, CircleShape))
        }
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.SpaceBetween) {
            swatches.forEach { value ->
                val swatch = Color(value)
                Box(Modifier.weight(1f).height(44.dp)
                    .testTag("appearance-swatch:$label:$value")
                    .semantics { contentDescription = "$label #${value.toString(16).takeLast(6)}" }
                    .selectable(color.toArgb() == swatch.toArgb(), role = Role.RadioButton,
                        onClick = { update(AppearanceColorSelection.fromColor(swatch)) }),
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.size(26.dp).background(swatch, CircleShape)
                        .border(if (color.toArgb() == swatch.toArgb()) 3.dp else 1.dp,
                            if (color.toArgb() == swatch.toArgb()) colors.onSurface else colors.outline, CircleShape))
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(92.dp).testTag("appearance-color-pad:$label")
            .semantics {
                contentDescription = "$label 饱和度与明度"
                customActions = listOf(
                    CustomAccessibilityAction("增加饱和度") { select(selection.saturation + .05f, selection.value); true },
                    CustomAccessibilityAction("减少饱和度") { select(selection.saturation - .05f, selection.value); true },
                    CustomAccessibilityAction("增加明度") { select(selection.saturation, selection.value + .05f); true },
                    CustomAccessibilityAction("减少明度") { select(selection.saturation, selection.value - .05f); true },
                )
            }
            .pointerInput(label) {
                detectTapGestures { point -> select(point.x / size.width, 1f - point.y / size.height) }
            }
            .pointerInput(label) {
                detectDragGestures(onDragStart = { point -> select(point.x / size.width, 1f - point.y / size.height) }) { change, _ ->
                    change.consume()
                    select(change.position.x / size.width, 1f - change.position.y / size.height)
                }
            }) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(selection.hue, 1f, 1f))))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val cursor = Offset(selection.saturation * size.width, (1f - selection.value) * size.height)
            drawCircle(Color.Black, 7.dp.toPx(), cursor, style = Stroke(3.dp.toPx()))
            drawCircle(Color.White, 7.dp.toPx(), cursor, style = Stroke(1.5.dp.toPx()))
        }
        val rainbow = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
        val trackColors = if (LocalLayoutDirection.current == LayoutDirection.Rtl) rainbow.reversed() else rainbow
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            Canvas(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(12.dp)
                .align(Alignment.Center).clip(CircleShape).testTag("appearance-color-hue-track:$label")) {
                drawRect(Brush.horizontalGradient(trackColors))
            }
            Slider(value = selection.hue, onValueChange = { update(selection.selectHue(it)) },
                valueRange = 0f..360f,
                colors = SliderDefaults.colors(thumbColor = colors.onSurface,
                    activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().align(Alignment.Center).testTag("appearance-color-hue:$label")
                    .semantics { contentDescription = "$label 色相" })
        }
    }
}
