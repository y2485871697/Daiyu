package app.daiyu.android.balance

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

/** Persistence must encrypt the entire record (configuration AND result), atomically. */
interface BalancePersistence {
    fun read(): ByteArray?
    fun write(record: ByteArray?)
}

/** One-use revision-bound permit; late/cancelled/foreign responses cannot write. */
class BalanceRefreshTicket internal constructor(
    internal val owner: Any,
    internal val revision: String,
    internal val requestId: String,
    internal val configuration: BalanceConfiguration,
    internal val operation: Job? = null,
    internal val startedAtMillis: Long = System.currentTimeMillis(),
) {
    override fun toString() = "BalanceRefreshTicket(<redacted>)"
}

/**
 * P05 encrypted host store with frozen coordinator semantics: keep last successful amount on
 * refresh/failure, timestamp only on success, and compute changes only within one configuration.
 * One process-retained instance per namespace; every explicit save/reload/delete fences replies.
 */
class ProviderBalanceStore(private val persistence: BalancePersistence) {
    private val lock = Any()
    private val identity = Any()
    private var configuration: BalanceConfiguration? = null
    private var pending: BalanceRefreshTicket? = null
    private var changeSeq = 0L
    private val mutableState = MutableStateFlow(BalanceState())
    val state: StateFlow<BalanceState> = mutableState.asStateFlow()

    init { reload() }

    /** Local I/O only. Reads never create a key or start HTTP. */
    fun reload() = synchronized(lock) {
        configuration = null
        pending = null
        try {
            val bytes = persistence.read()
            if (bytes == null) mutableState.value = BalanceState() else {
                require(bytes.size <= MAX_RECORD_BYTES)
                val record = JSONObject(String(bytes, Charsets.UTF_8))
                require(record.getInt("version") == 1)
                val option = record.optJSONObject("option")?.let {
                    BalanceOption(it.getBoolean("enabled"), it.getString("preset"), it.getString("apiPath"),
                        it.getString("resultPath"), it.getString("userId"), it.getString("accessToken"))
                }
                val headers = record.optJSONObject("headers")?.let { json ->
                    json.keys().asSequence().associateWith { json.getString(it) }
                }.orEmpty()
                val config = BalanceConfiguration(record.getString("account"), record.getString("endpoint"),
                    record.getString("key"), record.getString("pointer"), record.getString("currency"), option, headers)
                config.validate()
                val revision = record.getString("revision")
                UUID.fromString(revision)
                val result = decodeResult(record.getJSONObject("result"))
                require(result !is BalanceResult.Available || result.money.currency == config.currency)
                val success = record.optJSONObject("lastSuccess")?.let { decodeResult(it) as? BalanceResult.Available }
                    ?: result as? BalanceResult.Available
                require(success == null || success.money.currency == config.currency)
                configuration = config
                // Events are process-local: restoration never replays an old deduction.
                mutableState.value = BalanceState(config.accountId, revision, true, result = result,
                    lastSuccessful = success, queryEnabled = config.queryEnabled)
            }
        } catch (_: Exception) { storageFailure() }
    }

    /** Explicit editor access only; no key/URL/headers in observable state. */
    fun configurationForEditing(): BalanceConfiguration? = synchronized(lock) { configuration?.snapshot() }

    fun save(value: BalanceConfiguration) = synchronized(lock) {
        value.validate()
        val config = value.snapshot()
        pending = null
        val revision = UUID.randomUUID().toString()
        try {
            persist(config, revision, BalanceResult.Unknown, null)
            configuration = config
            mutableState.value = BalanceState(config.accountId, revision, true, queryEnabled = config.queryEnabled)
        } catch (_: Exception) {
            storageFailure()
            throw IllegalStateException("余额配置未保存")
        }
    }

    fun clear() = synchronized(lock) {
        pending = null
        try {
            persistence.write(null)
            configuration = null
            mutableState.value = BalanceState()
        } catch (_: Exception) {
            storageFailure()
            throw IllegalStateException("余额配置删除未确认")
        }
    }

    /** Shared admission for manual refresh and owner-scoped polling; coalesces while in flight. */
    fun beginManualRefresh(operation: Job? = null): BalanceRefreshTicket? = synchronized(lock) {
        val config = configuration ?: return@synchronized null
        if (!config.queryEnabled || operation?.isActive == false) return@synchronized null
        // A cancelled owner must not block a new owner while an uncooperative old fetch unwinds.
        if (pending != null && pending?.operation?.isActive != false) return@synchronized null
        val revision = mutableState.value.revision ?: return@synchronized null
        BalanceRefreshTicket(identity, revision, UUID.randomUUID().toString(), config.snapshot(), operation).also {
            pending = it
            mutableState.value = mutableState.value.copy(refreshing = true)
        }
    }

    fun isCurrent(ticket: BalanceRefreshTicket): Boolean = synchronized(lock) { current(ticket) && ticket.operation?.isActive != false }

