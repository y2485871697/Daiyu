package app.daiyu.android.domain.question

/** A question is a single-choice barrier, never a default action. */
data class QuestionOption(val id: String, val label: String, val description: String = "")
data class UserQuestion(
    val title: String,
    val question: String,
    val options: List<QuestionOption>,
    val recommendedOptionId: String? = null,
    val allowOther: Boolean = true,
    val allowDelegation: Boolean = true,
    val allowNote: Boolean = true
) {
    fun validate() {
        require(title.isNotBlank() && title.length <= 200)
        require(question.isNotBlank() && question.length <= 4000)
        require(options.size in 2..8)
        require(options.map { it.id }.distinct().size == options.size)
        require(recommendedOptionId == null || options.any { it.id == recommendedOptionId })
        options.forEach {
            require(it.id.isNotBlank() && it.id.length <= 64)
            require(it.label.isNotBlank() && it.label.length <= 200)
            require(it.description.length <= 1000)
        }
    }
}

sealed interface QuestionAnswer {
    val note: String?
    data class Selected(val optionId: String, override val note: String? = null) : QuestionAnswer
    data class Other(val text: String, override val note: String? = null) : QuestionAnswer
    data class Delegate(override val note: String? = null) : QuestionAnswer
}

internal fun UserQuestion.accepts(answer: QuestionAnswer): Boolean {
    if (answer.note != null && (!allowNote || answer.note!!.length > 4000)) return false
    return when (answer) {
        is QuestionAnswer.Selected -> options.any { it.id == answer.optionId }
        is QuestionAnswer.Other -> allowOther && answer.text.isNotBlank() && answer.text.length <= 4000
        is QuestionAnswer.Delegate -> allowDelegation
    }
}

const val ASK_USER_TOOL_NAME = "ask_user"

/** Exported for model registration; execute validates even when a provider ignores the schema. */
object AskUserContract {
    val parametersJson: String = """{"type":"object","additionalProperties":false,"required":["title","question","options"],"properties":{"title":{"type":"string","minLength":1,"maxLength":200},"question":{"type":"string","minLength":1,"maxLength":4000},"options":{"type":"array","minItems":2,"maxItems":8,"items":{"type":"object","additionalProperties":false,"required":["id","label"],"properties":{"id":{"type":"string","minLength":1,"maxLength":64},"label":{"type":"string","minLength":1,"maxLength":200},"description":{"type":"string","maxLength":1000}}}},"recommended_option_id":{"type":"string","maxLength":64},"allow_other":{"type":"boolean","default":true},"allow_delegation":{"type":"boolean","default":true},"allow_note":{"type":"boolean","default":true}}}"""
    val description = "在关键决策前向用户提出一个问题，提供 2–8 个互斥选项；等待用户明确回答后才能继续。同批最多一个问题；推荐选项不会自动选择。"

    fun parse(arguments: String): UserQuestion {
        val root = StrictQuestionJson.parse(arguments).objectValue()
        root.only("title", "question", "options", "recommended_option_id", "allow_other", "allow_delegation", "allow_note")
        val options = (root["options"] as? List<*>) ?: throw IllegalArgumentException("options")
        val question = UserQuestion(
            title = root.requiredString("title"), question = root.requiredString("question"),
            options = options.map { value ->
                val option = value.objectValue()
                option.only("id", "label", "description")
                QuestionOption(option.requiredString("id"), option.requiredString("label"), option.optionalString("description"))
            },
            recommendedOptionId = if (root.containsKey("recommended_option_id")) root.requiredString("recommended_option_id") else null,
            allowOther = root.optionalBoolean("allow_other"),
            allowDelegation = root.optionalBoolean("allow_delegation"),
            allowNote = root.optionalBoolean("allow_note")
        )
        question.validate()
        return question
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.objectValue(): Map<String, Any?> = this as? Map<String, Any?> ?: throw IllegalArgumentException("object")
internal fun Map<String, Any?>.only(vararg names: String) { require(keys.all { it in names }) }
internal fun Map<String, Any?>.requiredString(key: String): String = this[key] as? String ?: throw IllegalArgumentException(key)
internal fun Map<String, Any?>.optionalString(key: String): String = if (containsKey(key)) requiredString(key) else ""
internal fun Map<String, Any?>.optionalBoolean(key: String): Boolean = if (containsKey(key)) this[key] as? Boolean ?: throw IllegalArgumentException(key) else true
