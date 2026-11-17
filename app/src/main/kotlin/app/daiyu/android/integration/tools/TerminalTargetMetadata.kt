package app.daiyu.android.integration.tools

import app.daiyu.android.domain.runtime.TerminalExecutionTarget
import app.daiyu.android.domain.tools.ToolExecutionContext
import org.json.JSONObject

internal object TerminalTargetMetadata {
    fun publish(context: ToolExecutionContext, receipt: JSONObject) {
        val backend = receipt.opt("backend") as? String ?: return
        if (backend !in setOf("android", "shizuku", "linux:debian", "linux:alpine")) return
        fun uid(key: String): Int? {
            val number = receipt.opt(key) as? Number ?: return null
            val value = number.toLong()
            return value.takeIf { it in 0..Int.MAX_VALUE.toLong() && number.toString() == it.toString() }?.toInt()
        }
        context.reportTerminalTarget(TerminalExecutionTarget(backend, uid("uid"), uid("guest_uid"),
            receipt.opt("root") as? Boolean, receipt.opt("host_root") as? Boolean))
    }
}
