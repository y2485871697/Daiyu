# F11/F19 接缝（接口冻结）

来源：当前重写项目 `ConversationDatabase.kt`（生产 SQLite schema4；中立文本备份 format version1（沿用历史 schema3 契约））、`ModelSettings.kt`、`ResponsesModel.kt`；验收 `docs/refactor-input/acceptance.json` AC-033–035/050–052，features F11/F19。Context 最终来源 `f7f51c10`、配置人工恢复来源 `faa1a1d2`，随本次集成提交；进度与证据统一见 `docs/重构清单.md` 的 R03/R11/R12 三项成品集成记录。没有读取工作树外旧策略或复用 Eta 源码。保留候选压缩策略由父代理提供；这里的保守默认边界不能作为旧策略被取代的审查证据。

## 主数据库唯一接线（v4 已实现；Context 生产分派仍未接通）

1. `ConversationDatabase` helper version 已为4；`onCreate` 现有建表后已调用
   `app.daiyu.android.context.ContextStorage.installSchema(db)`，安装六张扩展表。不是待父修改的建议。
2. `onUpgrade` 已支持 oldVersion in 1..3 / newVersion==4；oldVersion==1 创建 composer；oldVersion<=2 增 folder/pinned；最后 `ContextStorage.installSchema(db)`。依旧拒绝未知版本、不删旧库。服务构造器不执行任何 DDL。
3. 删除会话已在现有 transaction 中按顺序：
   ```kotlin
   db.execSQL("DELETE FROM neutral_assets WHERE message_id IN (SELECT id FROM messages WHERE conversation_id=?)", arrayOf(id.value))
   db.delete("context_archives", "conversation_id=?", arrayOf(id.value))
   db.delete("context_branches", "conversation_id=?", arrayOf(id.value))
   db.delete("context_usage", "branch_id=?", arrayOf(id.value))
   // 然后现有 messages/conversations 删除
   ```
   不同步删 blob：跨分支共享 hash；孤儿以后按数据库引用集清理。
4. 完整备份 Controller 入口已共享主数据库。Context 宿主仍须共享该 helper 和 owner executor；以下是未接入生产 dispatcher 的组装契约，**不是当前 ChatController 已使用 Context 的代码**：
   ```kotlin
   val contextService = ContextService(context, conversations)
   val backupService = NeutralBackupService(context, conversations)
   val registry = ContextRegistry(contextService)
   // 授权必须由宿主冻结，不能从模型 JSON、archive_id 或 branch/run 字符串推导。
   val modelContextTools = registry.forModel(ContextRegistryScope(conversationId))
   ```
   主构造函数支持同连接 `DatabaseTransactions { conversations.writableDatabase }` 注入；禁止另开 sidecar 数据库实现影子前缀。`ContextRegistry.executeScoped` 通过 `ContextService.readQuery` 的外层事务完成授权及读取，内部 owner/page/usage 查询沿用同一 store 的连接。父已核同数据库事务结构，本树静态核对亦相符；这不是 Android runtime 一致快照验证通过。

## 发送 / UI（待宿主接通的契约，不是完整业务已实现声明）

当前生产源码未见 ChatController/模型 dispatcher 使用 ContextRegistry 或 ContextService。以下 prepareSend、压缩、分支、修订、用量接收与展示仍须真实接线和验收；模型 adapter 存在不等于生产入口已用它。

