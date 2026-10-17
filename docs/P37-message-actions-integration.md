# P37 消息操作：真实后端与 Main 接缝

## 范围和证据

基线 `af18af3`。本叶不改 MainActivity、ChatTurnMapper、ChatScreenModels、ChatMessageList、时间戳字段、DB schema/version、CI 或唯一清单，也不重做复制/朗读。父需将下面的接缝合入 Main；没有合入前 Main 上五个 pendingFeature 仍不会调用新后端。下载 `a2d41ef` 的 Controller 改动由父三方组合，本叶未改 generation transport/下载授权。

已核实的现有接口：

* `context/ContextService.kt`：`branch` 已执行真实 SQLite 分支、run/message/historySegments 重映射、neutral_assets 内容寻址附件复制；新增仅 asset-capable 消息操作调用的 `branchWithVideos`，普通 `branch` 未注入原件能力仍拒绝含视频会话。`revise` 是保留截点的**尾截断**，不是单轮删除，并会删被编辑用户的附件；不适合直接接“删除这一轮/回填编辑”，仍拒绝原视频。
* `ChatController.kt`：`pause/resume/stop` 只控制当前精确拥有的活跃 runner，没有历史失败工具安全重试接口；`idleAvailable`、资源清理、子任务门禁可复用。
* `storage/SQLiteMessageVideoStore.kt`、`ControllerVideoManifestWitness.kt`：一物理消息一份 sidecar/witness，FK 随物理消息级联；不能只删 sidecar，也不能从缺失 sidecar 推断“本来没有视频”。
* `media/MessageImageAssetStore.kt`：图片引用是 owner-bound，不是任意文件路径；已有 `resolver.read` + `restore` 可以把已验证原件复制到新 owner。
* `media/video/VideoAssetStore.kt`、`backup/BackupVideoAssets.kt`：已有 `MessageVideoBackupAssets.read/restore`（底层 `openOriginal/restore`）读取验证原件并恢复到新 owner；不是缺少原视频复制契约。消息分支只复用这个原件能力，不调用 backup sidecar restore、不删除或覆盖现存空 witness。
* `ControllerGenerationResources.kt`、`storage/SQLiteGenerationOutputStore.kt`：生成原件与 operation/conversation/assistant/run/tool/account 强绑定；本叶未发现可安全复制到已完成新 owner 的导入接口，不能直接复制 receipt 或 INSERT 一份伪造的新 attempt。

## 新 API

全部是 app 内部 API，不引入新外观。

```kotlin
controller.loadMessageActionSnapshot(conversationId) { snapshot: MessageActionSnapshot? -> ... }: Boolean
controller.messageAction(action: MessageAction, target: MessageActionTarget) { result: MessageActionResult -> ... }: Boolean
ControllerMessageActionHost(controller).bind(snapshot, isCurrent, result): Callbacks
host.invalidate()
```

`loadMessageActionSnapshot` 和 `messageAction` 从 UI 线程发起，SQLite/原件工作在 Controller 单一 worker 上，完成回 UI 线程。返回 false 是**未准入**，不要自动排队重试。快照同时提供 `targets[displayedMessageId]`、`steps[toolCallId]` 和 `matchesRendered(physicalMessages)`。不要用当前选中会话临时查询旧 lambda 的 id，也不要从分页列表计算全量 revision。

每个 target 含 conversation、显示 message、物理 owner、run、role、全量原始历史 revision、conversation assistant binding；步骤还含精确 toolCallId。Controller 检查恢复/运行/准备/资源/drain/生成/读者/后台子任务门禁，store 在最终同一 SQLite 事务重新核验全部身份与 revision。

### 操作语义