    fun complete(ticket: BalanceRefreshTicket, result: BalanceResult): Boolean = synchronized(lock) {
        if (!current(ticket) || ticket.operation?.isActive == false) return@synchronized false
        val config = configuration ?: return@synchronized false
        try {
            val previous = mutableState.value
            val available = result as? BalanceResult.Available
            require(available == null || (available.money.currency == config.currency && available.checkedAtMillis >= 0))
            val success = available ?: previous.successful
            val old = previous.amountValue
            val change = if (available != null && old != null && old.compareTo(available.money.amount) != 0) {
                BalanceChange(++changeSeq, available.money.amount - old, available.checkedAtMillis)
            } else previous.lastChange
            persist(config, ticket.revision, result, success)
            pending = null
            mutableState.value = BalanceState(config.accountId, ticket.revision, true, result = result,
                lastSuccessful = success, lastChange = change, queryEnabled = config.queryEnabled)
            true
        } catch (_: Exception) {
            storageFailure()
            false
        }
    }

    internal fun successForSharing(snapshot: BalanceState): Pair<BalanceConfiguration, BalanceResult.Available>? = synchronized(lock) {
        if (mutableState.value != snapshot) return@synchronized null
        val result = snapshot.result as? BalanceResult.Available ?: return@synchronized null
        val config = configuration?.takeIf { it.queryEnabled } ?: return@synchronized null
        config.snapshot() to result
    }

    /** Each recipient keeps its encrypted record and revision fence, even for a shared account. */
    internal fun acceptSharedResult(expectedRevision: String?, source: BalanceConfiguration,
                                    result: BalanceResult.Available): Boolean = synchronized(lock) {
        val config = configuration ?: return@synchronized false
        val previous = mutableState.value
        if (expectedRevision == null || previous.revision != expectedRevision || !config.sharesQueryWith(source)) return@synchronized false
        if (previous.successful?.checkedAtMillis?.let { it >= result.checkedAtMillis } == true) return@synchronized false
        if (pending?.let { result.checkedAtMillis < it.startedAtMillis } == true) return@synchronized false
        pending?.operation?.cancel()
        val ticket = BalanceRefreshTicket(identity, expectedRevision, UUID.randomUUID().toString(), config.snapshot())
        pending = ticket
        complete(ticket, result)
    }

    /** Cancellation changes neither successful amount nor its timestamp. */
    fun cancel(ticket: BalanceRefreshTicket) = synchronized(lock) {
        if (current(ticket)) {
            pending = null
            mutableState.value = mutableState.value.copy(refreshing = false)
        }
    }

    private fun current(ticket: BalanceRefreshTicket): Boolean =
        ticket.owner === identity && pending === ticket && ticket.revision == mutableState.value.revision

    private fun storageFailure() {
        pending = null
        configuration = null
        mutableState.value = BalanceState(result = BalanceResult.Failed(BalanceFailure.STORAGE))
    }

    private fun BalanceConfiguration.snapshot() = copy(customHeaders = customHeaders.toMap())

    private fun persist(config: BalanceConfiguration, revision: String, result: BalanceResult, success: BalanceResult.Available?) {
        val record = JSONObject().put("version", 1).put("account", config.accountId)
            .put("endpoint", config.endpoint).put("key", config.apiKey).put("pointer", config.amountPointer)
            .put("currency", config.currency).put("revision", revision).put("result", encodeResult(result))
            .put("headers", JSONObject(config.customHeaders))
        config.option?.let {
            record.put("option", JSONObject().put("enabled", it.enabled).put("preset", it.preset)
                .put("apiPath", it.apiPath).put("resultPath", it.resultPath).put("userId", it.userId).put("accessToken", it.accessToken))
        }
        success?.let { record.put("lastSuccess", encodeResult(it)) }
        val bytes = record.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_RECORD_BYTES)
        persistence.write(bytes)
    }

    private fun encodeResult(result: BalanceResult): JSONObject = when (result) {
        BalanceResult.Unknown -> JSONObject().put("kind", "unknown")
        is BalanceResult.Failed -> JSONObject().put("kind", "failed").put("reason", result.reason.name)
        is BalanceResult.Available -> JSONObject().put("kind", "available").put("amount", result.money.amount.toPlainString())
            .put("currency", result.money.currency).put("time", result.checkedAtMillis)
    }

    private fun decodeResult(json: JSONObject): BalanceResult = when (json.getString("kind")) {
        "unknown" -> BalanceResult.Unknown
        "failed" -> BalanceResult.Failed(BalanceFailure.valueOf(json.getString("reason")))
        "available" -> BalanceResult.Available(
            BalanceMoney(json.getString("amount").toBigDecimal(), json.getString("currency")),
            json.getLong("time").also { require(it >= 0) })
        else -> error("Invalid balance record")
    }

    companion object { internal const val MAX_RECORD_BYTES = 32 * 1024 }
}
