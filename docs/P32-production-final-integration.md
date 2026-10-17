# P32 最后生产入口组合（已组合，待统一验证）

## 范围 / 证据 / 未执行事项

本次测试/文档增量基于任务指定的 `03333fe` 工作树，修正审查 `780feaeb` 已确认的 receipt 测试问题及过期组合说明，不改生产行为。工具没有 Git HEAD、提交、Shell 或构建接口，未独立核验 SHA，未创建提交或声称测试通过。本文所述生产入口及账户修复已在当前源码组合，仍待父统一验证；来源是以下接缝文档和对应源码，非 leaf 自述验收：

- `production-personal-cascade-integration.md`
- `P32-account-preview-host.md`
- `P32-universal-production-host.md`
- `P32-voice-account.md`

本次只改 P32 生产入口测试及本文，不重复搬入 leaf 或调整生产回调。未调用供应商、付费服务、麦克风或浏览器；没有读取实际连接资料/密钥、下载权重、改 Manifest、依赖、CI、旧 Eta 或唯一重构清单。父统一构建、测试、提交与 artifact 门禁。

## 1. 个人音色有限级联 / 两个独立 profile

实际入口：`MainActivity.DictationComposerEntry` → **ProductionPersonalCascadeConsent** → 原 `ActivityCascadeVoiceHost.preparePersonalConsent / armPersonal`。不再是旧 Main 的 `arm()` 文本按钮。

- 组件 `isCurrent` 只捕获原 editor、voiceRequestEpoch、remote operationToken、speech owner，**不捕获准备操作自己会改变的 cascade token**；还排除 UNIVERSAL 活跃许可。RESUMED、旧准备/确认/取消回调的精确 proposal 校验由现组件负责。
- 原 `sendDraft` 中 `cascadeVoice.reserve(pending.ticket)`，原 durable Accepted 的 `cascadeVoice.accepted(pending.ticket, result.assistant)`，原 rejected/Unknown 的 `rejected(pending.ticket)` 均保留。没有复制聊天发送实现。
- 普通 Stop / 后台仍 `close()`，永久 `onDestroy` 改为 `dispose()` 注销 runtime listener。
- runtime **仍是 Controller 的原实例**。READ_ALOUD 手动朗读/普通听写/原双工默认路径不变；级联与 UNIVERSAL 明确冻结 CONVERSATION。配置页按 profile 保存、显式迁移与 picker 失效保持原实现；切换 profile 新增先撤输出。
- UNIVERSAL 暂不接个人声音：宿主现在也拒绝豆包个人 ID，避免把有限手动级联或单条许可变成多轮/多句授权。个人声音请使用上述有独立上限的级联入口。

## 2. 账户音色 / 真实样本 / 实际试听

路径：原设置页 `open-voice-settings` → `VoiceSettingsPanel` → `voice-account-open` → **ProductionVoiceAccountPage** → 既有账户面板。

Controller 持有唯一 `voiceAccounts`；同一原 runtime 注入 state-only `voices.selected(config.voice, config.speech())` resolver。Main 启动在 IO 做账户本机 `load()`，不查询网络；加载失败个人 ID 失败关闭，不影响系统配置修复页。

页面冻结已保存 profile revision，以 composition incarnation 隔离旧页面。原账户面板保留真实 Android `GetContent(audio/*)` 样本选择与独立训练确认；导入/选择不上传。选中音色时同步严格停 preview → `runtime.invalidateSpeech()` → 保存**目标 profile**，新 revision 后回设置。Main 的集中 route/editor/lifecycle 撤权函数直接调用页面注册的 revoke callback，而非等待下一帧 dispose 或下次播放器事件。注销函数按 callback identity 清理，旧页面不能注销新页面。

试听使用 `ActivityVoiceAccountPreviewHost.forContext` → 现 `SpeechSynthesisClient.synthesize(config, 固定文本, voice.id, voice)` → 原 MediaPlayer。固定文本为“你好，这是当前账户音色的试听。”，不是读取 demoUrl。页面明确说明可能计费、取消不保证免计费、失败不重试；**正向同意**设置 atomic admission，离页/后台同步撤回。host admission 同时检查原 revision/profile、页面 admission、实时 selected account/status/models/accepted，不是恒 true。当前 leaf 对 demo 非空的限制未绕过。

### 已组合的账户修复与严格停止接口（待统一验证）

当前源码已包含 `VoiceAccountPanel.kt` / `DoubaoVoiceCatalog.kt` 的账户修复。实际 `VoiceAccountPanel` 已暴露 `stopPreviewStrictly: () -> Boolean` 尾参，生产包装页 `ProductionVoiceAccountPage` 已通过 `VoiceAccountPanel(..., stopPreviewStrictly = { ... })` 显式接入严格停止证明；不再存在需另行补齐外层参数的组合前置条件，也不依赖默认 `{ false }` 或把普通 `stop()` 当成功证明。该组合状态仅经源码核对，不代表编译或测试通过。

已接入的宿主接口：

```kotlin
ActivityVoiceAccountPreviewHost.stopPreviewStrictly(): Boolean
```

它同步取消确切 preview lease，要求 active 已清空，保留任何 `closeStrict()` 失败，并读取**原唯一 gate** 的 `releaseHealthy()`。新 gate 方法仅只读 `!releasing && !releaseFailed`，不 acquire、reset、释放其他 owner 或模拟成功。即便自然完成已退租，旧失败仍 false。生产 leaf 回调严格成功后再同步 `runtime.invalidateSpeech()`，最后确认 gate 健康；false/throw 由独立账户修复锁存拒绝 account/voice 变更。runtime invalidation listener 同样严格停 preview，失败抛回保存流程。账户 slots 分页完整性、leaf execute 严格停止屏障及在线试听计费确认文案修复均已组合；这些接缝仍需与生产包装页一起统一验证，不能把已组合描述成已验证。

