# P35 · 真实逐消息时间

## 已核实的基线

- `core-domain/.../Conversation.kt` 的 `Message` 与 `MessageHistorySegment` 原先均没有消息时间。
- `storage/ConversationDatabase.kt` v14 的 `messages` 没有时间列；`conversations.updated` 是会话更新时间，不能作为逐消息时间。
- 现有 `MessageTimestamp` 使用 `yyyy-MM-dd HH:mm`，且空字符串不显示；本变更不修改组件外观。

## 数据约定

`Message.createdAt: Long?` 是接受消息时记录的一次性 Unix epoch 毫秒，不是 token 到达、完成、重新读取或会话更新时间。`null` 表示未知，`0` 是有效 epoch 时间，负值拒绝。构造器默认 **null**：解码旧数据、临时上下文消息或重新构造身份校验消息不得隐式读取当前时间。

新接受消息的创建方必须显式记录一次，例如：

```kotlin
val acceptedAt = System.currentTimeMillis()
val user = Message(/* 原有参数 */, createdAt = acceptedAt)
val assistant = Message(/* 原有参数 */, createdAt = acceptedAt)
```

也可分别在各消息被接受时取一次时间。流式更新必须沿用原对象 `copy(...)`，不得对每次快照重新取时；补充用户消息在其被接受时单独取时。重试身份核验应使用已冻结的原始消息/时间，而不是新的时钟值。

特别注意：现有 `ControllerImageSending` / `ControllerVideoSending` 会重新构造期望 `Message` 并做完整相等比较。**不能**简单把 domain 构造器默认值改成 `System.currentTimeMillis()`；生产创建路径赋时后，这些核验也必须携带相同冻结时间，或在核验现有业务字段时显式使用存储行的原始时间。本存储子任务不修改控制器，创建接缝由父代理整合。

### 历史投影

- `MessageHistorySegment.createdAt` 同样可空，支持只有历史段而没有 standalone 行的消息。
- 展开 assistant 段时使用段的真实时间（若有）或其同 ID 物理消息时间。
- 展开补充 user 段时使用段的真实时间或同 conversation/run/id 的 standalone 行时间。没有两者时保持 null，**不得继承 assistant owner 的时间**。
- 两个已知的 owner/segment 时间若冲突则拒绝，避免呈现互相矛盾的“真实时间”。

## schema v15

本次统一由 v14 升级至 v15：

```sql
ALTER TABLE messages ADD COLUMN created_at INTEGER
CHECK(created_at IS NULL OR (typeof(created_at)='integer' AND created_at>=0));
```

无 DEFAULT、无 UPDATE 回填。原行读取为 null，不以当前时间或 `conversations.updated` 猜测。升级在 SQLiteOpenHelper 的原事务内执行。旧 schema 测试夹具若已经保留此列，则不重复 ADD。

- 从 v13 升级仍执行此前 v13→v14 的 `SQLiteChildTaskStore.installCommandSchema`，语义不变。
- v13/v14 的非空原视频 `message_video_inputs` 与 `message_video_witnesses` 不重建、不修补、不重算 hash；v14 不重复安装 command schema。
- v12 及更早版本继续原视频迁移逻辑，时间列在该逻辑读取消息之前安装。
- `save` / runtime checkpoint 写入拒绝改变已有物理消息时间；包括把历史 null 偷换成当前时间。

## 序列化、重开与中性备份

可选严格字段 `created_at` 由 `MessageTimeCodec` 共用：仅整数且非负；缺失或 JSON null 解码为未知。未知时间编码时省略字段，保持旧档案/旧备份字节与 digest，不为旧记录添加当前时间。

覆盖路径：

- SQLite 消息读取/写入；ContextService 的原文读取与分支消息写入。
- `ArchiveCodec` 档案及其复用的 `RunCheckpointCodec`（因此流式检查点、恢复和历史都携带原值）。
- `MessageHistoryCodec` 的可选段字段。
- neutral text backup v1/v2 的可选消息字段（不改变已有 envelope 版本）；complete neutral backup 复用该路径。
- video backup manifest 的物理消息投影保留该字段，但视频 sidecar/witness 格式与 hash 输入不变。

时间仅是本地展示元数据，不读取模型协议中的任意 `created_at`，不增加凭据、URL、模型授权或运行所有权信息到备份。

## 父代理投影接缝

新 helper 位于原时间组件同包：

```kotlin
MessageTimestampFormatter.timestamp(
    message,
    enabled = appearance.messageTimestampsEnabled,
    zone = ZoneId.systemDefault(), // 可省略；测试可注入固定时区
)
```

返回 `String`。禁用或时间未知时返回 `""`。只有展示时做时区转换；持久化原始毫秒不受时区改变影响。父代理可将闭包传给 `ChatTurnMapper.map(..., timestamp = { message -> ... })`。本任务不修改 MainActivity、mapper、screen models、list、消息操作接口或原 MessageTimestamp 组件。

## 新增测试源码及验证边界

- `core-domain/.../MessageTimeTest.kt`：未知时间、copy 稳定、历史段/standalone 各自时间、不借用 owner 时间、负值拒绝。
- `app/src/test/.../MessageTimestampFormatterTest.kt`：固定时区、分钟格式、开关、未知、epoch、DST。
- `app/src/androidTest/.../storage/MessageTimeDatabaseTest.kt`：显式新接受时间、流式替换/完成、拒绝修改时间、重开、中断恢复、checkpoint 编解码、档案/neutral backup round trip、旧字段缺失、严格类型拒绝；真实无时间列 v14 messages 表升级并保留非空原视频 sidecar/witness 和 command schema。
- 原 `ChildCommandSchemaMigrationTest` 仅将两处升级目标断言改为15，继续验证 v13 command 迁移、非空视频不变和失败回滚。

此实现代理没有 shell/构建权限，**未执行**测试、构建、设备测试。源码检查不是运行通过的证据。父代理须统一更新其余旧数据库目标版本断言、接通新消息赋时与开关、执行最终验证；不能仅凭 formatter 测试声称生产新发送已接通。
