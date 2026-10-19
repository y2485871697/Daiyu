package app.daiyu.android.ui.screens.chat.conversation

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material3.Icon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.domain.question.QuestionAnswer
import app.daiyu.android.domain.question.QuestionIdentity
import app.daiyu.android.domain.question.QuestionRecord
import app.daiyu.android.question.QuestionCardBinding
import app.daiyu.android.question.QuestionCardView.Reply
import app.daiyu.android.ui.icons.DaiyuIcons
import java.util.concurrent.atomic.AtomicBoolean
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The binding owns the draft and all persistence, including while this row is not composed. */
@Composable
internal fun QuestionCard(
    owner: QuestionIdentity,
    record: QuestionRecord,
    canAnswer: Boolean,
    binding: QuestionCardBinding,
    modifier: Modifier = Modifier,
) {
    QuestionCardContent(owner, record, canAnswer, binding, binding::submit, modifier)
}

/** Callback-only counterpart of QuestionCardView.bind; suitable for read-only transcript rows too. */
@Composable
internal fun QuestionCard(
    owner: QuestionIdentity,
    record: QuestionRecord,
    canAnswer: Boolean,
    submit: (QuestionIdentity, QuestionAnswer, (Reply) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    QuestionCardContent(owner, record, canAnswer, null, submit, modifier)
}

@Composable
private fun QuestionCardContent(
    owner: QuestionIdentity,
    record: QuestionRecord,
    canAnswer: Boolean,
    binding: QuestionCardBinding?,
    submit: (QuestionIdentity, QuestionAnswer, (Reply) -> Unit) -> Unit,
    modifier: Modifier,
) {
    check(Looper.myLooper() == Looper.getMainLooper())
    require(record.callId == owner.storageKey) { "question_card_identity_mismatch" }
    val session = remember(owner, binding) {
        QuestionCardSession(owner, binding?.draft(owner) ?: QuestionCardBinding.Draft(), binding != null)
    }
    // Draft fields deliberately remain binding-owned, not a second rememberSaveable form model.
    @Suppress("UNUSED_VARIABLE") val revision = session.revision
    val draft = session.draft
    val saved = record.answer ?: draft.saved.takeIf { binding != null || !record.interrupted }
    val value = if (record.answer == null && saved != null) record.copy(answer = saved) else record
    val confirmedAnswer = value.answer
    val completed = confirmedAnswer != null || value.interrupted
    val selection = confirmedAnswer ?: draft.selection
    val otherText = if (confirmedAnswer is QuestionAnswer.Other) confirmedAnswer.text else draft.other
    val noteText = if (confirmedAnswer != null) confirmedAnswer.note.orEmpty() else draft.note
    val editable = canAnswer && !completed && !session.busy && !session.blocked
    val status = when {
        value.answer != null && draft.reply == Reply.SAVED_REFRESH_FAILED -> "答案已保存，消息刷新失败；请稍后查看状态"
        value.answer != null -> "答案已保存"
        value.interrupted -> "提问已中断"
        session.busy -> "正在保存答案…"
        session.validation.isNotEmpty() -> session.validation
        draft.reply == Reply.REJECTED -> "答案未被接受；可修改后重试，若提问已过期请刷新状态"
        draft.reply == Reply.UNCONFIRMED -> "提交结果未确认，请刷新状态；不会自动重复提交"
        draft.reply == Reply.STOPPED -> "本轮已停止，不能再回答"
        else -> ""
    }
    val isError = draft.reply == Reply.SAVED_REFRESH_FAILED || (!completed &&
        (session.validation.isNotEmpty() || draft.reply == Reply.REJECTED || draft.reply == Reply.UNCONFIRMED))
    SideEffect { session.bind(record, value, canAnswer, submit) }
    DisposableEffect(session) {
        session.attach()
        val changed: () -> Unit = { session.refresh() }
        if (binding != null) draft.changed = changed
        // Also read any result delivered between composition and installing the subscription.
        session.refresh()
        onDispose {
            if (draft.changed === changed) draft.changed = null
            session.detach()
        }
    }

    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    var expanded by remember(owner) { mutableStateOf(true) }
    Column(
        modifier.testTag("question-card").fillMaxWidth().clip(shape).background(colors.surface)
            .border(1.dp, colors.outline, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().testTag("question-toggle").heightIn(min = 44.dp)
            .clickable(role = Role.Button) { expanded = !expanded }
            .semantics { stateDescription = if (expanded) "已展开" else "已折叠" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(DaiyuIcons.Interactive, null, Modifier.size(16.dp), tint = colors.primary)
            Text(value.question.title, Modifier.weight(1f), color = colors.onSurface,
                style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp))
            Icon(if (expanded) DaiyuIcons.ChevronDown else DaiyuIcons.ChevronRight, null, Modifier.size(16.dp), tint = colors.onSurfaceVariantSummary)
        }
        if (!expanded) {
            val summary = when (val answer = confirmedAnswer) {
                is QuestionAnswer.Selected -> value.question.options.firstOrNull { it.id == answer.optionId }?.label ?: "已保存选项"
                is QuestionAnswer.Other -> answer.text
                is QuestionAnswer.Delegate -> "本题交给代鱼决定；不扩大权限"
                null -> if (value.interrupted || !canAnswer) "提问已失效，当前不可作答" else "需要你回答"
            }
            Text(summary, color = colors.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1)
            confirmedAnswer?.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MiuixTheme.textStyles.footnote1) }
        }
        if (expanded) {
        Text(value.question.question, color = colors.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2.copy(fontSize = 13.sp))
        Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            value.question.options.forEachIndexed { index, option ->
                QuestionOptionRow(
                    label = option.label,
                    description = option.description,
                    recommended = option.id == value.question.recommendedOptionId,
                    selected = selection is QuestionAnswer.Selected && selection.optionId == option.id,
                    enabled = editable,
                    onClick = { session.select(QuestionAnswer.Selected(option.id)) },
                    modifier = Modifier.testTag("question-option:$index"),
                )
            }
            var index = value.question.options.size
            if (value.question.allowOther) {
                QuestionOptionRow("其他（自行填写）", "", false, selection is QuestionAnswer.Other, editable,
                    { session.select(QuestionAnswer.Other("")) }, Modifier.testTag("question-option:$index"))
                index++
            }
            if (value.question.allowDelegation) {
                QuestionOptionRow("由你决定", "仅授权当前问题，不扩大设备权限", false, selection is QuestionAnswer.Delegate, editable,
                    { session.select(QuestionAnswer.Delegate()) }, Modifier.testTag("question-option:$index"))
            }
        }
        if (value.question.allowOther && selection is QuestionAnswer.Other) {
            QuestionInput(otherText, "其他答案", editable, { session.editOther(it) }, Modifier.testTag("question-other"))
        }
        if (value.question.allowNote && (!completed || noteText.isNotEmpty())) {
            QuestionInput(noteText, "补充说明（可选）", editable, { session.editNote(it) }, Modifier.testTag("question-note"))
        }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(status, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                color = if (isError) colors.error else colors.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp))
            if (!completed) {
                Box(Modifier.testTag("question-submit").clip(CircleShape)
                    .background(if (editable) colors.primary else colors.surfaceContainerHigh)
                    .clickable(enabled = editable, role = Role.Button, onClick = session::submit)
                    .heightIn(min = 48.dp).padding(horizontal = 24.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center) {
                    Text("提交答案", color = if (editable) colors.onPrimary else colors.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp))
                }
            }
        }
    }
}

