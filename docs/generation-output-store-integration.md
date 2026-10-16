# P19/P20 generation output side-table：叶模块与待接入清单

## 本次边界（尚未接入运行路径）

`storage/SQLiteGenerationOutputStore.kt` 与隔离 SQLite instrumentation 测试提供输出注册；
恢复/原子准入实现涉及该 store、`media/MessageGenerationAssets.kt` 及专属测试/文档。
本次 review 修正仅修改 output store、对应 androidTest 及本文档，不修改资产模块或共享 gate 接线。
**没有修改** `ConversationDatabase`、Controller、Main、Session、Resources、
`SQLiteGenerationAttemptStore`、MessageBlock 或既有生成/attempt 算法。没有网络、资产 GC、
自动 retry POST、清空 attempt、UI 或后台任务。新增类不会自行打开数据库或安装 schema。
以下父模块工作全部是 **TODO**，不能把本叶模块称为已完成端到端 P19/P20 集成。

本次依据的现有契约：

- `storage/SQLiteGenerationAttemptStore.kt`：`GenerationAttemptBinding` 根据真实
  conversation/assistant/run/call 生成稳定 operation ID；toolName 仍必须精确校验；
  `generation_attempts` 主键 `(operation_id,item_id)` 是持久 tombstone。
- `model/generation/GenerationContracts.kt`：`GenerationAttempt` 带 identity/fingerprint/protocol；
  `GenerationAssetReceipt` 只是 assetId，不是聊天消息的图片 UUID。
- `media/MessageGenerationAssets.kt`：实际 pending 类型是 **`PendingGenerationAsset`**，
  `pending(receipt)` 会验证 record 和原始文件，`openOriginal` 返回已验真、归零的私有只读流。
  两者会持有 asset 文件锁/fence；返回 pending 不表示数据库注册成功。
- `storage/MessageHistoryCodec.kt`：非空 `history_segments` 为权威；不能用 legacy `blocks`
  中仍然存在的 tool 绕过已改变的 history。
- 当前 `ConversationDatabase` schema version 为 **10**。它当前既不安装 attempt 表，也不安装
  output 表；`onUpgrade` 仅接受 `oldVersion in 1..9 && newVersion == 10`。

## API 与持久化保证

```kotlin
val owner = GenerationAssetOwner(stableAccountScope, persistedBinding)
val assets = MessageGenerationAssets(privateRoot, realOwnerFence)
val outputs = SQLiteGenerationOutputStore({ parent.writableDatabase }, owner, assets)

// attempt 来自原始 plan，fingerprint/protocol 必须保持原值。
val registered = outputs.register(attempt, receipt)
// 也可传 assets.pending(...) 得到的 PendingGenerationAsset；它仍会被重新验真。
val metadata = outputs.find(attempt, outputIndex) // 授权且无 row 才返回 null
outputs.open(attempt, outputIndex)?.use { original -> /* 私有读取，禁止导出路径 */ }
```

`register` 的顺序：

1. 拒绝嵌套事务、只读/非磁盘 DB、低于 FULL 的 synchronous、off/memory journal。
2. **事务外**调用真实 `assets.pending`，读取并验证原始文件。传入的 pending 不被当作证据；
   重验后逐字段核对 owner、identity、index、receipt、kind、MIME、bytes、SHA-256、宽高、时长。
3. 文件锁及先前的 fence 准入释放后，通过窄接口 `assets.admitRegistration(owner) { SQL }`
   **重新取得同一个 owner fence** 并执行现有 `validateOwner`，随后开启 **唯一一个顶层事务**。
   gate 持有到 `endTransaction()` 提交成功或失败返回，固定顺序为 **gate → DB**。
   查询真实 conversation 与 assistant message，核对 run、role、
   唯一 tool call 的 ID/name；history 非空时完全不用 legacy tool 投影。查询同库
   `generation_attempts` 核对 identity 对应的全部 owner 字段、fingerprint、protocol。
