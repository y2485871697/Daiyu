# P32：语音核心、一次性听写、生产 TTS 桥（父接线清单）

## 状态与来源

**这是 voice 包及专属测试的交付，不是已接好的 UI 功能。** 没有修改 MainActivity、ChatController、Manifest、Gradle 或其它模块，没有加入云端、实时双工、下载或网络依赖。现有 `AndroidSpeechPlayer.kt` / `SpeechPlayback.kt` 保持不动；原 `INTEGRATION.md` 继续描述它们。

本次只读参考 `build/agent-input/SOURCE.json` 声明的 `1a165ee5cc0ea4d6364557f2d3c1a4f822576184` retained 文件：

- `VoiceSession.kt`、`VoiceMainThread.kt` 原样迁入（分别 311 / 29 行，14961 / 1438 字符；父代理仍应做字节比较/哈希核验）。
- `AndroidSpeech.kt` 的 `DictationHost`、`SystemDictationRecognizer`、`SystemDictationPlatform`、私有 Android 平台实现、`AndroidDictation` 提取至 `AndroidDictation.kt`，只调整文件头/所需 imports。不迁 `AndroidSpeechOutput`、旧系统 TTS 平台、音频焦点实现。
- `AndroidDictationSafetyTest` 的听写与 releaseOnMain 用例提取自 retained `AndroidSpeechSafetyTest`；没有迁旧 TTS 测试依赖。
- `VoiceSessionCoreTest` 是 retained `VoiceBehaviorTest` / `VoiceSessionSafetyTest` 核心不变量的精简 JVM 用例，不声称迁入整个原测试集。
- 新写 `ProductionSpeechOutput` / `ProductionVoiceSession`、`DictationDraftTarget` / `DraftDictationSession` 及专属测试。源码引用只是输入清单声明，未独立验证 Git 对象；未读取/迁入云端候选或 retained-reference。

## 1. 父层唯一声音 gate

在覆盖同一声音交互范围的宿主创建并复用 **一个** `AudioOwnerGate`，传给朗读、语音链和听写。不能各自 new gate 后声称互斥。gate 是软件资源所有权，不是 Android audio focus，也不承诺压制其它应用音频。

父层现有 `VoiceHost` / 朗读入口尚未使用这个 gate。父必须选其一完成接线：

1. 把原显式「朗读」动作内部也接成 `ProductionVoiceSession`：冻结 owner → begin → done(owner, fullText)，这样仍经现有已修复的 `AndroidSpeechPlayer` 播放；把 VoiceState 转换为原 UI 状态，不得保留一条绕过 gate 的 `player.play` 路径。
2. 若保留原 VoiceHost 播放路径，则在实际 `play` 前 acquire 同一个 gate；旧 lease 的取消回调必须同步使旧请求失效并停止/关闭旧 player，Android 释放须通过主线程完成；仅在资源释放完成后让 acquire 返回。在 COMPLETE / FAILED 时使用 `releaseAfter(lease) { teardown }`，在 STOP / 生命周期时 cancel 精确 owner。所有 collector 必须同时核验 lease、不可复用 player-owner token、requestId；初始化/排队状态不算出声。不要使用裸 `release` 跳过 teardown，不要让旧 A 的 stop 关闭 B 的 player。

取消/释放异常会 poison gate，不得捕获后假装释放成功或随意 new gate 绕过资源屏障。必要时提示用户失败并结束宿主，先明确旧资源已释放再恢复。主线程绝不能等待被它阻塞的语音 worker 完成；VoiceChain 会在主线程释放等待前放下其状态锁。

## 2. 生产语音链/显式朗读

每个冻结的 conversation + generation run + voice session 都用新的 `VoiceOwner`；三个字段全部比较，不能只用 conversationId，也不能在回调中重读「当前可见会话」。runId 应来自不可复用的请求快照，sessionId 每次尝试重建。不要把三元组用分隔符拼接作唯一键。

```kotlin
val gate = sharedAudioOwnerGate // 父宿主提供，也供现有朗读和听写使用
val owner = VoiceOwner(frozenConversationId, frozenRunId) // 新 sessionId
val session = ProductionVoiceSession(applicationContext, owner, gate) { state ->
    // 回调来自语音事件线程或调用线程，不保证 main。
    // post 到 UI 后再次核验完整 owner / 当前宿主代际，再显示状态。
}
session.begin { frozenRequestStillAdmitted() } // admission 不拿宿主/registry 锁
// 仅显式用户动作/已授权该次语音请求后才执行；不要自动朗读历史消息。
session.delta(owner, deltaVerbatim)
session.done(owner, finalSnapshot)
// 或手动朗读全文：begin 后直接 done(owner, fullText)，不要先重复 delta 全文。
// 取消：session.stop(owner)。宿主销毁/切会话：session.close()。
```

`ProductionVoiceSession` 只 begin 一次；重试/切换建立新对象。内部 output 永久绑定完整 owner，player key 用独立 UUID，句子 requestId 每次也用独立 UUID。旧 owner/请求/句子事件不能更新新状态。底层 factory 惰性创建；main 上创建、bind、play、collect、close。stop 会 **close 并丢弃** 当前 player/collector（不只保留 ready 引擎），close 还取消 output 自有 coroutine scope。取消先使请求失效，再同步等 Android 释放；不把 stop 当 completed。

