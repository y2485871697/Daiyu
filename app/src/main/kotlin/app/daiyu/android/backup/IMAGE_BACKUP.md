# 中立完整备份：消息原图资产

## 本包已实现

`NeutralBackupService` 的完整包（`daiyu-neutral-complete`, version 1）在已有
`text_backup` / `tables` / `blobs` 基础上增加可选 `message_images` 数组。无图片时不输出该成员，
保持原纯文本完整包的字段、摘要和导入兼容；这不是对单独 `ConversationDatabase.exportBackup()`
文本格式的扩展。**单独文本格式仍只含图片引用，不应宣称自包含图片备份。**

* 原图清单来自此次已导出的实际 `messages` 行的 blocks、history segment 的真实 message ID，
  以及此次会话范围内每个 `context_archives` blob 经 `ArchiveCodec.decode` 后的行/段；不是从
  当前活动会话、UI预览、引用自述owner或 `neutral_assets` 猜测。归档独有/补充段独有图片也计入。
* `MessageImages` 检查引用与承载行/段的 owner；相同 owner/id 的重复快照必须具有完全一致的
  metadata。跨行/归档按 owner/id 去重；同一 owner 最多8张、累计64MiB。清单最多50,000项、
  UTF-8编码最多4MiB。单图沿用域模型20MiB上限；全部解码blob仍共用64MiB、完整包112MiB上限。
* 导出通过注入的 `BackupImageAssets.read(owner, reference)` 读取真实消息资产，然后再次核对
  大小/SHA-256。不能读取、损坏或未接入能力均报错，不产生引用-only的“成功”完整包。
  原图与归档/显式附件复用已有 content-addressed blob 数组，SHA-256相同只携带一份bytes。
  `inventory.attachments` 包含唯一消息图片引用数与原有显式附件数，`blob_bytes` 是去重后的大小。
* 导入先完整解析、核对原有文本/归档协议、引用归属、闭合清单、blob大小/摘要、库存。清单必须
  与持久化行和归档所需引用集合完全相同，不允许缺项、重复项、额外未引用项或不同metadata。
  图片存在但没有清单的旧“引用-only”包明确拒绝；没有图片的旧完整包继续兼容。
* 校验成功后才进入原SQLite事务。在文本/表/配置冲突检查后，调用注入能力按owner原子发布
  原图，再读回验证；全部成功后写导入标记并提交。任何异常回滚可见DB；不删除或覆盖既有资产。
  再导入也会验证已存在原图，导入标记不能掩盖事后缺档。没有恢复能力时在DB写入前失败。

## 当前宿主接线与能力边界

`MessageImageAssetStore.restore(owner, references, contents)` 提供生产恢复接口；
`MessageImageBackupAssets` 直接委托既有 store 的 resolver/restore，不伪造草稿调用 prepare。
Controller 将其已有 `messageImageAssets` 注入 `NeutralBackupService`；发送与备份复用同一实例，
备份仍走 `dataTask` 的单线程 executor 与运行/准备排他门禁。两个旧 Context 构造器仍只提供
无图片 capability 的兼容入口，不猜测目录；其他调用者如需完整图片包必须显式注入。

恢复验证完整 owner 批次的 metadata、数量、大小、摘要及实际解码图像后原子发布；已有等价
批次按 ID/metadata 只读验证，不要求原 draft 来源或顺序相同，任何冲突不覆盖。
SQLite 与文件系统不是同一个事务，晚失败可留下新发布完整孤儿，但不删除原有 DB/资产。
孤儿仅能由宿主确认 DB 无引用后回收，backup 没有删除 capability，不在 finally 清理。
每 root 单实例与宿主串行化不是跨进程锁，也不承诺断电目录事务。

## 测试状态（只编写，未运行）

* `BackupImageManifestTest`：真实段owner、重复快照去重/冲突、闭合清单、原图一致性、未知字段/
  路径字段/重复项拒绝、每owner和清单上限、旧纯文本兼容。
* `NeutralImageBackupTest`：源端使用真实 `MessageImageAssetStore.resolver`（清草稿后读取）；
  行+段独有+归档独有图片、同bytes去重、单会话范围、原图缺失/损坏、清单/摘要/owner篡改先于
  任何DB/资产发布失败、配置/资产冲突回滚、旧纯文本包及无能力明确失败。
  **目标端RecordingAssets是显式发布测试替身**，证明backup编排，不证明media恢复实现或重启
  后真实资产可读。真实目标恢复、store 重开、等价批次、冲突和晚失败已有 `MessageImageBackupAssetsTest` 源码；尚未运行，也未覆盖真实进程重启及文件写入故障注入。
* 没有运行构建、测试、创建CI或改schema；构建测试与最终整合由父代理负责。