@Composable
private fun QuestionOptionRow(
    label: String,
    description: String,
    recommended: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Row(modifier.fillMaxWidth().clip(shape)
        .background(if (selected) colors.primaryContainer else colors.surface)
        .border(1.dp, if (selected) colors.primary else colors.outline, shape)
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(24.dp).clip(CircleShape)
            .background(if (selected) colors.primary else colors.surface)
            .border(1.dp, if (selected) colors.primary else colors.outline, CircleShape),
            contentAlignment = Alignment.Center) {
            if (selected) Image(DaiyuIcons.Check, null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(colors.onPrimary))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, color = colors.onSurface, style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp))
            if (description.isNotEmpty()) Text(description, color = colors.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp))
        }
        if (recommended) Text("推荐", color = colors.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp))
    }
}

@Composable
private fun QuestionInput(
    value: String,
    hint: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    BasicTextField(value = value, onValueChange = { onValueChange(questionTextLimit(it)) }, enabled = enabled,
        modifier = modifier.fillMaxWidth().clip(shape).background(colors.surface)
            .border(1.dp, colors.outline, shape).heightIn(min = 48.dp).padding(12.dp),
        textStyle = MiuixTheme.textStyles.body2.copy(fontSize = 13.sp, color = colors.onSurface),
        cursorBrush = SolidColor(colors.onSurface),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(hint, color = colors.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2.copy(fontSize = 13.sp))
                inner()
            }
        })
}

private fun questionTextLimit(text: String): String {
    if (text.length <= 4000) return text
    val end = if (Character.isHighSurrogate(text[3999]) && Character.isLowSurrogate(text[4000])) 3999 else 4000
    return text.substring(0, end)
}

