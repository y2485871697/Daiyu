# F11/F19 接缝（接口冻结）

来源：当前重写项目 `ConversationDatabase.kt` schema3 文本备份/消息分页、`ModelSettings.kt`、`ResponsesModel.kt`；验收 `docs/refactor-input/acceptance.json` AC-033–035/050–052，features F11/F19。没有读取工作树外旧策略或复用 Eta 源码。保留候选压缩策略由父代理提供；这里的保守默认边界不能作为旧策略被取代的审查证据。

## 主数据库唯一接线（父代理修改公共文件）

1. helper version 3 -> 4；`onCreate` 现有建表后调用
   `app.daiyu.android.context.ContextStorage.installSchema(db)`。
2. `onUpgrade` 放宽到 oldVersion in 1..3 / newVersion==4；oldVersion==1 创建 composer；oldVersion<=2 增 folder/pinned；最后 `ContextStorage.installSchema(db)`。依旧拒绝未知版本、不删旧库。服务构造器不执行任何 DDL。
3. 删除会话时，现有 transaction 中按顺序：
   ```kotlin
   db.execSQL("DELETE FROM neutral_assets WHERE message_id IN (SELECT id FROM messages WHERE conversation_id=?)", arrayOf(id.value))
   db.delete("context_archives", "conversation_id=?", arrayOf(id.value))
   db.delete("context_branches", "conversation_id=?", arrayOf(id.value))
   db.delete("context_usage", "branch_id=?", arrayOf(id.value))
   // 然后现有 messages/conversations 删除
   ```
   不同步删 blob：跨分支共享 hash；孤儿以后按数据库引用集清理。
4. 所有上下文、导入操作与 UI 发送沿用单一数据库 helper 和 owner executor。真实共享：
   ```kotlin
   val contextService = ContextService(context, conversations)
   val backupService = NeutralBackupService(context, conversations)
   val registry = ContextRegistry(contextService)
   ```
   主构造函数支持同连接 `DatabaseTransactions { conversations.writableDatabase }` 注入；禁止另开 sidecar 数据库实现影子前缀。

## 发送 / UI

- **先构造上下文，再插入 RUNNING 占位**：调用 `contextService.prepareSend(id, policy, summarizer)` 得 `messages`。然后现有 send 写 user/assistant，ChatRunner.run(initial, prepared.messages + user)。自动摘要不能在 RUNNING 后触发，因为正在生成的历史不可修订。
- 新会话无 id 时 history 为空；不要调用 prepareSend 对不存在 id 自动创建会话。
- 用户分支按钮调用 `branch(id, completedMessageId)`，返回实际 ConversationId，刷新会话列表并选新 id；原会话不变，消息/run IDs 重映射，附件引用同事务复制。
- 删除后续或改用户输入按钮调用 `revise(id, throughMessageId, replacementUserText?)`。必须在 owner executor 运行并重新加载 UI；实体 messages 已截断，所以现有分页和下轮模型不会读到废弃后续。用量保留作原历史请求账本，不因此伪造退费。
- 手动压缩调用 `compress`；`ContextSummarizer` 接收完整事实/工具状态/协议原文及固定不可信数据指令，在服务 write transaction 之外运行。不能使用宿主助手历史。摘要失败、不缩小、并发历史改动均不发布；原表从不删原文。
- 保留候选的 CompressionBoundary/SendPolicy 接到 `ContextService(..., compressionBoundary = { messages, keepTurns -> ... })`；保留事实/证据策略应落实在 `ContextSummarizer`，这里不替代它。默认 fallback 仅支持连续 completed user/assistant 同 run 完整工具轮，保守不跨失败/运行单元。
- `archivePage(id, offset, limit)` 或 Registry `context_archive_page` 是成功归档原文真实查询，最大每页200。档案在 app 私有 `filesDir/neutral-context-blobs`；缺档/散列不符拒分支/修订，不凭摘要补原文。
- 模型 usage 接收器成功收到 provider usage 时调用 `recordUsage(UsageRecord(responseId, runId, conversationId, ...))`。每个工具续请求用自己的 request/response ID；完整重放幂等，跨分支/数值冲突拒绝。未返回 usage 可登记 null；估算单独在 PreparedContext.estimatedTokens。`usage(branchId, runId)` 不混任务；cached 是 input 子集、reasoning 是 output 子集、不二次相加。
- provider window 未知时 policy.contextTokens=null，保留未知，不触发自动容量压缩。
- Registry 格式 `schemas: JSONArray[{name,description,parameters,sideEffect}]`，execute(name,JSONObject):JSONObject；只注册原文查询与用量。branch/revise/import 是显式 UI 操作，不开放模型写入。

## 完整中立包（不改变严格 v1 文本格式）

- `exportPackage` / `preflight` / `importPackage(InputStream|String)` 独立 format `daiyu-neutral-complete`, version1，内部原样 schema3 `daiyu-neutral-text`。原 exportBackup/importBackup 不改，不接受扩展字段。
- 导入旧 source 仅只读流；不删除/移动/覆写旧源。预检核对总数、关系、原文 revision、附件 byte/hash、清单整体摘要和身份。最终原 text payload 仍由原 parser 严格验证；其异常回滚整包。preflight 不等于已有 strict text parser 已通过（父可暴露其验证入口改善早期错误提示）。
- 私有 blob 先 sync/rename 不可变发布，再插入 SQLite 引用；text/分支/归档/usage/附件引用/配置/import marker 一个 helper 事务。强杀到提交前只有无引用 hash 文件；重启只有完整旧/新 DB。不能把附件导入到外部共享路径。
- 配置只允许 endpoint/model/responses-path/battery/device-status。endpoint 禁 userInfo/query/fragment，路径禁 query/fragment/..。不迁移任何 API key、iv、alias、token、cookie、权重；未知字段拒导入，export projection 以固定原因列剔除，不打印秘密值。
- captureNonSecretConfiguration(context) 只投影旧偏好；导入非秘密设置进 `neutral_config`，不改 model-settings/keystore/账户，requiresAuthorization=true。UI 显示待重新授权配置并由用户明确确认新密钥；不得自动套旧凭据或切账户。
- 附件新增 `attach(id,messageId,mime,bytes)`，实际 `attachment(id)` 验证 hash 返回。主附件模型若另有持久化表，应与该引用表对接，不重复拷贝源文件；所有展示/发送附件关系需遵循 message_id。

## 测试状态

已新增 domain/context 与 androidTest/context、backup 针对性测试。尚未执行（子代理工具无 Shell/构建）。Android 测试显式安装 schema 以独立测试；父集成 v4 后可移除该安装调用。模拟事务抛错/重开是回滚覆盖，不声称已完成设备强杀证据。需父执行编译、JVM 测试、仪器测试及实际 kill/restart 断电场景。
