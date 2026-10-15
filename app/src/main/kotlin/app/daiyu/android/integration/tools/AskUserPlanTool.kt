package app.daiyu.android.integration.tools

import app.daiyu.android.domain.question.ASK_USER_TOOL_NAME
import app.daiyu.android.domain.question.AskUserContract
import app.daiyu.android.domain.tools.*

internal object AskUserPlanTool : RegisteredTool {
    const val CAPABILITY = "user.question.ask"
    private val optionSchema = ToolSchema(listOf(
        ArgumentField("id", ArgumentType.Text(maxLength = 64, minimumLength = 1)),
        ArgumentField("label", ArgumentType.Text(maxLength = 200, minimumLength = 1)),
        ArgumentField("description", ArgumentType.Text(maxLength = 1000), required = false)))
    override val definition = ToolDefinition(
        ASK_USER_TOOL_NAME, 1, AskUserContract.description, CAPABILITY,
        ToolEffect.USER_BARRIER, ToolSchema(listOf(
            ArgumentField("title", ArgumentType.Text(maxLength = 200, minimumLength = 1)),
            ArgumentField("question", ArgumentType.Text(maxLength = 4000, minimumLength = 1)),
            ArgumentField("options", ArgumentType.ArrayValue(ArgumentType.ObjectValue(optionSchema), 2, 8)),
            ArgumentField("recommended_option_id", ArgumentType.Text(maxLength = 64), required = false),
            ArgumentField("allow_other", ArgumentType.BooleanValue, required = false),
            ArgumentField("allow_delegation", ArgumentType.BooleanValue, required = false),
            ArgumentField("allow_note", ArgumentType.BooleanValue, required = false))))
    override fun prepare(arguments: ToolArguments): PreparedTool {
        AskUserContract.parse(arguments.canonicalJson())
        return PreparedTool.userBarrier()
    }
}