* **编辑**：返回 `EditDraft(原用户文本)`，数据库原消息及其附件不变。Main 只回填当前草稿；不调用 send，不自动付费。用户再次显式发送走原发送链。
* **分支**：复制到稳定新会话，所选消息所在**整个 run**都复制，保留完整工具批次/所有者/补充消息，不把用户菜单截点切在工具批次中间。仅已完成前缀；未完成工具批次拒绝。图片原件按新 conversation/message owner 独立复制；删除原消息/原会话或之后清理原图片不会破坏分支。原视频通过 Controller 的现有 app-scoped `videoAttachments.store` 读取和恢复私有原件：按物理消息的源 conversation/run/message 三元组，重映射普通用户、assistant historySegments 内虚拟用户以及 standalone supplement owner，保留原件 ID/有序引用/全部媒体元数据。源 sidecar+witness 前后核验；目标物理行首次 `SQLiteMessageVideoStore.insert` 就携带真实重映射 inputs，原子创建非空 sidecar 和相应 witness，绝不先写空 witness 再 backup 覆盖。目标 codec 编码绑定新的物理 run/segment owner，最终重读比较全部 inputs。源消息删除和已确认后的源原件清理不会删除分支私有原件。内容寻址 neutral_assets 使用现有拷贝。
* **删除**：删除所选消息所在整个 run 的所有物理行（包括工具结果/补充消息），而不是一个 UI 文本块。失效 active summary，UPDATE 后在同一最终事务查询确认不存在 active≠0 档案；`RAISE(IGNORE)` 等无效 UPDATE 也会回滚整轮删除及关联元数据，不能返回 Deleted；原视频 sidecar 与 witness 在同一事务随物理行删除，其他 witness 在前后均验证。生成输出引用按精确 conversation/assistant/run 删除；生成 attempt/runtime 安全墓碑不主动清理。Controller 先校验 target，再 seal 精确生成 run，worker 等 publication barrier，关闭局部 reader，最终事务再 CAS。错误/UNKNOWN 不重新开放已 seal 的发布权。文件不自动 GC，因此无 DB/FS 跨事务删除风险。
* **重新生成**：显式 fork-and-new-request，不是 resume、旧请求重放或原地覆写。只接受已完成、无 error、无原视频/图片/工具的已知纯文本基线，以及普通一用户一助手的目标 run。新分支只包含目标 run 之前的历史；原用户文本作为**一个新请求**经 `sendPrepared` 正常发送。基线 opaque provider continuation 清空，不携带旧请求/工具调用 ID。原会话和旧答案保留；回执为 `Regenerated(conversation, outcome)`，`outcome` 是 `NOT_SENT / ACCEPTED / UNKNOWN`，始终携带本次新分支定位：准备未进入持久发送为 NOT_SENT；正常持久回执为 ACCEPTED；`sendPrepared` 返回空且**本次** `Preparation.persistenceUnconfirmed` 为真则 UNKNOWN，绝不折叠成未发送。UNKNOWN 只刷新核实，不自动发送、不重新 prepare；即使刷新未看到消息也不是自动重发授权。准备失败时新分支/草稿仍可达。复杂/失败/UNKNOWN/工具运行拒绝，不猜测安全性。当前发送配置/助手配置照常验证，可能需要用户先配置模型。
* **失败步骤重试**：精确验证步骤 owner/revision 后返回可达 `Rejected`，原因明确“没有安全重试契约；UNKNOWN 或外部副作用不会自动重放”。不能 toast 成功、清除原失败或调用 resume/send。

### 明确限制，不宣称已完成

**有 generation_outputs 的前缀分支目前仍明确拒绝。** 与已有安全原件 restore 的原视频不同，当前 generation store 的 register/recover 必须依赖已授权 generation_attempt、operation/tool/account/owner；未发现供“已完成历史静态复制”的安全导入接口。原数据/原件保留，不会创建缺资产却显示成功的分支。复制 raw receipt、复用旧 message owner、伪造新 paid attempt 或把旧工具 outcome 标成新请求成功，都不安全。剩余工作是独立 audited 静态生成资产导入契约（验证原件、全新 owner/operation 静态 provenance，不创建可付费/可重放 attempt，含 DB/FS 回滚及 UNKNOWN），不能声称所有生成媒体分支已完成。原视频分支现已实现，但超过现有 `MessageVideoBackupAssets.read` 字节上限或 `VideoAssetStore` 恢复限制、原件/metadata/witness 损坏时仍失败关闭，保留源会话。

文件复制与 SQLite 不是跨介质原子事务。分支 DB 回滚可留下不可达私有复制件；不自动清理未知回执的文件，不影响原件或其他分支。视频 witness 缺失/损坏拒绝删除而不是 read-repair。新字段时间戳由 P35/父处理，本叶没有用默认 now。

## Main 精确接缝（交父合入，不在本叶编辑 Main）

### 1. Activity 字段及快照获取

新增 imports：`app.daiyu.android.storage.MessageActionSnapshot`、`app.daiyu.android.domain.context.ContextRevision`。