4. 使用 `(operation_id,item_id,output_index)` 主键插入 `generation_outputs`；另有 receipt 唯一约束、
   实际 tool owner/item/index 唯一约束。保存 accountScope、conversation/assistant/run/call/name、
   fingerprint/protocol 及完整 immutable receipt/media 元数据。**不使用 INSERT OR REPLACE，
   不 update 已有 row**。同键已有 row 只有所有字段完全相等才幂等成功。
5. 回读完整 row，并再次核对 owner/attempt，捕获 IGNORE、修改 row/owner/attempt 的 trigger。
   只有 `endTransaction()` 成功提交后才返回。trigger/commit 失败一律不能返回“注册成功”。

数值完整性不依赖 SQLite 的宽松转换：新表对全部五个数值列同时校验
`typeof(...)='integer'` 与范围：`output_index` 为 0..31，`width/height` 为
1..2147483647，`byte_count` 为 1..Long.MAX_VALUE，`duration_ms` 为 0..Long.MAX_VALUE。
这是**落盘 storage class** 的约束；SQLite INTEGER affinity 能无损规范成 INTEGER 的输入
（如 `1.0`）不被当作坏数据，实际保留为 REAL 的 `1.5`/`0.5` 则必须拒绝。
读取全部 19 个字段（包括实际存储的 operation/item/index，而非拿查询参数代填）先检查
`Cursor.getType`：文本只接受 STRING，数值只接受 INTEGER；数值按 Long 验界，宽高和 index
验界后才转 Int。因此 `4294967297` 不能被截断成宽高 `1`，小数不能被截成看似正确的整数。
随后仍核对 identity、fingerprint、全部 owner 字段、receipt/hash、kind/MIME/protocol 及
图片 duration=0、视频 duration>0 的既有契约；实际媒体解码与资源限制仍由资产 API 负责。
这些读取防线同样用于 find/open、既存行幂等比较与 INSERT 后回读；坏行不会被自动修复覆盖。

事务中没有资产 IO、文件锁或网络；也不会更新 attempt outcome。注册在事务**之前**取得
asset fence，并持有到提交完成；绝不在 DB 事务内反向取得 gate。`validateOwner` 仍由父实现，
在 gate 内、事务外执行，之后还由 SQL 在事务内复核真实 owner/attempt。
同一 fence 的 revoke 先赢则最后注册拒绝（包括幂等注册），register 先赢则已提交回执仍有效；
不在 commit 之后追加 cancellation 检查、撤销回执或删除已发布文件。元数据回执不是未来读取许可。
状态 COMPLETED 的真实 owner 仅在父当前策略仍允许时可恢复已生成输出，不以“重新 reserve/重新
POST”代替恢复。父 fence 决定当前 owner/cancellation 是否可用；本模块不会强行放行 fence。
异常在本边界统一为无 cause 的 `IllegalStateException("generation_output_store_unavailable")`，
调用方不能把异常转换成重试 POST 的许可。

`find` 同样在顶层事务内核对真实 owner 与 attempt，并核对 row 所属；它是 **metadata 查询**，
不保证此刻文件存在/完好，也不持有 asset 锁。`open` 先执行该授权查询、结束事务，再通过
pending/openOriginal 核对完整元数据和原始文件。数据库授权的线性化点是 find 事务；资产
fence 是独立的 live admission。**没有跨 DB+文件系统原子性，也没有“已经发出的流可被 revoke
收回”的保证**。父模块必须按既有约定先 revoke，再变更/删除执行 owner，并在操作结束关闭流。

accountScope 是稳定的私有本地账户/DB 标识，不是 URL、凭据或每次生成的新 token。
与 attempt store 一样，这个实现假设 **一个应用 DB 就是一个账户范围**；output 表额外存的
accountScope 不能弥补 attempt 表没有 account 列的事实。共享多账户 DB 需要父模块先升级
真实 owner/attempt schema 和授权；仅换 constructor 参数不能获得多账户隔离。

## 无网络精确恢复与父共享 fence 前提

