package app.daiyu.android.question

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.InputFilter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import app.daiyu.android.domain.question.QuestionAnswer
import app.daiyu.android.domain.question.QuestionIdentity
import app.daiyu.android.domain.question.QuestionRecord
import java.lang.ref.WeakReference

/** Chat-row UI. All persistence is delegated to the owner-bound worker; recommendations never select. */
class QuestionCardView(context: Context) : LinearLayout(context) {
    enum class Reply { SAVED, SAVED_REFRESH_FAILED, REJECTED, UNCONFIRMED, STOPPED }
    private var identity: QuestionIdentity? = null
    private var record: QuestionRecord? = null
    private var generation = 0L
    private var attachmentEpoch = 0L
    private var writable = false
    private var busy = false
    private var detached = false
    private var blocked = false
    private val main = Handler(Looper.getMainLooper())
    private var button: Button? = null
    private var statusView: TextView? = null
    private var retainedBinding: QuestionCardBinding? = null
    private var currentDraft: QuestionCardBinding.Draft? = null
    private var submitAction: ((QuestionIdentity, QuestionAnswer, (Reply) -> Unit) -> Unit)? = null
    init { orientation = VERTICAL }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        detached = false
        currentDraft?.let { refreshRetained(it) }
    }

    override fun onDetachedFromWindow() {
        detached = true
        attachmentEpoch++
        if (busy) blocked = true
        busy = false
        if (currentDraft == null) writable = false
        button?.isEnabled = false
        super.onDetachedFromWindow()
    }

    fun bind(owner: QuestionIdentity, incoming: QuestionRecord, canAnswer: Boolean,
        submit: (QuestionIdentity, QuestionAnswer, (Reply) -> Unit) -> Unit) {
        bindCard(owner, incoming, canAnswer, submit, null)
    }

    fun bind(owner: QuestionIdentity, incoming: QuestionRecord, canAnswer: Boolean, binding: QuestionCardBinding) {
        bindCard(owner, incoming, canAnswer, binding::submit, binding)
    }

    private fun refreshRetained(state: QuestionCardBinding.Draft) {
        if (currentDraft !== state || detached) return
        val owner = identity ?: return
        val value = record ?: return
        val submit = submitAction ?: return
        bindCard(owner, value, writable, submit, retainedBinding)
    }
    private fun retainedStatus(state: QuestionCardBinding.Draft): String = when {
        state.saving -> "正在保存答案…"
        state.reply == Reply.REJECTED -> "答案未被接受；可修改后重试，若提问已过期请刷新状态"
        state.reply == Reply.UNCONFIRMED -> "提交结果未确认，请刷新状态；不会自动重复提交"
        state.reply == Reply.STOPPED -> "本轮已停止，不能再回答"
        else -> ""
    }
    private fun rememberText(field: EditText?, changed: (String) -> Unit) {
        field?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { changed(s?.toString().orEmpty()) }
        })
    }
    private fun bindCard(owner: QuestionIdentity, incoming: QuestionRecord, canAnswer: Boolean,
        submit: (QuestionIdentity, QuestionAnswer, (Reply) -> Unit) -> Unit, binding: QuestionCardBinding?) {
        check(Looper.myLooper() == Looper.getMainLooper())
        require(incoming.callId == owner.storageKey) { "question_card_identity_mismatch" }
        val draft = binding?.draft(owner)
        if (incoming.answer != null) draft?.saved = incoming.answer
        submitAction = submit
        writable = canAnswer
        // Never reopen a locally confirmed answer because a delayed refresh still says pending.
        val value = if (draft?.saved != null && incoming.answer == null) incoming.copy(answer = draft.saved)
            else record?.takeIf { identity == owner && retainedBinding === binding &&
                it.answer != null && incoming.answer == null && !incoming.interrupted } ?: incoming
        val same = identity == owner && record == value && retainedBinding === binding
        retainedBinding = binding; currentDraft = draft
        if (draft != null) {
            val view = WeakReference(this)
            draft.changed = { view.get()?.refreshRetained(draft) }
            busy = draft.saving
            blocked = draft.reply == Reply.UNCONFIRMED || draft.reply == Reply.STOPPED
        }
        if (same) {
            if (draft != null) statusView?.text = retainedStatus(draft)
            button?.isEnabled = writable && !busy && !blocked && !detached; return
        }
        identity = owner; record = value; generation++; busy = false; blocked = false; button = null
        statusView = null
        if (draft != null) {
            busy = draft.saving
            blocked = draft.reply == Reply.UNCONFIRMED || draft.reply == Reply.STOPPED
        }
        removeAllViews()
        addView(TextView(context).apply { text = value.question.title; textSize = 17f })
        if (value.answer != null || value.interrupted) {
            addView(TextView(context).apply { text = value.summary() })
            if (draft?.reply == Reply.SAVED_REFRESH_FAILED) addView(TextView(context).apply {
                text = "答案已保存，消息刷新失败；请稍后查看状态"
            })
            return
        }
        addView(TextView(context).apply { text = value.question.question })
        val options = RadioGroup(context)
        val ids = linkedMapOf<Int, String>()
        value.question.options.forEach { option ->
            val id = View.generateViewId(); ids[id] = option.id
            options.addView(RadioButton(context).apply {
                this.id = id
                text = option.label + (if (option.id == value.question.recommendedOptionId) "（推荐，需自行选择）" else "") +
                    if (option.description.isNotEmpty()) "\n${option.description}" else ""
            })
        }
        val otherId = if (value.question.allowOther) View.generateViewId() else -1
        val delegateId = if (value.question.allowDelegation) View.generateViewId() else -1
        if (otherId != -1) options.addView(RadioButton(context).apply { id = otherId; text = "其他（自行填写）" })
        if (delegateId != -1) options.addView(RadioButton(context).apply { id = delegateId; text = "由你决定" })
        addView(options)
        val other = if (otherId != -1) EditText(context).apply {
            hint = "其他答案"; filters = arrayOf(InputFilter.LengthFilter(4000)); visibility = GONE; addView(this)
        } else null
        val note = if (value.question.allowNote) EditText(context).apply {
            hint = "补充说明（可选）"; filters = arrayOf(InputFilter.LengthFilter(4000)); addView(this)
        } else null
        if (draft != null) {
            other?.setText(draft.other); note?.setText(draft.note)
            val selectedId = when (val selected = draft.selection) {
                is QuestionAnswer.Selected -> ids.entries.firstOrNull { it.value == selected.optionId }?.key ?: -1
                is QuestionAnswer.Other -> otherId
                is QuestionAnswer.Delegate -> delegateId
                null -> -1
            }
            if (selectedId != -1) options.check(selectedId)
            rememberText(other) { draft.other = it }; rememberText(note) { draft.note = it }
        }
        other?.visibility = if (options.checkedRadioButtonId == otherId) VISIBLE else GONE
        options.setOnCheckedChangeListener { _, checked ->
            other?.visibility = if (checked == otherId) VISIBLE else GONE
            if (draft != null) draft.selection = when {
                checked in ids -> QuestionAnswer.Selected(ids.getValue(checked))
                checked == otherId && otherId != -1 -> QuestionAnswer.Other("")
                checked == delegateId && delegateId != -1 -> QuestionAnswer.Delegate()
                else -> null
            }
        }
        val status = TextView(context).apply { if (draft != null) text = retainedStatus(draft) }
        addView(status); statusView = status
        val send = Button(context).apply { text = "提交答案"; isEnabled = writable && !busy && !blocked && !detached }
        addView(send); button = send
        val token = generation
        send.setOnClickListener {
            if (generation != token || identity != owner || !writable || busy || detached || blocked) return@setOnClickListener
            val noteText = note?.text?.toString()?.takeIf { it.isNotEmpty() }
            val selected = options.checkedRadioButtonId
            val answer = when {
                selected in ids -> QuestionAnswer.Selected(ids.getValue(selected), noteText)
                selected == otherId && otherId != -1 -> {
                    val text = other?.text?.toString().orEmpty()
                    if (text.isBlank()) { status.text = "请填写其他答案"; return@setOnClickListener }
                    QuestionAnswer.Other(text, noteText)
                }
                selected == delegateId && delegateId != -1 -> QuestionAnswer.Delegate(noteText)
                else -> { status.text = "请选择一个答案；推荐选项不会自动选择"; return@setOnClickListener }
            }
            busy = true; send.isEnabled = false; status.text = "正在保存答案…"
            val submittedAttachment = attachmentEpoch
            submitAction?.invoke(owner, answer) { reply ->
                main.post {
                    if (generation != token || identity != owner || detached || submittedAttachment != attachmentEpoch) return@post
                    if (draft != null) { refreshRetained(draft); return@post }
                    busy = false
                    when (reply) {
                        Reply.SAVED, Reply.SAVED_REFRESH_FAILED -> {
                            bind(owner, value.copy(answer = answer), false, submit)
                            if (reply == Reply.SAVED_REFRESH_FAILED) addView(TextView(context).apply { text = "答案已保存，消息刷新失败；请稍后查看状态" })
                        }
                        Reply.REJECTED -> { blocked = false; status.text = "答案未被接受；可修改后重试，若提问已过期请刷新状态" }
                        Reply.UNCONFIRMED -> { blocked = true; writable = false; status.text = "提交结果未确认，请刷新状态；不会自动重复提交" }
                        Reply.STOPPED -> { blocked = true; writable = false; status.text = "本轮已停止，不能再回答" }
                    }
                    send.isEnabled = writable && !blocked && !detached
                }
            }
        }
    }
}
