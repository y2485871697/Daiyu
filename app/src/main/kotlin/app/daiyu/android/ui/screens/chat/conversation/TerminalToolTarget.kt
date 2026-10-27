package app.daiyu.android.ui.screens.chat.conversation

import java.util.Locale
import org.json.JSONObject

/** Historical receipt metadata only; neither requested identity nor current settings are evidence. */
internal data class TerminalToolTarget(val environment: String, val identity: String, val hostIdentity: String? = null) {
    val label: String get() = "$environment • $identity" +
        if (environment != "Android" && identity == "root" && hostIdentity == "user") "（PRoot）" else ""

    companion object {
        // Shizuku can retain 256 KiB of bytes, represented as escaped text plus base64.
        private const val MAX_RECEIPT = 2_097_152
        fun fromResult(toolName: String, result: String?, parsed: JSONObject? = null): TerminalToolTarget? {
            val receipt = parsed ?: result?.takeIf { it.length <= MAX_RECEIPT && it.trimStart().startsWith('{') }
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            return from(toolName, receipt)
        }

        fun from(toolName: String, receipt: JSONObject?): TerminalToolTarget? {
            if (receipt == null) return null
            fun readUid(key: String): Long? {
                val number = receipt.opt(key) as? Number ?: return null
                val value = number.toLong()
                return value.takeIf { value in 0..Int.MAX_VALUE.toLong() && number.toString() == value.toString() }
            }
            val backend = (receipt.opt("backend") as? String)?.lowercase(Locale.ROOT)
            val uid = readUid("uid")
            val unknown = "身份未确认"
            if (backend == "shizuku" || !receipt.has("backend") && toolName == "shizuku_execute" && uid in setOf(0L, 2000L)) {
                return TerminalToolTarget("Android", if (uid in setOf(0L, 2000L)) "Shizuku" else unknown)
            }
            if (backend == "android") {
                val rootFlags = listOf("root", "host_root").mapNotNull { receipt.opt(it) as? Boolean }
                val identity = when {
                    uid == null || rootFlags.any { it != (uid == 0L) } -> unknown
                    uid == 0L -> "root"
                    else -> "user"
                }
                return TerminalToolTarget("Android", identity)
            }
            if (backend in setOf("linux:debian", "linux:alpine")) {
                val environment = if (backend == "linux:debian") "Debian" else "Alpine"
                val guestUid = if (receipt.has("guest_uid") && !receipt.isNull("guest_uid")) readUid("guest_uid") else uid
                val conflictingUid = receipt.has("uid") && !receipt.isNull("uid") && (uid == null || guestUid != uid)
                val identity = when { guestUid == null || conflictingUid -> unknown; guestUid == 0L -> "root"; else -> "user" }
                val hostFlags = listOf("root", "host_root").mapNotNull { receipt.opt(it) as? Boolean }.distinct()
                val host = when (hostFlags.singleOrNull()) { true -> "root"; false -> "user"; null -> null }
                return TerminalToolTarget(environment, identity, host)
            }
            return null
        }
    }
}
