package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.components.MiuixScaffoldPage
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class SettingsThemeMode { SYSTEM, LIGHT, DARK }

internal data class AppearanceSettingsUi(
    val themeMode: SettingsThemeMode = SettingsThemeMode.SYSTEM,
    val interfaceScale: Float = 1f,
    val showMessageTimestamps: Boolean = false,
)

/** Presentation only: icon entries preserve the host's existing custom icon implementation. */
@Composable
internal fun AppearanceSettingsScreen(
    state: AppearanceSettingsUi,
    onThemeModeChange: (SettingsThemeMode) -> Unit,
    onInterfaceScaleChange: (Float) -> Unit,
    onOpenAppIcon: () -> Unit,
    onOpenIconAppearance: () -> Unit,
    onOpenPet: () -> Unit,
    onOpenVibration: () -> Unit,
    onMessageTimestampsChange: (Boolean) -> Unit,
    onOpenRefreshRate: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    scaleRange: ClosedFloatingPointRange<Float> =
        app.daiyu.android.data.model.MIN_INTERFACE_SCALE..app.daiyu.android.data.model.MAX_INTERFACE_SCALE,
) {
    val colors = MiuixTheme.colorScheme
    MiuixScaffoldPage(title = "外观与阅读", onBack = onBack, modifier = modifier) {
        item(key = "appearance_theme") {
            SettingsSectionTitle("主题模式")
            SettingsCard {
                listOf(
                    SettingsThemeMode.SYSTEM to "跟随系统",
                    SettingsThemeMode.LIGHT to "浅色",
                    SettingsThemeMode.DARK to "深色",
                ).forEach { (mode, title) ->
                    SettingsRadioRow(title, state.themeMode == mode) { onThemeModeChange(mode) }
                }
            }
        }
        item(key = "appearance_scale") {
            SettingsSectionTitle("界面缩放")
            SettingsCard {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("${(state.interfaceScale * 100).roundToInt()}%", style = MiuixTheme.textStyles.body1,
                        color = colors.onSurface)
                    Slider(
                        value = state.interfaceScale.coerceIn(scaleRange),
                        onValueChange = onInterfaceScaleChange,
                        valueRange = scaleRange,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.primary,
                            activeTrackColor = colors.primary,
                            inactiveTrackColor = colors.primaryContainer,
                        ),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "界面缩放" },
                    )
                }
            }
        }
        item(key = "appearance_personalization") {
            SettingsSectionTitle("个性化")
            SettingsCard {
                SettingsRow("应用图标", onClick = onOpenAppIcon, leading = {
                    DaiyuIcon(DaiyuIcons.AppIcon, contentDescription = null, tint = colors.onSurfaceVariantSummary, size = 24.dp)
                })
                SettingsRow("图标外观设置", onClick = onOpenIconAppearance, leading = {
                    DaiyuIcon(DaiyuIcons.Palette, contentDescription = null, tint = colors.onSurfaceVariantSummary, size = 24.dp)
                })
                SettingsRow("鲸鱼女仆", onClick = onOpenPet, leading = {
                    DaiyuIcon(DaiyuIcons.Pet, contentDescription = null, tint = colors.onSurfaceVariantSummary, size = 24.dp)
                })
                SettingsRow("震动设置", onClick = onOpenVibration, leading = {
                    DaiyuIcon(DaiyuIcons.Vibration, contentDescription = null, tint = colors.onSurfaceVariantSummary, size = 24.dp)
                })
            }
        }
        item(key = "appearance_reading") {
            SettingsSectionTitle("阅读与显示")
            SettingsCard {
                SettingsSwitchRow("消息时间戳", state.showMessageTimestamps, onMessageTimestampsChange)
                SettingsRow("刷新率策略", onClick = onOpenRefreshRate, leading = {
                    DaiyuIcon(DaiyuIcons.Refresh, contentDescription = null, tint = colors.onSurfaceVariantSummary, size = 24.dp)
                })
            }
        }
    }
}
