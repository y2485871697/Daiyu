# P32 UNIVERSAL：Activity 生产桥接宿主

## 交付范围与验证状态

仅新增 `ActivityUniversalVoiceHost.kt`，`voice/control/AndroidUniversalRecognition.kt`、
`AndroidUniversalPlayback.kt`、`AndroidUniversalOwnerObserver.kt`、`AndroidUniversalReceiptFence.kt` 及本专属文档/测试。
没有修改 Main、ChatController、ControllerVoiceRuntime、现有 host/player 或 UNIVERSAL leaf 状态机；
不接入系统数字助理接管入口。Main 由父集成，本叶子不是已经挂载的 UI 功能。

**没有执行构建、测试、设备收音/播放或供应商网络。** 下述 JVM 测试是待父执行的 adapter/真实
VoiceChain 测试，不是设备音频或物理可听性证明。没有创建分支。

## 稳定挂载 API

均为 `internal`，同 app module 可直接调用：

```kotlin
ActivityUniversalVoiceHost(
    context: Context,
    runtime: ControllerVoiceRuntime, // 仅供现 RemoteVoiceHostPlayer 使用
    entrySnapshot: (VoiceControlProfile) -> VoiceEntrySnapshot,
    admission: (VoiceControlPermit) -> VoiceAdmission,
    prepareConversationSpeech: () -> FrozenVoiceSpeech,
    current: () -> Boolean,
    recognitionConfiguration: () -> UniversalRecognitionConfiguration,
    requestMicrophonePermission: (VoiceControlPermit) -> Unit,
    submit: (VoiceControlTurn, String, (VoiceControlReceipt) -> Unit) -> Unit,
    observe: (VoiceAssistantOwner, (VoiceAssistantBody) -> Unit) -> VoiceControlCancellation,
    stopAcceptedRun: (VoiceAssistantOwner) -> Unit,
    revokePendingTicket: (VoiceControlPermit) -> Unit,
)

val state: StateFlow<VoiceControlState>
fun start(profile: VoiceControlProfile, limits: VoiceControlLimits = VoiceControlLimits()): String?
fun permissionResult(permitId: String, granted: Boolean)
fun stop(permitId: String)
fun revoke(permitId: String, reason: VoiceControlStopReason = VoiceControlStopReason.REVOKED)
fun close()
```

宿主自身实现计时/排队、冻结配置、真实识别、真实播放与资源清理；不要求父再实现 ASR/TTS ports。
父 hooks 只保留现有业务边界：原 editor/sendDraft、permission launcher、原 admission、确切 owner 观察/停止。
不能构造第二个 gate；生产识别、播放均使用 `ActivityVoiceAudio.gate`。播放器 adapter 的可选 gate 参数仅为
专属 JVM 测试隔离 poison 状态，Activity 宿主不暴露 gate 参数。

`start/permissionResult/stop/revoke/close` 和 submit/observe hooks 在 main 调用。
**`admission` 与 `current` 必须另外能在音频线程/共享 gate 的 commit 检查中读取**：无 I/O、无副作用、
不取 host/controller/registry 锁、不 post-and-wait。建议父发布不可变的 `@Volatile` admission 快照，
主线程根据原 UI/Controller 条件更新，再让 hook 只读该快照；不要在 gate admission 里临时查询 DB 或问题投影。
宿主不持锁进行实际 audio release；现输出事件始终排队到 control，而非在 VoiceChain 锁内 inline 回调。

### 独立冻结的 ASR / conversation speech

`recognitionConfiguration()` 在显式开始的 entry 一次调用；后续每轮复用，不读取普通听写设置：

```kotlin
UniversalRecognitionConfiguration.System(language = "zh-CN")
UniversalRecognitionConfiguration.Doubao(key = conversationAsrKey, resource = conversationAsrResource)
UniversalRecognitionConfiguration.Offline
```

- SYSTEM：调用现 `AndroidDictation` 的一次 `onResults`，读取已完成的 `ConfirmableTranscript.confirm()`。
  partial/end-of-speech 不提交，不经过 composer 自动应用草稿路径。权限由父请求。
- DOUBAO：固定现 `DoubaoAsrProtocol` + `VoiceNetworkAdapters.asr()` + `AndroidDuplexAudio`，PCM16/16k/mono。
  `utteranceDone` 只触发停捕获、发送 last marker，**不是 final**；仅协议最终帧文字可发送。
  默认 20 秒捕获上限，且不超过 recognition timeout 的一半，以保留等待最终结果时间；上限结束也只发送
  last marker，不使用最后一个 partial 代替 final。不重连、不重发、无隐式本地/系统 fallback。
