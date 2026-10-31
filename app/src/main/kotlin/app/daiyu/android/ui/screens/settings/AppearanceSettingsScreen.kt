package app.daiyu.android.ui.screens.settings

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import app.daiyu.android.ui.haptics.selectable
import androidx.compose.foundation.selection.selectableGroup
import app.daiyu.android.ui.haptics.material.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.data.model.AppearanceTopBarBlurStyle
import app.daiyu.android.ui.screens.chat.conversation.ChatSelectionSheet
import app.daiyu.android.ui.screens.assistants.AssistantPreviewButton
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import app.daiyu.android.ui.app.DaiyuPalette
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
private fun ThemeChoice(mode: AppearanceThemeMode, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MiuixTheme.colorScheme
    val title = when (mode) {
        AppearanceThemeMode.SYSTEM -> "跟随系统"
        AppearanceThemeMode.LIGHT -> "浅色"
        AppearanceThemeMode.DARK -> "深色"
    }
    val preview = if (mode == AppearanceThemeMode.DARK) DaiyuPalette.dark else DaiyuPalette.light
    Column(modifier.selectable(selected, role = Role.RadioButton, onClick = onClick)
        .testTag("settings-radio:$title").padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().height(104.dp)
            .background(preview.background, RoundedCornerShape(14.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) DaiyuPalette.selectionAccent else colors.outline, RoundedCornerShape(14.dp))) {
            if (mode == AppearanceThemeMode.SYSTEM) Box(Modifier.align(Alignment.CenterEnd)
                .fillMaxWidth(0.5f).height(102.dp)
                .background(DaiyuPalette.dark.background, RoundedCornerShape(topEnd = 13.dp, bottomEnd = 13.dp)))
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.size(16.dp).background(preview.primary, CircleShape))
                Box(Modifier.fillMaxWidth(0.72f).height(5.dp).background(preview.outline, CircleShape))
                Box(Modifier.fillMaxWidth().height(20.dp).background(preview.primaryContainer, RoundedCornerShape(7.dp)))
                Box(Modifier.fillMaxWidth(0.54f).height(5.dp).background(preview.outline, CircleShape))
            }
            if (selected) Box(Modifier.align(Alignment.BottomEnd).padding(5.dp).size(20.dp)
                .background(DaiyuPalette.selectionAccent, CircleShape), contentAlignment = Alignment.Center) {
                DaiyuIcon(DaiyuIcons.Check, null, tint = colors.onPrimary, size = 13.dp)
            }
        }
        Text(title, style = MiuixTheme.textStyles.body2.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (selected) colors.onPrimaryContainer else colors.onSurface)
    }
}