```kotlin
private val messageActionHost by lazy { ControllerMessageActionHost(controller) }
private var messageActionSnapshot: MessageActionSnapshot? = null
private data class MessageActionRenderKey(
    val conversation: ConversationId, val editor: Long, val rows: String, val data: Long)
private var messageActionKey: MessageActionRenderKey? = null
private var messageActionLoading: MessageActionRenderKey? = null
// Last attempted key, NOT last successful key. Failed/null/mismatched receipts consume it too.
private var messageActionAttempted: MessageActionRenderKey? = null

private fun prepareMessageActionSnapshot() {
    val owner = selected?.id ?: run {
        messageActionSnapshot = null; messageActionKey = null
        messageActionLoading = null; messageActionAttempted = null
        messageActionHost.invalidate(); return
    }
    if (!activityUsable() || !selectionReady || !controller.ready || controller.dataBusy ||
        controller.active != null) return
    val key = MessageActionRenderKey(owner, editorGeneration,
        ContextRevision.digest(shown.map { it.second }), controller.dataRevision)
    if (messageActionKey == key || messageActionLoading == key || messageActionAttempted == key) return
    messageActionSnapshot = null; messageActionKey = null
    messageActionAttempted = key // BEFORE admission; no auto queue/retry even if not admitted
    messageActionLoading = key
    val admitted = controller.loadMessageActionSnapshot(owner) { candidate ->
        if (messageActionLoading == key) {
            messageActionLoading = null
            val rows = shown.map { it.second }
            if (activityUsable() && selected?.id == key.conversation &&
                editorGeneration == key.editor && controller.dataRevision == key.data &&
                ContextRevision.digest(rows) == key.rows &&
                candidate != null && candidate.conversationId == key.conversation && candidate.matchesRendered(rows)) {
                messageActionSnapshot = candidate
                messageActionKey = key
                publishChat()
            }
            // Keep attempted on null, stale owner/rows, or rendered mismatch.
            // Controller dispatchChanged follows this callback and MUST NOT reload the same key.
        }
    }
    if (!admitted) messageActionLoading = null // keep attempted, no automatic retry
}
```

在 `refresh()` 已刷新 `shown`、最终 `publishChat()` **之前**调用 `prepareMessageActionSnapshot()`；早期 dataBusy 分支不要调用。分页加载/跳历史后也调用。**仅 success-key / loading-key 不足**：snapshot 失败返回 null、candidate 与 rendered 不匹配时 completion 仍会 `dispatchChanged → refresh`，若只清 loading 就会无限重载同 key。必须像上例在调用前记录 attempted-key，null / mismatch / 未准入也保留；只有 conversation/editor/rows/data 形成新 key，或用户显式“重新获取消息操作”时才开放一次新尝试。显式重试应在无 loading、Activity/owner 仍匹配时同时清 snapshot/key/attempted，再调用本方法；普通 refresh、completion、dispatchChanged 不清 attempted。没有安全快照时点击返回可达“请刷新/重新获取”错误，不回退到旧 id 查询。父需验证 null、render mismatch、准入 false 各场景重复 refresh 仅一次尝试，rows/data 改变和手动重试各允许一次新尝试。

在 `select()` 更换 editor owner、真实 draftRevision 改变、Activity stop/destroy 时 `messageActionHost.invalidate()`；切换 owner 时清空 snapshot/key/loading/attempted（editorGeneration 必须单调增加，旧回调不能成为新 owner 同 key）。不要因普通 busy/stream rerender 调用 invalidate，否则会丢已准入结果。`bind` 本身会废弃旧**手势**，但同 owner rerender 不会窃取已准入**完成回执**。

### 2. chatActions() 中绑定新 callbacks

紧接现有局部 `editorMatches()` / `gestureOwner()`，在 `return ChatScreenActions(...)` 前加：