- **先构造上下文，再插入 RUNNING 占位**：调用 `contextService.prepareSend(id, policy, summarizer)` 得 `messages`。然后现有 send 写 user/assistant，ChatRunner.run(initial, prepared.messages + user)。自动摘要不能在 RUNNING 后触发，因为正在生成的历史不可修订。
- 新会话无 id 时 history 为空；不要调用 prepareSend 对不存在 id 自动创建会话。
- 用户分支按钮调用 `branch(id, completedMessageId)`，返回实际 ConversationId，刷新会话列表并选新 id；原会话不变，消息/run IDs 重映射，附件引用同事务复制。
- 删除后续或改用户输入按钮调用 `revise(id, throughMessageId, replacementUserText?)`。必须在 owner executor 运行并重新加载 UI；实体 messages 已截断，所以现有分页和下轮模型不会读到废弃后续。用量保留作原历史请求账本，不因此伪造退费。
- 手动压缩调用 `compress`；`ContextSummarizer` 接收完整事实/工具状态/协议原文及固定不可信数据指令，在服务 write transaction 之外运行。不能使用宿主助手历史。摘要失败、不缩小、并发历史改动均不发布；原表从不删原文。
- 保留候选的 CompressionBoundary/SendPolicy 接到 `ContextService(..., compressionBoundary = { messages, keepTurns -> ... })`；保留事实/证据策略应落实在 `ContextSummarizer`，这里不替代它。默认 fallback 仅支持连续 completed user/assistant 同 run 完整工具轮，保守不跨失败/运行单元。
- `archivePage(id, offset, limit)` 或 Registry `context_archive_page` 是成功归档原文真实查询，最大每页200。档案在 app 私有 `filesDir/neutral-context-blobs`；缺档/散列不符拒分支/修订，不凭摘要补原文。
- 模型 usage 接收器成功收到 provider usage 时调用 `recordUsage(UsageRecord(responseId, runId, conversationId, ...))`。每个工具续请求用自己的 request/response ID；完整重放幂等，跨分支/数值冲突拒绝。未返回 usage 可登记 null；估算单独在 PreparedContext.estimatedTokens。`usage(branchId, runId)` 不混任务；cached 是 input 子集、reasoning 是 output 子集、不二次相加。
- provider window 未知时 policy.contextTokens=null，保留未知，不触发自动容量压缩。
- Registry 格式 `schemas: JSONArray[{name,description,parameters,sideEffect}]`，只注册原文查询与用量。生产 adapter 唯一接缝为 `registry.forModel(ContextRegistryScope(...))` 返回的 `ModelAdapter.schemas/execute(name,JSONObject)`；scope 是宿主传入、不可变且防御复制的授权快照，不出现在模型 schema/arguments 中。无 scope 的旧 `registry.execute` 默认返回 `trusted_scope_required`；仅显式非模型本地查询可用 `executeLocal`，不得将它注册给模型。branch/revise/import 是显式 UI 操作，不开放模型写入。
- `ContextRegistryScope(conversationId, authorizedBranches = ..., authorizedRuns = ...)`：基础授权仅当前会话，branch_id 沿用已物化会话 ID；父子来源关系不自动授权。额外会话（包括其他分支）的 archive/usage 必须由宿主先取得明确授权后加入 authorizedBranches，不是由模型给出合法字符串就获权。archive 查持久化所属会话，校验完整档案所有消息 owner 与该会话一致后才分页（不能仅校验当前页，超出末尾的空页也不能绕过）；branch 必须仍存在，run 同时核 messages/usage 两种来源且所属唯一。授权核验和读取位于同一 SQLite 事务快照，无表写入。
- authorizedRuns=null 表示已授权会话内全部 run；非 null（含空集）仅允许所列 `ContextRunOwner(branchId, runId)` 对的 usage 查询，且仍核真实所属。该窄 scope 拒绝不带 run 的 branch 总量及整会话 archive，避免宽查询越过 run 授权。摘要/标题等只出现在 usage 账本的 run 也能核验；未知或跨会话冲突的 run 拒绝。不改变 recordUsage、统计或压缩算法。此接缝是本次集成的 R11 范围授权成品，不表示生产发送/模型分派已接线或 R11 完成。

## 完整中立包（不改变严格 v1 文本格式）