`play == true`、INITIALIZING、READY、QUEUED 均不触发 started；只观察匹配 player + owner + request 的 `SPEAKING` 才触发。证据标记 `TTS_ON_START` 只代表现有播放器接收 Android onStart，不是物理可听证明。StateFlow 会 conflation，极短 SPEAKING 可能没被 collector 观察；这种情况下完成也不伪造 started。语句切分、空白保留、final snapshot 更正限制、取消原因/teardown poison 沿用 retained 核心。

只在 `ProductionVoiceSession`（或同样严格冻结 owner 的自建 VoiceChain）中使用 `ProductionSpeechOutput`。不要拿某 A 的裸 output 接给 begin(B) 的链。不要让父原 player 与 bridge 复用同一可变实例：bridge 按 lease 拥有并关闭自己的播放器。

## 3. 独立草稿 target 与一次性听写

空白/尚未持久化的 composer **不需要伪造真实 conversationId**：

```kotlin
val target = DictationDraftTarget(
    conversationId = optionalConversationId, // 可 null
    editorGeneration = currentEditorGeneration,
    draftRevision = currentDraftRevision,
) // 新 sessionId
val attempt = DraftDictationSession(applicationContext, gate, target, draftHost) { frozen ->
    // 父保存 activeDictationTarget；必须比较 conversation(含 null)、editorGeneration、
    // draftRevision、sessionId 全部字段，且验证当前页面/生命周期仍有效。
    frozen == activeDictationTarget && editorIsCurrentAndForeground(frozen)
}
attempt.start() // 必须来自显式用户动作
// 切编辑器/编辑草稿/切会话/后台/销毁：先失效 target，再 stop/close attempt。
```

`start/stop/close` 和 DraftDictationHost 均在 main 使用。内部 synthetic audioOwner 只供 gate 使用，不可拿它的 conversationId 创建/定位会话或发聊天请求。每次 attempt 即使误复用 target，audio runId 仍新建；但父仍必须新建 target.sessionId。

父 `DraftDictationHost` 的义务：

- `permissionRequired(target)` 只引导 Activity 请求权限，遵循用户操作；获准后不得自动 start，用户再次显式点击才建立新 attempt/target。拒绝/不可用/启动失败/权限中途撤销都显示可理解错误，不发送草稿。
- `partial(target, text)` 仅作识别预览，不覆盖用户刚编辑的输入；任何编辑增加 draftRevision 并失效旧 attempt。不要因 partial 增加底层编辑器 revision 后又误接受旧 final。
- `finalDraft(target, transcript)` 只提供可编辑草稿，不能自动发送。应用到 composer 前必须再次核验完整 target；应用后由父将草稿 token 原子移交给新 revision（或关闭识别 token），不能继续接受旧回调。
- 若用户明确确认该识别草稿并发送，先校验当前确认 token/编辑器 revision，再调用 `transcript.confirm(editedText)`；仅非 null 可触发一次父现有发送入口。普通 composer 的手动发送不要额外触发第二次确认发送。`confirm` 不替代父的原子 UI 目标校验，也不负责聊天网络请求。
- UI post/权限返回后再次校验，不能仅在 callback 入队时检查。host 回调不得抛异常。

保留参考语义：权限预检失败不 acquire gate、不创建 recognizer、不抢占正在朗读的 lease；授权不会自动重试。有效 start 才抢占；STOP 尝试 cancel **且** destroy；自然成功/失败 destroy 后释放；空 final 不把 partial 升为确认草稿；late callbacks 通过 lease 丢弃。取消仅是本地停止，不承诺远端已取消或不会计费。系统 SpeechRecognizer 可能联网，**不宣称听写离线，也不是实时双工**。

Manifest 的 RECORD_AUDIO、speech recognition 服务 package visibility、运行时权限入口及生命周期接线本次均未修改/核验；由父在允许的集成阶段独立确认。不能因接口存在就宣称权限或设备兼容已经完成。

## 4. 验证清单与未验证项

新增专属测试：

- JVM `VoiceSessionCoreTest`：完整 owner、句子代际、保留空白、同步排队、final 更正拒绝、confirm-once、释放屏障/poison。
- JVM `ProductionSpeechOutputTest`：真实 MutableStateFlow + 假 player；接受不等于 started，owner/request/source 过滤，相同文本新 request，排队回调取消、stop-before-create、close、失败/重试、与另一音频 lease 互斥。
- Android `AndroidDictationSafetyTest`：提取参考听写 seam 用例，包含权限/撤权/拒绝 admission、空 final、late callbacks、停止前不创建、cancel/destroy 失败、同步主线程释放。
- Android `DraftDictationSessionTest`：null conversation 草稿、完整 target 各维失效、权限不抢占/显式重试、stop/close、不取消新 owner。所有 recognizer 都是 fake。

本子代理仅静态读写/复核：**未执行构建、JVM/仪器测试、Lint、设备运行、下载、开麦或发声。** 父在许可阶段统一编译并运行上述类及现有 SpeechPlayback 回归集；新增测试不代表已经通过。Android 服务可用性、权限弹窗、locale、离线 TTS voice 选择、真实 onStart/状态观察、后台释放、全 App 的 gate 互斥和编辑器原子确认仍需父集成与设备验证。仍不提供物理 audibility 或系统听写离线保证。