- OFFLINE：仅当 `OfflineSpeechPack.state.enabled && ready`，复用 `OfflineSpeechSession` 和
  `AndroidOfflineSpeechDevice`；缺包、禁用、JNI 不可用明确失败，不转 SYSTEM。
  取消严格同步释放麦克风；JNI decoder 仍按原 session 的 worker 所有权异步退出，取消不等待慢模型加载。
  此语义来自现离线 session，不把它描述成取消已等待整个 native worker 完结。

`prepareConversationSpeech()` 也只在 entry 调用一次，返回父 conversation profile 冻结的 `FrozenVoiceSpeech`；
不直接依赖另一个 worker 尚不存在的新 runtime 签名。`current()` 必须对应**这两个冻结快照**，包括配置 revision、
离线 enable 状态等。SYSTEM 输出复用 `ProductionSpeechOutput`；远程输出复用 `RemoteVoiceHostPlayer`，
并传入同一个 frozen speech。远程 player 原本还会调用 `runtime.current(frozen.configuration)`；父 runtime
必须识别这份 conversation snapshot 为 current。若 profile worker 尚未支持它，远程播放应拒绝而不是跳过校验。

所有个人 `mimo-local-*` voice 或非空 `FrozenVoiceSpeech.reference` 在 UNIVERSAL entry 都拒绝。
已有单次 reference grant 不会变成多轮/多句上传与付费许可。以后若支持必须另交有限、独立 grant，不能复用单次许可。

## Main 最小接线（父负责实施，不绕过原 sendDraft）

以下给出当前 Main 的接线位置/代码，不建立第二个发送实现。保留 Main 原 `PendingTextSend`、草稿持久化、
`sendText`、`completeTextSend` 与 ack 流程。不要在 host 的 submit hook 另直接调用一次 `controller.sendText`。

### 1. 原 sendDraft 增加可选关联，而非第二条发送路径

给 Main 增加一次提交的关联记录：

```kotlin
private class UniversalSend(
    val turn: VoiceControlTurn,
    val expected: ChatController.TextSendOwner,
    val result: (VoiceControlReceipt) -> Unit,
)
private val universalTickets = java.util.IdentityHashMap<ChatController.TextSendTicket, UniversalSend>()
private val universalAssistants = mutableMapOf<VoiceAssistantOwner, Message>()
```

将原 `sendDraft(text: String)` 增加可选参数 `universal: UniversalSend? = null`。在其**原来的**
`val pending = PendingTextSend(owner, text)` 后、`controller.sendText` 前加入：

```kotlin
if (universal != null) {
    check(universal.expected == owner)
    universalTickets[pending.ticket] = universal
}
```

原 `controller.sendText(...){ result -> completeTextSend(pending, result) }` 回调改为：

```kotlin
{ result ->
    // 放在原 completeTextSend 的 activityUsable / pendingTextSend identity guard 外面。
    // 即使原 editor 已丢失，迟到的 Accepted 也必须送回原 voice callback 进行 exact cleanup。
    val voice = universalTickets.remove(pending.ticket)
    try {
        completeTextSend(pending, result) // 原 UI/草稿/ack 行为，不另写一份
    } finally {
        if (voice != null) {
            // 先按原 editor 的确切 Accepted 结果更新 admission 快照；自身 editorGeneration /
            // draftRevision 的迁移只准许本 ticket。UI 没有成功迁移就先撤权，仍报告 Accepted。
            updateUniversalAdmissionAfterOriginalReceipt(pending, result)
            voice.result(universalReceipt(voice, result))
        }
    }
}
```

`updateUniversalAdmissionAfterOriginalReceipt` 是父快照更新点，不是新发送实现：需要比较
`pending.owner / pending.editorOwner / result.owner`，只迁移这张 ticket 已确认的本 editor 修订。
不得让原 conversation 被切换、其他编辑或 question waiting 的状态变回 READY。

当前原 `sendDraft` 把 `sendText` 异常合并到 false。对于关联的 voice 必须保留 **Unknown 与明确未入队的
false 的区分**：异常后报告 Unknown，保留 ticket 关联供迟到结果，不得先删除关联或报告 Rejected；
原 `sendText` 正常返回 false（明确未入队）才移除关联并报告 Rejected。早期 guard 未满足也是 Rejected。
这是父集成必须加的几个回执分支，不能靠原函数的 Unit 返回值猜 Accepted。
原非 voice 的 toast/草稿规则可保持原样。不要在 Unknown 后自动再调用 sendDraft。

### 2. submit hook 只持久化/冻结原 editor，然后调用上述原 sendDraft

