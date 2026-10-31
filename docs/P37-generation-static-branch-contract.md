# P37：生成原件静态分支——契约核查与实施方案

## 本次结论与交付边界

**现有安全的原件读取能力确实存在；现有“复制到新 owner 的静态导入”能力不存在。** 这不是“没有生成原件”，也不是需要打开任意私有路径权限。不能把 backup 的读取能力误认为 live restore 或新 owner 导入授权。

因此按任务的“缺安全契约先交精确方案”分支交付：本次不开放含生成输出前缀的成功分支，不新建 generation attempt，不放宽 fence，不复制 receipt 冒充新注册输出。实现静态分支仍需下述目的端契约及配套验证，不能标为完成。

核查另外发现一个与本任务直接相关的静默丢失入口：`ContextService.branchInternal` 原先只检查 `generation_outputs`，已经通过完整 backup 恢复的只读生成原件不在该表，分支会复制文本却遗漏只读资产。本次仅补此入口的失败关闭，并补真实 SQLite/PNG/backup 回归源码。未改唯一清单、更新说明、数据库版本、时间戳、Main UI、生成网络或历史自有源码。

## 已核实证据

以下均为本工作树源码，不依据旧文档推测接口：

| 来源 | 已核实事实 |
| --- | --- |
| `docs/重构清单.md`，P37 与 R12 条目 | P37 的剩余项是生成前缀静态分支；R12 已接完整原件读取及只读历史展示，不恢复执行许可。只读本清单，未改其状态。 |
| `storage/MessageActionStore.kt`，`branch` / `authorize` | target 包含全量历史 revision、conversation/assistant binding、物理 owner/run；最终事务重新核验，复制整个已完成闭合 run，而非一个 UI 块。当前整个 `branch` 包在 `DatabaseTransactions.run` 内。 |
| `context/ContextService.kt`，`branchInternal` | 重映射 conversation、run、物理/segment message；图片适配返回 remapped 消息。原视频首次消息插入就写真实 sidecar+witness，最后重读源/目标。原件复制能力缺失时拒绝，不能先空 witness 再覆盖。 |
| `ChatController.kt`，`readGenerationBackup` / `generationOutputReader` | 从 `GenerationSettingsStore.readAccountScope` 获取 manifest-only 账户身份，不读取编辑配置或解密凭据。复用 retained Resources 和 reader，检查 operation 与原 binding 一致；双索引保留 reader 的 conversation 归属。 |
| `ControllerGenerationResources.kt`，`ControllerGenerationOutputReader.readBackup` | 首先由同一个 owner fence 调用真实 `SQLiteGenerationOutputStore.find`，要求持久注册引用逐字段相等，然后才读文件。不是只看到 scalar row 就信任。 |
| `backup/MessageGenerationBackupAssets.kt`，`read` | 比较 pending 的完整 account/owner/operation/item/index/receipt/kind/MIME/长度/hash/尺寸/时长；按声明长度有界读，要求额外一字节为 EOF，最终重验长度与 SHA-256。 |
| `media/MessageGenerationAssets.kt`，`pending` / `openOriginal` / `readRecord` / `openVerified` | owner/slot/mapping/真实原件及媒体信息均被核验；只返回私有只读 descriptor，没有按任意路径读取的 API。slot 和 record 都绑定原 owner。没有 restore-to-new-owner 或已发布原件删除 API。 |
| `storage/SQLiteGenerationOutputStore.kt`，`registerVerified` / `authorize` | register/recover 是原 attempt、原 account/assistant/run/tool 的 live 注册，不是历史静态导入；检查最终 owner/attempt/注册回执。公开调用先拒绝外层事务，锁序是 file 验证释放后 gate → 顶层 SQL。 |
| `storage/SQLiteGenerationAttemptStore.kt`，`GenerationAttemptBinding.operationId` / `reserve` | operationId 包含 conversation/assistant/run/tool-call。新 paid admission 要求真实 RUNNING owner 且工具没有 result；已有 null-outcome 墓碑返回 Existing，不是 Started。不能为完成历史调用 reserve 来“顺手复制”。 |
| `backup/BackupGenerationCodec.kt`，`validateIdentity` / `validateOwner` / `restore` / `verifyRestored` | backup 只恢复原身份的 null-outcome 防重墓碑和 `neutral_backup_history`；明确要求恢复的输出不在 live 表。payload 的 operation 必须由 payload 的 owner 派生，工具来源必须唯一且完全匹配。 |
| `backup/BackupHistoryRows.kt`，`key` 调用方 / `historyRow` / `history` | 只读历史主键是 `(kind, identity)`；generation identity 是原 operation/item/index。`owner_message_id` FK 级联且必须等于 payload 的 assistant_id。同一 source identity 不能直接再插给第二个 branch。 |
| `backup/NeutralBackupService.kt`，`importPackage` / `historicalGenerationOriginal` / `validate` | 完整导入保留消息身份，并把已验证原件写为内容寻址 blobs；只读读取重新检查历史成员身份和 hash。现有完整包 v1/v2 严格拒绝未知字段，不能隐式塞入新映射而宣称仍可完整往返。 |
| `context/ContextStorage.kt`，`PrivateBlobs` | 内容寻址、不可变、写前验 hash，临时文件 finally 清理。**没有已发布共享 blob 的回滚删除权或引用计数契约**；不能在失败时按 hash 直接删文件，否则可能删掉别的 backup/归档引用。 |