/** Callbacks preserve the host's existing appearance persistence and custom icon implementation. */
@Composable
internal fun AppearanceSettingsScreen(
    state: AppearanceSettings,
    onChange: (AppearanceSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    iconContent: @Composable (expanded: Boolean) -> Unit = {},
    scaleRange: ClosedFloatingPointRange<Float> =
        app.daiyu.android.data.model.MIN_INTERFACE_SCALE..app.daiyu.android.data.model.MAX_INTERFACE_SCALE,
) {
    val colors = MiuixTheme.colorScheme
    var scaleDraft by remember(state.interfaceScale) { mutableStateOf(state.interfaceScale) }
    var iconExpanded by rememberSaveable { mutableStateOf(false) }
    var choosingBlur by remember { mutableStateOf(false) }
    val blurStyles = listOf("高斯模糊", "渐进模糊")
    val blurSupported = isRuntimeShaderSupported()
    if (choosingBlur) ChatSelectionSheet(
        title = "顶栏模糊样式", onDismiss = { choosingBlur = false },
        showCloseButton = false, titleFontSize = 20.sp,
        footer = {
            val finishDismiss = app.daiyu.android.ui.components.rememberBottomSheetAction()
            AssistantPreviewButton("取消", { finishDismiss { choosingBlur = false } },
                Modifier.fillMaxWidth().testTag("choice-sheet-cancel"))
        },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 440.dp)
            .verticalScroll(rememberScrollState()).selectableGroup()) {
            blurStyles.forEachIndexed { index, title ->
                SettingsRadioRow(title, selected = state.topBarBlurStyle.ordinal == index) {
                    onChange(state.copy(topBarBlurStyle = AppearanceTopBarBlurStyle.entries[index]))
                }
            }
        }
    }
    SettingsScaffoldPage(title = "外观与主题", onBack = onBack, modifier = modifier) {
        item(key = "appearance_theme") {
            SettingsSectionTitle("主题模式")
            SettingsCard {
                Row(Modifier.fillMaxWidth().selectableGroup().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppearanceThemeMode.entries.forEach { mode ->
                        ThemeChoice(mode, state.themeMode == mode, { onChange(state.copy(themeMode = mode)) }, Modifier.weight(1f))
                    }
                }
            }
        }
        item(key = "appearance_colors") {
            SettingsSectionTitle("色彩与背景")
            SettingsCard {
                SettingsSwitchRow("纯黑背景", state.pureBlackEnabled) { onChange(state.copy(pureBlackEnabled = it)) }
            }
        }
        item(key = "appearance_icon") {
            SettingsSectionTitle("应用图标")
            SettingsCard {
                SettingsRow("图标", onClick = { iconExpanded = !iconExpanded },
                    modifier = Modifier.semantics { stateDescription = if (iconExpanded) "已展开" else "已收起" },
                    trailing = { DaiyuIcon(DaiyuIcons.ChevronDown, null,
                        modifier = Modifier.settingsDisclosureRotation(iconExpanded, expandedDegrees = 180f),
                        tint = colors.onSurfaceVariantSummary, size = 20.dp) })
                iconContent(iconExpanded)
            }
        }
        item(key = "appearance_scale") {
            SettingsSectionTitle("界面缩放")
            SettingsCard {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("文字与控件大小", style = MiuixTheme.textStyles.body1.copy(fontSize = 15.sp), color = colors.onSurface)
                        Text("${(scaleDraft * 100).roundToInt()}%", style = MiuixTheme.textStyles.body2,
                            color = colors.onPrimaryContainer, modifier = Modifier.testTag("appearance-scale-value"))
                    }
                    Column(Modifier.fillMaxWidth().background(colors.surfaceVariant, RoundedCornerShape(14.dp)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("阅读预览", style = MiuixTheme.textStyles.body2.copy(fontSize = 11.sp), color = colors.onSurfaceVariantSummary)
                        Text("让界面刚刚好", style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium), color = colors.onSurface)
                        Text("留一点空白，专注每一次对话。", style = MiuixTheme.textStyles.body2, color = colors.onSurfaceVariantSummary)
                    }
                    Slider(value = scaleDraft.coerceIn(scaleRange), onValueChange = { scaleDraft = it },
                        onValueChangeFinished = {
                            val requested = app.daiyu.android.data.model.normalizeInterfaceScale(
                                (scaleDraft * 100).roundToInt() / 100f)
                            scaleDraft = state.interfaceScale
                            onChange(state.copy(interfaceScale = requested))
                        },
                        valueRange = scaleRange,
                        colors = SliderDefaults.colors(thumbColor = DaiyuPalette.switchTrack, activeTrackColor = DaiyuPalette.switchTrack,
                            inactiveTrackColor = DaiyuPalette.switchTrack.copy(alpha = .15f)),
                        modifier = Modifier.fillMaxWidth().testTag("appearance-scale").semantics { contentDescription = "界面缩放" })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${(scaleRange.start * 100).roundToInt()}%", style = MiuixTheme.textStyles.body2, color = colors.onSurfaceVariantSummary)
                        Text("${(scaleRange.endInclusive * 100).roundToInt()}%", style = MiuixTheme.textStyles.body2, color = colors.onSurfaceVariantSummary)
                    }
                }
            }
        }
        item(key = "appearance_reading") {
            SettingsSectionTitle("界面与效果")
            SettingsCard {
                SettingsSwitchRow("消息时间戳", state.messageTimestampsEnabled) { onChange(state.copy(messageTimestampsEnabled = it)) }
                SettingsSwitchRow("模糊效果", state.blurEnabled && blurSupported, enabled = blurSupported) {
                    onChange(state.copy(blurEnabled = it))
                }
                if (blurSupported) {
                    if (state.blurEnabled) {
                        SettingsRow("顶栏模糊样式", summary = blurStyles[state.topBarBlurStyle.ordinal], onClick = { choosingBlur = true })
                    }
                }
            }
        }
        item(key = "appearance_gestures") {
            SettingsSectionTitle("导航与手势")
            SettingsCard {
                SettingsSwitchRow("横移返回手势", state.swipeDismissEnabled) {
                    onChange(state.copy(swipeDismissEnabled = it))
                }
                SettingsSwitchRow("预测性返回手势", state.predictiveBackEnabled) {
                    onChange(state.copy(predictiveBackEnabled = it))
                }
            }
        }
    }
}