```kotlin
submit = { turn, text, result ->
    if (universalAdmission(turn.permit) != VoiceAdmission.READY) {
        result(VoiceControlReceipt.Rejected(turn.requestId))
    } else {
        // updateDraft 会使用原数据库与 draftRevision；不是 composer dictation callback。
        // 如父给 updateDraft 增加统一撤权，需要对此精确 own-send 做来源标记，而非全局免撤权。
        updateDraft(text)
        if (draft != text || selected?.id?.value != turn.permit.conversationId) {
            result(VoiceControlReceipt.Rejected(turn.requestId))
        } else {
            val expected = ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision)
            freezeUniversalEditorForOwnSend(turn, expected)
            try { sendDraft(text, UniversalSend(turn, expected, result)) }
            catch (_: Exception) { result(VoiceControlReceipt.Unknown(turn.requestId)) }
        }
    }
}
```

上述 `freezeUniversalEditorForOwnSend` 同样只是父 admission 快照保存精确 expected revision，允许本 ticket 从
SUBMITTING 过渡到其 Accepted owner；不能允许另一个文本 ticket、手动朗读、duplex/级联、附件/审批入口同时准入。
原 editor 不是空草稿时建议 entry 拒绝，不应自动覆盖用户未发送文字。

回执映射（只能从原 durable TextSendResult.Accepted 得到成功）：

```kotlin
private fun universalReceipt(s: UniversalSend, r: ChatController.TextSendResult): VoiceControlReceipt {
    val id = s.turn.requestId
    if (r.owner != s.expected || r.owner.conversationId?.value != s.turn.permit.conversationId)
        return VoiceControlReceipt.Unknown(id)
    return when (r) {
        is ChatController.TextSendResult.Rejected -> VoiceControlReceipt.Rejected(id)
        is ChatController.TextSendResult.Unknown -> VoiceControlReceipt.Unknown(id)
        is ChatController.TextSendResult.Accepted -> {
            val a = r.assistant
            if (a == null || a.role != Message.ASSISTANT_ROLE || a.conversationId != r.conversation.id ||
                a.conversationId != s.expected.conversationId) VoiceControlReceipt.Unknown(id)
            else {
                val o = VoiceAssistantOwner(a.conversationId.value, a.runId.value, a.id.value)
                universalAssistants[o] = a // 保存 exact Message；不是 controller.active/latest
                VoiceControlReceipt.Accepted(id, "text-ticket:$id", o)
            }
        }
    }
}
```

字符串 receiptId 只是已确认原 ticket 的关联标识，不是拿 requestId 代替持久化成功证据；只有上面的
Accepted 分支能产生它。映射/回调不打印正文、ID、key 或供应商异常。

宿主的 `AndroidUniversalReceiptFence` 对原 request 另外保留回执栅栏：leaf 只消费首个匹配 receipt，
因此首个 Unknown/Rejected 后发现的真实 Accepted 不再当作第二次业务成功交 leaf，而是在桥接层按
确切 owner 调父 stop 做清理。原 Accepted 的重复回调不停止仍活跃的原 owner；不同 request/conversation
或空 receiptId 的回调不能触发停止。父仍必须报告后续确认，不能因先报告 Unknown 而丢弃原 ticket callback。

### 3. 精确正文 observer / exact stop / pending ticket revoke

生产 observer 可直接组合：

```kotlin
val exactBodies = AndroidUniversalOwnerObserver(controller) { message ->
    exactQuestionWaitingProjection(message) // 父现有确切 owner 问题投影，不能用 RUNNING 猜
}
// ActivityUniversalVoiceHost 构造参数：
observe = exactBodies::observe,
stopAcceptedRun = { owner ->
    universalAssistants.remove(owner)?.let { exact -> controller.stop(exact); Unit }
},
revokePendingTicket = { permit ->
    universalTickets.entries.filter { it.value.turn.permit.id == permit.id }
        .map { it.key }.forEach { controller.cancelTextSend(it) }
    // 不删除未确认关联！原 callback 即使迟到 Accepted 也必须报告精确 owner。
},
```

`AndroidUniversalOwnerObserver` 先 `controller.observe(listener)` 再 replay，活跃 exact assistant 直接投影，
否则 `Dispatchers.IO` 从原 conversation DB 按 200 条分页找三字段匹配的 row（不局限最新 50 条）；异步 DB 结果带序号 fence。
仅拼接 `MessageBlock.Text`，排除 tools/protocol/reasoning/user/history segments。
不使用列表最后一个回答、不用 `isStreaming` 推断 owner、不把 DB 异常当 COMPLETE。
父也可以注入自己的现有 observer，但必须具有同样的 exact + replay + off-main DB 合约。

