package app.daiyu.android.integration.tools

import app.daiyu.android.domain.delegation.*
import app.daiyu.android.domain.tools.*

internal object ChildToolAccessArguments {
    val fields = listOf(
        ArgumentField("tool_names", ArgumentType.ArrayValue(ArgumentType.Text(200, minimumLength = 1), maximumItems = 128), false),
        ArgumentField("browser_access", ArgumentType.Text(20, setOf("full", "read_only", "disabled"), 1), false)
    )
    fun parse(arguments: ToolArguments): ChildToolAccess = ChildToolAccess.of(
        if (arguments.contains("tool_names")) arguments.array("tool_names").map { it as String }.toSet() else null,
        if (arguments.contains("browser_access")) ChildBrowserAccess.valueOf(arguments.text("browser_access").uppercase())
        else ChildBrowserAccess.FULL)
}
