# P32 C：有限 UNIVERSAL 持续语音控制层

## 本叶子交付 / 非交付

新增（不替换旧实现）：

- `app/src/main/kotlin/app/daiyu/android/voice/control/VoiceControlPorts.kt`
- 同目录 `VoiceEntryPolicy.kt`、`VoiceModeController.kt`、`VoiceControlPanel.kt`
- `app/src/test/kotlin/app/daiyu/android/voice/control/VoiceModeControllerTest.kt`
- 同目录 `VoiceEntryPolicyTest.kt`
- 本文档。

父端组合 Main、现有 ASR/输出/Controller 精确正文 observer；本叶子不修改 Main、ChatController、runtime、现有 host/player/settings，不重做 ASR/TTS/duplex/gate。系统数字助理 service 不在本叶子。端口签名以这四个 Kotlin 文件为准，父端负责桥接，而不是通过观察“最新回答”绕过收据。

**未编译、未运行任何测试/构建，也未做设备收音/播放验证。** 单元测试为确定性 fake 端口测试，不是生产播放器 drain 或实机可听性证据。没有执行分支/push 操作。

## 已核实来源与迁移取舍

经授权参考的原文件：

- `build/agent-input/fork/app/src/main/kotlin/io/github/mangi/eta/agent/voice/VoiceEntryPolicy.kt`：模式可用性、单一模式入口与历史选择。
- 同目录 `VoiceModeController.kt`：UNIVERSAL 顺序交互与功能独立配置的意图。**未迁移**其中 `lastAgentId` / `isStreaming` 推断 owner、无限循环和延时后重开麦克风的实现。

核实过的当前接线依据：

- `app/src/main/kotlin/app/daiyu/android/ChatController.kt`：`TextSendOwner` / `TextSendTicket` / `TextSendResult.Accepted(assistant)`、`sendText`、`cancelTextSend(ticket)`、`stop(owner: Message)`。
- `app/src/main/kotlin/app/daiyu/android/ActivityCascadeVoiceHost.kt`：仅授权手动下一条；确切 assistant、Text 正文过滤、现有 VoiceChain 与输出配置组合。
- `app/src/main/kotlin/app/daiyu/android/voice/DraftDictationSession.kt`：一次识别、严格 target 校验与普通听写只交草稿的边界。
- 同目录 `VoiceSession.kt`：VoiceChain 的句子提交、完成与共享 AudioOwnerGate；COMPLETE 不能单独当作所有适配器严格关闭的证据。
- `core-domain/src/main/kotlin/app/daiyu/android/domain/Conversation.kt`：RunStatus 不含 QUESTION_WAITING，不能从 RUNNING 猜是否有问题等待。父端必须用确切问题投影/运行许可状态单独撤权。

## 稳定 API 与责任

### 开始与撤权

`VoiceModeController(ports)` 的公开命令在同一 control/UI 线程调用；`ports.dispatch` 必须始终排队到该线程，不得 inline。定时器、识别、发送、正文、播放器回调都由控制层转派。不可把这些端口绑定到普通听写的自动提交逻辑。

- `start(profile, limits): String?`：显式用户点击一次；已有许可时不重复创建。返回本次 permit ID；拒绝返回 null。默认最多 **3 轮 / 180 秒**，先到即停；最多可配置 20 轮 / 15 分钟，各阶段还有不可关闭的超时。
- `permissionResult(permitId, granted)`：仅原许可仍有效时可继续；拒绝、迟到或其他许可 ID 不开音频。权限前只有状态、定时器和权限请求，没有 recognition/playback 资源。
- `stop(permitId)` / `revoke(permitId, reason)`：只停止捕获到的本次许可；旧按钮/生命周期回调不能停止新许可。
- `close()`：页面销毁时撤销本控制器许可；不可再 start。
- `state: StateFlow<VoiceControlState>`：明确阶段、permit ID、profile、轮数、识别预览、固定失败原因，不暴露原始服务错误。

`VoiceControlProfile(id, revision, label)` 只有不含秘密的配置身份；ASR/TTS 凭据、模型、reference、授权与冻结配置保留在父现有 runtime/host。每轮复用开始时的 profile 快照，不能跟随别的听写/朗读设置偷偷变化。配置变化需撤销并重新点击开始；本许可不扩大个人 reference 的单请求付费授权。

`entrySnapshot` 检查 UNIVERSAL 启用、已选持久会话、前台、profile 就绪与无问题等待。此版明确要求已选会话 ID，未保存新会话不猜归属。`admission(permit)` 必须短小、无副作用，逐边界校验原 lifecycleKey、会话/编辑器准入、配置 revision、前台、权限、问题等待及父端排他状态；自身已确认发送引起的编辑器修订，应由父端精确迁移，不能一律当作无关编辑。