`exactQuestionWaitingProjection` 应从父已有问题投影/许可事件获得，并在该事件到来时**先更新 admission 再撤权**，
而不是只等 body observer 下一次回调。原 `questionProjection(message, call)` 可用于构建此快照。
`stopAcceptedRun` 只调用 `controller.stop(exactMessage)`，绝不无参 stop 当前 run。
正常 COMPLETE 后也应在观察取消/permit 安全终结时清理已完成 Message 映射；尚未完成或 pending 的关联不得提前删。
这样映射随有限许可释放，不积累全部聊天历史。

### 4. 生命周期和权限

```kotlin
// 显式按钮；普通 dictation / duplex 的按钮与状态机均不改变。
val permitId = universalHost.start(profile, limits)
// 权限 launcher 保存 requestMicrophonePermission(permit).id，不用后来 active ID 替换。
universalHost.permissionResult(originalPermissionPermitId, granted)

// onStop、页面离开、会话/编辑器/配置修改、权限撤回、问题等待：
val captured = universalHost.state.value.permitId
invalidateUniversalAdmissionSnapshot() // 同步、先撤权
captured?.let { universalHost.revoke(it) }
// Activity destroy：在音频/controller host 依赖仍存活时 universalHost.close()
```

## 真正 drain 与严格清理

播放仍由现 VoiceChain 管句子、修订、串行队列。`AndroidUniversalPlayback` 只桥接整段正文快照：

1. final 正文已提交；流式非追加修改拒绝，最终未提交 tail 修订交 VoiceChain。
2. 每个已排队 utterance 收到 **ProductionSpeechOutput 的播放器 COMPLETED**（系统 TTS onDone / 远程
   MediaPlayer onCompletion），而不是 HTTP bytes、合成完成、enqueue 成功、空队列、正文 COMPLETE。
3. VoiceChain strict stop 后再永久 strict close。adapter 自行跟踪 stop/close 进行中，避免幂等的第二次 close
   提前返回被错当已释放；正在释放就失败封锁，不在 control 线程等待一个反过来等 main 的 worker。
4. close 成功后再次确认 admission 才 drained；抢占、撤权、release failure 均 failed。回调总是先排队离开
   chain 锁。显式 close 先同步 fence，再在锁外真实 release；已排队 COMPLETE 不能复活旧轮。

空最终正文也必须 final + strict release 后才能 drain；已播放过文本的最终修订为空明确失败，不能假装已撤回声音。
这些是软件播放完成/释放证据，不是扬声器物理可听性证明。

## 专属待执行测试

- `AndroidUniversalPlaybackTest`：11 个纯 JVM 用例，真实 VoiceChain + fake output/gate，覆盖 final+实际完成+
  strict close 三条件、暂空队列、多句逐个完成、空 final、close 失败、抢占 exact owner、撤权/迟到事件、
  非追加修改、final tail 修订、worker 正在 release 时不能伪确认关闭。
- `AndroidUniversalOwnerObserverTest`：4 个纯投影用例，覆盖三字段/role、仅 Text 正文、独立 question waiting、
  各运行终态映射。不把这些纯投影测试冒充 DB 订阅竞态或真实设备验证。
- `AndroidUniversalReceiptFenceTest`：5 个纯回执桥接用例，覆盖 Unknown/Rejected 后迟到 Accepted 清理、
  重复 Accepted 不误停原 owner、不同 request/conversation 拒绝清理及额外 owner 精确停止。

父还需统一编译/执行测试，并验证真实 permission launcher、三种识别最终结果、Main 原草稿/receipt 迁移、
快速终态 DB replay、问题等待同步撤权、配置/前后台/共享音频抢占、远程播放器 completion 与 strict release。

## 已核实实现来源

- `docs/P32-universal-voice-control.md`：finite permit/turn、receipt owner、strict drain 与父职责。
- `voice/AndroidDictation.kt`：系统 onResults、ConfirmableTranscript 与严格 cancel/destroy。
- `ActivityRemoteVoiceHost.kt`、`voice/providers/DoubaoAsrProtocol.kt`、`voice/network/VoiceNetworkAdapters.kt`、
  `voice/duplex/AndroidDuplexAudio.kt`：现豆包 ASR 的协议/网络/PCM 与释放合约。
- `voice/offline/OfflineSpeechSession.kt`、`OfflineComposerHost.kt`：现离线 pack/session/device、取消/worker 所有权。
- `voice/VoiceSession.kt`、`ProductionSpeechOutput.kt`、`RemoteVoiceHostPlayer.kt`：现 gate、句子提交、软件完成与严格释放。
- `MainActivity.kt` 原 `sendDraft/completeTextSend/updateDraft`、`ChatController.kt` 原
  `TextSendOwner/Ticket/Result`、`observe/unobserve`、`stop(Message)`、`questionProjection`：最小父接线依据。