## 3. UNIVERSAL 手动控制生产调用链

`DictationComposerEntry` → `voice-universal-open` → **ProductionUniversalVoicePanel** 可滚动 Dialog → 原 **VoiceControlPanel**。打开、选 profile、改轮数/时长不申请权限、不打开音频、不发消息；必须再次明确点击开始。

1. Main 停原级联/普通听写/离线听写/手动输出，再调用 `ActivityUniversalVoiceHost.start`。
2. entry 冻结 CONVERSATION 输出与该 profile 的 ASR 独立字段。可明确选择离线识别，缺包/禁用不回退；SYSTEM/DOUBAO 使用现 AndroidUniversalRecognition 真实端口。现 conversation `dictation` 字段用于该 profile 识别，不读 READ_ALOUD 的普通听写字段。
3. 专属 Activity 权限 launcher 只保留原 permit ID，旧权限回调不会换成新 active ID。后台/系统权限界面若导致 Activity pause，该许可按保守前台规则结束；授予权限不自动恢复，需要用户再次开始。
4. 识别最终结果 → 原 `updateDraft` 本机持久化 → 原 `sendDraft` → 原 **ChatController.sendText**。非空草稿、未保存新会话、其他 pending ticket、附件/审批/终端/副屏/生成入口、其他 active owner 均拒绝。仅精确 own-send 可迁移自己的 editor revision；外部编辑先撤权。
5. Main 原 **TextSendResult.Accepted** 是唯一成功证据。`productionUniversalReceipt` 校验原 owner、会话与非空 exact assistant，保留 conversation/run/message 三元组及原 Message；enqueue true、草稿清空、controller.active/latest 不当成功。
6. 原异常报告 Unknown，不自动重发，保留原 ticket/回调关联。回调在原 UI guard 之外继续交原 host；Unknown 后真实 late Accepted 由原 AndroidUniversalReceiptFence 对确切 owner 清理。普通 false 才是明确 Rejected。原 Accepted 重复回调不能再次迁移 editor/撤新许可或重新插入已清理 owner 映射。
7. AndroidUniversalOwnerObserver 注册后 replay，exact active 或 IO 分页读取原 SQLite row；只朗读该条 assistant 的 Text 正文。问题投影在 Main 发布 admission 前读取真实 question record；等待时先写撤权快照再 revoke。正常完成 observation 取消时回收 owner 映射，停止只 `controller.stop(exactMessage)`。
8. 原 AndroidUniversalPlayback / VoiceChain / ProductionSpeechOutput / RemoteVoiceHostPlayer 保留真实 completion + strict release 后再收听的 drain，所有端口共用 `ActivityVoiceAudio.gate`。gate admission/current 仅读 volatile / StateFlow 快照，无 Controller/DB/registry 查询。

原 Main 视频、离线听写设置、终端、副屏、子任务、桌宠、生成和其他聊天接线未整体重写。没有恢复厂商/Google/系统数字助理接管服务或入口；SYSTEM 仅用户手动选用的现识别/朗读端口。

## 4. 定向自然离线 release 失败修复

review 指出的链路成立：自然 endpoint 的麦克风 `release()` 失败被 session 缓存，finally 已 poison/退租后，原 close 的 `gate.cancel(false)` 会掩盖缓存错误。

`OfflineSpeechSession.close()` 现在 fence cancelled → `try gate.cancel(owner) finally releaseAudio()`，即使 lease 已退休仍传播缓存 release 失败。UNIVERSAL 的现 `closeAndDrain` 直接调用该 close，原 VoiceModeController.releaseRound 捕获错误，将普通识别失败提升为 **RELEASE_FAILED** 并永久禁止再次开始。不 reset gate，不等待慢 JNI decoder，后者仍由原 worker 关闭。

## 测试源码与剩余验证

新增：

- `ProductionUniversalReceiptTest`：真实生产 mapper 的 durable Accepted、缺失 assistant、错会话/editor/role、Rejected/Unknown、Unknown→late Accepted exact cleanup、旧 request、严格 editor migration。
- `P32ProductionEntryActivityTest`：真实 Main 控制面板可达且打开不发送、不申请录音；现有草稿拒绝入口；设置→账户页可达；原 Main sendDraft→真实 Controller/SQLite→exact durable receipt。本次将原始回调接入真实 `AndroidUniversalReceiptFence`，分别记录 raw receipts 与业务 delivery：业务 Accepted 仅一次，所有 raw Accepted 均指向同一 SQLite exact owner，重复 Accepted 不停止原 owner，SQLite 仍仅一对 user/assistant 消息且 fake model 仅 open 一次。不再要求 raw 回调 `single()`，保留 retained-state refresh 与原 callback 的合法双交付及既有 Unknown→late Accepted exact cleanup 测试。使用既有 fake model fixture，无供应商网络或设备收音。

扩充：

- `ActivityVoiceAccountPreviewHostTest`：strict idle 不建播放器、同步 close、自然 drain 失败缓存、别的 owner 已 poison 时不能宣称健康。
- `OfflineSpeechContractsTest`：自然 endpoint release 异常、重复 close 仍抛、gate 不恢复；实际 session→VoiceModeController 组合得到 RELEASE_FAILED 且不能再开始。

本次**未运行任何构建或测试**，以上是测试源码覆盖说明，不代表设备麦克风、播放可听性、UI 布局或构建通过。账户修复与 strict seam 已组合，待父统一编译/JVM/instrumentation 验证（包含 receipt 业务去重及 Unknown→late Accepted exact cleanup）；本工具无 Git commit 或 artifact 命令，实际净 diff 由 workspace 工具交付，父执行提交/artifact 门禁与最终审查。
