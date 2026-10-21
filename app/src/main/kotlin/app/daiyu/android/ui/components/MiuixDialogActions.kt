package app.daiyu.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick as semanticsClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalView
import app.daiyu.android.ui.haptics.TouchHaptics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import app.daiyu.android.ui.haptics.miuix.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import app.daiyu.android.R

/**
 * 弹窗底部按钮行：取消在左、确认在右，平分整行。
 * 遵循 Miuix 官方示例的双按钮惯例，全 App 弹窗统一使用。
 *
 * @param confirmText 确认按钮文案。
 * @param onCancel 取消回调。
 * @param onConfirm 确认回调。
 * @param modifier 根修饰符。
 * @param cancelText 取消按钮文案。
 * @param cancelEnabled 取消按钮是否可用。
 * @param confirmEnabled 确认按钮是否可用。
 * @param destructive 确认是否为破坏性操作（使用 error 配色）。
 */
@Composable
fun MiuixDialogActions(
    confirmText: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    cancelText: String? = null,
    cancelEnabled: Boolean = true,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
) {
    val view = LocalView.current
    val cancelClick = {
        if (cancelEnabled) {
            TouchHaptics.click(view)
            onCancel()
        }
        cancelEnabled
    }
    val confirmClick = {
        if (confirmEnabled) {
            TouchHaptics.click(view)
            onConfirm()
        }
        confirmEnabled
    }
    Row(
        modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(
            text = cancelText ?: stringResource(R.string.action_cancel),
            onClick = { cancelClick() },
            enabled = cancelEnabled,
            modifier = Modifier.testTag("dialog-cancel").weight(1f).fillMaxHeight().semantics {
                semanticsClick { cancelClick() }
            },
        )
        TextButton(
            text = confirmText,
            onClick = { confirmClick() },
            enabled = confirmEnabled,
            modifier = Modifier.testTag("dialog-confirm").weight(1f).fillMaxHeight().semantics {
                semanticsClick { confirmClick() }
            },
            colors = if (destructive) {
                ButtonDefaults.textButtonColorsPrimary(
                    color = MiuixTheme.colorScheme.error,
                    textColor = MiuixTheme.colorScheme.onError,
                )
            } else {
                ButtonDefaults.textButtonColorsPrimary()
            },
        )
    }
}
