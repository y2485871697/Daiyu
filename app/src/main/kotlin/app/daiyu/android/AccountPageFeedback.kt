package app.daiyu.android

import app.daiyu.android.voice.account.PersonalVoiceQueryFailure
import app.daiyu.android.voice.account.VoiceCatalogFailure
import app.daiyu.android.voice.account.AccountTransportException
import kotlinx.coroutines.CancellationException

/** A page visit owns its notices; returning to the same page does not revive old work. */
internal data class AccountPageFeedback(val visit: Long = 0, val text: String = "") {
    fun nextVisit() = AccountPageFeedback(visit + 1)
    fun clear() = copy(text = "")
    fun report(origin: Long, message: String) = if (origin == visit) copy(text = message) else this
}

internal fun voiceAccountFailure(failure: Exception, fallback: String?): String = when (failure) {
    is PersonalVoiceQueryFailure -> failure.safeMessage
    is VoiceCatalogFailure -> failure.safeMessage
    is AccountTransportException -> failure.safeMessage
    else -> fallback ?: "操作未完成，请核对账户、权限或本机存储后重试。"
}

internal suspend fun voiceNameSyncFailure(sync: suspend () -> Unit): String? = try {
    sync()
    null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    voiceAccountFailure(failure, "名称操作结果未确认，请稍后重试名称同步。")
}
