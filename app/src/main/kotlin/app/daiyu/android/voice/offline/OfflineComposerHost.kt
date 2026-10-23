package app.daiyu.android.voice.offline

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import app.daiyu.android.voice.ActivityVoiceAudio
import app.daiyu.android.voice.DictationDraftTarget
import app.daiyu.android.voice.VoiceOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface OfflineSpeechAttempt : AutoCloseable { fun start() }

/** Main-thread editor owner. A partial is presentation only, and nothing in this host can send. */
internal class OfflineComposerHost(
    private val factory: (VoiceOwner, OfflineSpeechSession.Events) -> OfflineSpeechAttempt,
    private val enabled: () -> Boolean,
    private val permission: () -> Boolean,
    private val requestPermission: () -> Unit,
    private val current: (DictationDraftTarget) -> Boolean,
    private val currentText: () -> String,
    private val applyDraft: (DictationDraftTarget, String, Int) -> Unit,
) : AutoCloseable {
    constructor(context: Context,
        requestPermission: () -> Unit,
        current: (DictationDraftTarget) -> Boolean,
        currentText: () -> String,
        applyDraft: (DictationDraftTarget, String, Int) -> Unit,
        inputEnabled: () -> Boolean = { OfflineSpeechPack.state.value.enabled },
        ready: () -> Boolean = { OfflineSpeechPack.state.value.ready },
        sessionFactory: (VoiceOwner, () -> Boolean, OfflineSpeechSession.Events) -> OfflineSpeechAttempt =
            { owner, allowed, events -> OfflineSpeechSession(ActivityVoiceAudio.gate, owner, allowed,
                AndroidOfflineSpeechDevice(context.applicationContext), events) },
    ) : this(
        factory = { owner, events -> sessionFactory(owner, { inputEnabled() && ready() }, events) },
        enabled = { inputEnabled() && ready() },
        permission = { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
        requestPermission = requestPermission, current = current, currentText = currentText, applyDraft = applyDraft,
    )

    data class State(val listening: Boolean = false, val preview: String = "", val status: String = "",
        val target: DictationDraftTarget? = null)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var active: DictationDraftTarget? = null
    private var attempt: OfflineSpeechAttempt? = null
    private var closed = false
    private var attemptSerial = 0L
    private data class PermissionRequest(val target: DictationDraftTarget, val serial: Long)
    private var pendingPermission: PermissionRequest? = null
    private fun owns(target: DictationDraftTarget, serial: Long) = !closed && active == target && attemptSerial == serial
    private fun accepts(target: DictationDraftTarget, serial: Long) = owns(target, serial) && current(target) && enabled()

    fun start(target: DictationDraftTarget, text: String, selectionStart: Int, selectionEnd: Int) {
        if (closed) return
        if (pendingPermission != null) {
            invalidate() // Retire editor authority, keeping the outstanding request until its result.
            return
        }
        if (active != null) { invalidate(); return }
        if (!current(target) || !enabled()) return
        invalidate()
        if (!permission()) {
            val pending = PermissionRequest(target, attemptSerial)
            pendingPermission = pending
            mutable.value = State(status = "离线听写需要麦克风权限；授权后请再次点击，不会自动开始")
            try { requestPermission() } catch (_: Exception) {
                if (pendingPermission === pending) {
                    pendingPermission = null
                    if (!closed && attemptSerial == pending.serial && current(target) && enabled()) {
                        mutable.value = State(status = "无法请求麦克风权限，请在系统设置中检查权限")
                    }
                }
            }
            return
        }
        val draft = SpeechDraft(text, selectionStart, selectionEnd)
        val serial = attemptSerial
        active = target
        mutable.value = State(target = target, status = "正在本机加载离线语音模型…")
        val callbacks = object : OfflineSpeechSession.Events {
            private var captureStopped = false
            override fun listening() {
                if (accepts(target, serial) && !captureStopped) mutable.value = mutable.value.copy(listening = true,
                    status = "离线听写中；再次点击可取消（不会发送）")
            }
            override fun captureStopped() {
                if (!accepts(target, serial)) return
                captureStopped = true
                mutable.value = mutable.value.copy(listening = false, status = "收音已结束，正在完成离线识别…")
            }
            override fun partial(text: String) {
                if (accepts(target, serial)) mutable.value = mutable.value.copy(preview = text)
            }
            override fun complete(text: String) {
                if (!accepts(target, serial)) return
                if (text.isBlank()) {
                    invalidate()
                    mutable.value = State(status = "未识别到文本；草稿未更改")
                    return
                }
                val next = draft.accept(currentText(), text)
                // Fence all callbacks before Activity edits/increments draftRevision.
                invalidate()
                if (next == null) return
                try {
                    applyDraft(target, next, draft.cursor)
                    mutable.value = State(status = "离线识别已填入捕获的草稿，请检查后手动发送")
                } catch (_: Exception) { mutable.value = State(status = "离线草稿保存失败，请重试") }
            }
            override fun cancelled() {
                if (!owns(target, serial)) return
                invalidate()
                mutable.value = State(status = "离线听写已停止；草稿未更改")
            }
            override fun failed() {
                if (!owns(target, serial)) return
                invalidate()
                mutable.value = State(status = "离线识别或音频释放失败；草稿未更改。若音频不可用，请重启应用")
            }
        }
        try {
            val next = factory(VoiceOwner(target.conversationId ?: "offline-draft-${target.sessionId}",
                "offline-${target.sessionId}", target.sessionId), callbacks)
            attempt = next
            next.start()
        } catch (_: Throwable) {
            invalidate()
            mutable.value = State(status = "无法启动离线语音；请检查权限、模型和音频占用")
        }
    }

    fun permissionResult(granted: Boolean) {
        val pending = pendingPermission ?: return
        pendingPermission = null
        if (closed || pending.serial != attemptSerial || !current(pending.target) || !enabled()) return
        mutable.value = State(status = if (granted) "麦克风权限已授予，请再次点击离线语音"
            else "麦克风权限被拒绝；草稿未更改")
    }

    fun invalidate() {
        attemptSerial++ // Retire callbacks even if the next attempt captures the same draft target.
        active = null
        val old = attempt
        attempt = null
        mutable.value = State()
        try { old?.close() } catch (_: Throwable) {
            mutable.value = State(status = "音频释放未获确认，已阻止继续使用语音；请重启应用")
        }
    }
    override fun close() { if (!closed) { closed = true; invalidate() } }
}
