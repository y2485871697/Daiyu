# P08 独立标题模型：生产闭环

## 状态与范围

本次沿用已有 `title/` 算法、配置、Factory overload、adapter、coordinator、面板及测试源码（委派上下文标为 `60f072f` 的13文件），没有重写标题算法。当前源码已接入 MainActivity、ChatController、唯一 ConversationDatabase helper 以及功能/设置导航 catalog；**这不是测试通过或已发布声明**。未运行编译、JVM/instrumentation、APK 构建或真实供应商请求；未读取真实凭据、未进行付费调用。重构清单未改。

以下是本次只读源码核对和实现的接线说明。原始保全来源沿用既有文档记录，未在本次重新计算 hash 或读取原活树：

| 冻结来源（授权 full-detail-f001fb82/fork） | 原记录 git_blob | 原记录 SHA-256 |
| --- | --- | --- |
| `model/app/src/main/kotlin/io/github/mangi/eta/agent/model/ConversationTitleModel.kt` | `72eee641f436054cb57ecef3c25f79a925f73b4c` | `0b4210b59e03ebf1e9b396f1fac2985ca5ac9ddf2dafbe3f4eb339cc68fb5c2e` |
| `model/app/src/test/kotlin/io/github/mangi/eta/agent/model/ConversationTitleModelTest.kt` | `a0f18d83a7fa3c8ecdfad8cbc28638cff1a71ee5` | `06c8989d36e70b4382729b2c23e5754c5def94235455c9890f13732ed0d90d37` |

原记录 frozen_commit 为 `f001fb82eb9941e5673970c656f5c4d5370fbf7f`；参考包不是生产源码或本次交付文件。

## 保全算法与严格绑定

`ConversationTitleModel` 的中文 prompt、`question.take(4000)`、normalize（首行、去引号标记、`take(24)`、空标题拒绝）、`mayApply` 四条件布尔表达式保留。原 system-role JSON 走既有供应商原生 instructions；固定 timeout=30秒/outputLimit=512，不把 delta/reasoning 或失败终态当标题，不开放工具，不自动重试/replay/resume。旧“custom=false 使用主聊天模型”分支继续明确禁用，不用主模型兜底。

`TitleModelSettingsStore` 独立 metadata 为 `conversation-title-settings/title-model-v1`，provider namespace 为 `title-model-provider`，加密 key alias prefix 为 `title-model-api-key`。默认关闭；缺失/损坏 binding、不可用密钥、身份或协议不一致、保存未确认均 fail closed。保存只做本地校验和加密持久化，不建立供应商请求。拒绝任意 custom request options 和工具开关。更改端点/协议/模型使用新的 opaque binding，保存和关闭均更新配置 revision。

## 实际 Main 入口与设置 visit

- `FeatureCatalog` P08 和 `SettingsCatalog` 的 `title_model` 均指向 `TITLE_MODEL_SETTINGS`；MainActivity 创建新的 `TitleModelSettingsEditor`，渲染 `TitleModelSettingsScreen`/既有 stateless `TitleModelSettingsPanel`。
- 每 visit 一个不可复用 owner；prepare 返回一次性 queued Work，worker CAS 赢得 admission 后才创建/访问 store。UI 不读密钥、不写设置、不执行网络。
- 离页、recreate/destroy 撤销旧 owner。排队但尚未 admitted 的工作不能触碰 store；已经 admitted 的本地写入可完成，但旧结果不能发布到旧页或新 visit。UI 不 join 等待。
- 草稿只用 `remember(owner)`，**不用 rememberSaveable/Bundle**。新 binding 随新草稿创建，非每次 recomposition 创建；保存成功/关闭成功及 dispose 清空密钥草稿。错误不保留供应商异常正文。

## sendPrepared 真实生产触发

准确入口是 `ChatController.sendPrepared`，不是不存在的 `acceptText`。

1. `database.send` 及其外层 `DatabaseTransactions.run` 真正返回 durable receipt 后，才可能 `titleCoordinator.prepare(saved.id, run)`。只在本次 `conversation == null` 的真正新建会话首轮执行；旧会话、旧空会话、后续轮、恢复、备份导入都不追补标题。不遍历“新会话”寻找待命名对象。
2. 该 send 的局部 `AtomicReference<TitleJob?>` 绑定准确首轮 user/run。ChatRunner 构造器 publisher 是持久化之后的回调；只在相同 conversation/run 的 assistant **COMPLETED** 终态时 `getAndSet(null)` 一次性消费 job，将准确 user+assistant 快照排入独立 `titleExecutor`。FAILED/STOPPED/INTERRUPTED 终态只撤销，不生成。adapter 仍核数据库内真实 COMPLETED assistant。
3. 标题不占聊天 executor，不等待 UI，不改变已 Accepted 的聊天回执。准备或生成/usage 持久化失败只发布独立 `titleNotice`，不会将已保存聊天改判拒绝，也不自动重试。标题成功使用独立 `titleRevision` 刷新可见标题，不替换编辑草稿/编辑 owner。
4. 正常 `cleanupRun` 仍会 fence 聊天运行，但**不撤销标题 job**；标题可以在聊天资源已清理后继续完成。Controller close 单独撤销标题 coordinator。标题 worker 的 `titleReaders` 纳入 `closeStorageWhenIdle`；真实 worker 完成且最终 usage 写入退出后才允许关闭数据库。cancel 返回不是 worker-drain 收据，UI close 不 join。

## 配置、请求 admission、撤权与 TOCTOU

设置 revision、job admission/session publication、最终 title CAS 使用同一个 settings/admission monitor。

