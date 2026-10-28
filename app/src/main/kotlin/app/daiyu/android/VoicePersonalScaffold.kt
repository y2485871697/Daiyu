package app.daiyu.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.haptics.miuix.IconButton
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.screens.settings.SettingsCard
import app.daiyu.android.ui.screens.settings.SettingsScaffoldPage
import app.daiyu.android.ui.screens.settings.SettingsRow
import app.daiyu.android.ui.screens.assistants.assistantPreviewPalette

@Composable
internal fun VoicePersonalScaffold(
    title: String,
    onBack: () -> Unit,
    enabled: Boolean,
    onAdd: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val colors = assistantPreviewPalette()
    Box(Modifier.fillMaxSize().testTag("voice-account")) {
        SettingsScaffoldPage(title, onBack, compactTitle = true, actions = {
            if (onSettings != null) IconButton(onClick = onSettings, enabled = enabled,
                modifier = Modifier.testTag("voice-account-menu")) {
                DaiyuIcon(DaiyuIcons.Settings, "声音设置")
            }
        }) {
            content()
            if (onAdd != null) item { Spacer(Modifier.height(80.dp)) }
        }
        if (onAdd != null) IconButton(onClick = onAdd, enabled = enabled,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
                .padding(24.dp).testTag("voice-account-add"),
            minHeight = 56.dp, minWidth = 56.dp, cornerRadius = 18.dp,
            backgroundColor = colors.primary) {
            DaiyuIcon(DaiyuIcons.Add, "添加声音", tint = colors.onPrimary)
        }
    }
}

@Composable
internal fun VoicePersonalEmptyPage(isCurrent: () -> Boolean, onConfigure: () -> Unit, onBack: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    VoicePersonalScaffold("我的声音", onBack, enabled = isCurrent(),
        onAdd = { if (isCurrent()) onConfigure() }, onSettings = { if (isCurrent()) menu = true }) {
        item {
            SettingsCard(Modifier.testTag("voice-account-empty")) {
                SettingsRow("还没有声音", summary = "先配置账户，再添加自己的声音。")
                SettingsRow("配置账户", tag = "voice-account-configure", onClick = {
                    if (isCurrent()) onConfigure()
                })
            }
        }
    }
    if (menu) VoiceAccountSettingsSheet(
        onSelect = { if (isCurrent() && it == "ACCOUNT") { menu = false; onConfigure() } },
        onDismiss = { menu = false }, isEnabled = { it == "ACCOUNT" && isCurrent() })
}
