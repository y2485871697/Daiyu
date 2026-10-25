package app.daiyu.android.model

/** Run-local metadata, never serialized to credentials, a request body or headers. */
data class UsageModelIdentity(val providerId: String, val providerName: String)
