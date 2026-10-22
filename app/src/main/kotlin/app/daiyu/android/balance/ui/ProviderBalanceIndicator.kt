package app.daiyu.android.balance.ui

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import app.daiyu.android.ui.icons.MoneyBag02
import androidx.compose.material3.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.daiyu.android.balance.BalanceState
import app.daiyu.android.balance.formatBalanceDisplay

/** Frozen static policy: only successful money occupies space; never status/error/refresh text. */
internal fun hasBalanceIndicatorContent(state: BalanceState?): Boolean = state?.successful != null

/**
 * Read-only, neutral, single-line amount for the composer and selectors.
 * Last success survives refresh/failure. No click handler, stale tint, spinner or status label.
 * The composer owns the preserved one-shot drawing-layer change animations.
 */
@Composable
fun ProviderBalanceIndicator(
    state: BalanceState?,
    modifier: Modifier = Modifier,
    animateChanges: Boolean = false,
    textStyle: TextStyle = MiuixTheme.textStyles.footnote1,
) {
    val money = state?.successful?.money ?: return
    val amount = formatBalanceDisplay(money.amount.toPlainString())
    // Reset animation ownership on account/configuration changes, not just on numeric seq.
    key(state.revision) {
        if (animateChanges) AnimatedProviderBalanceIndicator(state, amount, modifier, textStyle)
        else ProviderBalanceAmount(amount, modifier, textStyle)
    }
}

@Composable
internal fun ProviderBalanceAmount(amount: String, modifier: Modifier = Modifier,
    textStyle: TextStyle = MiuixTheme.textStyles.footnote1) {
    val neutral = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(MoneyBag02, contentDescription = null,
            modifier = Modifier.padding(end = 4.dp).size(12.dp), tint = neutral)
        Text(amount, modifier = Modifier.width(IntrinsicSize.Max), style = textStyle, color = neutral,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
