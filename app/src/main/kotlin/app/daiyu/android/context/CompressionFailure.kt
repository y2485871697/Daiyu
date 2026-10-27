package app.daiyu.android.context

internal fun compressionFailureMessage(failure: Exception): String =
    (failure as? app.daiyu.android.domain.ModelRequestException)?.userMessage
        ?: failure.message?.takeIf(String::isNotBlank) ?: "压缩失败，原始上下文保留"
