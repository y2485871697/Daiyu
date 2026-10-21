package app.daiyu.android.model

import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.domain.ModelFailure
import app.daiyu.android.domain.ModelRequestException
import org.json.JSONObject
import java.math.BigInteger

/** Protocol observations only; request ownership and durable accounting belong to the host. */
internal object ProviderUsage {
    const val MAX_TOKENS = 1_000_000_000_000L

    fun count(value: JSONObject, key: String, previous: Long? = null): Long? {
        if (!value.has(key) || value.isNull(key)) return previous
        val raw = value.get(key)
        val count = when (raw) {
            is Byte, is Short, is Int, is Long -> (raw as Number).toLong()
            is BigInteger -> {
                if (raw.signum() < 0 || raw > BigInteger.valueOf(MAX_TOKENS)) invalid()
                raw.toLong()
            }
            else -> invalid()
        }
        if (count !in 0..MAX_TOKENS) invalid()
        return count
    }

    fun responses(response: JSONObject): ModelEvent.Usage? {
        if (!response.has("usage") || response.isNull("usage")) return null
        val value = response.getJSONObject("usage")
        val input = count(value, "input_tokens")
        val output = count(value, "output_tokens")
        val details = if (!value.has("input_tokens_details") || value.isNull("input_tokens_details")) null
            else value.getJSONObject("input_tokens_details")
        val cached = details?.let { count(it, "cached_tokens") }
        if (input != null && cached != null && cached > input) invalid()
        return if (input == null && output == null && cached == null) null
            else ModelEvent.Usage(input, output, cached)
    }

    fun messages(input: Long?, output: Long?, cached: Long?, creation: Long?): ModelEvent.Usage? {
        val total = if (input != null && cached != null && creation != null) input + cached + creation else null
        if (total != null && total > MAX_TOKENS) invalid()
        return if (total == null && output == null && cached == null && creation == null) null
            else ModelEvent.Usage(total, output, cached, creation)
    }

    private fun invalid(): Nothing = throw ModelRequestException(ModelFailure.PROTOCOL)
}