### 不能直接复用的三条捷径

1. **复制旧 receipt / 注册 row**：新 owner 的 operation/slot 与旧 owner 不同；原 record 会拒绝。仅改数据库 owner 或路径白名单既不是原件映射，也不是安全导入。
2. **把旧只读 history row 改成新 owner**：不改 identity 会撞全局主键，改 identity/owner 又违反原 provenance 与 binding 校验；不能把新 operation 的墓碑伪装为实际发生过的新生成，更不能转成 live output。当前 import 保持原身份，并没有这个 remap 契约。
3. **把 `readGenerationBackup` 放进现有分支事务**：真实 reader/store 拒绝外层事务，而且会倒置 gate/file/SQL 的既有锁序。应先在事务外完成原件验证，最终事务只做身份/元数据 CAS 与引用提交，不改 validator 来适应嵌套事务。

## 本次小范围失败关闭修正

`ContextService.branchInternal` 在原 live output 检查旁，检查是否存在 `neutral_backup_history`，再按**本次复制前缀的物理 message owner** 查询 `kind='generation_output'`。命中则明确拒绝“只读生成原件尚无安全的分支映射契约；原会话和原件保留”。

- 不按任意路径、Tool.result 字符串、当前模型设置或可见分页判断资产。
- 检查即使原件已缺失仍拒绝，不把“文件不存在”理解为没有资产，不 read-repair。
- 新会话插入仍在原事务中，异常回滚；检查发生在图片/视频复制之前，不新增任何资产文件。
- 检查只约束复制前缀；后续 run 才有只读生成原件时，前面纯文本闭合前缀仍可分支。
- 原 live generation 拒绝不变；视频非空/空 witness、sidecar、源/目标重读、时间戳复制和 readonly history 本身不变。

## 建议的最小安全目的端契约（待实现与审查）

### 1. 显式区分 source provenance 与 destination ownership

新增 app-internal 的静态副本类型及 registry codec，不把 `GenerationOutputReference` 当目的端返回值：

- `sourceProvenance`：完整保留已校验的原 account/operation/item/index、原 conversation/assistant/run/tool、fingerprint/protocol、receipt、媒体元数据和 SHA-256；只描述来源，不能转换为新 owner live admission。
- `destination`：新 conversation/run/物理 assistant owner，加精确工具来源映射；historySegments 中的 assistant owner 和普通物理 owner 分别按现有规则验证。不要从最后一个 UI message 猜 owner。
- `identity`：包含 destination 和 source slot 的有界、版本化规范编码；不同 branch 可引用同一 source，目标同一 slot 的重复写必须逐字段相等，否则拒绝。
- 只允许完整静态描述和私有原件；没有 URL/endpoint/headers/handle/raw outcome/工具新请求状态，也不导入付费许可。

建议新增 `neutral_backup_history` 的独立 kind（例如 `generation_static_output`），复用其物理消息 FK、事务回执与 readonly 消费边界，但用新 codec 明确上述双身份。**不要把旧 `generation_output` payload 改语义。** 不向 `generation_outputs` 或 `generation_attempts` 写任何新行；源墓碑原样保留。目标工具原文只是已完成历史证据，不恢复执行。

