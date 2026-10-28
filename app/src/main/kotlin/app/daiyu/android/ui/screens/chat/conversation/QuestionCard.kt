package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.ui.screens.settings.settingsDisclosureRotation
import app.daiyu.android.ui.screens.settings.SettingsExpandableContent
import app.daiyu.android.ui.app.DaiyuPalette
import app.daiyu.android.ui.components.imeCursorBrush
import app.daiyu.android.ui.components.managedInput

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import app.daiyu.android.ui.haptics.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

    val colors = questionCardColors()
    val shape = RoundedCornerShape(14.dp)
    var expanded by remember(owner) {
        mutableStateOf(confirmedAnswer == null || draft.reply == Reply.SAVED_REFRESH_FAILED)
    }
    val headingInteraction = remember { MutableInteractionSource() }
    val headingFocused by headingInteraction.collectIsFocusedAsState()
    val headingStatus = when {
        confirmedAnswer != null -> "已回答"
        value.interrupted -> "提问已中断"
        session.busy -> "正在提交…"
        !canAnswer || session.blocked -> "当前不可作答"
        else -> "需要你回答"
    }
    // This is only a presentation gate. The session remains the authority for every submission.
    val validSelection = when (val answer = selection) {
        is QuestionAnswer.Selected -> value.question.options.any { it.id == answer.optionId }
        is QuestionAnswer.Other -> value.question.allowOther && otherText.isNotBlank()
        is QuestionAnswer.Delegate -> value.question.allowDelegation
        null -> false
    }
    val submitEnabled = editable && validSelection
    Column(
        modifier.testTag("question-card").fillMaxWidth().clip(shape)
            .background(colors.surface)
            .drawWithContent {
                drawContent()
                // The reference's inset top highlight; backdrop blur belongs to the parent chain.
                drawLine(colors.highlight, Offset(14.dp.toPx(), 1.dp.toPx()),
                    Offset(size.width - 14.dp.toPx(), 1.dp.toPx()), 1.dp.toPx())
            }
            .border(1.dp, colors.line, shape).padding(1.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().testTag("question-toggle").heightIn(min = 40.dp)
                .clickable(interactionSource = headingInteraction, indication = null, role = Role.Button) {
                    expanded = !expanded
                }
                .then(if (headingFocused) Modifier.border(1.dp, colors.accent, RoundedCornerShape(12.dp)) else Modifier)
                .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
                .padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(DaiyuIcons.Interactive, null, Modifier.size(14.dp), tint = colors.accent)
            Text("$headingStatus · ${value.question.title}",
                Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                color = colors.accent, style = questionTextStyle(10, 1.5f, FontWeight.Medium),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(DaiyuIcons.ChevronDown, null, Modifier.size(10.dp).settingsDisclosureRotation(expanded, expandedDegrees = 180f),
                tint = colors.muted)
        }
        SettingsExpandableContent(expanded) {
            QuestionDivider(colors.line)
            Column(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 12.dp)) {
                Text(value.question.question, color = colors.text, style = questionTextStyle(11, 1.7f))
                Spacer(Modifier.height(10.dp))
                when {
                    confirmedAnswer != null -> {
                        val option = (confirmedAnswer as? QuestionAnswer.Selected)?.let { answer ->
                            value.question.options.firstOrNull { it.id == answer.optionId }
                        }
                        val label = when (confirmedAnswer) {
                            is QuestionAnswer.Selected -> option?.label ?: "已保存选项"
                            is QuestionAnswer.Other -> "其他… ${confirmedAnswer.text}"
                            is QuestionAnswer.Delegate -> "你看着办"
                        }
                        Text("已选择：$label", color = colors.text,
                            style = questionTextStyle(11, 1.8f, FontWeight.Medium))
                        val description = if (confirmedAnswer is QuestionAnswer.Delegate)
                            "仅把当前问题交给代鱼决定，不扩大操作权限。" else option?.description.orEmpty()
                        if (description.isNotEmpty()) {
                            Spacer(Modifier.height(5.dp))
                            Text(description, color = colors.muted, style = questionTextStyle(10, 1.7f))
                        }
                        confirmedAnswer.note?.takeIf { it.isNotEmpty() }?.let { note ->
                            Spacer(Modifier.height(12.dp))
                            QuestionDivider(colors.line)
                            Spacer(Modifier.height(9.dp))
                            Text("补充说明（可选）", color = colors.muted,
                                style = questionTextStyle(9, 1.8f, FontWeight.Medium))
                            Spacer(Modifier.height(4.dp))
                            Text(note, color = colors.text, style = questionTextStyle(10, 1.8f))
                        }
                    }
                    value.interrupted -> {
                        // No fabricated expiry reason: the domain currently exposes interruption only.
                        Text("提问已中断，当前不可作答", color = colors.muted,
                            style = questionTextStyle(10, 1.8f))
                    }
                    else -> {
                        Column(Modifier.fillMaxWidth().selectableGroup().alpha(if (editable) 1f else .5f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                QuestionOptionRow("其他…", "", false, selection is QuestionAnswer.Other, editable,
                                    { session.select(QuestionAnswer.Other("")) }, Modifier.testTag("question-option:$index"))
                                if (selection is QuestionAnswer.Other) {
                                    QuestionInput(otherText, "填写你的选择", editable, { session.editOther(it) },
                                        Modifier.padding(start = 29.dp, end = 7.dp, bottom = 5.dp)
                                            .testTag("question-other"), fadeDisabled = false)
                                }
                                index++
                            }
                            if (value.question.allowDelegation) {
                                QuestionOptionRow("你看着办", "仅把当前问题交给代鱼决定，不扩大操作权限", false,
                                    selection is QuestionAnswer.Delegate, editable,
                                    { session.select(QuestionAnswer.Delegate()) }, Modifier.testTag("question-option:$index"))
                            }
                        }
                        if (value.question.allowNote) {
                            Spacer(Modifier.height(9.dp))
                            QuestionInput(noteText, "补充说明（可选）", editable, { session.editNote(it) },
                                Modifier.testTag("question-note"))
                        }
                        Spacer(Modifier.height(10.dp))
                        val submitShape = RoundedCornerShape(11.dp)
                        Box(Modifier.testTag("question-submit").fillMaxWidth().heightIn(min = 36.dp)
                            .alpha(if (submitEnabled) 1f else .4f).clip(submitShape)
                            .background(if (submitEnabled) colors.accent else colors.soft)
                            .border(1.dp, if (submitEnabled) colors.accent else colors.line, submitShape)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                                enabled = submitEnabled, role = Role.Button, onClick = session::submit)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center) {
                            Text(if (session.busy) "正在提交…" else "提交回答",
                                color = if (submitEnabled) colors.onAccent else colors.muted,
                                style = questionTextStyle(10, 1.5f, FontWeight.Medium))
                        }
                    }
                }
                // Keep real validation / delivery failures visible without duplicating normal header status.
                if (status.isNotEmpty() && !value.interrupted &&
                    (isError || draft.reply == Reply.UNCONFIRMED || draft.reply == Reply.STOPPED)) {
                    Spacer(Modifier.height(8.dp))
                    Text(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = if (isError) colors.error else colors.muted, style = questionTextStyle(10, 1.7f))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
    val colors = questionCardColors()
    val selectionAccent = DaiyuPalette.selectionAccent
    val shape = RoundedCornerShape(12.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp).clip(shape)
        .background(if (selected) colors.soft else Color.Transparent)
        .then(if (focused) Modifier.border(1.dp, selectionAccent, shape) else Modifier)
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton,
            interactionSource = interaction, indication = null, onClick = onClick)
        .padding(horizontal = 7.dp, vertical = 9.dp), verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.padding(top = 2.dp).size(13.dp)
            .border(1.3.dp, if (selected) selectionAccent else colors.muted, CircleShape),
            contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(6.dp).background(selectionAccent, CircleShape))
        }
        Column(Modifier.weight(1f)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, Modifier.align(Alignment.CenterVertically), color = colors.text,
                    style = questionTextStyle(10, 1.6f, FontWeight.Medium))
                if (recommended) {
                    val badgeShape = RoundedCornerShape(5.dp)
                    Box(Modifier.align(Alignment.CenterVertically).clip(badgeShape)
                        .background(colors.soft).border(1.dp, colors.line, badgeShape)
                        .padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("推荐", color = colors.accent, style = questionTextStyle(7, 1.6f))
                    }
                }
            }
            if (description.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(description, color = colors.muted, style = questionTextStyle(9, 1.65f))
            }
        }
    }
}

