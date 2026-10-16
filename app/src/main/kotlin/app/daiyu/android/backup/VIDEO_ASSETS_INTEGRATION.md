# P18 / R12 原视频完整备份接线

本组件不修改 `ChatController` / `MainActivity` / `ConversationDatabase` / manifest，也不升级 schema。
依赖已有 v12 `message_video_inputs`。下面的数据库片段由父代理合入；不合入前，普通文本以及
未注入视频能力的完整备份继续拒绝含视频会话，不会静默产出丢视频的包。

## 已提供的可编译组件 API

```kotlin
VideoAssetStore.restore(
    owner: MessageVideoOwner,
    references: List<MessageVideoReference>,
    contents: List<ByteArray>,
    isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
)

interface BackupVideoAssets {
    fun read(owner: MessageVideoOwner, reference: MessageVideoReference): ByteArray
    fun restore(owner: MessageVideoOwner, references: List<MessageVideoReference>, contents: List<ByteArray>)
}
class MessageVideoBackupAssets(store: VideoAssetStore) : BackupVideoAssets

data class BackupVideoSidecar(val messageId: String, val payload: String)
class BackupVideoIntegration(
    val assets: BackupVideoAssets,
    val exportText: (SQLiteDatabase, ConversationId?) -> String,
    val restoreSidecars: (SQLiteDatabase, List<BackupVideoSidecar>) -> Unit,
)

NeutralBackupService(
    context: Context,
    store: ConversationDatabase,
    imageAssets: BackupImageAssets?,
    videoIntegration: BackupVideoIntegration,
)
```

`MessageVideoBackupAssets` 必须包装宿主持有的 **同一个** VideoAssetStore；不要新建 store/root。
整个导入/导出由宿主现有 acceptance/删除串行化锁保护；本组件不会私建宿主锁。
恢复不经过 draft/import/prepare，不重生成 ID，不回放请求，不创建 upload grant。

## 父需要的 ConversationDatabase 精确修改片段

当前源码约 330 行：保留两个公开文本入口不变；仅将 private exporter 增加可选能力参数。

```kotlin
fun exportBackup(): String = exportBackup(null)
fun exportConversationBackup(conversationId: ConversationId): String = exportBackup(conversationId)

// 新增，仅供已接入真实视频原件能力的完整备份使用。
internal fun exportBackupForVideoAssets(
    db: SQLiteDatabase,
    conversationId: ConversationId?,
    assets: app.daiyu.android.backup.BackupVideoAssets,
): String {
    check(db === writableDatabase && db.inTransaction())
    return exportBackup(conversationId, assets)
}

// 将原 private fun exportBackup(conversationId:ConversationId?):String 改为：
private fun exportBackup(
    conversationId: ConversationId?,
    videoAssets: app.daiyu.android.backup.BackupVideoAssets? = null,
): String {
    // 原有主体全部保留；只替换原视频拒绝条件：
    // check(!SQLiteMessageVideoStore.hasVideos(db, conversationId?.value)) { ... }
    // 为下面条件，原错误文案也保留：
    // check(videoAssets != null || !SQLiteMessageVideoStore.hasVideos(db, conversationId?.value)) { ... }
    // 其余大小限制、顺序、历史、owner、协议验证以及 parseBackup(result) 原样保留。
}
```

上面的 private 主体为定位片段，不是空实现。唯一绕过的限制是“纯文本不能保留视频”；真实原件
及 sidecar 的完整性仍由 NeutralBackupService 强制验证，单独拿到 text 返回值不是完整备份。
禁止把普通 `exportBackup()` 的拒绝删除，也禁止公开 boolean skipVideos 开关。

新增同事务恢复入口（可直接粘贴在 ConversationDatabase 类内，无新 import）：

```kotlin
internal fun restoreVideoBackupSidecars(
    db: SQLiteDatabase,
    sidecars: List<app.daiyu.android.backup.BackupVideoSidecar>,
) {
    check(db === writableDatabase && db.inTransaction())
    sidecars.forEach { sidecar ->
        // 身份来自已持久化的物理 message 行，不信任 sidecar 的自述 owner。
        val conversation = db.rawQuery(
            "SELECT conversation_id FROM messages WHERE id=?", arrayOf(sidecar.messageId)
        ).use { cursor ->
            check(cursor.moveToFirst()) { "视频物理消息不存在" }
            ConversationId(cursor.getString(0))
        }
        val physical = storedMessage(db, conversation, sidecar.messageId)
        val inputs = MessageVideoInputCodec.decode(physical, sidecar.payload)
        check(inputs.isNotEmpty())
        SQLiteMessageVideoStore.insert(db, physical, inputs)
    }
}
```