### 2. 复用已存在的安全 source 能力，绝不回退裸文件

- live source：仍由 Controller 的 manifest-only account scope + retained reader 调用 `readGenerationBackup`，完整注册比较、原 owner fence、mapping/hash/有界 EOF 全保留。
- backup-owned source：由现有 readonly 历史成员验证和 `historicalGenerationOriginal` 读取。新 static 副本则由上述新 codec/registry + 静态存储 owner 校验读取。两者都不尝试恢复 live reader。
- 在任何路径上缺映射、缺原件、hash/长度/媒体信息错误、scope 变化或取消，都明确失败。禁止“找不到 live row 就读某个 private path”，也禁止拿工具 URL 补下载。
- 捕获完整前缀资产，不能沿用 UI 的 `.take(32)`/`LIMIT 32` 展示截断；保留每 item 的 index 上限、现有历史元数据行数/字节限制，读取前按 SHA-256 去重预留总字节预算及单件上限。超限拒绝，不提高限制掩盖缺口。

### 3. 两阶段分支与锁序

需要将 `MessageActionStore.branch` 从“一个外层 SQL 事务内做所有工作”调整为显式准备与最终提交：

1. **SQL 捕获**：authorize target；确定相同闭合整 run 前缀；捕获完整历史 revision/binding、生成来源 scalar 集合及其规范摘要、视频 inputs+witness。分配新的 branch/run/message 映射但不提交任何目标消息。
2. **事务外原件准备**：释放 SQL/cursor 后，经现有安全 reader 读取所有生成原件；关闭本次 reader/descriptor，不能关闭其他 owner reader 或 seal 原 run。准备每个目标静态副本，校验 hash/长度/元数据和取消。
3. **最终同一 SQL 事务**：再次 authorize 全历史/binding；逐字段重比来源集合（包括新增/删除 output），不能仅靠 ContextRevision——生成 side table 并不在消息 digest 内。确认取消未发生；插入新 conversation/messages，按现有路径首次写真实视频 sidecar+witness，插入静态映射。所有生成引用与消息在同一事务提交。
4. **最终回读**：在最后一次 SQL 写入之后，确认目标消息、所有映射/物理 FK、source provenance、目标无 live outputs/新 attempts，确认所有视频 inputs/witness，源消息和来源集合仍相等。`RAISE(IGNORE)`、mutating trigger、owner 变化都回滚，不能用 insert 返回值代替回执。
5. 释放全部 SQL 后再结束本次静态复制 lease。不能在持有 DB/Controller monitor 时等待 owner publication gate、做媒体 file-lock IO 或进行 revoke barrier。

复制不需要撤销源发布权；不得为了方便导入而 seal 源会话。若准备期间 source 被发布/删除/改写，最终来源 CAS 失败，只能用户显式重新操作，不自动重读重试。

### 4. 资产发布、取消、失败和 UNKNOWN

必须先补可审计的**静态目的端** prepare/publish/settle lease；目前原件 store 和 PrivateBlobs 都没有这项能力，不能把共享 hash blob 的删除当作实现。

建议 owner-isolated 的静态 namespace，record 绑定 destination+source provenance；复用现有固定名称、NOFOLLOW_LINKS、fsync/原子 rename 的实现原则，但不是 live `commit/register`，也不是接受任意路径的 restore。内部 API 仅接受本次安全读取产生的有界已验证 bytes/descriptor 与新映射，不对 UI/tool 暴露 receipt/path。

- 准备和取消检查都在 SQL 外；只清本次创建且未发布的固定 staging 文件，不递归删除不明目录。
- 发布后的目标仍由本次 lease 唯一拥有，SQL 已确认回滚、且精确目标无引用时，才能清理本次新目标副本；不得碰源、共享 blob、旧副本或别的 branch。
- 若 rename/COMMIT/endTransaction 回执 UNKNOWN，保留所有可能已发布的副本与源；返回可定位的新 branch identity 供刷新核实，不自动重发/重建分支/重放工具。不能把异常当“肯定没提交”。
- COMMIT 已确认后发生取消或 UI 已离页，持久成功仍然成立；只丢失 UI 消费权，不删除已提交分支。普通 reader close 只关局部 fence，不 seal 全局 owner。
- 不宣称 DB/FS 跨介质原子性或断电后绝无 orphan。崩溃遗留的未知目录没有 GC 授权，另行审查恢复/回收；这不影响已确认回滚的本次 staging 清理。