@Composable
private fun QuestionInput(
    value: String,
    hint: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    fadeDisabled: Boolean = true,
) {
    val colors = questionCardColors()
    val shape = RoundedCornerShape(11.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(value = value, onValueChange = { onValueChange(questionTextLimit(it)) }, enabled = enabled,
        interactionSource = interaction,
        modifier = modifier.managedInput().fillMaxWidth().heightIn(min = 48.dp, max = 160.dp)
            .alpha(if (fadeDisabled && !enabled) .5f else 1f).clip(shape).background(colors.soft)
            .border(1.dp, if (focused) colors.accent else colors.line, shape)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        textStyle = questionTextStyle(10, 1.6f).copy(color = colors.text),
        cursorBrush = imeCursorBrush(colors.text),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(hint, color = colors.muted, style = questionTextStyle(10, 1.6f))
                inner()
            }
        })
}

@Composable
private fun QuestionDivider(color: Color) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(color))
}

@Composable
private fun questionTextStyle(size: Int, leading: Float, weight: FontWeight = FontWeight.Normal): TextStyle =
    MiuixTheme.textStyles.body2.copy(
        fontSize = size.sp,
        lineHeight = (size * leading).sp,
        fontWeight = weight,
        letterSpacing = 0.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )

private data class QuestionCardColors(
    val accent: Color,
    val muted: Color,
    val line: Color,
    val surface: Color,
    val soft: Color,
    val text: Color,
    val onAccent: Color,
    val error: Color,
    val highlight: Color,
)

@Composable
private fun questionCardColors(): QuestionCardColors =
    // Follow the active app theme, including an explicit theme override rather than system mode.
    if (MiuixTheme.colorScheme.surface.luminance() < .5f) {
        QuestionCardColors(
            accent = Color(0xFFBBAACB), muted = Color(0xFF8F879B), line = Color(0x24C2ADDB),
            surface = Color(0x85282331), soft = Color(0x13BEA8D7), text = Color(0xFFE0DCE8),
            onAccent = Color(0xFF241E2E), error = Color(0xFFD896A1), highlight = Color(0x08FFFFFF),
        )
    } else {
        QuestionCardColors(
            accent = Color(0xFF756980), muted = Color(0xFF918997), line = Color(0x2692819F),
            surface = Color(0x8CFAF8FC), soft = Color(0x108A779A), text = Color(0xFF36333F),
            onAccent = Color.White, error = Color(0xFFAD626E), highlight = Color(0x70FFFFFF),
        )
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