```kotlin
// originalAttempt 必须是已持久化的原始 identity + fingerprint + protocol。
when (val result = outputs.recoverPublished(originalAttempt, originalOutputIndex)) {
    is GenerationOutputRecovery.Registered -> useRegisteredReference(result.reference)
    GenerationOutputRecovery.Missing -> keepOriginalAttemptWithoutResending()
}
```

`recoverPublished` 只调用现有 `assets.pending(originalAttempt.identity, originalOutputIndex)`，
按原 owner/operation/item/index 的固定 slot 重读 record 并核验完整原始字节、摘要、真实媒体元数据。
找到了就走两种 `register` 共用的 `registerVerified`，包括相同的最终 gate 准入、SQL 授权、
不可变行对比和提交回读；不依赖调用方还保留 receipt。不扫描目录寻找替代输出、不访问网络、
不 POST、不下载、不改 operation/item/fingerprint/protocol、不创建 attempt、不删除已发布文件。

`GenerationOutputRecovery.Missing` **仅表示该固定 slot 未发布且同 index 没有已注册 row**，
不是故障的统称，更不是再次计费许可。即使没有 slot，也要在同 fence、同一个顶层 SQL 事务内
核对原 attempt 的数据库授权，并通过既有 `(operation_id,item_id,output_index)` 精确查询确认
没有 row。该查询复用完整字段读取、storage class/数值范围和引用校验；若已有 row，无论它是
合法引用还是坏数据，整个 published slot 缺失都是损坏/冲突，必须抛出既有脱敏异常。
已有目录但 record/original 缺损、原始字节冲突、fingerprint/protocol 改变或 owner 拒绝也全部
抛出异常；不会静默归类 Missing，不会用新字节覆盖旧行。Missing 是一次固定 slot 的缺失观察，
不承诺阻止并发发布；重试只允许同原始参数再次进行本地恢复。已有 DB 引用也不能代替缺失
文件生成注册成功结果。

**必要前提，属于独立的父 owner 生命周期任务：** 同一 live owner 的所有 leaf、输出 store、
取消/删除/切账户路径必须复用**同一 GenerationAssetFence 实例**。仅重建相同字段的新 fence
没有共同 gate，不能提供撤权与注册的全局排序。本补丁没有修改 Resources/Session/Controller，
不声称已完成此父接线。父必须先在事务外 revoke、再变更/删除 owner；不得持 DB 事务等待 gate。
进程重启后的新 fence 只可在重新验证持久 owner **以及当前取消/账户策略**后创建；同进程中
不得为已撤销 owner 换 fence 复活权限。文件锁不是跨进程 owner lease，多进程策略仍由父负责。

## 父数据库 TODO：新建与 10 → 新版本迁移

父维护者决定版本号；若此变更是下一版，则使用 **11**。本次没有修改或提升 version。
迁移不能放在 register/find/open、Controller 首次使用、onOpen 或文件发布之后懒执行。

### 新 DB 的 onCreate

在已有 `conversations`、`messages`（包括 `history_segments`）及其他现有 schema 安装逻辑之后，
在 SQLiteOpenHelper 的 schema 事务中依序加入：

```kotlin
SQLiteGenerationAttemptStore.installSchema(db)
SQLiteGenerationOutputStore.installSchema(db)
```

两者都是 additive 的 `CREATE TABLE IF NOT EXISTS` 安装助手，不管理 user_version，不会验证/修复
一个同名但结构不兼容的既有表。尤其是已有旧版 output 表时，重调 installSchema **不会补上**
本次数值 storage class/范围 CHECK；若父模块曾安装旧表，需在其版本迁移事务中显式重建表、
严格验证保留的数据，不能通过 CAST/截断“修好”坏数据。本次不实现父迁移或自动删行；读路径
对选中的坏行仍 fail closed。父模块需要自己的 schema/迁移回归测试。

### 现有 v10 DB → v11（或父选定的下一版）

1. 将 helper 的 version 改为父选定新版本。
2. 重构当前 `onUpgrade` 的版本分支与 guard，使 `oldVersion == 10` 合法。
3. **v10 分支不得再执行** `ALTER TABLE conversations ADD COLUMN assistant_id`，也不得再添加
   已存在的 history 列。这两者已经在 v10。只安装上述 attempt/output schema，保留既有数据。