### 5. readonly 消费与完整 backup 必须一起接通

- Main 继续通过 readonly 投影显示图片缩略图/视频元信息、显式保存静态原件；展示和保存再次按目标 registry+hash 核验，不依赖当前生成设置/凭据。
- 导出/导入新增静态 mapping 的版本化区段，建议完整包 v3；v1/v2 原格式仍能读取，旧客户端遇 v3 必须明确拒绝，不能静默丢资产。旧 `generation_history` 和 null-outcome 墓碑契约不变。
- 完整包必须包含来源 provenance、目标映射与真实原件并计入 inventory/总字节；导入仅 readonly，重复导入重新验证目标原件。不为静态副本造 live attempt。
- 删除原消息/会话及已确认后的源原件清理后，新 branch 和再次 branch 的静态原件仍可读取/保存；删除一个副本只级联自己的映射，不删源或其他副本。

## 回归与验证状态

### 本次已有新增源码（未执行）

`app/src/androidTest/kotlin/app/daiyu/android/backup/ExtendedNeutralBackupTest.kt` 复用真实 ConversationDatabase、实际 MessageGenerationAssets PNG inspector/私有原件、真实注册与完整 backup 恢复：

1. live generation 前缀仍明确拒绝；完整导出、源历史/原件、attempt/output 数量和 video witness 不变，不伪造新 attempt。
2. 已恢复 readonly generation 前缀在**原件存在 / 原件缺失**两种情况下都明确拒绝；无新 branch/消息/输出，readonly 引用与 null-outcome 防重墓碑不变，缺失不修复。
3. readonly generation 在截点之后时，较早纯文本 run 可分支；新旧消息文本/时间戳和空 witness 正确，后面的 readonly 原件仍保留。

测试将工具的 legacy blocks 和 authoritative historySegments 都闭合为 UNKNOWN 历史证据，避免只触发较早的“未完成工具批次”检查而产生虚假的资产拒绝覆盖。测试直接调用真实 store，未提供网络、runner、模型或生成请求能力。

### 开放成功分支前必须补齐的验收

- Controller/worker 真实入口：live 图片、真实编码生成视频、多个 outputs、混合普通图片/原视频 sidecar/segment supplement；成功后显示并显式保存原字节，所有 transport/tool/UNKNOWN 请求计数为零。
- 源 owner/account/operation/item/index/receipt/fingerprint/protocol 逐项伪造，原件缺失/截断/多余尾字节/hash/映射/媒体 metadata 损坏，全失败且不触其他 private 内容。
- 准备后消息 revision、assistant binding、来源 side table 集合、witness 变化时 CAS 拒绝；无文件锁与 SQL/gate 反序死锁。
- 静态 mapping/message/sidecar/witness 的 IGNORE、触发器改写、最后一次 SQL 写后变化，确认整事务回滚；已确认失败的本次 staging/副本清理、共享/源资产不变。
- 读取中/发布前/SQL 前取消、COMMIT 后取消、rename/commit UNKNOWN，分别验证 cleanup 与保留边界；刷新永不重新发请求。
- 删除源会话和源原件后副本仍可读；两个 branch/再次 branch 相互隔离；static history 完整包导出→新 DB 导入→重开→重复导入及缺原件失败，均不恢复 live generation 权限。
- 既有 `ControllerMessageActionsTest` 的视频 sidecar/witness、摘要 UPDATE IGNORE、时间戳、Main attempted-key、NOT_SENT/ACCEPTED/UNKNOWN 和 readonly history 回归仍需通过。

本次只能进行 workspace 源码读取/修改与静态核查；没有 Shell、构建、设备、GUI 或网页能力。未运行编译、JVM、instrumentation 或 diff --check，不把测试源码写成通过报告。构建/测试和最终方案批准由主代理负责。
