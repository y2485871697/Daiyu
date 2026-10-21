package app.daiyu.android.ui.screens.settings

import app.daiyu.android.balance.BalanceConfiguration

/** Resolve against the current provider draft at query/save time, never a second credential form. */
internal fun ModelSettingsDraftUi.providerBalanceConfiguration(slotId: String = "balance-preview"): BalanceConfiguration? {
    if (!balanceOption.enabled && balanceConfiguration == null && balanceOption == app.daiyu.android.balance.BalanceOption()) return null
    val legacy = balanceConfiguration?.takeIf {
        it.option == null && balanceOption == app.daiyu.android.balance.BalanceOption(enabled = true)
    }
    if (legacy != null) {
        val provider = java.net.URI(endpoint.trim())
        val previous = java.net.URI(legacy.endpoint)
        return legacy.copy(accountId = slotId, apiKey = apiKey,
            endpoint = java.net.URI(provider.scheme, null, provider.host, provider.port,
                previous.rawPath, previous.rawQuery, null).toASCIIString())
    }
    return BalanceConfiguration(
        accountId = slotId,
        endpoint = endpoint.trim(),
        apiKey = apiKey,
        amountPointer = "",
        currency = balanceConfiguration?.currency ?: "USD",
        option = balanceOption,
        customHeaders = balanceConfiguration?.customHeaders.orEmpty(),
    )
}

internal fun BalanceConfiguration.validateProviderBinding() {
    validate()
    if (!queryEnabled) return
    val base = java.net.URI(endpoint)
    val resolved = java.net.URI(resolvedEndpoint())
    fun port(uri: java.net.URI) = if (uri.port == -1) 443 else uri.port
    require(base.scheme.equals(resolved.scheme, true) && base.host.equals(resolved.host, true) &&
        port(base) == port(resolved)) { "余额路径必须属于当前提供商" }
}