4. 对 `oldVersion < 10`，保留原先到 v10 的迁移语义，再安装 attempt/output schema；不能把原先
   无条件 ALTER 原样放到新的所有升级路径。保留其他 context/runtime/child/usage/folder 迁移。
5. 在同一个 SQLiteOpenHelper upgrade 事务提交 schema 与版本。保留 foreign_keys=ON、
   synchronous=FULL 与持久 journal。安装顺序与 onCreate 一致。
6. 验证 fresh create、精确 v10 → 新版、旧版逐级升级、第二次重开、故意中断迁移保留原数据。
   本次隔离测试只手动建最小真实 owner 表并安装叶表，**不是这些父迁移测试**。

不从旧 Tool.result 推测注册凭证、不扫描任意文件制造 attempt、不为旧消息生成新的 call/run。
如果资产已发布但 output row 尚未提交，可以用相同 account/binding/原始 attempt/index 调用
`recoverPublished`（无需保留 receipt），或用原 receipt 重验并注册。
失败可能留下未引用的 published 文件；保留它用于恢复，不删除、不重新计费生成。

## 父删除/账户生命周期 TODO

本 schema 故意没有级联删除 attempt 的 FK；本类也没有 delete API。
当前 `ConversationDatabase.delete(id)` 仅删 neutral/context/messages/conversations，**未删本新表**。
在正式接入时，父模块应在现有删除事务内、messages 删除前执行：

```sql
DELETE FROM generation_outputs WHERE conversation_id = ?;
```

对单 message 删除（如未来添加），在同一 owner 删除事务内执行：

```sql
DELETE FROM generation_outputs WHERE conversation_id = ? AND assistant_id = ?;
```

- 删除前先在事务外 revoke 对应 live asset fences/停止新读写；不得在 SQL 事务里获取 fence。
- **不得删除 `generation_attempts` tombstones** 来实现聊天删除、取消、重试或 output 清理。
  否则旧 identity 可能再次发送付费 POST。保留 fingerprint/protocol/identity 的现有保证。
- 若父尚未加 output 删除 SQL，遗留 row 也不能绕过授权：缺 conversation/message/call 时
  register/find/open 全部拒绝。它们只是待清理的私有元数据，不是可读许可。
- 文件清理/备份保留/账户物理销毁是独立父策略，本叶模块不做资产 GC。需要先处理 in-flight
  descriptor、引用保留和 tombstone 范围，不能依据一次 DB/文件异常就删文件。
- 备份导出/导入当前也没有接本表；不能宣称生成输出已包含在现有备份中。若要支持，必须同时
  设计私有原始文件、完整引用及不可重复计费 tombstone 的账户绑定与一致性策略。

## 父 reader / tool execution TODO

- 从**持久化的真实 assistant tool invocation**重建 binding，不使用任意 caller token；
  重启恢复还要恢复原始 attempt fingerprint/protocol。asset fence 的 validateOwner 必须查询
  真实账户、conversation、assistant、run、call/name，并与父删除/cancel 顺序协调；示例中的
  `realOwnerFence` 不是 `GenerationAssetFence(owner) { true }`。
- 保持既有生成客户端和 attempt 算法。在既有下载/发布获得 receipt 后，显式调用 register，
  注册成功才能把 side-table 引用当作可展示的生成输出；失败保留 receipt 走恢复，而非再次 POST。
- reader 按已授权的 attempt + outputIndex 用 find/open 读取；原始流仅在私有渲染/播放范围使用。
  不允许直接按 receipt/path 枚举其他 owner，不把 receipt 视作 bearer capability。
- `genasset-v1-...` **不是 UUID**。现有 MessageBlock.Image/MessageImageReferenceCodec 只接受它们的
  UUID asset 协议；不得把此 ID 塞入 MessageBlock，也没有悄悄改既有图片/视频资产协议。
  父需单独添加生成 output reader/投影适配层，不能用旧 reader 假装已经支持。
