package app.daiyu.android.question

import app.daiyu.android.domain.question.*
import org.json.JSONArray
import org.json.JSONObject

internal fun UserQuestion.json(): JSONObject = JSONObject().put("title", title).put("question", question)
    .put("options", JSONArray().apply { options.forEach { put(JSONObject().put("id", it.id).put("label", it.label).put("description", it.description)) } })
    .apply { recommendedOptionId?.let { put("recommended_option_id", it) } }
    .put("allow_other", allowOther).put("allow_delegation", allowDelegation).put("allow_note", allowNote)

internal fun QuestionAnswer.json(): JSONObject = JSONObject().apply {
    when (val answer = this@json) {
        is QuestionAnswer.Selected -> put("kind", "option").put("option_id", answer.optionId)
        is QuestionAnswer.Other -> put("kind", "other").put("text", answer.text)
        is QuestionAnswer.Delegate -> put("kind", "delegate")
    }
    this@json.note?.let { put("note", it) }
}

internal fun decodeAnswer(value: String): QuestionAnswer {
    val json = JSONObject(value)
    val note = if (json.has("note")) json.getString("note") else null
    return when (json.getString("kind")) {
        "option" -> QuestionAnswer.Selected(json.getString("option_id"), note)
        "other" -> QuestionAnswer.Other(json.getString("text"), note)
        "delegate" -> QuestionAnswer.Delegate(note)
        else -> throw IllegalStateException("Invalid persisted answer")
    }
}
