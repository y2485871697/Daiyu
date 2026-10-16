package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.daiyu.android.data.model.AppearancePaletteStyle
import app.daiyu.android.data.model.AppearanceAccentColor
import app.daiyu.android.data.model.AppearanceTopBarBlurStyle
import app.daiyu.android.ui.components.DaiyuChoiceSheet
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import app.daiyu.android.ui.app.DaiyuPalette
import app.daiyu.android.ui.components.MiuixScaffoldPage
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
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outline, RoundedCornerShape(14.dp))) {
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
                .background(colors.primary, CircleShape), contentAlignment = Alignment.Center) {
                DaiyuIcon(DaiyuIcons.Check, null, tint = colors.onPrimary, size = 13.dp)
            }
        }
        Text(title, style = MiuixTheme.textStyles.body2.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (selected) colors.primary else colors.onSurface)
    }
}

/** Callbacks preserve the host's existing appearance persistence and custom icon implementation. */
@Composable
internal fun AppearanceSettingsScreen(
    state: AppearanceSettings,
    onChange: (AppearanceSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    scaleRange: ClosedFloatingPointRange<Float> =
        app.daiyu.android.data.model.MIN_INTERFACE_SCALE..app.daiyu.android.data.model.MAX_INTERFACE_SCALE,
) {
    val colors = MiuixTheme.colorScheme
    var choice by remember { mutableStateOf<String?>(null) }
    val palettes = listOf("柔和", "中性", "鲜艳", "表现力", "彩虹", "水果沙拉", "单色", "忠实", "内容")
    val accents = listOf("跟随壁纸", "蓝色", "紫色", "粉色", "红色", "橙色", "黄色", "绿色", "青色")
    val blurStyles = listOf("高斯模糊", "渐进模糊")
    val blurSupported = isRuntimeShaderSupported()
    val currentChoice = choice
    if (currentChoice != null) DaiyuChoiceSheet(
        title = when (currentChoice) { "palette" -> "色彩风格"; "accent" -> "强调色"; else -> "顶栏模糊样式" },
        options = when (currentChoice) { "palette" -> palettes; "accent" -> accents; else -> blurStyles },
        onSelect = { index ->
            onChange(when (currentChoice) {
                "palette" -> state.copy(paletteStyle = AppearancePaletteStyle.entries[index], monetEnabled = true)
                "accent" -> state.copy(accentColor = AppearanceAccentColor.entries[index], monetEnabled = true)
                else -> state.copy(topBarBlurStyle = AppearanceTopBarBlurStyle.entries[index])
            })
            choice = null
        }, onDismiss = { choice = null },
    )
    MiuixScaffoldPage(title = "外观与主题", onBack = onBack, modifier = modifier) {
        item(key = "appearance_theme") {
            SettingsSectionTitle("主题模式")
            SettingsCard {
                Row(Modifier.fillMaxWidth().selectableGroup().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppearanceThemeMode.entries.forEach { mode ->
                        ThemeChoice(mode, state.themeMode == mode, { onChange(state.copy(themeMode = mode)) }, Modifier.weight(1f))
                    }
                }
                SettingsDivider()
                SettingsRow("色彩风格", summary = if (state.monetEnabled) palettes[state.paletteStyle.ordinal] else "代鱼默认配色",
                    onClick = { choice = "palette" })
                SettingsDivider()
                SettingsRow("强调色", summary = if (state.monetEnabled) accents[state.accentColor.ordinal] else "墨绿",
                    onClick = { choice = "accent" })
                SettingsDivider()
                SettingsSwitchRow("纯黑背景", state.pureBlackEnabled) { onChange(state.copy(pureBlackEnabled = it)) }
                SettingsDivider()
                SettingsRow("图标", onClick = {},
                    leading = { SettingsIcon(DaiyuIcons.AppIcon) })
            }
        }
        item(key = "appearance_scale") {
            SettingsSectionTitle("界面缩放")
            SettingsCard {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("文字与控件大小", style = MiuixTheme.textStyles.body1.copy(fontSize = 15.sp), color = colors.onSurface)
                        Text("${(state.interfaceScale * 100).roundToInt()}%", style = MiuixTheme.textStyles.body2,
                            color = colors.primary, modifier = Modifier.testTag("appearance-scale-value"))
                    }
                    Column(Modifier.fillMaxWidth().background(colors.surfaceVariant, RoundedCornerShape(14.dp)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("阅读预览", style = MiuixTheme.textStyles.body2.copy(fontSize = 11.sp), color = colors.onSurfaceVariantSummary)
                        Text("让界面刚刚好", style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium), color = colors.onSurface)
                        Text("留一点空白，专注每一次对话。", style = MiuixTheme.textStyles.body2, color = colors.onSurfaceVariantSummary)
                    }
                    Slider(value = state.interfaceScale.coerceIn(scaleRange), onValueChange = { onChange(state.copy(interfaceScale = it)) },
                        valueRange = scaleRange,
                        colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary,
                            inactiveTrackColor = colors.primaryContainer),
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
                SettingsDivider()
                if (blurSupported) {
                    SettingsSwitchRow("模糊效果", state.blurEnabled) { onChange(state.copy(blurEnabled = it)) }
                    if (state.blurEnabled) {
                        SettingsDivider()
                        SettingsRow("顶栏模糊样式", summary = blurStyles[state.topBarBlurStyle.ordinal], onClick = { choice = "blur" })
                    }
                } else {
                    SettingsRow("模糊效果", onClick = {})
                    SettingsDivider()
                    SettingsRow("顶栏模糊样式", onClick = {})
                }
                SettingsDivider()
                SettingsRow("圆形加载指示器", onClick = {})
                SettingsDivider()
                SettingsRow("仅消息生成前", onClick = {})
                SettingsDivider()
                SettingsRow("横移返回手势", onClick = {})
                SettingsDivider()
                SettingsRow("预测性返回手势", onClick = {})
            }
        }
    }
}