```kotlin
val actionSnapshot = messageActionSnapshot?.takeIf {
    it.conversationId.value == renderedConversation &&
        it.matchesRendered(shown.map { row -> row.second })
}
val messageCallbacks = messageActionHost.bind(actionSnapshot,
    isCurrent = {
        editorMatches() && draftRevision == renderedDraftRevision &&
            terminalEditor == null && virtualDisplayEditor == null &&
            !videoSending && !imageHost.sending && !shizukuWritesBlocked
    }, result = { outcome -> guard {
        when (outcome) {
            is MessageActionResult.EditDraft -> {
                // Only refill this exact editor. Existing attachments stay as-is; no send.
                updateDraft(outcome.text)
                selectionStart = draft.length; selectionEnd = draft.length
                publishChat()
            }
            is MessageActionResult.Branch -> select(outcome.conversation)
            MessageActionResult.Deleted -> { shown.clear(); refresh() }
            is MessageActionResult.Regenerated -> {
                select(outcome.conversation)
                when (outcome.outcome) {
                    RegenerationSendOutcome.ACCEPTED -> Unit
                    RegenerationSendOutcome.NOT_SENT -> toast("新分支和原用户草稿已保留；本次未发送")
                    RegenerationSendOutcome.UNKNOWN -> {
                        shown.clear(); refresh()
                        toast("新分支发送保存结果 UNKNOWN；请核实历史和服务端结果，不会自动重发或重新准备")
                    }
                }
            }
            is MessageActionResult.Rejected -> toast(outcome.reason)
            is MessageActionResult.Unknown -> {
                shown.clear(); refresh(); toast(outcome.reason)
            }
        }
    } })
```

只替换五个 pendingFeature callback：

```kotlin
onRegenerate = messageCallbacks.onRegenerate,
onBranch = messageCallbacks.onBranch,
onEditUser = messageCallbacks.onEditUser,
onDeleteTurn = messageCallbacks.onDeleteTurn,
onRetryStep = messageCallbacks.onRetryStep,
```

现有 onSpeak/onCopy/onContextClick 和全部布局不变。onDeleteTurn 如需确认可沿用既有确认宿主，确认后必须调用**捕获的** callback，并保留 owner/draft checks，不从确认对话框重新查当前 message id。回填如要覆盖非空草稿前确认，同样须沿用该精确 owner 与 draftRevision，不能在确认后改写新草稿。

`isCurrent` **不要**包含 `controller.dataRevision == renderedDataRevision`：持久操作自身会在投递结果前增加 dataRevision。owner/editor/draft/lifecycle 才是 UI 消费门禁；全量历史 CAS 由 target 的 revision 在后端完成。也不要在普通刷新时无条件 invalidate host。

## 新测试源码及验证状态

`app/src/androidTest/kotlin/app/daiyu/android/ControllerMessageActionsTest.kt` 包含生产 Controller/worker/SQLite/runner 路径测试：

* 编辑原文回填且消息/草稿/网络不变；
* stale 全量 revision、伪造 owner、变化 assistant binding 拒绝；
* 整 run 工具批次分支/删除、原会话与副本隔离、视频 witness 级联；
* UNKNOWN 步骤重试和含工具重生成拒绝、无 transport 调用；
* 显式纯文本重生成只开一个新请求，历史前缀正确，不带旧目标答案；NOT_SENT 保留空分支/草稿，发送 INSERT 被 RAISE(IGNORE) 后 UNKNOWN 携带精确分支且刷新不自动重试；
* 旧渲染 lambda/新 editor/失效 lifecycle 完成不越权；同 owner busy rerender 不丢回执；
* RAISE(IGNORE) 删除回执失败原子回滚；active 档案失效 UPDATE 被 RAISE(IGNORE) 时整轮/witness/档案原子回滚，去掉故障后仅显式重试可成功；视频 witness 缺失时删除/分支均不修复；
* 活跃运行准入拒绝；
* PNG 真原件复制后删除源 owner，副本仍能验证并读取原字节；
* 设备端编码真实 AVC/MP4，经生产 Android inspector + 私有 VideoAssetStore 原件分支/恢复；视频 sidecar INSERT 被 RAISE(IGNORE) 原子回滚且源原件完好；成功分支源 run/owner 变化、新 witness 真实非空，删除源元数据和源原件后副本仍读原字节；
* 额外 segment-only / standalone supplement 两种历史形态验证 owner/run/segment 同步映射和独立私有原件（这两个形态用 synthetic inspector，不当作真实 Android 解码证据）。

**未执行编译或测试，未运行全量构建，未调用付费接口；不得标为通过。** 父需至少编译对应 app main/androidTest 源并在设备执行该 instrumentation 类，再按资产限制评审。现有 ContextService/context、原视频 witness、生成资源 publication-barrier 回归由父按相关局部测试选择执行。真实 MP4 测试要求设备具有 AVC 编码器；目前只有源码静态检查，未获得设备执行证据。生成输出复制仍明确拒绝，没有完成性声明。Main attempted-key 门控只交精确接缝方案，需父在 Main 落地后补 UI/生命周期集成验证。
