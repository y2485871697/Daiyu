package app.daiyu.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.daiyu.android.backup.legacy.LegacyConversionReport
import app.daiyu.android.backup.legacy.LegacyImportService
import app.daiyu.android.backup.legacy.LegacyMedia
import app.daiyu.android.backup.legacy.LegacyPreview
import app.daiyu.android.backup.legacy.LegacyReadOnlyHistory
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * One controller-owned library, one Page per route visit. No executor, coroutine, SQLite helper,
 * native chat, model, tool, file-reference resolver or network client is created here.
 *
 * serviceFactory MUST construct one LegacyImportService on the controller's SAME database, wiring
 * its beforeCommit to the supplied callback. This makes the service's last pre-write fence check
 * the captured visit, rather than a replacement page. Construction and every service call occur
 * inside Gateway work. Page/picker events and state reads are UI-thread only; owner predicates
 * and pre-write assertions also run on the existing worker and must be thread-safe.
 * See LegacyLibraryHost.README.md for exact Main/dataTask hooks.
 */
internal class LegacyLibraryHost(
    private val resolver: ContentResolver,
    serviceFactory: (beforeCommit: () -> Unit) -> LegacyImportService,
    private val gateway: Gateway,
) {
    /** Existing controller.dataTask only. False means not queued and work was NOT invoked.
     * Work is serialized with all other DB/data tasks; completed runs once on UI after settling.
     */
    fun interface Gateway {
        fun submit(work: () -> Unit, completed: (Boolean) -> Unit): Boolean
    }

    private var commitAdmission: (() -> Unit)? = null
    private val service by lazy(LazyThreadSafetyMode.NONE) {
        serviceFactory { checkNotNull(commitAdmission) { "No legacy commit admission" }.invoke() }
    }
    private val seenSources = linkedSetOf<String>()

    fun openPage(ownerCurrent: () -> Boolean): Page = Page(ownerCurrent)

    /** Main retains this exact object through its source launcher callback; never restore it. */
    class SourcePicker internal constructor(private val deliver: (Uri?) -> Unit) {
        fun result(uri: Uri?) = deliver(uri)
    }

    /** Main launches ACTION_CREATE_DOCUMENT, application/octet-stream, using this safe name. */
    class SavePicker internal constructor(private val deliver: (Uri?) -> Unit) {
        val suggestedName: String = "legacy-original.bin"
        val mimeType: String = "application/octet-stream"
        fun result(uri: Uri?) = deliver(uri)
    }

    inner class Page internal constructor(ownerCurrent: () -> Boolean) {
        private val owner = LegacyLibraryOwner(ownerCurrent)
        private var confirmation: Confirmation? = null
        private var opened: LegacyReadOnlyHistory? = null
        var state by mutableStateOf(LegacyLibraryState())
            private set

        fun checkOwner() { if (owner.current() == null) revoke() }

        /** UI-thread, synchronous and memory-only. Does not wait for or cancel admitted I/O. */
        fun revoke() {
            owner.revoke()
            confirmation = null
            opened = null
            state = LegacyLibraryState(active = false, notice = "本次页面已撤销，请返回后重新打开。")
        }

        private fun assertOwner(epoch: Long) { check(owner.accepts(epoch)) { "Legacy visit revoked" } }

        private fun changeScope(): Long? {
            val epoch = owner.rotate() ?: run { revoke(); return null }
            confirmation = null
            opened = null
            state = LegacyLibraryState(histories = state.histories, contentKey = Any())
            return epoch
        }

        fun cancelPending() {
            changeScope() ?: return
            state = state.copy(notice = "已撤销本次预览、确认与文件选择。已开始的持久化操作请从历史核实。")
        }

        fun requestSource(): SourcePicker? {
            if (owner.current() == null) { revoke(); return null }
            if (state.busy) return null
            val epoch = changeScope() ?: return null
            val once = LegacyLibraryOnce(owner, epoch)
            state = state.copy(pending = true, notice = "仅选择来源进行只读预览；不会自动导入。")
            return SourcePicker { uri ->
                if (once.consume()) acceptSource(epoch, uri)
            }
        }

        private fun acceptSource(epoch: Long, uri: Uri?) {
            if (uri == null) {
                state = state.copy(pending = false, notice = "已取消来源选择。")
                return
            }
            if (!isContent(uri)) {
                state = state.copy(pending = false, notice = "仅接受只读 content URI 的 JSON / ZIP。")
                return
            }
            synchronized(seenSources) { seenSources += sourceKey(uri) }
            work(epoch, risky = false) {
                val preview = requireNotNull(resolver.openInputStream(uri)).use { service.preview(it) }
                requireSha(preview.sha256)
                requireReadOnly(preview.report)
                val ticket = Confirmation(uri, preview.sha256, LegacyLibraryOnce(owner, epoch))
                WorkResult(
                    LegacyLibraryState(
                        histories = stateHistoriesAtStart,
                        pending = true,
                        previewSha256 = preview.sha256,
                        canConfirm = true,
                        sections = listOf(LegacyTextSection("来源 URI（只读，不默认保留授权）", uri.toString())) + previewSections(preview),
                        contentKey = Any(),
                        notice = "只读预览完成。请核对报告后，显式确认本 URI 与 SHA 的一次性导入。",
                    ),
                    confirmation = ticket,
                )
            }
        }

        fun confirmPreview() {
            val ticket = confirmation ?: return
            if (state.busy || !ticket.once.consume()) return
            confirmation = null // consume BEFORE queue admission, including rejected/unknown work
            state = state.copy(canConfirm = false, pending = false)
            val epoch = ticket.once.epoch
            work(epoch, risky = true) {
                assertOwner(epoch)
                check(commitAdmission == null)
                commitAdmission = { assertOwner(epoch) }
                val result = try {
                    requireNotNull(resolver.openInputStream(ticket.uri)).use {
                        // Reopens the same URI. The service verifies the exact SHA before commit.
                        service.commit(it, ticket.sha256)
                    }
                } finally { commitAdmission = null }
                requireReadOnly(result.report)
                WorkResult(LegacyLibraryState(
                    histories = stateHistoriesAtStart,
                    importId = result.importId,
                    sections = listOf(
                        LegacyTextSection("导入结果（兼容库，不是原生会话）",
                            "importId: ${result.importId}\nconversationIds:\n${result.conversationIds.joinToString("\n")}"),
                        reportSection(result.report),
                    ),
                    contentKey = Any(),
                    notice = "兼容库提交已返回。请显式读取历史并打开记录查看；不会自动创建或切换原生会话。",
                ))
            }
        }

        /** Even an in-flight old page loses authorization synchronously before queue admission. */
        fun histories() {
            val epoch = changeScope() ?: return
            work(epoch, risky = false) {
                val summaries = service.histories()
                require(summaries.map { it.id }.distinct().size == summaries.size)
                WorkResult(LegacyLibraryState(
                    histories = summaries.map { it.id },
                    sections = listOf(LegacyTextSection("独立导入历史索引", summaries.joinToString("\n\n") {
                        "id: ${it.id}\nimportId: ${it.importId}\nsourceId: ${it.sourceId}\ntitle: ${it.title}\n" +
                            "sourceCreatedAt: ${it.sourceCreatedAt}\nsourceUpdatedAt: ${it.sourceUpdatedAt}"
                    })),
                    contentKey = Any(),
                    notice = "已读取 ${summaries.size} 条兼容历史。请显式选择打开。",
                ))
            }
        }

        fun openHistory(id: String) {
            // IDs are taken only from this visit's independent index, never a native chat route.
            if (id !in state.histories) return
            val epoch = changeScope() ?: return
            work(epoch, risky = false) {
                val history = service.reopen(id)
                require(history.summary.id == id && history.readOnly)
                requireReadOnly(history.report)
                val stock = service.originals(history.summary.importId)
                val originals = inventory(stock)
                WorkResult(LegacyLibraryState(
                    histories = stateHistoriesAtStart,
                    importId = history.summary.importId,
                    sections = historySections(history) + LegacyTextSection("完整原件库存（含未引用或不可用条目）", mediaText(stock)),
                    originals = originals,
                    contentKey = Any(),
                    notice = "原 UI、model history 与 checkpoint 分开只读展示；没有恢复执行入口。",
                ), history = history)
            }
        }

        fun requestSave(entry: String): SavePicker? {
            if (owner.current() == null) { revoke(); return null }
            if (state.busy || state.pending) return null
            val history = opened ?: return null
            val original = state.originals.singleOrNull { it.key == entry } ?: return null
            val epoch = owner.current() ?: return null
            val once = LegacyLibraryOnce(owner, epoch)
            val conversationId = history.summary.id
            val importId = history.summary.importId
            state = state.copy(pending = true, notice = "请选择明确的 SAF 新文档目标。不会覆盖所知来源 URI；失败后不自动重试。")
            return SavePicker { target ->
                if (once.consume()) acceptSave(epoch, conversationId, importId, original, target)
            }
        }

        private fun acceptSave(epoch: Long, conversationId: String, importId: String,
            expected: LegacyOriginalRow, target: Uri?) {
            state = state.copy(pending = false)
            if (target == null) {
                state = state.copy(notice = "已取消原件另存。")
                return
            }
            if (!isContent(target)) {
                state = state.copy(notice = "保存只接受明确选择的 SAF content URI。")
                return
            }
            val previous = state
            val currentHistory = opened
            work(epoch, risky = true) {
                val history = service.reopen(conversationId)
                require(history.summary.id == conversationId && history.summary.importId == importId && history.readOnly)
                requireReadOnly(history.report)
                val stock = service.originals(importId)
                val actual = inventory(stock).singleOrNull { it.key == expected.key }
                require(actual == expected)
                rejectSourceTarget(target, history, stock)
                // Service original() verifies stored bytes/hash. Independently validate its return,
                // then reread once more immediately before output, so picker delay cannot pin bytes.
                verifyOriginal(service.original(importId, expected.key), expected)
                val bytes = service.original(importId, expected.key)
                verifyOriginal(bytes, expected)
                rejectSourceTarget(target, history, stock)
                assertOwner(epoch) // immediately before opening a potentially truncating target
                requireNotNull(resolver.openOutputStream(target, "wt")).use { output ->
                    var offset = 0
                    while (offset < bytes.size) {
                        assertOwner(epoch)
                        val count = minOf(32 * 1024, bytes.size - offset)
                        output.write(bytes, offset, count)
                        offset += count
                    }
                    assertOwner(epoch)
                    output.flush()
                }
                WorkResult(previous.copy(busy = false, pending = false, uncertain = false, notice = "原件已写入所选目标。请自行保管；兼容库没有删除原件。"),
                    history = currentHistory)
            }
        }

        /** Validated immutable start values only; workers never read Compose mutable state. */
        private inner class WorkScope(val stateHistoriesAtStart: List<String>)

        private fun work(epoch: Long, risky: Boolean, block: WorkScope.() -> WorkResult) {
            if (!owner.accepts(epoch)) { revoke(); return }
            val previous = state.copy(busy = false, pending = false, uncertain = false)
            val scope = WorkScope(previous.histories)
            state = previous.copy(busy = true)
            val result = AtomicReference<WorkResult?>()
            val submitted = try {
                gateway.submit(work = {
                    assertOwner(epoch)
                    result.set(scope.block())
                }, completed = { success ->
                    if (owner.accepts(epoch)) {
                        val value = result.get()
                        if (success && value != null) {
                            confirmation = value.confirmation
                            opened = value.history
                            state = value.state
                        } else {
                            confirmation = null
                            state = previous.copy(canConfirm = false, uncertain = risky,
                                notice = if (risky) "操作结果未确认；不自动重试。请显式重读历史；目标可能不完整。"
                                    else "读取或校验失败；未自动修复或重试。请检查来源并显式重新选择。")
                        }
                    }
                })
            } catch (_: Exception) { false }
            if (!submitted && owner.accepts(epoch)) {
                confirmation = null
                state = previous.copy(canConfirm = false,
                    notice = "当前无法排入数据操作；未重试。请等待后台任务结束后重新显式操作。")
            }
        }
    }

    private data class Confirmation(val uri: Uri, val sha256: String, val once: LegacyLibraryOnce)
    private data class WorkResult(val state: LegacyLibraryState,
        val confirmation: Confirmation? = null, val history: LegacyReadOnlyHistory? = null)

    internal fun rejectSourceTarget(target: Uri, history: LegacyReadOnlyHistory, stock: List<LegacyMedia> = emptyList()) {
        val key = sourceKey(target)
        require(synchronized(seenSources) { key !in seenSources })
        // Reference strings are compared as text only, never opened or fetched.
        require((history.media + stock).none { it.reference == target.toString() ||
            (it.reference.startsWith("content:", ignoreCase = true) && sourceKey(Uri.parse(it.reference)) == key) })
    }

    private fun isContent(uri: Uri): Boolean = uri.scheme.equals("content", ignoreCase = true) && !uri.authority.isNullOrBlank()

    private fun sourceKey(uri: Uri): String {
        // No provider call: tree/document aliases with the same authority/documentId compare equal.
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        return if (documentId != null) "${uri.authority}|$documentId"
        else "${uri.authority}|${uri.path}" // conservatively reject query/encoding variants too
    }

    internal fun inventory(items: List<LegacyMedia>): List<LegacyOriginalRow> {
        val rows = items.filter { it.available }.map {
            val entry = requireNotNull(it.entry)
            val sha = requireNotNull(it.sha256)
            val bytes = requireNotNull(it.bytes)
            require(entry.isNotEmpty() && bytes >= 0)
            requireSha(sha)
            LegacyOriginalRow(entry, sha, bytes)
        }
        // Multiple references to one identical stored original are legitimate; conflicts are not.
        require(rows.groupBy { it.key }.values.all { group -> group.distinct().size == 1 })
        return rows.distinctBy { it.key }
    }

    private fun requireSha(value: String) { require(value.matches(Regex("[0-9a-fA-F]{64}"))) }
    private fun requireReadOnly(report: LegacyConversionReport) {
        require(report.readOnly && report.nativeMessagesCreated == 0 && report.runtimeRequestsCreated == 0)
    }

    internal fun verifyOriginal(bytes: ByteArray, expected: LegacyOriginalRow) {
        require(bytes.size.toLong() == expected.bytes)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(hash.equals(expected.sha256, ignoreCase = true))
    }

    private fun reportSection(report: LegacyConversionReport) = LegacyTextSection("兼容转换报告",
        "readOnly: ${report.readOnly}\nnativeMessagesCreated: ${report.nativeMessagesCreated}\n" +
            "runtimeRequestsCreated: ${report.runtimeRequestsCreated}\n" +
            report.issues.joinToString("\n") { "${it.code} (${it.count}): ${it.detail}" })

    private fun previewSections(preview: LegacyPreview) = listOf(
        LegacyTextSection("来源预览统计", "format: ${preview.format}\nschemaVersion: ${preview.schemaVersion}\n" +
            "conversations: ${preview.conversations}\nuiMessages: ${preview.uiMessages}\n" +
            "modelHistoryMessages: ${preview.modelHistoryMessages}\nattachments: ${preview.attachments}\nblobBytes: ${preview.blobBytes}"),
        reportSection(preview.report),
    )

    private fun mediaText(items: List<LegacyMedia>) = items.joinToString("\n\n") {
        "reference: ${it.reference}\nentry: ${it.entry}\nsha256: ${it.sha256}\nbytes: ${it.bytes}\npurpose: ${it.purpose}\navailable: ${it.available}"
    }

    private fun historySections(history: LegacyReadOnlyHistory) = listOf(
        LegacyTextSection("历史身份与原始时间", history.summary.let {
            "id: ${it.id}\nimportId: ${it.importId}\nsourceId: ${it.sourceId}\ntitle: ${it.title}\n" +
                "sourceCreatedAt: ${it.sourceCreatedAt}\nsourceUpdatedAt: ${it.sourceUpdatedAt}"
        }),
        LegacyTextSection("原 UI 消息（纯文字）", history.messages.joinToString("\n\n") {
            "id: ${it.id}\nsourceId: ${it.sourceId}\ntype: ${it.type}\ncreatedAt: ${it.createdAt}\n" +
                "generatedAtMillis: ${it.generatedAtMillis}\ncontent:\n${it.content}"
        }),
        LegacyTextSection("model history（原文，只读，不执行）", history.modelHistoryJson),
        LegacyTextSection("checkpoint history（独立原文，只读，不恢复）", history.checkpointHistoryJson ?: "（来源没有 checkpoint history）"),
        LegacyTextSection("原始引用（仅文字，不读文件、不联网）", mediaText(history.media)),
        LegacyTextSection("compatibility（原文）", history.compatibilityJson),
        reportSection(history.report),
    )
}