已有 `storedMessage` 约 209 行可直接复用；不要调用 `send`、不要更新全局 schema、不要另开连接。
`SQLiteMessageVideoStore.insert` 自身会读取回执，NeutralBackupService 还会核对 payload 和物理
history/owner。任何后续原件/marker失败导致外层 SQL 回滚，物理行、sidecar、配置和 marker 同事务。

## 父宿主入口

```kotlin
val videoAssets = MessageVideoBackupAssets(existingVideoStore)
val completeBackup = NeutralBackupService(context, database, existingImageBackupAssets,
    BackupVideoIntegration(videoAssets,
        exportText = { db, scope -> database.exportBackupForVideoAssets(db, scope, videoAssets) },
        restoreSidecars = { db, sidecars -> database.restoreVideoBackupSidecars(db, sidecars) },
    ))
```

父须将全量/单会话完整导出和导入统一接到这个服务，并在宿主已有串行化边界内调用。不要只给
导出接 adapter；未接 adapter 的导入面对视频包会明确失败。预检验证结构/字节但不向模型发送。

## 格式、安全与兼容

- 外层仍是 `daiyu-neutral-complete` version 1；可选 `message_videos` 是闭合对象：
  `{"version":1,"sidecars":[{"message_id":"...","payload":"<原 v1 MessageVideoInputCodec 字符串>"}]}`。
  无视频的旧完整包、图片包均兼容；未知 manifest 版本拒绝。
- 物理 sidecar 按 text_backup 的物理行顺序排列，不重复；每个 payload 使用严格现有 codec，
  校验物理行的 conversation/run/id、展开历史中的真实 user owner、完整 metadata、输入和引用顺序。
  相同历史 owner 的重复快照必须具有完全相同的有序 references。实际原文/历史仍用既有 text
  parser 和 ArchiveCodec 验证，未放宽它们的协议或 owner 限制。
- 视频原字节复用已有 content-addressed `blobs`，每个引用的 byte count/SHA-256 必须一致；缺件、
  多余 blob、篡改、未知字段、重复物理项拒绝。不是封面代替原视频。恢复还经真实 inspector 核对
  MIME/duration/dimensions，所以只有 metadata 不能通过原件恢复。
- 保持已有总 decoded blob 64 MiB、外层 UTF-8 112 MiB、单 blob 64 MiB 限制；预读前预留去重后
  字节预算，Base64 分配前检查总量。视频 manifest 最多 4 MiB / 50,000 sidecars / 50,000 独立
  references；每 sidecar 1 MiB，codec 自身另有每 owner 8 引用等限制。本层不解 ZIP、不扩容 ZIP
  防炸包上限。超过这些限制会明确失败，绝不静默截断大视频或丢图片。
- VideoAssetStore.restore 使用原 owner/ID，校验完整 reference 与真实 bytes/inspector；已有原件
  必须完全相同且文件真实可读；冲突拒绝。失败只清理本调用新建的 staging/publication，不删已有
  原件。跨 owner / SQLite 后续失败可能留下无引用 immutable 原件，与原图片恢复语义相同。
- Duplicate marker 仍重新验证 sidecar、当前物理 owner/history、原视频文件摘要；marker存在但文件
  删除/损坏不会假成功。未新增自动修复或覆盖已有原件行为。
- draft source provenance 不是 message reference 的授权字段；新恢复目录使用本地恢复标记，
  不冒充某个 draft。原 accepted reference 的 owner/ID/MIME/bytes/hash/duration/width/height 全保留。

## 测试与当前验证状态

新增 `BackupVideoManifestTest`、`VideoAssetStoreTest` 恢复用例、`NeutralVideoBackupTest`。
覆盖原 ID/metadata/顺序、历史段 owner、跨会话/run 篡改、未知版本/重复项、缺失/损坏字节、
批量失败保留已有原件、同事务 sidecar 失败回滚、重复 marker 但文件缺失拒绝。
单测 store 使用 fake inspector；仪器集成用 fake asset adapter，仅证明存储/事务协议，不宣称真实
Android 视频解码测试。此实现代理未运行 Shell/Gradle；构建和选择性测试由父统一执行。
