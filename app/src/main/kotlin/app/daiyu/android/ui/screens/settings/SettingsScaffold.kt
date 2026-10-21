package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import app.daiyu.android.ui.components.MiuixScaffold
import app.daiyu.android.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.theme.MiuixTheme

// Settings components are also used by terminal and task screens; keep the new style page-scoped.
internal val LocalSettingsPageStyle = staticCompositionLocalOf { false }

@Composable
internal fun settingsPageBackground(): Color = if (MiuixTheme.colorScheme.background == Color.Black) Color.Black
    else MiuixTheme.colorScheme.surfaceContainerHigh

@Composable
internal fun SettingsScaffoldPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    CompositionLocalProvider(LocalSettingsPageStyle provides true) {
        MiuixScaffoldPage(title = title, onBack = onBack, modifier = modifier, actions = actions, listState = listState,
            containerColor = settingsPageBackground(),
            listModifier = Modifier.background(settingsPageBackground()), content = content)
    }
}

@Composable
internal fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues, ScrollBehavior, Dp) -> Unit,
) {
    CompositionLocalProvider(LocalSettingsPageStyle provides true) {
        MiuixScaffold(title = title, onBack = onBack, modifier = modifier, containerColor = settingsPageBackground()) { padding, scrollBehavior, sidePadding ->
            Box(Modifier.fillMaxSize().background(settingsPageBackground())) {
                content(padding, scrollBehavior, sidePadding)
            }
        }
    }
}
