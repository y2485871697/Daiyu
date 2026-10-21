package app.daiyu.android.question

import app.daiyu.android.ui.haptics.setHapticNegativeButton
import app.daiyu.android.ui.haptics.setHapticPositiveButton
import app.daiyu.android.ui.haptics.setHapticClickListener
import android.app.Activity
import app.daiyu.android.ui.components.startDaiyuEnterMotion
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.daiyu.android.domain.question.*

/** Native View UI. Own this alongside the registry, attach/detach in Activity lifecycle.
 * onRecord is the transcript/card callback: render summary() in the existing message UI, including
 * answered/interrupted records. Detaching dismisses only the window, never submits/cancels a choice.
 */
class QuestionHost(private val registry: QuestionRegistry) {
    private val main = Handler(Looper.getMainLooper())
    private var activity: Activity? = null
    private var dialog: AlertDialog? = null
    private var onRecord: ((QuestionRecord) -> Unit)? = null
    private var generation = 0
    private var submittedCallId: String? = null

    companion object {
        private const val WRITE_FAILED = "答案保存失败，请重试；尚未提交答案"
        private const val REFRESH_FAILED = "答案已提交，但消息刷新失败；请稍后查看提问状态"
        private const val STATE_FAILED = "提问状态刷新失败，请稍后重试"
    }

    fun attach(activity: Activity, onRecord: (QuestionRecord) -> Unit = {}) {
        check(Looper.myLooper() == Looper.getMainLooper())
        detach()
        this.activity = activity; this.onRecord = onRecord
        val token = ++generation
        try {
            registry.barrier.attach { _ ->
                main.post {
                    if (token != generation || this.activity !== activity || activity.isFinishing || activity.isDestroyed) return@post
                    refresh(activity)
                }
            }
        } catch (_: Exception) {
            // attach reads pending state synchronously, before the posted refresh can protect it.
            Toast.makeText(activity, STATE_FAILED, Toast.LENGTH_LONG).show()
        }
    }

    // Includes posted barrier notifications: storage reads and transcript callbacks are fallible too.
    private fun refresh(owner: Activity) {
        try {
            registry.records().forEach { onRecord?.invoke(it) }
            val pending = registry.barrier.pending()
            dialog?.dismiss(); dialog = null
            if (pending != null) show(owner, pending)
        } catch (_: Exception) {
            Toast.makeText(owner, if (submittedCallId != null) REFRESH_FAILED else STATE_FAILED, Toast.LENGTH_LONG).show()
        }
    }

    fun detach() {
        check(Looper.myLooper() == Looper.getMainLooper())
        generation++
        registry.barrier.detach()
        dialog?.dismiss(); dialog = null
        activity = null; onRecord = null
    }

    private fun show(activity: Activity, record: QuestionRecord) {
        if (submittedCallId != record.callId) submittedCallId = null
        val question = record.question
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16) }
        content.addView(TextView(activity).apply { text = question.question })
        val group = RadioGroup(activity)
        val choices = linkedMapOf<Int, String>()
        question.options.forEach { option ->
            val id = View.generateViewId(); choices[id] = option.id
            group.addView(RadioButton(activity).apply {
                this.id = id
                text = option.label + (if (option.id == question.recommendedOptionId) "（推荐，需自行选择）" else "") +
                    (if (option.description.isNotEmpty()) "\n${option.description}" else "")
            })
        }
        val otherId = if (question.allowOther) View.generateViewId() else -1
        val delegateId = if (question.allowDelegation) View.generateViewId() else -1
        if (otherId != -1) group.addView(RadioButton(activity).apply { id = otherId; text = "其他（自行填写）" })
        if (delegateId != -1) group.addView(RadioButton(activity).apply { id = delegateId; text = "由你决定" })
        content.addView(group)
        val other = if (question.allowOther) EditText(activity).apply { hint = "其他答案"; filters = arrayOf(InputFilter.LengthFilter(4000)); visibility = View.GONE; content.addView(this) } else null
        for (index in 0 until group.childCount) group.getChildAt(index).setHapticClickListener {}
        group.setOnCheckedChangeListener { _, checked -> other?.visibility = if (checked == otherId) View.VISIBLE else View.GONE }
        val note = if (question.allowNote) EditText(activity).apply { hint = "补充说明（可选）"; filters = arrayOf(InputFilter.LengthFilter(4000)); content.addView(this) } else null
        val error = TextView(activity).apply { visibility = View.GONE; content.addView(this) }
        val window = AlertDialog.Builder(activity).setTitle(question.title)
            .setView(ScrollView(activity).apply { addView(content) })
            .setHapticPositiveButton("提交答案", null).setHapticNegativeButton("稍后回答", null).create()
        window.setOnShowListener {
            window.startDaiyuEnterMotion()
            window.getButton(AlertDialog.BUTTON_POSITIVE).setHapticClickListener {
                val checked = group.checkedRadioButtonId
                val noteText = note?.text?.toString()?.takeIf { it.isNotEmpty() }
                val answer: QuestionAnswer? = when {
                    choices.containsKey(checked) -> QuestionAnswer.Selected(choices.getValue(checked), noteText)
                    checked == otherId && otherId != -1 -> QuestionAnswer.Other(other?.text?.toString().orEmpty(), noteText)
                    checked == delegateId && delegateId != -1 -> QuestionAnswer.Delegate(noteText)
                    else -> null
                }
                if (answer == null) { error.text = "请选择一个答案；推荐选项不会自动选择"; error.visibility = View.VISIBLE; return@setHapticClickListener }
                var refreshFailed = false
                try {
                    registry.submit(record.callId, answer)
                } catch (_: QuestionRefreshException) {
                    // Durable commit succeeded; a display callback is not a failed write.
                    refreshFailed = true
                } catch (_: QuestionException) {
                    error.text = "答案无效或提问已过期，请查看消息中的提问状态"; error.visibility = View.VISIBLE
                    return@setHapticClickListener
                } catch (_: Exception) {
                    // Do not dismiss or disable: preserve selection/note and permit explicit retry.
                    error.text = WRITE_FAILED; error.visibility = View.VISIBLE
                    return@setHapticClickListener
                }
                submittedCallId = record.callId
                // From here on the answer is durable. Never offer another submission after refresh failure.
                window.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                try {
                    registry.records().firstOrNull { it.callId == record.callId }?.let { onRecord?.invoke(it) }
                } catch (_: Exception) { refreshFailed = true }
                if (refreshFailed) {
                    error.text = REFRESH_FAILED; error.visibility = View.VISIBLE
                    window.getButton(AlertDialog.BUTTON_NEGATIVE).text = "关闭"
                } else window.dismiss()
            }
        }
        window.setOnDismissListener { if (dialog === window) dialog = null }
        dialog = window
        window.window?.setWindowAnimations(0)
        window.show()
    }

    /** Existing message-card action can explicitly reopen after 'answer later'. */
    fun showPending() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val owner = activity ?: return
        try {
            val record = registry.barrier.pending() ?: return
            dialog?.dismiss(); show(owner, record)
        } catch (_: Exception) { Toast.makeText(owner, STATE_FAILED, Toast.LENGTH_LONG).show() }
    }
}