- 关闭/重新保存配置在该 monitor 内同步将所有原 job 标为 revoked，收集其真实 session；**锁外同步调用 session.cancel**，在配置 API 返回前完成取消调用。不能仅靠最终 CAS 拒绝来冒充已撤销供应商请求。
- createModel/open/next 及 cancel 均不在该 monitor 内执行。open 期间 close/配置改变若胜出，原 job 永久撤权；迟到 open 的 session 不发布、不调用 next，直接锁外取消。
- session 发布与请求 admission 重新验证同一冻结 revision+完整 provider；发布后取消命中真实 session。现有 Responses/ Messages session 的取消位在 next 开始处校验，覆盖 admission 后、next 前的窗口。
- 最终 `whileCurrent` 到 DB CAS 仍保留。即使 transport 忽略 cancel 并回调，也不能提交标题；不会重绑定到新 provider 或新 session。
- 手动 rename/delete 不需要经过 coordinator。DB title/首消息 revision 是最终权威；会话 A 的结果不会写到 B。

## 唯一 helper v15 → v16

`ConversationDatabase` 是唯一 SQLiteOpenHelper，版本16。fresh 的 onCreate 与 upgrade 均在 schema-owner 事务安装 `SqliteTitleCommitAdapter.installSchema(db)`；upgrade 的安装位于 `oldVersion >= 13` 的早退之前，因此13/14/15都覆盖。未改原视频迁移逻辑，不重建原视频 witness，不回填或改变已有 message.created_at。

`conversation_title_revisions` 是每个存活会话的一份 revision witness，不存标题正文、请求或用量，不是另一套账本。安装时仅为已有会话补 revision；不生成标题。insert 新建 revision，delete 移除；`AFTER UPDATE OF title` 每次更新 revision 并设 title_touched=1，**同值手动改名也生效**。已有手动 rename 路径未绕行、未修改 timestamp 语义。

`TitleRevision` 冻结 conversation、原 title、title revision、首条 user 的 ID/run/role/blocks/history_segments 摘要。question 来自提交后的首条 user，使用 SQLiteText 分块读，不取当前 UI draft。提交先核 preserved mayApply、准确 parentRun 与数据库内 COMPLETED assistant，再做带 revision EXISTS 的 CAS、回读证明更新，随后才调用同事务 usage port。capture/isCurrent/commit 不得放入未提交的父事务；返回成功以 endTransaction 真结束为准。

## 同连接、同步、既有 usage 账本

Controller 必填 `ParentTitleUsagePort` 实际接到同一个 `ContextService.recordUsage(UsageRecord(...))`，断言同一 SQLiteDatabase 实例和当前事务；不异步 enqueue、不换 helper、不另造 title usage 表。

- 成功 title CAS 与 usage 在同一外层事务原子提交。recordUsage 的同连接嵌套事务不是独立 durable commit。usage 端口失败抛 `TitleUsagePersistenceException` 并回滚标题/revision，coordinator 不吞掉该错误，Controller 只将其作为独立标题保存未确认通知。
- 实际已经开始的失败、超时、撤权或 stale 请求也在 finally 通过 adapter 的 usage-only 事务进入**同一 context_usage**；不是因为标题没写入就隐去真实消耗。未开始请求不登记。
- 保留 requestId、准确 conversationId、parentRunId；最后一个 provider usage snapshot 替换先前 snapshot。撤权边界先捕获 Usage 再检查 gate；无 provider 报告仍为 null/unknown，绝不伪造零或价格。usage-only 持久化失败同样向上报告，不静默忽略，不自动重试。
- 端口方法沿用兼容名 `recordAfterTitleSaved`，现也承接上述实际失败请求的 usage-only 事务；名称不代表失败请求不计费。

## 测试源码与剩余验证（全部未运行）

沿用现有 normalize/late guard、绑定/设置/Factory 测试。补改的源码包括：

- `SqliteTitleCommitAdapterTest`：capture 前创建全新 untouched witness，先断言 isCurrent=true，再执行相同值/不同值 rename；移除“前置 UPDATE title 已撤权”造成的假覆盖，delete/recreate 也用有效前置状态。
- `TitleModelCoordinatorTest`：held next 下 close/disable/rebind 必须同步 cancel 真 session；open-publication TOCTOU 中配置胜出则 next=0；锁外 transport 断言；实际失败/stale usage、存储异常不吞。
- `TitleProductionDatabaseTest`：真实 fresh v16，无测试手动装 schema；13/14/15 升级、时间戳与视频 witness 保持、同值 rename、现有 usage 原子回滚/重开/删除，以及 neutral backup title+usage 往返和幂等导入。
- `ControllerTitleProductionTest`：真实 sendPrepared 新建首轮只触发一次、准确 COMPLETED 后触发、正常 cleanup 不撤标题、持有标题请求时后续聊天可完成、同值 rename、旧空会话/失败首轮不触发、UI close 不 join 且 DB 等待 worker 真退出、配置关闭不改 Accepted 聊天。
- `TitleModelSettingsEditorTest`：排队撤权不访问 store、admitted 操作完成但旧结果不发布、操作 identity、密钥字符串脱敏。
- `TitleModelSettingsActivityTest` 与 catalog 断言：真实设置入口、本地-only、离页/重建撤销 visit，无密钥 Bundle 恢复。

主代理需统一执行 Kotlin/Compose 编译、相关 JVM 与 instrumentation 测试，以及已有 schema/时间戳/原视频/备份回归。仅文件工具环境不能构建、运行测试或创建 Git 提交；由主代理审核 diff 后创建非空提交，保持 detached worktree、不建分支、不 push。不要编译 APK 或为了验证调用付费模型。源码断言、检查清单和本文均不构成测试通过证据。
