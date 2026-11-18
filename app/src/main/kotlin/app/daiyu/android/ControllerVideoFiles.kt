package app.daiyu.android

import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.domain.*
import app.daiyu.android.media.checkMediaPath
import app.daiyu.android.media.initializeMediaRoot
import app.daiyu.android.media.mediaDirectory
import app.daiyu.android.media.video.MessageVideoOwner
import app.daiyu.android.media.video.MessageVideoReference
import app.daiyu.android.media.video.VideoAssetStore
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.model.images.FrozenMessageImagePlan
import app.daiyu.android.model.videos.MessageVideoInput
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.workspace.ConversationWorkspaceRun
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

internal data class ControllerVideoFile(
    val owner: MessageVideoOwner,
    val reference: MessageVideoReference,
    val linuxPath: String,
    val hostPath: String,
)

/** Materialize only verified message-owned originals inside the run's selected workspace.
 * Copies are tool inputs, never an alternative authority for the private original/sidecar.
 */
internal class ControllerVideoFileExporter(private val assets: VideoAssetStore,
    private val hostRoot: File, private val guestRoot: String) {
    fun export(inputs: List<MessageVideoInput>, cancelled: () -> Boolean): List<ControllerVideoFile> {
        checkMediaPath(hostRoot)
        check(hostRoot.isDirectory) { "video_workspace_missing" }
        val root = initializeMediaRoot(File(hostRoot, ".daiyu-video-inputs"))
        check(root.parentFile == hostRoot.canonicalFile) { "video_workspace_changed" }
        return inputs.flatMap { input ->
            input.references.map { ref ->
                check(ref.owner == input.owner)
                check(!cancelled()) { "video_file_cancelled" }
                val ownerKey = MessageDigest.getInstance("SHA-256").digest(
                    "${input.owner.conversationId}\u0000${input.owner.messageId}".toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                val directory = mediaDirectory(root, ownerKey, create = true)
                val extension = app.daiyu.android.media.video.videoFileExtension(ref.mime)
                val name = "${ref.id}.$extension"
                val destination = File(directory, name)
                val pending = File(directory, ".${UUID.randomUUID()}.pending")
                checkMediaPath(destination)
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    var copied = false
                    assets.openOriginal(input.owner, ref, cancelled).use { source ->
                        if (matches(destination, ref, cancelled)) return@use
                        pending.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                check(!cancelled()) { "video_file_cancelled" }
                                val count = source.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                total += count
                                check(total <= ref.bytes) { "video_file_size_changed" }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                        copied = true
                    }
                    if (copied) {
                        val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                        check(total == ref.bytes && sha == ref.sha256) { "video_file_integrity_changed" }
                        check(!cancelled()) { "video_file_cancelled" }
                        checkMediaPath(hostRoot)
                        checkMediaPath(destination)
                        Files.move(pending.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                    }
                } finally {
                    Files.deleteIfExists(pending.toPath())
                }
                ControllerVideoFile(input.owner, ref,
                    "${guestRoot.trimEnd('/')}/.daiyu-video-inputs/$ownerKey/$name", destination.path)
            }
        }
    }

    private fun matches(file: File, reference: MessageVideoReference, cancelled: () -> Boolean): Boolean {
        checkMediaPath(file)
        if (!file.isFile || file.length() != reference.bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check(!cancelled()) { "video_file_cancelled" }
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                bytes += count
                if (bytes > reference.bytes) return false
                digest.update(buffer, 0, count)
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        return bytes == reference.bytes && sha == reference.sha256
    }
}

internal fun videoFileModelHistory(rows: List<Message>, files: List<ControllerVideoFile>): List<Message> {
    val expanded = MessageHistory.expand(rows)
    val byOwner = files.groupBy { it.owner }
    check(byOwner.keys.all { owner -> expanded.count {
        it.role == Message.USER_ROLE && it.conversationId.value == owner.conversationId && it.id.value == owner.messageId
    } == 1 }) { "video_file_owner_not_in_history" }
    return expanded.map { row ->
        val videos = byOwner[MessageVideoOwner(row.conversationId.value, row.id.value)].orEmpty()
        if (videos.isEmpty()) row else {
            check(row.role == Message.USER_ROLE && row.protocolItems.isEmpty())
            val manifest = buildString {
                append("\n\n[用户视频附件：本地原视频文件，尚未分析]\n")
                videos.forEachIndexed { index, file ->
                    append("视频 ${index + 1}: ${file.reference.mime}, ${file.reference.durationMs} ms, ")
                    append("${file.reference.width}×${file.reference.height}, ${file.reference.bytes} bytes\n")
                    append("Linux 路径: ${JSONObject.quote(file.linuxPath)}\n")
                    append("Android 路径: ${JSONObject.quote(file.hostPath)}\n")
                }
                append("视频没有直接上传给模型。请使用当前已选 Linux 环境按需读取、抽帧；")
                append("缺少抽帧工具时，可自行通过当前 Linux 环境的包管理器安装。")
                append("抽帧图片及其他分析临时文件必须放在 Linux /tmp 下为本次分析新建的独立临时目录，")
                append("例如使用 mktemp -d /tmp/daiyu-video-XXXXXX 创建；不要写入会话工作区。")
                append("调用 read_image 读取 /tmp 中的 PNG 抽帧，单张 PNG 不超过 128 KiB；超限时缩小画面后重新抽帧。")
                append("通过图片读取工具实际查看所需画面并完成分析后，立即删除本次生成的临时文件和该目录；")
                append("失败或取消时也要清理，但不能在图片读取完成前删除。")
                append("只清理本次分析产物，不得清空整个 /tmp，不得删除输入视频原件、")
                append("会话工作区 .daiyu-video-inputs 中的原视频副本或其他任务、用户的文件。")
                append("请依据实际读取结果回答，不要仅凭文件名推测内容。")
                append("不要切换 Linux 后端，也不要在 Android 环境安装替代工具。")
            }
            row.copy(blocks = row.blocks + MessageBlock.Text("local-video-files", manifest))
        }
    }
}

/** Ordinary model/tool route: local file metadata is projected only at the model boundary.
 * Physical history and video sidecars still participate in the existing acceptance CAS.
 */
internal class ControllerVideoFiles(private val database: ConversationDatabase,
    private val assets: VideoAssetStore, private val images: ControllerMessageImages) {
    private val history = ControllerVideoHistory(database)

    fun preflight(snapshot: ControllerVideoHistorySnapshot, provider: ProviderSnapshot, user: Message,
        pending: PreparedControllerVideoSend?, workspace: ConversationWorkspaceRun,
        cancelled: () -> Boolean): ControllerVideoModelPlan {
        require(user.conversationId == snapshot.conversation && (pending == null || pending.user == user))
        return freeze(snapshot.rows + user, snapshot.inputs + pending?.inputs.orEmpty(),
            provider, user, workspace, cancelled)
    }

    private fun freeze(rows: List<Message>, inputs: List<MessageVideoInput>, provider: ProviderSnapshot,
        candidate: Message?, workspace: ConversationWorkspaceRun, cancelled: () -> Boolean): ControllerVideoModelPlan {
        check(!cancelled()) { "video_file_cancelled" }
        validateVideoHistory(rows, inputs)
        val frozen = physicalVideoHistoryCopy(rows)
        val imagePlan = images.freeze(frozen, provider, candidate, cancelled)
        val files = ControllerVideoFileExporter(assets, workspace.resolved.hostDirectory,
            workspace.resolved.guestDirectory ?: "/workspace").export(inputs, cancelled)
        return ControllerVideoModelPlan(frozen, null, imagePlan, inputs, files)
    }

    fun wrap(delegate: ModelPort, provider: ProviderSnapshot, conversation: ConversationId,
        activeAssistant: MessageId, baseline: ControllerVideoModelPlan, workspace: ConversationWorkspaceRun,
        cancelled: () -> Boolean): ModelPort = object : ModelPort {
        private fun openPlan(source: List<Message>, definitions: List<ToolDefinition>,
            supplied: ModelImagePlan? = null): ModelSession {
            val snapshot = history.capture(conversation)
            check(snapshot.exists) { "video_conversation_missing" }
            val users = MessageHistory.expand(baseline.history).filter { it.role == Message.USER_ROLE }
                .map { MessageVideoOwner(it.conversationId.value, it.id.value) }.toSet()
            fun signature(inputs: List<MessageVideoInput>) = inputs.associate { it.owner to it.references }
            check(signature(baseline.inputs) == signature(snapshot.inputs.filter { it.owner in users })) {
                "video_baseline_sidecar_changed"
            }
            fun normalized(rows: List<Message>) = MessageHistory.expand(ModelHistory.project(rows)).map {
                if (it.id == activeAssistant && it.role == Message.ASSISTANT_ROLE)
                    it.copy(status = RunStatus.COMPLETED, error = null) else it
            }
            val active = snapshot.rows.singleOrNull { it.id == activeAssistant && it.role == Message.ASSISTANT_ROLE }
            val expected = if (active != null && active.blocks.isEmpty() && active.protocolItems.isEmpty() &&
                active.historySegments.isEmpty()) snapshot.rows.filterNot { it.id == activeAssistant } else snapshot.rows
            check(normalized(source) == normalized(expected)) { "video_model_requires_full_physical_history" }
            val plan = freeze(physicalVideoHistoryCopy(source), snapshot.inputs, provider, null, workspace, cancelled)
            if (supplied != null) {
                require(supplied is FrozenMessageImagePlan)
                supplied.validate(provider.asModelSettings(), provider.protocol, provider.imageInputSupported, source)
            }
            DatabaseTransactions { database.readableDatabase }.run { history.assertCurrent(it, snapshot) }
            check(!cancelled()) { "video_file_cancelled" }
            val projected = plan.modelHistory()
            val imagePlan = plan.images.withAppendedUserText(projected)
            val session = if (imagePlan.hasImages) delegate.open(projected, definitions, imagePlan)
                else delegate.open(projected, definitions)
            return guardControllerVideoModelSession(session, cancelled)
        }
        override fun open(history: List<Message>): ModelSession = open(history, provider.asModelSettings().enabledTools())
        override fun open(history: List<Message>, tools: Set<ReadOnlyTool>): ModelSession = openPlan(history, tools.map {
            ToolDefinition(it.toolName, it.description,
                "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}")
        })
        override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession = openPlan(history, definitions)
        override fun open(history: List<Message>, definitions: List<ToolDefinition>, images: ModelImagePlan): ModelSession =
            openPlan(history, definitions, images)
    }
}
