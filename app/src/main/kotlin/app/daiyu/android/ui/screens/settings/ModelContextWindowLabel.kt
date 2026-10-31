package app.daiyu.android.ui.screens.settings

import java.math.BigDecimal

internal fun modelContextWindowLabel(tokens: Int?): String = when {
    tokens == null -> "上下文未知"
    tokens >= 1_000_000 -> "${BigDecimal.valueOf(tokens.toLong(), 6).stripTrailingZeros().toPlainString()}M 上下文"
    tokens % 1000 == 0 -> "${tokens / 1000}K 上下文"
    else -> "$tokens 上下文"
}