父端须在 onStop/页面离开/会话切换/编辑器被替换/配置修改/权限撤回/问题等待时同步撤销捕获的 permit，并先改变其 admission；不能只等待下一次语音回调。不可把重建后状态恢复成活跃许可。

### 有限状态流

```text
显式开始
  ├─ 权限不足 -> PERMISSION_REQUIRED -> 原许可授权且仍准入
  └─ CONNECTING -> LISTENING
         partial: 仅预览
         final: 一次性栅栏，严格关闭输入
    -> SUBMITTING：恰好一次现有发送调用
         Rejected / Unknown / 异常 / 超时 -> 停止，不重发
         Accepted + requestId + receiptId + exact assistant
    -> WAITING_FOR_BODY：绑定 conversationId/runId/messageId
    -> SPEAKING：仅该 assistant 的 Text 正文快照
    -> DRAINING：正文 COMPLETE，但仍不重开输入
         正文已 final + 真正音频播放结束 + 严格释放 -> 下一轮
         达到本次轮数/时限 -> 撤销，不再收听
```

文本上限 100,000 字符；空 final 不发送、不自动重听。无隐含 fallback、重连、重发、无限循环或定时延迟伪 drain。owner 三字段任何一个不匹配的正文都忽略；缺 owner 的 Accepted 必须由桥接映射 Unknown。重复 final/收据不发送第二次；旧 input/body/drain/failure callback 不影响新轮或新许可。

任何 QUESTION_WAITING、失败、抢占、UNKNOWN 或准入失效均终止许可；问题不由模型继续“语音代答”。清理先 fence 许可，再撤销 pending admission、关输入、移除 observer、关确切输出、精确停止尚未完成的 admitted run。清理失败封锁本控制器后续 start，不重置共享 gate。超时后迟到的匹配 Accepted 仍精确 stop 其 owner，不朗读、不自动补发，也不误停新 run。

### 真正 drain 的桥接要求

`playback.body(text, complete)` 接收**整个正文快照**；由现有 VoiceChain/host 处理 append、完整句子、final correction 与串行播放，控制层不实现分句/TTS。`observe` 订阅完成后必须重放该确切 owner 的当前快照，防止快速回答丢失。

`drained` 仅能在 final 正文已提交、所有排队句子的实际播放器完成回调、严格关闭/释放成功后触发。网络返回、合成完成、enqueue 成功、队列暂空、正文 COMPLETE 都不满足。没有音频的空正文也须经过 final 和资源释放后才能 drained。VoiceChain 的 COMPLETE 后仍须按现有适配器语义确认严格 close；失败或被其他共享 gate owner 抢占调用 `failed`，不能伪装为 drained。证据是播放器软件完成/释放，不等于物理可听性。

## 父端最小接线示例（组合片段，不是第二套 runtime）

下面片段用于现有父端 host 内；`ports` 的 recognition/playback/observe 由父现有组件桥接。没有要求创建新的 ASR/TTS 实现或改普通听写行为。

### control thread、计时、UI 与生命周期

```kotlin
val handler = Handler(Looper.getMainLooper())
// VoiceControlPorts 的三个基础实现：
override fun dispatch(action: () -> Unit) { check(handler.post { action() }) }
override fun nowMillis(): Long = SystemClock.elapsedRealtime()
override fun schedule(delayMillis: Long, action: () -> Unit): VoiceControlCancellation {
    val task = Runnable { action() }
    check(handler.postDelayed(task, delayMillis))
    return VoiceControlCancellation { handler.removeCallbacks(task) }
}

val voiceControl = VoiceModeController(ports)
// Compose 父页面持有选择和有限 limits；只收集 state，不用 effect 自动 start。
VoiceControlPanel(
    state = voiceState, profiles = profiles, selectedProfile = selected,
    limits = limits, onSelectProfile = { selected = it },
    onLimitsChange = { limits = it }, onConfigureProfile = ::openExistingVoiceSettings,
    onStart = { p, l -> voiceControl.start(p, l) }, onStop = voiceControl::stop,
)

// 页面离开/后台/会话或配置变更：先失效父 admission，再撤本次 ID。
val capturedId = voiceControl.state.value.permitId
invalidateParentAdmission()
capturedId?.let { voiceControl.revoke(it, VoiceControlStopReason.REVOKED) }
// 权限 launcher 必须保留原 permit ID（包括已失效时），不能套用后来 active ID。
permissionRequestPermitId?.let { voiceControl.permissionResult(it, granted) }
```

### 现有发送收据映射

以下在 `submit(turn, finalText, result)` 内组合；editor/conversation/ticket 使用父现有的持久化草稿、修订冻结和清理逻辑。`sendText` 的 false 只在其明确未入队语义下映射 Rejected；异常后不能推断未发送。

