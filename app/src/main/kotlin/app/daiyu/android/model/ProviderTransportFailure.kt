package app.daiyu.android.model

import app.daiyu.android.domain.ModelFailure
import app.daiyu.android.domain.ModelRequestException
import java.io.Closeable

/** Keep provider-owned exception chains out of transport results, not caller failures. */
internal fun providerTransportFailure(failure: Exception, configuringRequest: Boolean): Exception = when {
    failure is ModelRequestException -> ModelRequestException(failure.failure)
    configuringRequest && (failure is IllegalArgumentException || failure is IllegalStateException || failure is SecurityException) ->
        ModelRequestException(ModelFailure.REQUEST)
    failure is SecurityException -> ModelRequestException(ModelFailure.PERMISSION)
    failure is java.net.SocketTimeoutException -> ModelRequestException(ModelFailure.TIMEOUT)
    failure is javax.net.ssl.SSLException -> ModelRequestException(ModelFailure.TLS)
    failure is java.io.IOException -> ModelRequestException(ModelFailure.NETWORK)
    failure is org.json.JSONException -> ModelRequestException(ModelFailure.PROTOCOL)
    else -> failure
}

/** A close failure must not be appended to a caller's exception by Closeable.use. */
internal inline fun <T : Closeable, R> T.useProviderResource(block: (T) -> R): R {
    var failed = false
    try {
        return block(this)
    } catch (failure: Throwable) {
        failed = true
        throw failure
    } finally {
        if (failed) {
            try { close() } catch (_: Exception) {}
        } else close()
    }
}