- `exportPackage` / `preflight` / `importPackage(InputStream|String)` 独立 format `daiyu-neutral-complete`, version1，内部原样 schema3 `daiyu-neutral-text`。原 exportBackup/importBackup 不改，不接受扩展字段。
- 导入旧 source 仅只读流；不删除/移动/覆写旧源。预检核对总数、关系、原文 revision、附件 byte/hash、清单整体摘要和身份。最终原 text payload 仍由原 parser 严格验证；其异常回滚整包。preflight 不等于已有 strict text parser 已通过（父可暴露其验证入口改善早期错误提示）。
- 私有 blob 先 sync/rename 不可变发布，再插入 SQLite 引用；text/分支/归档/usage/附件引用/配置/import marker 一个 helper 事务。强杀到提交前只有无引用 hash 文件；重启只有完整旧/新 DB。不能把附件导入到外部共享路径。
- 配置只允许 endpoint/model/responses-path/battery/device-status。endpoint 禁 userInfo/query/fragment，路径禁 query/fragment/..。不迁移任何 API key、iv、alias、token、cookie、权重；未知字段拒导入，export projection 以固定原因列剔除，不打印秘密值。
- `captureNonSecretConfiguration(context)` 仅投影五白名单；生产 Controller 完整导出实际使用 `ModelSettingsStore.nonSecretConfiguration()` 的当前非秘密快照，通过 `exportPackage(configuration)` 非持久化投影，不改已导入 metadata。完整导入非秘密设置只进 `neutral_config`，不自动改 model-settings/keystore/账户，requiresAuthorization=true。
- 配置人工恢复已接入 `MainActivity`“备份与导入 → 人工恢复已导入的模型配置”：Controller `previewImportedConfiguration` 只读 metadata 与五个当前非秘密字段，Store `prepareNonSecretRestore` 生成不可变且绑定 store 的预览。用户明确确认后经 Controller 单次消费，复查 ready/active/dataBusy/恢复失败、metadata 与当前设置快照，才调用 `applyNonSecretRestore`；取消或页面重建丢弃预览，不重放、不发模型或工具请求。空 metadata 为 no-op，缺字段保留，显式空串清空，非空恢复强制 battery/device-status 关闭；不把备份视为系统或工具授权。
- endpoint/responses-path/model 改变时持久设置 `credential-rebind-required`：旧密文不读、不展示、不修改；`load` 在读取凭据前拒绝，`loadForEditing` 密钥留空，用户必须重新输入密钥并经正常 `save` 成功后才能清除门闩。原本存在的重绑要求不得被恢复解除。commit 失败补偿旧字段/存在性/门闩，补偿失败保持 store fail-closed 并要求显式重新保存。此生产入口有回归源码，但本增量尚未在 Android 执行，不声明配置业务验收完成或断电原子性。
- 附件新增 `attach(id,messageId,mime,bytes)`，实际 `attachment(id)` 验证 hash 返回。主附件模型若另有持久化表，应与该引用表对接，不重复拷贝源文件；所有展示/发送附件关系需遵循 message_id。

## 测试状态

- 生产 v4 初始化已接通；现有 Context/Backup 测试及本次 `ContextRegistryScopeTest` 使用生产 helper，不再要求手动 installSchema。v4/既有备份与文件入口的历史双 API 证据见清单，不转授本次增量。
- 本次新增 Context 查询范围8个、配置恢复 Store15个 + Controller6个 + Activity6个仪器方法均未执行。三项成品另有 Responses 取消13方法，也未执行；48是新增源码方法数，不是 runner 实际执行数量。模拟事务抛错/重开、synthetic transport/commit 注入不是设备强杀或真实网络证据。
- 父提供 Context/Responses 来源 host 编译 exit0、独立静态 review 无确定代码阻断；Config 来源 CHECK_SHA=`faa1a1d2`，全部 domain/app/androidTest host 编译 exit0，独立 review `794eda30` completed、六文件无源码 blocking。组合 dirty-tree host 47个 Kotlin 输入编译 exit0，执行时 HEAD 仍为基线加 seed 差异；输入 sha256 manifest 在父报告 `/workspace/daiyu-provider-handoff/next-step/integration-host/check.json`，不将该结果绑成基线或未来集成 SHA 的 host 验证。
- 基线 Python CHECK_SHA=`f0b5b8e` 第二 attempt 完成65项/69.430秒/exit0，父报告 `/workspace/daiyu-provider-handoff/next-step/baseline-python-check.json`；保留首 attempt 30秒命令 timeout，不将其当源码失败或 pass。这是基线工具测试，不证明本次 Kotlin 业务或仪器通过。
- 上述仓库外证据由父提供，本任务仅能核授权树源码/文档，未重跑构建或测试。下一步父以同次代码+清单提交经集成门禁合流，唯一既有 workflow 对实际集成 SHA 跑完整构建与 API26/API37.2；尚无本集成新 run。生产 Context dispatcher/发送/usage 接线及必要 kill/restart、真实业务验证仍待完成。保持 minSdk26，不改 workflow、runner/history 候选或其它范围。


### 本次云端回归补证

精确源码708ac280的run38621624723/attempt1已success，API26/API37.2各138项通过，包含Context范围8、配置Store15与Controller/Activity新增6+6、Responses取消13；既有Context/Responses回归同时通过。提交时“尚未执行”段为历史状态，现已由此执行证据核销。JVM128、Python65、Lint/build成功。生产Context dispatcher/发送/usage接线仍未完成；真实网络中断、强杀重启等不因synthetic或顺序回归计为完成。后续仅文档补记不生成新APK。本轮仅下载报告、不下载或安装APK。