```kotlin
val expected = ChatController.TextSendOwner(conversation.id, editorGeneration, draftRevision)
val ticket = ChatController.TextSendTicket()
// 调用前：检查该 turn 的原 permit；持久化 finalText 并冻结精确 editor revision。
// 把 ticket 与 turn.requestId/permit.id 关联保留；不能靠当前可见草稿取回归属。
val queued = controller.sendText(conversation, editorGeneration, draftRevision, finalText, ticket) { r ->
    val receipt = if (r.owner != expected) {
        VoiceControlReceipt.Unknown(turn.requestId)
    } else when (r) {
        is ChatController.TextSendResult.Accepted -> {
            val a = r.assistant
            if (a == null || a.role != Message.ASSISTANT_ROLE ||
                a.conversationId != conversation.id) VoiceControlReceipt.Unknown(turn.requestId)
            else {
                val owner = VoiceAssistantOwner(a.conversationId.value, a.runId.value, a.id.value)
                acceptedMessages[owner] = a // 保留确切 Message 供 stop(owner)，不是 latest assistant。
                // 这是已有 Accepted 的关联标识，不把 requestId 自身当作持久成功证据。
                VoiceControlReceipt.Accepted(turn.requestId, "text-ticket:${turn.requestId}", owner)
            }
        }
        is ChatController.TextSendResult.Rejected -> VoiceControlReceipt.Rejected(turn.requestId)
        is ChatController.TextSendResult.Unknown -> VoiceControlReceipt.Unknown(turn.requestId)
    }
    result(receipt)
    // 仅按原 ticket/editor 的现有规则确认、清理或保留草稿；Unknown 不清空、不重发。
}
if (!queued) result(VoiceControlReceipt.Rejected(turn.requestId))
```

`revoke(permit)` 撤销该 permit 的桥接准入，并对仍 pending 的**原 ticket**调用 `cancelTextSend(ticket)`；不能删除回执关联或调用无参 `controller.stop()`。迟到 Accepted 仍须交回原 `result`。`stopAcceptedRun(owner)` 使用保存的确切 `acceptedMessages[owner]` 调 `controller.stop(message)`；该重载内部再比较 conversation/run/message。返回 false 可能表示该 run 已结束，并不准许尝试停止“当前 run”。父端在安全终结/清理确认后释放 ticket/Message 映射，不能无限保留。

### 精确正文 observer / 现有音频

父的 controller 更新回调应寻找**与已绑定 owner 三字段全部相同且 role=assistant** 的 Message；不是列表末尾，不是 latest，不通过全局 streaming 开关挑 owner。正文仅取：

```kotlin
val text = message.blocks.filterIsInstance<MessageBlock.Text>().joinToString("") { it.text }
val status = when {
    exactOwnerQuestionWaiting -> VoiceBodyStatus.QUESTION_WAITING
    message.status == RunStatus.RUNNING -> VoiceBodyStatus.STREAMING
    message.status == RunStatus.COMPLETED -> VoiceBodyStatus.COMPLETE
    message.status == RunStatus.FAILED -> VoiceBodyStatus.FAILED
    else -> VoiceBodyStatus.CANCELLED
}
listener(VoiceAssistantBody(boundOwner, text, status))
```

问题等待从父精确问题投影/许可事件获得，不从正文、最新消息或 RUNNING 猜。observer 注销后仍可能已经入队的 callback 由状态机 fencing；原 owner 暂不在快照中时等待或明确 Unknown，不绑定别的回答。

识别桥接可以复用现有一次识别会话，但把其已完成的最终 transcript 交给 `RecognitionEvents.finalResult`，而不是经过普通 composer host 的 finalDraft 自动发送。普通听写仍走原 applyDraft + 人工发送。播放器复用原 shared gate 与输出，不能另建 gate；在播放严格关闭成功后调用 drained。父端现有手动下一条级联和 duplex 要先通过排他准入协调，不可同时开两套音频。

## 专属测试清单与仍需父端验证

`VoiceModeControllerTest` 16 个 JVM 用例涵盖：final/receipt/body/drain 的完整两轮有限链；partial 不发送；权限前零音频；旧 grant；拒绝/UNKNOWN/submit 异常；重复 final/receipt；迟到 Accepted 精确停止且不影响新许可；错误 owner/receipt；问题等待；配置/权限撤销；旧 input/body/drain/failure；过早 drain/播放失败；五阶段超时与总时限；严格释放失败封锁；空 final；普通听写/duplex/不可用入口不进入循环。

`VoiceEntryPolicyTest` 3 个 JVM 用例涵盖：未知/已禁用历史不猜 UNIVERSAL、可用性与权限分离、有限参数不能配置成无限/零超时。

**执行状态全部为未执行。** 父最终统一编译/测试后还需实际桥接测试：现有 Accepted 身份/原草稿修订，精确问题等待通知，真实 ASR final，现有 TTS/远程播放器 drain/严格关闭，前后台与 permission launcher 竞态，音频抢占，配置/profile 切换及 UI 点击。不把这些 fake 测试当作已完成端到端功能或真实音频 drain 验证。
