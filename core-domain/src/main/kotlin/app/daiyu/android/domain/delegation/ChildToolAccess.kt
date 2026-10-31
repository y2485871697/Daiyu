package app.daiyu.android.domain.delegation

import app.daiyu.android.domain.tools.*
import java.util.Collections

enum class ChildModelRole { REVIEW, IMPLEMENTATION }
enum class ChildBrowserAccess { FULL, READ_ONLY, DISABLED }

/** A parent-requested ceiling, frozen in the task journal; never a permission by itself. */
class ChildToolAccess private constructor(val toolNames: Set<String>?, val browser: ChildBrowserAccess) {
    fun permits(name: String): Boolean = toolNames == null || name in toolNames
    fun toJson(): String = "{\"browser_access\":" + jsonQuote(browser.name) +
        (toolNames?.sorted()?.joinToString(",", ",\"tool_names\":[", "]") { jsonQuote(it) } ?: "") + "}"

    override fun equals(other: Any?): Boolean = other is ChildToolAccess && toolNames == other.toolNames && browser == other.browser
    override fun hashCode(): Int = 31 * (toolNames?.hashCode() ?: 0) + browser.hashCode()
    companion object {
        val DEFAULT = of(null, ChildBrowserAccess.FULL)
        val NONE = of(emptySet(), ChildBrowserAccess.DISABLED)
        fun of(names: Set<String>?, browser: ChildBrowserAccess = ChildBrowserAccess.FULL): ChildToolAccess {
            require(names == null || (names.size <= 128 && names.all { it.isNotBlank() && it.length <= 200 }))
            return ChildToolAccess(names?.let { Collections.unmodifiableSet(it.toSet()) }, browser)
        }
        fun fromJson(value: String): ChildToolAccess {
            require(value.length <= 32 * 1024)
            val json = ToolSchema(listOf(
                ArgumentField("browser_access", ArgumentType.Text(20, ChildBrowserAccess.entries.map { it.name }.toSet(), 1)),
                ArgumentField("tool_names", ArgumentType.ArrayValue(ArgumentType.Text(200, minimumLength = 1), maximumItems = 128), false)
            )).parse(value)
            val names = if (json.contains("tool_names")) json.array("tool_names").map { it as String }.toSet() else null
            return of(names, ChildBrowserAccess.valueOf(json.text("browser_access")))
        }
    }
}

fun interface ChildToolAssemblyFactory {
    fun restrict(session: app.daiyu.android.domain.tools.SessionCapabilities, access: ChildToolAccess): ChildToolAccess = access
    /** Pure reference retention for an accepted queued task. */
    fun retain(): AutoCloseable = AutoCloseable {}
    /** Construction must be I/O-free. The batch retains the returned resource before prepare. */
    fun create(owner: ChildRunOwner, parentSession: app.daiyu.android.domain.tools.SessionCapabilities,
        parentAuthorization: app.daiyu.android.domain.tools.TaskAuthorization, access: ChildToolAccess): ChildToolAssembly
}
