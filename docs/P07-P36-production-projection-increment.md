# P07 / P36 生产投影增量与 5827 展示修正

## 边界与状态

本次先读 `docs/重构清单.md` 最新记录；复用已有真实助手侧栏、加载四参数与分段定位接线，不重做 `03333fe` 所述成果。未改自研动画、侧栏外观、平台读取策略或读取实现。只补确定的状态接缝；状态为待父集成 / 待验证，**不是业务验收完成**。

本实现代理无 Shell / 构建运行能力。本次只做源码读取、净改动和静态复核；**未运行编译、JVM 单测、instrumentation、Lint、APK 或实机测试**。下列测试是新增/修改的测试源码，不是通过证据。清单与更新说明由父代理同提交维护，本任务没有并行改唯一清单。

## P36：真实 listening → Main → ComposerUi

- `ComposerDictationHost` 只在原 owner 的 `VoicePhase.LISTENING` 发布 `listening=true`；点击、PREPARING、等待最终结果、完成、失败与取消均不是聆听证据。用现有 active owner 保持“准备中再次点击取消”，不再拿展示 listening 当任务存活标志。
- `OfflineComposerHost` 只在本机 session 的 `Events.listening()` 发布 true；加载模型或 partial 不推断聆听。状态附原 `DictationDraftTarget`，复用原 editor/session fence；取消先撤 active，再关闭旧资源。
- `ActivityRemoteVoiceHost.listeningTarget` 仅在原 attempt 已实际 `startCapture` 后发布，在结束录音、清理及 invalidate 前清空。不以连接文案、点击、TTS、播放状态判断。设置页双工不投影成聊天页聆听。
- `MainActivity.projectedVoiceComposer` 收集这些真实 flow 和现有 `VoiceModeController.state`，向 `ComposerUi.listening` 传值。复查最新发布状态、原 conversation/editor/draft revision、前台、chat 页面与侧栏遮挡。持续语音只认原 permit/editor 且 phase=LISTENING，不认 CONNECTING / SUBMITTING / SPEAKING 或聊天 RUNNING。
- 未改 `SpeechMorphButton` / `ContainedMorphLoadingIndicator`，未重做 loading 四参数。

## P07：完成事件与精确阅读确认

`ConversationCompletionMarkers` 是最小本地 SQLite 增量：

1. 在现有消息表上只观察真实 assistant 物理行 `RUNNING → COMPLETED` 的 UPDATE，事件和原消息 / checkpoint 在同一事务提交。现有 legacy save 与原子 checkpoint 两条持久路径均覆盖。
2. 每会话仅留最新未阅完成事件，绑定 conversation/message/run 和随机 token。完成历史 INSERT、升级安装、重复保存已结束消息不产生事件；不把 latest COMPLETED 历史全部点亮。
3. `RetainedConversationData.summaries` 将真实事件存在性投到原 `hasCompletionMarker`，不改原侧栏组件。搜索不是阅读确认。
4. Main 在真实 chat actions 生成时捕获原物理 completed owner / event / 最后 assistant segment 的 listKey。`ChatMessageList` 只读现有 LazyList measured layout，等待一帧并重查该 completed item 仍与 viewport 正面积相交且尾部已进入 viewport，再回调；不改变原跟随/定位/滚动。Main 复查原 editor / data revision / 前台 / 页面 / 侧栏，以捕获的完整 receipt 精确删除。未进入 viewport 的尾段、只加载了历史或显示同 owner 早期 segment 不确认。**确认时不重新读取新 token**，旧阅读确认不能清掉新完成。不声称测量用户眼睛或物理阅读。
5. 消息删除清相关事件；会话 FK 删除清事件；事务回滚不会留下完成标记。未阅是本地 presentation 状态，不改变备份格式、运行终态或网络发送。

### 父集成必做：与 usage 合并为同一个 v17

本工作树基线 `ConversationDatabase` 仍是 v16。按任务要求，**本增量没有另行改版本号/升级版本范围**，只在 `onCreate` 和 `onUpgrade`（原 >=13 early return 之前）各加一行 `ConversationCompletionMarkers.installSchema(db)`。

父需和 usage 的 v17 升级组合：保留 usage 的 version=17、允许 oldVersion=16 的升级条件，同时保留这两处增量安装。不要整文件覆盖数据库或回滚 usage/title/video witness。若不组合版本升级，既有 v16 数据库不会调用 onUpgrade，缺表；不能把本补丁单独当可发布成品。

## 5827：工具错误展示用可信持久标志，不猜任意 JSON

已核实真实链路：

- `integration/tools/RunDeviceResources.executeRead` 校验平台 receipt 的 `ok` 类型后返回 `ExecutionReceipt.Confirmed(payload, isError=...)`。
- Dispatcher 的 `DispatchOutcome.Confirmed.isError` 经 `core-domain/.../ChatRunner.kt` 映射成 `ToolRecord.disposition=FAILED / SUCCEEDED`，持久化于现有 runtime checkpoint。`MessageBlock.Tool` 本身没有 isError 字段。
- 复用已有 `SQLiteRuntimeStore.readCheckpointSnapshot` 验证过的只读快照，`RetainedToolOutcomes` 按 conversation/run/物理 owner（含历史 segment）、完整 invocation 与精确 result 绑定可信结果，再传入 Main 的 `ChatTurnMapper`。无匹配快照 / 元数据缺失时沿用旧展示，不从模型任意 JSON 造权威。
- `ChatTurnMapper` 在可信 FAILED 时画失败，可信 SUCCEEDED 时画成功；UNKNOWN / 未执行 / 其他 disposition 不新增权威映射，保持原有展示和运行 UNKNOWN 语义。没有更改重放、授权、工具策略或平台读取。

## 测试源码清单（均未运行）

- `DictationActivityTest`：真实 Main/actions/editor + 假 Android recognizer；准备不聆听，ready 才聆听，end-of-speech 等最终结果不聆听，换 editor / 后台后的迟到 ready 不复活，RUNNING 不等于 listening。保留原权限/取消测试。
- `OfflineSpeechContractsTest`：真实 offline host 的加载 / partial / listening / complete / late-listening 状态合同。
- `CompletionProjectionActivityTest`：真实 Controller/Runner → SQLite terminal → Main 侧栏未阅 → measured viewport 帧确认；侧栏遮挡不能确认；旧 token / 旧 editor 不清新完成；不产生新请求。
- `storage/ConversationCompletionMarkersTest`：真实 legacy save、重复 terminal、重开、旧确认 CAS、新完成替换、失败/停止/中断、事务回滚、删除、实际备份导入不生事件，以及安装不 backfill 历史。
- `RetainedToolOutcomesTest`：真实 Runner + SQLite checkpoint + 重开 + mapper；相同 `ok:false` 文本、不同可信 isError 得到不同展示；跨 run / 改参数 / 改结果不能借旧标志；无可信记录的 JSON 不变成权威；UNKNOWN 无变化。
- `ChatTurnMapperTest`：可信错误闭包与缺失权威的任意 JSON 回归。
- 既有 `RetainedConversationDataTest` / `RetainedSearchDataTest` 的两个旧默认无标记断言已按真实已完成且未阅读事件改为 true；搜索不清未阅。

## 剩余验证与不确定性

父合并 v17 后需运行上述测试和共享 Main 全量编译 / instrumentation，尤其检查数据库旧 v16 → v17（含 usage/title/video witness）的组合升级、前后台/页面重入与 Compose 帧时序。真实麦克风、联网豆包、持续语音与物理播放未测；不能据测试源码或状态机日志声称硬件/网络验收通过。
