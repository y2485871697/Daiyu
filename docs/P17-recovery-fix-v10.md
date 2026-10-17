# P17 recovery review 修复 v10

## 范围与依据

- 任务指定基线：`806fd473`；review 证据标识：`6ef6061d`。此实现环境仅提供工作树文件读写，未查询 Git 元数据，故没有独立核实当前 HEAD 或 detached 状态。
- 仅修改图片 Host、其持久化，以及 `ControllerImageSendingTest` / `ImageChatHostActivityTest` 和本文；未修改 MainActivity、ChatController、清单、媒体算法或 journal 算法。
- 源码证据：`ImageChatHost.finish` 原先在 Accepted 后只消费附件；`MainActivity.settleImageReceipt` 要求当前文字和数字 revision 均匹配才清文字、推进 revision。`ControllerImageAttempt.reserve` 对同 owner/revision 保留 tombstone，内容不同则 UNKNOWN。已有会话已接受文字但丢失回执时，重建后的空文字使 Main 拒绝清空回调，下一次不编辑文字的纯图发送可撞旧键。

## 身份修复

1. `ImageHostDraftStore.Draft` 新增向后兼容的 `generation`（默认 0）和 `retired`（默认 false）。计数仅在原 attempt 获得精确 Accepted 并确认回执后递增，不由 Main 的清文字或导航结果驱动。
2. Host 消费原 submission 后，先持久化“代际 +1、原 owner 已 retired、无 frozen/ready 附件”。该提交先于 Activity settlement、先于全局写门开放。到达 Long 上限或存储失败时保持 fail-closed，不回绕身份。
3. 为兼容 Main 未修改的精确 owner/revision 校验，settlement 回调期间暂留旧的内存 owner。之后为同 conversation 创建带代际和随机 nonce 的新 draft owner，并持久化；数字 editor revision 不必改变。即使 Main 因文字不同不执行回调，也必定建立后继图片 owner。
4. 如果进程在 retirement 提交之后、后继 owner 保存之前消失，Session 恢复看到 retired 标志即创建后继 owner，不能恢复可发送的旧 journal lane。若在 retirement 提交之前消失，磁盘仍保留原 frozen fence，只能原身份查询回执。中间态同样不允许重发原 POST。
5. 只转换成功 attempt 所属 Session，删除仍限定为回执中的精确原附件。发送门关闭期间不能开始新 picker/import；不会改变当前选择的另一个 Session、文字、光标或导航。Rejected/Unknown 不推进代际、不放开未知结果重发。
6. 恢复仍走 `recover -> readImageReceipt`，没有转入 `send` / prepare / freeze / model。`editorReplaced` 的持久化保护保持不变。

## 回归覆盖

### ControllerImageSendingTest

- 两处手工模拟 DB 接受的 fixture 改为 assistant RUNNING 传给 `db.send`，再 `db.save(...COMPLETED)`；遵守 DB 的初始 pair 约束。
- 原有新 Controller/Host 的只读恢复用例增加：删除原 draft 图像文件、清模型配置后仍能 read Accepted；验证查询前后 journal / messages 不变及 factory 次数为零。这可检出恢复误入 freeze/prepare 的路径。
- 接受后检查持久 generation=1 和不同 successor owner；在**相同数字 revision=17** 下给后继 owner reserve 新图片成功，而不是用人为 +1 revision 回避旧 tombstone。
- 模拟 retirement 已提交、后继 owner 尚未保存的持久中间态；重建 Session 必须生成不同 owner，保留 generation/revision 且没有旧附件。

### ImageChatHostActivityTest

- 新增 `testExistingConversationLostReceiptRecoveryThenUneditedPureImageUsesDurableNextOwner`：真实会话 C，非空文字+图片已 DB 接受且原模型只打开一次，丢弃原 Controller 回执，重建 Controller/Host 并重开 C。断言文字为空、Main revision 等于原 revision；recover 后 Main revision **仍不改变**，原 rows、选择及调用次数保持不变，图片 owner/generation 已持久推进。再次重建后不编辑文字，导入一张新图并正常发送：共两次模型打开、四行消息，旧 pair 不变，新消息只有新图片，且不 UNKNOWN。第二次是用户新发送，绝非原 POST 重放。
- 加强同文草稿保护用例：第一次 Controller/Host 重建后录入与原文相同的新草稿并持久化 editorReplaced；第二次真正重建 Controller/Host 后再核对标志。通过测试反射明确将 Main 的数字 revision 设为原 frozen revision，并断言两者相等，再恢复旧 Accepted。旧 receipt 不清新同文草稿、不改变文字选择范围、不导航，DB 中新 draft 也保留。恢复模型 factory 均设为报错，以防误走准备路径。
- 测试 fixture 将模型作为成员保留，仅用于后继用户新发送；没有改变生产模型逻辑。

## 验证状态与限制

- 已做源码路径核对和工作树差异检查；没有使用 Shell、Git、网络或读取凭据。
- **未运行构建、单元测试或设备 instrumentation 测试。** 当前工具没有构建/执行能力，编译及运行结果必须由主代理验证，不能将以上新增断言当作已经通过的运行证据。
- 建议主代理运行两个修改后的完整 instrumentation 类，特别关注真实 Activity 重建、Controller handler 回执丢弃和后续纯图发送；必要时补跑图片相关既有测试。
- detached 状态、基线 commit、干净隔离及最终允许文件范围须由主代理通过其可用集成工具核验；本实现未创建分支、提交或 push。
