# MainActivity R13 接线

## 替换范围

- 移除 MainActivity 的原生 LinearLayout 聊天根、工具栏、ScrollView 时间线、EditText 草稿、发送/暂停 Button、TextView 消息行、工具展开按钮及 Choreographer/原生行坐标滚动代码。
- `ConversationSidePaneScaffold` 的 content 改为 `DaiyuChatScreen`。消息始终来自 `controller.database.messages` 的 `shown` 加载窗口，经 `ChatTurnMapper.map` 投影；没有引入另一套消息仓库。
- 移除 MainActivity 的所有 `android.app.AlertDialog`：模型设置/目录、备份格式选择/导入确认、配置恢复预览/确认、搜索、文件夹选择、会话选择/操作、重命名、移动与删除。
- 模型设置、备份与恢复预览、外观分别由 `retainedPage = models/backup/appearance` 展示新页面；侧栏设置进入 `settings`，不再打开 FeatureHub。子页面返回键返回聊天。
- 文本输入、选择与确认使用 `DaiyuTextInputDialog`、`DaiyuChoiceSheet`、`DaiyuConfirmDialog`。
- 侧栏、ManageChatsScreen、UsageStatsScreen 本体没有改动；Manifest、启动图标及图标资源没有改动。
- **刻意保留的原生适配器**：`QuestionCardView`（作为每个助手消息内的独立 AndroidView，而非聊天区根 View）仍接原 `controller.questionProjection` / `QuestionCardBinding`，避免丢失问答屏障、持久答复与停止竞态。技能管理仍使用原 `SkillManagementDialog`；本次没有重写其业务或外观。

## 回调与业务边界

| UI 回调/入口 | 宿主接线 |
| --- | --- |
| 草稿 onTextChange | `database.draft(text)` 或 `database.draft(selected.id,text)`，成功写入后才更新受控输入；不 trim |
| 选区变化 | Compose TextFieldValue/TextRange → `selectionStart/selectionEnd`；切换/暂停时保存到既有 `controller.selections` |
| 发送/运行中有文字追加 | 始终调用既有 `controller.send(selected,text)`；返回非 null 才清输入、选中保存的会话、加载消息并请求到底部。Controller 拒绝时精确保留草稿，不旁路其 idle/owner 守卫 |
| 控制短按 | 经既有 `currentControlOwner()`，读取 `controller.runState(owner)`；`pauseRequested ? resume(owner) : pause(owner)` |
| 控制长按 | `controller.stop(owner)`；手势 down 捕获旧 owner，保留 id/runId/conversationId 检查，旧回调不转向新 run |
| 控制显示 | `composerPhaseForRun(runState?.pauseRequested)`，仅 UI 投影，无持久 PAUSED RunStatus |
| observer 刷新 | 原 `controller.observe(listener)` / `unobserve(listener)`；从已加载窗口更新数据库消息与 active 投影，超过 200 条时分批读，保留早历史窗口 |
| 跟随/回到底部 | ChatMessageList 观察条目高度及窗口尺寸；触摸 down（含子按钮）停止跟随并取消用户消息跳转；显式到底部重新跟随 |
| 加载更早 | `database.messages(selected.id,shown.first.seq)` 前置到 shown；LazyColumn 的 message-id key 保持可见锚点/偏移，不重新打开 tail |
| 定位用户消息 | DaiyuChoiceSheet → `jumpToMessage(id)` → 带递增 serial 的 Compose scroll request |
| 历史搜索命中 | 既有 `retainedData.search`；查询命中 owner 的 seq 后加载相应窗口并跳到该 message id |
| 复制 | 从原 Message blocks 取原文/工具结果 → ClipboardManager |
| 会话打开/新建 | `select(conversation/null)`；先保存旧 owner 草稿/选区，选中偏好 commit 成功后才切换草稿 owner |
| 重命名/移动/置顶/删除 | 原 `database.rename/organize/delete`；删除回调重新核实 active owner，确认框为一次性能力 |
| 文件夹 | 原 `retainedData.createFolder/changeFolder`；不变更侧栏布局 |
| 模型页打开 | `settings.loadForEditing()`，仅内存草稿，不进入 saved state；活动 run 时拒绝修改 |
| 模型页保存 | `settings.save(copy,protocolChangeAuthorized,requestOptionsReplacementAuthorized)`；重新核实当前页、原草稿身份、无 active/busy、保存协议及两项明确授权 |
| 协议切换 | 新页面既有 `selectProtocol` 清空 key/旧绑定授权，Messages 清空并禁用 Responses 路径 |
| 模型目录 | `controller.fetchModelCatalog(当前内存草稿, cancelled, callback)`；每次草稿变化、保存尝试、离页、销毁使 generation/cancel token 过期；返回时再次检查 Activity/page/generation/完整草稿；选择结果只改草稿 |
| 备份导出/导入 | 原 SAF 101/102/103/104 → `exportUserBackup` / 一次性明确确认后 `importUserBackup` |
| 备份最终授权 | 每次入口、SAF 回调及确认回调均重新核实 ready/no active/no dataBusy/no recovery failure；busy observer 撤销可见备份确认，过期闭包无权提交 |
| 配置预览/恢复 | `previewImportedConfiguration` → 页面 before/after 数据；“恢复配置”后还须“确认恢复” → `restoreImportedConfiguration(preview,authorized=true)`；离页/busy/销毁调用 `cancelConfigurationRestore` |
| 外观 | 私有 appearance-settings 偏好 commit 成功后更新 `AppearanceSettings` 并驱动 `AgentAppTheme`；主题/缩放有效，不操作启动图标 |
| 助手标签/管理 | 单一 `SharedPreferencesAssistantStore` + 单一 `FileAssistantAssets` 传所有助手界面；选择调用 store.select，编辑进入 AssistantEditScreen；技能列表来自 IO 上的 `controller.skillManagement().snapshot()` |
| 子代理设置/协作 | 单一 `ConversationSubAgentPreferences` 经 repositoryFactory 传 `SubAgentSettingsScreen` 与 `ConversationCollaborationDialog`；协作捕获 conversation owner 并检查 ownerMatches/taskRunning/busy，不打开时创建会话 |
| 推理标签 | 打开原迁移的 `SubAgentThinkingEffortPickerDialog`；当前 Controller 没有主代理 reasoning/capability setter，故只提供 DEFAULT，不擅自修改 customBody 或虚构能力 |
| 设置中的聊天控制条目 | 可见但不发送/暂停/停止，设置导航不是执行授权 |

