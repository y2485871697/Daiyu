package app.daiyu.android.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.daiyu.android.R
import app.daiyu.android.ui.navigation.LocalPageBackToolbarReturn
import top.yukonga.miuix.kmp.basic.Icon
import app.daiyu.android.ui.haptics.miuix.IconButton

/**
 * 二级页面统一返回按钮，保持图标、语义、RTL 方向与点击区域一致。
 */
@Composable
fun MiuixBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pageReturn = LocalPageBackToolbarReturn.current
    IconButton(
        onClick = {
            if (pageReturn == null) onClick() else pageReturn(onClick)
        },
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(R.string.action_back),
        )
    }
}