/** Main-thread UI attachment fence. It never starts a worker or resubmits on recomposition. */
private class QuestionCardSession(
    private val owner: QuestionIdentity,
    val draft: QuestionCardBinding.Draft,
    private val retained: Boolean,
) {
    var revision by mutableStateOf(0)
        private set
    var validation = ""
        private set
    private val main = Handler(Looper.getMainLooper())
    private var attached = false
    private var attachmentEpoch = 0L
    private var generation = 0L
    private var shownRecord: QuestionRecord? = null
    private var shownIncoming: QuestionRecord? = null
    private var writable = false
    private var submitting = false
    private var detachedSubmission = false
    private var submitAction: ((QuestionIdentity, QuestionAnswer, (Reply) -> Unit) -> Unit)? = null
    val busy: Boolean get() = submitting || draft.saving
    val blocked: Boolean get() = detachedSubmission || draft.reply == Reply.UNCONFIRMED || draft.reply == Reply.STOPPED
    private val editable: Boolean get() = attached && writable && !busy && !blocked &&
        shownRecord?.let { it.answer == null && !it.interrupted } == true

    fun bind(incoming: QuestionRecord, value: QuestionRecord, canAnswer: Boolean,
        submit: (QuestionIdentity, QuestionAnswer, (Reply) -> Unit) -> Unit) {
        if (incoming.answer != null) draft.saved = incoming.answer
        else if (!retained && incoming.interrupted) draft.saved = null
        val previous = shownIncoming
        shownIncoming = incoming
        if (previous != null && previous != incoming && previous.question != incoming.question) {
            // A different pending question replaced this card: drop transient form state. The
            // retained binding keeps owning its draft; only the callback overload resets its own.
            validation = ""
            submitting = false
            detachedSubmission = false
            if (!retained && incoming.answer == null) {
                draft.selection = null; draft.other = ""; draft.note = ""
                draft.saving = false; draft.reply = null
            }
        }
        if (shownRecord != value) {
            // Completions for the previous record are fenced by generation; release their local saving.
            if (shownRecord != null && submitting && !retained) { submitting = false; draft.saving = false }
            shownRecord = value; generation++
        }
        writable = canAnswer
        submitAction = submit
    }

    fun attach() { attached = true }
    fun detach() {
        attached = false
        attachmentEpoch++
        if (!retained && busy) detachedSubmission = true
        submitting = false
    }
    fun refresh() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!attached) return
        if (!draft.saving) submitting = false
        revision++
    }
    fun select(answer: QuestionAnswer) {
        if (!editable) return
        draft.selection = answer
        validation = ""
        revision++
    }
    fun editOther(text: String) {
        if (!editable) return
        draft.other = text
        validation = ""
        revision++
    }
    fun editNote(text: String) {
        if (!editable) return
        draft.note = text
        revision++
    }

    fun submit() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!editable) return
        val value = shownRecord ?: return
        val action = submitAction ?: return
        val note = draft.note.takeIf { value.question.allowNote && it.isNotEmpty() }
        val answer = when (val selection = draft.selection) {
            is QuestionAnswer.Selected -> if (value.question.options.any { it.id == selection.optionId })
                QuestionAnswer.Selected(selection.optionId, note) else null
            is QuestionAnswer.Other -> if (value.question.allowOther && draft.other.isNotBlank())
                QuestionAnswer.Other(draft.other, note) else null
            is QuestionAnswer.Delegate -> if (value.question.allowDelegation) QuestionAnswer.Delegate(note) else null
            null -> null
        }
        if (answer == null) {
            validation = if (draft.selection is QuestionAnswer.Other && value.question.allowOther) "请填写其他答案" else "请选择一个答案；推荐选项不会自动选择"
            revision++
            return
        }
        val token = generation
        val epoch = attachmentEpoch
        val delivered = AtomicBoolean(false)
        submitting = true
        validation = ""
        // Binding.submit claims its own saving flag synchronously. Do not pre-set it here.
        if (!retained) { draft.saving = true; draft.reply = null }
        revision++
        action(owner, answer) { reply ->
            if (delivered.compareAndSet(false, true)) main.post {
                if (!attached || attachmentEpoch != epoch || generation != token) return@post
                submitting = false
                if (!retained) {
                    draft.saving = false
                    draft.reply = reply
                    if (reply == Reply.SAVED || reply == Reply.SAVED_REFRESH_FAILED) draft.saved = answer
                }
                // A retained binding updates the draft before calling complete, even if detached.
                refresh()
            }
        }
    }
}
