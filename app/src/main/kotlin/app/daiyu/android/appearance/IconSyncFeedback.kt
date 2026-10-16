package app.daiyu.android.appearance

import app.daiyu.android.domain.appearance.IconSyncResult

internal fun iconSyncFailureMessage(result: Result<IconSyncResult>): String? {
    result.exceptionOrNull()?.let { return it.message?.takeIf(String::isNotBlank) ?: it.javaClass.simpleName }
    val receipt = result.getOrThrow()
    if (receipt.complete) return null
    return receipt.failures.map { it.detail }.filter(String::isNotBlank)
        .joinToString("；").ifBlank { "桌面图标尚未应用" }
}
