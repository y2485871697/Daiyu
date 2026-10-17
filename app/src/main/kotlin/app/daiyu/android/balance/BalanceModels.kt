package app.daiyu.android.balance

import java.math.BigDecimal
import java.net.URI

/** Dedicated balance credentials. Never falls back to a chat/model key or token accounting. */
data class BalanceConfiguration(
    val accountId: String,
    val endpoint: String,
    val apiKey: String,
    val amountPointer: String,
    val currency: String,
    /** Null only for the already persisted v1 RFC6901 configuration. Endpoint otherwise means base URL. */
    val option: BalanceOption? = null,
    val customHeaders: Map<String, String> = emptyMap(),
) {
    val queryEnabled: Boolean get() = option?.enabled ?: true
    fun resolvedEndpoint(): String = option?.resolved()?.let {
        ProviderBalanceFetcher.resolveBalanceUrl(endpoint, it.apiPath, it.preset)
    } ?: endpoint
    fun resolvedToken(): String = option?.accessToken?.ifBlank { apiKey } ?: apiKey

    fun validate() {
        require(accountId.isNotBlank() && accountId.length <= 120 && accountId.none { it.isISOControl() })
        require(endpoint.length <= 2048)
        fun validateUrl(value: String) {
            require(value.length <= 2048)
            val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("Invalid balance HTTPS URL") }
            require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535))
        }
        validateUrl(endpoint)
        if (queryEnabled) validateUrl(resolvedEndpoint())
        require(apiKey.length <= 8192 && apiKey.all { it.code in 33..126 })
        require(resolvedToken().length <= 8192 && resolvedToken().all { it.code in 33..126 })
        require(currency.matches(Regex("[A-Z]{3}")))
        if (option == null) BalanceJsonPointer.segments(amountPointer) else {
            require(option.preset in listOf(BalanceOption.PRESET_CUSTOM, BalanceOption.PRESET_NEW_API,
                BalanceOption.PRESET_SUB2API, BalanceOption.PRESET_DEEPSEEK))
            require(option.apiPath.length <= 2048 && option.resultPath.length <= 512 && option.userId.length <= 120)
            val resolved = option.resolved()
            if (option.enabled) require(resolved.apiPath.isNotBlank() && resolved.resultPath.isNotBlank())
        }
        require(amountPointer.length <= 512)
        require(customHeaders.size <= 16)
        require(customHeaders.keys.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size == customHeaders.size)
        customHeaders.forEach { (name, value) ->
            require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,80}")))
            require(name.lowercase(java.util.Locale.ROOT) !in setOf("authorization", "proxy-authorization", "host", "cookie",
                "content-length", "transfer-encoding", "connection", "accept", "cache-control"))
            require(value.length <= 2048 && value.all { it.code in 32..126 })
        }
    }
    override fun toString() = "BalanceConfiguration(<redacted>)"
}

data class BalanceMoney(val amount: BigDecimal, val currency: String) {
    init {
        require(currency.matches(Regex("[A-Z]{3}")))
        // Includes every finite Double expression result, without truncating it to two decimals.
        // Bounds keep malicious exponents from allocating an unbounded toPlainString().
        require(amount.precision() <= 64 && amount.scale() in -308..325)
    }
    fun display(): String = "$currency ${amount.stripTrailingZeros().toPlainString()}"
}

enum class BalanceFailure {
    AUTHORIZATION, HTTP, RATE_LIMITED, NETWORK, RESPONSE_TOO_LARGE, INVALID_RESPONSE, STORAGE,
}

sealed interface BalanceResult {
    data object Unknown : BalanceResult
    data class Available(val money: BalanceMoney, val checkedAtMillis: Long) : BalanceResult
    data class Failed(val reason: BalanceFailure) : BalanceResult
}

/** Same-configuration success-to-success change, preserved from the frozen coordinator. */
data class BalanceChange(val seq: Long, val delta: BigDecimal, val atMillis: Long)

/** Public projection deliberately contains neither credentials, endpoint, nor raw server errors. */
data class BalanceState(
    val accountId: String? = null,
    val revision: String? = null,
    val configured: Boolean = false,
    val refreshing: Boolean = false,
    val result: BalanceResult = BalanceResult.Unknown,
    val lastSuccessful: BalanceResult.Available? = null,
    val lastChange: BalanceChange? = null,
    val queryEnabled: Boolean = true,
) {
    val successful: BalanceResult.Available? get() = (result as? BalanceResult.Available) ?: lastSuccessful
    val amountValue: BigDecimal? get() = successful?.money?.amount

    fun project(): BalanceProjection {
        val available = successful
        return BalanceProjection(
            balanceText = available?.money?.display(),
            statusText = when {
                refreshing -> "正在查询余额（顶部金额保留上次成功值）"
                configured && !queryEnabled -> "独立余额查询已关闭"
                result is BalanceResult.Failed -> when (result.reason) {
                    BalanceFailure.AUTHORIZATION -> "余额查询未授权；请检查独立密钥"
                    BalanceFailure.RATE_LIMITED -> "余额查询受限；请稍后刷新"
                    BalanceFailure.HTTP -> "余额服务拒绝查询；未跟随重定向"
                    BalanceFailure.NETWORK -> "余额查询网络失败；请稍后刷新"
                    BalanceFailure.RESPONSE_TOO_LARGE -> "余额响应超出上限"
                    BalanceFailure.INVALID_RESPONSE -> "余额响应格式无效"
                    BalanceFailure.STORAGE -> "余额配置或结果存储不可用；请明确重新保存"
                }
                result == BalanceResult.Unknown && available != null -> "本次未取得金额；保留上次成功值"
                available != null -> "余额由独立账户接口返回"
                configured -> "余额未知；可手动查询或由宿主轮询刷新"
                else -> "未配置独立余额查询"
            },
            checkedAtMillis = available?.checkedAtMillis,
            canRefresh = configured && queryEnabled && !refreshing,
        )
    }
}

data class BalanceProjection(
    val balanceText: String?,
    val statusText: String,
    val checkedAtMillis: Long?,
    val canRefresh: Boolean,
)

/** RFC6901 pointer only: no scripts, arithmetic, inferred provider protocol, or token estimates. */
internal object BalanceJsonPointer {
    fun segments(pointer: String): List<String> {
        require(pointer.length in 1..512 && pointer.startsWith('/'))
        return pointer.substring(1).split('/').also { require(it.size <= 16) }.map { part ->
            require(!Regex("~(?![01])").containsMatchIn(part))
            part.replace("~1", "/").replace("~0", "~")
        }
    }
}
