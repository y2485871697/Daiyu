package app.daiyu.android

import app.daiyu.android.domain.*
import app.daiyu.android.media.MessageImageAssetStore
import app.daiyu.android.media.video.*
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.model.videos.MessageVideoInput
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.workspace.*
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ControllerVideoFilesTest {
    private lateinit var root: File
    private lateinit var assets: VideoAssetStore
    private lateinit var database: ConversationDatabase
    private lateinit var journal: ControllerVideoJournal
    private lateinit var images: ControllerMessageImages
    private lateinit var workspace: ConversationWorkspaceRun
    private val provider = ProviderSnapshot.capture(ModelSettings(
        "https://fixture.invalid/v1/responses", "fixture-current-model", "fixture-secret", false))
    private val bytes = byteArrayOf(0, 1, 2, 3, 127, -1)

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("conversations.db")
        database = ConversationDatabase(context)
        ControllerVideoManifestWitness.installSchema(database.writableDatabase)
        journal = ControllerVideoJournal(context.getSharedPreferences("video-files-${UUID.randomUUID()}", 0))
        root = Files.createTempDirectory("video-tool-files").toFile()
        assets = VideoAssetStore(File(root, "originals"), VideoAttachmentLimits(),
            VideoInspector { _, _, _ -> VideoMetadata("video/mp4", 4031, 1080, 2344) })
        images = ControllerMessageImages(database, MessageImageAssetStore(File(root, "images")))
        val directory = File(root, "selected workspace").apply { mkdirs() }
        workspace = ConversationWorkspaceRun(ResolvedConversationWorkspace(
            ConversationWorkspaceBinding.Private(directory.path), directory, null, null), null)
    }
    @After fun tearDown() {
        workspace.close(); database.close()
        RuntimeEnvironment.getApplication().deleteDatabase("conversations.db")
        root.deleteRecursively()
    }
    private fun request(): VideoDraftRequest {
        val owner = VideoAttachmentOwner(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        return VideoDraftRequest(null, owner, 7, "请读取视频", listOf(assets.import(owner, bytes.inputStream())))
    }
    private fun prepared(request: VideoDraftRequest): Pair<ControllerVideoAttempt, PreparedControllerVideoSend> {
        val attempt = ControllerVideoAttempt(request, journal)
        assertNull(attempt.reserve(database))
        return attempt to attempt.prepareFiles(assets, 1234L) { false }
    }
    private fun exporter(guest: String = "/workspace") =
        ControllerVideoFileExporter(assets, workspace.resolved.hostDirectory, guest)

    @Test fun exactOriginalBytesAndPinnedWorkspaceMappingSurviveRepeatedExports() {
        val value = prepared(request()).second
        val files = exporter().export(value.inputs) { false }
        val file = files.single()
        assertArrayEquals(bytes, File(file.hostPath).readBytes())
        assertEquals(File(file.hostPath).relativeTo(workspace.resolved.hostDirectory).invariantSeparatorsPath,
            file.linuxPath.removePrefix("/workspace/"))
        assertTrue(file.linuxPath.startsWith("/workspace/.daiyu-video-inputs/"))
        assertEquals(files, exporter().export(value.inputs) { false })
        assertFalse(root.walkTopDown().any { it.name.endsWith(".pending") })
        assets.openOriginal(file.owner, file.reference).use { assertArrayEquals(bytes, it.readBytes()) }
        assertTrue(exporter("/home/user/videos/").export(value.inputs) { false }.single()
            .linuxPath.startsWith("/home/user/videos/.daiyu-video-inputs/"))
    }
    @Test fun modifiedToolCopyIsRepairedFromTheVerifiedOriginal() {
        val value = prepared(request()).second
        val file = exporter().export(value.inputs) { false }.single()
        File(file.hostPath).writeBytes(ByteArray(bytes.size))
        exporter().export(value.inputs) { false }
        assertArrayEquals(bytes, File(file.hostPath).readBytes())
    }
    @Test fun replacedWorkspaceRootCannotRedirectExport() {
        val value = prepared(request()).second
        val selected = workspace.resolved.hostDirectory
        val parked = File(root, "parked-workspace")
        val outside = File(root, "outside-root").apply { mkdirs() }
        check(selected.renameTo(parked))
        Files.createSymbolicLink(selected.toPath(), outside.toPath())
        try {
            assertNotNull(runCatching { exporter().export(value.inputs) { false } }.exceptionOrNull())
            assertTrue(outside.listFiles().orEmpty().isEmpty())
        } finally {
            Files.delete(selected.toPath())
            check(parked.renameTo(selected))
        }
    }
    @Test fun videoFilenameExtensionsMatchVerifiedContainers() {
        assertEquals("mp4", videoFileExtension("video/mp4"))
        assertEquals("mov", videoFileExtension("video/quicktime"))
        assertEquals("webm", videoFileExtension("video/webm"))
        assertEquals("mkv", videoFileExtension("video/x-matroska"))
        assertEquals("3gp", videoFileExtension("video/3gpp"))
        assertEquals("3g2", videoFileExtension("video/3gpp2"))
    }
    @Test fun cancellationAndForgedMetadataCannotPublishFiles() {
        val value = prepared(request()).second
        assertNotNull(runCatching { exporter().export(value.inputs) { true } }.exceptionOrNull())
        val input = value.inputs.single()
        val forged = MessageVideoInput(input.owner, input.references.map { it.copy(sha256 = "0".repeat(64)) })
        assertNotNull(runCatching { exporter().export(listOf(forged)) { false } }.exceptionOrNull())
        assertFalse(File(workspace.resolved.hostDirectory, ".daiyu-video-inputs").walkTopDown().any { it.isFile })
    }
    @Test fun symlinkInputRootCannotEscapeSelectedWorkspace() {
        val value = prepared(request()).second
        val outside = File(root, "outside").apply { mkdirs() }
        val link = File(workspace.resolved.hostDirectory, ".daiyu-video-inputs").toPath()
        Files.createSymbolicLink(link, outside.toPath())
        try {
            assertNotNull(runCatching { exporter().export(value.inputs) { false } }.exceptionOrNull())
            assertTrue(outside.listFiles().orEmpty().isEmpty())
        } finally { Files.delete(link) }
    }
    @Test fun manifestOnlyAppendsOwnerUserTextWithoutMutatingPhysicalHistoryOrUploadingVideo() {
        val value = prepared(request()).second
        val files = exporter().export(value.inputs) { false }
        val assistant = value.assistant.copy(status = RunStatus.COMPLETED,
            blocks = listOf(MessageBlock.Text("reply", "原回答")))
        val projected = videoFileModelHistory(listOf(value.user, assistant), files)
        assertEquals(assistant, projected.last())
        assertEquals(value.user.blocks, projected.first().blocks.dropLast(1))
        val manifest = (projected.first().blocks.last() as MessageBlock.Text).text
        fun manifestPath(prefix: String): String {
            val encoded = manifest.lineSequence().single { it.startsWith(prefix) }.removePrefix(prefix)
            return JSONTokener(encoded).nextValue() as String
        }
        assertEquals(files.single().linuxPath, manifestPath("Linux 路径: "))
        assertEquals(files.single().hostPath, manifestPath("Android 路径: "))
        assertTrue(manifest.contains("当前已选 Linux 环境"))
        assertTrue(manifest.contains("包管理器安装"))
        assertTrue(manifest.contains("Linux /tmp"))
        assertTrue(manifest.contains("mktemp -d /tmp/daiyu-video-XXXXXX"))
        assertTrue(manifest.contains("查看所需画面并完成分析后，立即删除"))
        assertTrue(manifest.contains("失败或取消时也要清理"))
        assertTrue(manifest.contains("不能在图片读取完成前删除"))
        assertTrue(manifest.contains("不得清空整个 /tmp"))
        assertTrue(manifest.contains("不得删除输入视频原件"))
        assertTrue(manifest.contains(".daiyu-video-inputs 中的原视频副本"))
        assertTrue(manifest.contains("其他任务、用户的文件"))
        assertFalse(manifest.contains("data:video/"))
        assertFalse(manifest.contains("fixture-secret"))
        assertNotNull(runCatching { videoFileModelHistory(listOf(assistant), files) }.exceptionOrNull())
    }
    @Test fun ordinaryProviderCommitsOnceAndModelOverloadsKeepToolsAndLocalFiles() {
        val request = request(); database.draft(request.text)
        val (attempt, value) = prepared(request)
        val snapshot = ControllerVideoHistory(database).capture(attempt.conversationId)
        val adapter = ControllerVideoFiles(database, assets, images)
        val plan = adapter.preflight(snapshot, provider, value.user, value, workspace) { false }
        assertNull(plan.videos); assertEquals(1, plan.files.size)
        assertEquals(VideoDraftOutcome.ACCEPTED, attempt.commit(database, snapshot, value, plan,
            "default", request.text, { false }).outcome)
        assertEquals(VideoDraftOutcome.ACCEPTED, journal.recover(request, database).outcome)
        request.videos.forEach { assets.remove(request.owner, it) }
        var opens = 0
        var seenTools = emptyList<ToolDefinition>()
        val delegate = object : ModelPort {
            override fun open(history: List<Message>): ModelSession = error("definitions must be forwarded")
            override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
                opens++; seenTools = definitions
                assertEquals(plan.modelHistory(), history)
                val manifest = (history.first().blocks.last() as MessageBlock.Text).text
                assertTrue(manifest.contains("Linux 路径:")); assertFalse(manifest.contains("data:video/"))
                assertArrayEquals(bytes, File(plan.files.single().hostPath).readBytes())
                return object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {}
                    override fun cancel() {}
                }
            }
        }
        var cancelled = false
        val port = adapter.wrap(delegate, provider, attempt.conversationId, value.assistant.id, plan, workspace) { cancelled }
        val rows = listOf(value.user)
        val tool = ToolDefinition("terminal", "Linux tool fixture", "{\"type\":\"object\",\"properties\":{}}")
        port.open(rows).cancel()
        port.open(rows, setOf(ReadOnlyTool.BATTERY)).cancel()
        assertEquals("get_battery_status", seenTools.single().name)
        port.open(rows, listOf(tool)).cancel(); assertEquals(listOf(tool), seenTools)
        port.open(rows, listOf(tool), images.freeze(rows, provider)).cancel()
        assertEquals(4, opens)
        cancelled = true
        assertNotNull(runCatching { port.open(rows, listOf(tool)) }.exceptionOrNull())
        assertEquals(4, opens); assertEquals(2, database.messages(attempt.conversationId).size)
        val original = plan.files.single()
        assertArrayEquals(bytes, File(original.hostPath).readBytes())
        assets.openOriginal(original.owner, original.reference).use { assertArrayEquals(bytes, it.readBytes()) }
    }
    @Test fun videoBudgetIncludesTheExactFileManifestAndStillRejectsAnOversizedInput() {
        val value = prepared(request()).second
        val files = exporter().export(value.inputs) { false }
        val physical = listOf(value.user)
        val plan = ControllerVideoModelPlan(physical, null, images.freeze(physical, provider), value.inputs, files)
        val projected = plan.modelHistory()
        assertEquals(physical, plan.history)
        assertEquals(videoFileModelHistory(physical, files), projected)
        val policy = app.daiyu.android.domain.context.CompressionPolicy(
            contextTokens = 272000, reserveTokens = 4096, tokenTail = true)
        val estimate = app.daiyu.android.context.estimateSendContextTokens(policy, projected)
        assertTrue(estimate > app.daiyu.android.context.estimateSendContextTokens(policy, physical))
        val atLimit = policy.copy(contextTokens = estimate + 4096)
        assertTrue(atLimit.fitsSendBudget(estimate))
        assertFalse(atLimit.copy(contextTokens = estimate + 4095).fitsSendBudget(estimate))
        val nativeTextView = ControllerVideoModelPlan(physical, null, plan.images, value.inputs)
        assertEquals(physical, nativeTextView.modelHistory())
    }

}