- 本 API 自己拥有顶层事务，不能包在父 `save(message)` 事务内。因此 **Tool.result 更新与
  generation_outputs 注册不是同一个事务**；本次也不写 Tool.result。父将来要设计结果投影、
  可恢复状态与 reconciliation，不能宣称一个 register 调用已经原子提交消息/工具结果。

## 验证状态

新增 `app/src/androidTest/kotlin/app/daiyu/android/storage/SQLiteGenerationOutputStoreTest.kt`：
最小私有磁盘 SQLite owner schema、原 attempt schema、静态 PNG 与真实资产 API；覆盖独立
helper/重开/同键幂等、out-of-order indices、owner/account/call/name/history 冲突、fingerprint/
protocol 冲突、伪造 pending 元数据、每个已存字段冲突、不可信文件、嵌套事务/弱 durability、
IGNORE/ABORT/变更 trigger、deferred-FK commit 失败以及失败后保留文件/tombstone。
新增数值完整性回归覆盖：五个数值列的 NULL/TEXT/BLOB/REAL/越界 schema 拒绝；绕过 CHECK
预置超 Int 的 `2147483648`/`4294967297`、REAL 宽高 `1.5`、长度小数、duration `0.5`
等坏行，并验证 find/open/两种 register 均拒绝且不覆盖坏行、不删除文件或 attempt；另用旧版
宽松 REAL index 表验证 `0.0` 不能靠查询参数伪装为合法 INTEGER index。AFTER INSERT trigger
分别在 CHECK 开启与绕过时注入超 Int/REAL 元数据，检查拒绝及整笔回滚，并保留正常恢复路径。
新增恢复/排序回归源：真实 SQLite + Android PNG decoder 验证发布后 SQL 失败、helper 重开、
只按原 slot 恢复（不注册兄弟 index）、幂等恢复、无文件 Missing、破损/缺原始文件、已注册同 slot
不同有效字节冲突；保持 identity/fingerprint/protocol 与 attempt tombstone 不变。通过真实解码
完成时注入撤权，覆盖两种 register 和 recovery 的最终准入；线程/latch 覆盖 revoke 先赢以及
已撤销 fence 不因重建 assets 获得权限。真实 SQLite INSERT trigger 调用阻塞函数，在事务内
暂停注册、并发 revoke，验证 gate 保持至 SQL 完成且成功回执之后仍能查到。
该单条并发测试使用公开 API 30+ `setCustomScalarFunction(String, UnaryOperator<String>)`，
trigger 传一个字符串参数，回调在 latch 放行后返回该参数。沿用项目 JUnit3 runner，以方法入口的
`Build.VERSION.SDK_INT` guard 在 API 26–29 跳过这一条；未提高测试类或构建的最低 SDK，其他
测试仍可在 API 26 执行。没有隐藏 API 或反射，也没有用 mock 替换真实 SQLite 交错。
本次补充已注册后整个 slot 丢失、整个 slot 丢失并且 row 数值/owner 损坏的回归；通过移走测试
slot 模拟缺失，验证不是 Missing、坏 row 不变、兄弟 slot 可恢复及未注册 index 仍返回 Missing，
同时保留原始文件、record 和 attempt tombstone。
测试 fence callback 用于断言不在 SQL 事务内调用，不是生产 owner validator。

本任务只做源代码静态检查；**未构建、未执行 JVM/instrumentation 测试、未运行真实设备、未推送**。
未运行范围包括本测试类及新增回归、全量测试、父数据库迁移/端到端集成；未构建 APK。
测试代码是待执行验证，不构成已经通过的证据。


## 父库 v11 接线更新

ConversationDatabase 的新建及 v1..v10 升级已安装 attempt/output 表。旧 output 表复制前核对原始 storage class 与五列数值范围，再事务内重建严格表；不使用目标 affinity 清洗旧坏数据。会话删除在同事务内清 output 引用且检查删除结果，attempt 墓碑保留。上文以 v10 为前提的待接线说明仅为组件原始边界；Controller、reader 与备份端到端接线仍待完成，测试尚未运行。
