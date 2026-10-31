package app.daiyu.android.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.layout.WidePageContent
import app.daiyu.android.ui.layout.horizontalCutoutPadding
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 二级列表页的统一骨架：手机折叠大标题、宽屏固定小标题、顶栏毛玻璃、横屏安全区、
 * 宽屏内容居中、滚动边界触感与越界回弹均由此处统一提供。
 */
@Composable
fun MiuixScaffoldPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    topBarModifier: Modifier = Modifier,
    listModifier: Modifier = Modifier,
    editorScrollIsolation: EditorScrollIsolation? = null,
    scrollBehavior: ScrollBehavior = MiuixScrollBehavior(),
    containerColor: Color = MiuixTheme.colorScheme.surface,
    compactTitle: Boolean = false,
    bottomSpacerMinHeight: Dp = 0.dp,
    content: LazyListScope.() -> Unit,
) {
    app.daiyu.android.diagnostics.ObserveFrameContext("screen", title)
    val pageScrollConnection = remember(scrollBehavior, editorScrollIsolation) {
        editorScrollIsolation?.gate(scrollBehavior.nestedScrollConnection) ?: scrollBehavior.nestedScrollConnection
    }
    val editorOwnsGesture = editorScrollIsolation?.editorOwnsGesture == true
    val pageGestures = editorScrollIsolation?.let { Modifier.editorPageGestures(it) } ?: Modifier
    val pageScrollEffects = if (editorOwnsGesture) Modifier else Modifier.scrollEndHaptic().overScrollVertical()
    val backdrop = rememberTopBarBackdrop(containerColor)
    val topBarColor = topBarContainerColor(backdrop, containerColor)

    WithoutPressRipple {
    Scaffold(
        containerColor = containerColor,
        modifier = modifier.fillMaxSize().then(pageGestures),
        topBar = {
            TopBarBackdrop(backdrop, containerColor) {
                AdaptiveTopAppBar(
                    title = title,
                    compactTitle = compactTitle,
                    modifier = topBarModifier.testTag("page-topbar:$title"),
                    color = topBarColor,
                    navigationIcon = { MiuixBackButton(onClick = onBack) },
                    actions = actions,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { innerPadding ->
        WidePageContent { sidePadding ->
            // 保留 MiuixTheme 注入的默认越界工厂，让短内容页也能回弹到顶栏采样区。
            LazyColumn(
                state = listState,
                modifier = listModifier.testTag("page:$title")
                    .fillMaxSize()
                    .horizontalCutoutPadding()
                    .captureForTopBar(backdrop)
                    .then(pageScrollEffects)
                    .nestedScroll(pageScrollConnection),
                contentPadding = PaddingValues(
                    start = sidePadding,
                    top = innerPadding.calculateTopPadding(),
                    end = sidePadding,
                ),
            ) {
                content()
                item(key = "bottom_spacer") {
                    MiuixPageBottomSpacer(Modifier.heightIn(min = bottomSpacerMinHeight))
                }
            }
        }
    }
    }
}

/**
 * 自定义内容二级页的低层骨架。调用方负责把顶部 padding、横向安全区与 nested scroll
 * 接入自己的内容；[sidePadding] 用于在宽屏限制实际内容宽度，滚动容器本身仍应保持全宽。
 */
@Composable
fun MiuixScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    containerColor: Color = MiuixTheme.colorScheme.surface,
    compactTitle: Boolean = false,
    content: @Composable (
        paddingValues: PaddingValues,
        scrollBehavior: ScrollBehavior,
        sidePadding: Dp,
    ) -> Unit,
) {
    app.daiyu.android.diagnostics.ObserveFrameContext("screen", title)
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberTopBarBackdrop(containerColor)
    val topBarColor = topBarContainerColor(backdrop, containerColor)

    Scaffold(
        containerColor = containerColor,
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopBarBackdrop(backdrop, containerColor) {
                AdaptiveTopAppBar(
                    title = title,
                    compactTitle = compactTitle,
                    color = topBarColor,
                    navigationIcon = { MiuixBackButton(onClick = onBack) },
                    actions = actions,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().captureForTopBar(backdrop)) {
            WidePageContent { sidePadding ->
                content(paddingValues, scrollBehavior, sidePadding)
            }
        }
    }
}

@Composable
fun MiuixPageBottomSpacer(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .height(24.dp)
            .navigationBarsPadding(),
    )
}