/** Read-only presentation values. Never persist, parcel, log, or pass them into native chat. */
internal data class LegacyTextSection(val title: String, val text: String) {
    override fun toString() = "LegacyTextSection(<redacted>)"
}
internal data class LegacyOriginalRow(val key: String, val sha256: String, val bytes: Long)
internal data class LegacyLibraryState(
    val active: Boolean = true,
    val busy: Boolean = false,
    val pending: Boolean = false,
    val notice: String? = null,
    val uncertain: Boolean = false,
    val histories: List<String> = emptyList(),
    val importId: String? = null,
    val sections: List<LegacyTextSection> = emptyList(),
    val originals: List<LegacyOriginalRow> = emptyList(),
    val previewSha256: String? = null,
    val canConfirm: Boolean = false,
    val contentKey: Any? = null,
) { override fun toString() = "LegacyLibraryState(<redacted>)" }

/** Memory-only admission fence. Critical sections never perform I/O or wait for a worker. */
internal class LegacyLibraryOwner(private val ownerCurrent: () -> Boolean) {
    private var revoked = false
    private var epoch = 0L
    @Synchronized fun current(): Long? {
        if (revoked) return null
        if (!runCatching(ownerCurrent).getOrDefault(false)) { revoked = true; epoch++; return null }
        return epoch
    }
    @Synchronized fun accepts(expected: Long): Boolean = current() == expected
    @Synchronized fun rotate(): Long? { if (current() == null) return null; epoch++; return epoch }
    @Synchronized fun revoke() { revoked = true; epoch++ }
}

/** Consumption precedes queue admission; rejection/unknown outcomes never silently retry. */
internal class LegacyLibraryOnce(private val owner: LegacyLibraryOwner, val epoch: Long) {
    private var consumed = false
    @Synchronized fun consume(): Boolean {
        if (consumed) return false
        consumed = true
        return owner.accepts(epoch)
    }
}