## 未接通入口（保留按钮，无数据副作用）

- 聊天：附件图片/视频/文件、生图/生视频、语音/听写/TTS、上下文统计/压缩、交互模式、单消息重新生成/朗读/分支/编辑/删除、单会话导出。
- 设置：聊天记录搜索单独入口、会话 ID 与网关、供应商余额、标题模型、辅助视觉模型、错误重连、上下文压缩/统计/保护/状态、请求运行详情、语音与文件打开方式、浏览器、设备/Root/无障碍/虚拟屏/Linux 终端权限入口、消息操作栏。
- 外观：应用图标、图标外观、鲸鱼女仆、震动、刷新率策略按钮不写数据。刷新率仍沿用原宿主最高支持刷新率生命周期行为。
- 时间戳开关仅保存阅读偏好：现有 Message/消息表没有时间戳，不伪造消息时间。
- 助手人格/记忆/技能配置及子代理预设/会话协作配置按已有组件契约持久化，但 Controller 没有相关运行时注入接口；本次不擅自改模型 prompt、权限或运行调度。
- 子代理模型来源暂用 `SubAgentModelSource.Empty`：宿主只有单模型设置，没有可核实 capability/provider registry，不泄露密钥或虚构模型能力。
- 推理强度只显示 DEFAULT，待真实运行时能力和 setter 提供后再接写入。

UI 不添加“未实现/占位”标识，无渐变或硬编码颜色。

## 测试迁移与验证状态

- 新增 `ComposeActivityTestCase`：将 Compose empty rule 的 root registry 包裹既有 JUnit3 `runBare` 生命周期，保留原自建 Controller/ContentProvider/SAF fixture；AndroidJUnitRunner 同时兼容其余旧测试。
- 固定 `ui-test-junit4/ui-test-manifest:1.9.0`、`androidx.test:runner:1.7.0`；版本解析仍须主代理构建核实是否需与最终运行时依赖对齐。
- 迁移 `ActivityTest`、`ActivityLifecycleTest`、`RunControlActivityTest`、`BackupActivityTest`、`IntegrationSettingsActivityTest`、`FeatureHubActivityTest` 与 `QuestionBarrierIntegrationTest` 宿主发送；保留原 test 方法名和业务断言，新增 busy 撤销确认测试。
- FeatureHub 测试改测设置首页同一 catalog，空入口仍检查数据库字节、设置及零模型请求；老“详情页旧按钮”改测离页/重建后保留 semantics 回调失效。
- Builder 的异步排队确认现改为 Compose 同步提交：对应生命周期测试保留 semantics OnClick 闭包，在 finish/销毁/取消后主动投递，检查 provider opens=0、revision=0、备份字节不变、model calls=0。
- 设备滚动测试改查 LazyColumn 可见 key 的像素偏移、滚动轴实际 end、触摸后增长不再到底部、加载更早前后 offset、定位用户行与真实数据库终态；不再反射 View 行坐标/Choreographer 字段。
- 模型设置测试改用受控节点断言密钥清空、协议切换授权、损坏策略替换授权、目录晚到失效、保存失败不变、凭据重输及精确草稿恢复。
- 稳定标签：`chat-draft/chat-send/chat-control/chat-model/chat-title/chat-messages/chat-turn:<id>`、`page:<title>`、`settings-field/radio/switch/row:<label>`、`settings-search`、`model-save/model-catalog-fetch`、`prompt-input/prompt-choice:<index>`、`dialog-confirm/dialog-cancel`。
- 新增 JVM `ComposerPhaseProjectionTest`，保留既有测试（含 ChatTurnMapper 测试）。
- 未删测试；技能管理与问答卡的原生断言保留，因为对应控件没有迁移。没有因无法迁移而禁用/忽略的单条测试。
- 实现代理工具只能读写文件，**没有运行编译、JVM 或设备测试，也不能执行 Git 提交**。请主代理执行 compileDebugKotlin、compileDebugAndroidTestKotlin、testDebugUnitTest 与设备回归，并在审核后于 main 工作树提交（不建分支、不推送）。

## 契约来源

- `ui/screens/chat/conversation/{ChatScreen,ChatComposer,ChatMessageList,ChatScreenModels,ChatTurnMapper,ChatMenus}.kt`
- `ui/screens/settings/{SettingsHomeScreen,ModelSettingsScreen,BackupScreen,AppearanceSettingsScreen}.kt`
- `delegation/INTEGRATION.md`（实际位于 Kotlin 包目录）
- `docs/integration/assistant-ui-c2-handoff.md`
- 既有 `ChatController.kt`、`storage/ModelSettings.kt`、`storage/ConversationDatabase.kt` 与原 Activity/androidTest 行为。
