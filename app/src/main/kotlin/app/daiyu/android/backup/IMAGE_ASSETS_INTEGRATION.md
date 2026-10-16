# 真实消息图片备份能力

## 生产 API / 当前接线

```kotlin
// media；构造签名保持兼容。restore 固定使用 AndroidImageInspector 真解码。
class MessageImageAssetStore(
    root: File,
    limits: ImageAttachmentLimits = ImageAttachmentLimits(),
) {
    fun restore(
        owner: MessageImageOwner,
        references: List<MessageImageReference>,
        contents: List<ByteArray>,
    ): Unit
}

// backup；构造函数只接受宿主已持有的同一个实例，无 Context/File 构造器。
class MessageImageBackupAssets(store: MessageImageAssetStore) : BackupImageAssets
```

Controller 组合已使用下述接线；adapter 只接收 Controller 已有实例，不创建第二个目录或 store：

```kotlin
// messageImageAssets 是 Controller 已有、供消息接受/读取使用的 app-scoped store。
val backup = NeutralBackupService(context, database, MessageImageBackupAssets(messageImageAssets))
```

继续用宿主现有单线程 executor 串行运行接受、备份/恢复、删除和相关 DB 工作。不要为了备份新建目录、第二个 store 或另一套锁。adapter 不调度任务，也不绕过 NeutralBackupService 对 package/message owner 的授权验证；它只传递真实 store capability。

## 恢复保证

- 先快照引用、检查完整批次数量/重复 id、owner、id/hash 格式、MIME、尺寸/像素限制、单图/总 bytes 上限，再复制和校验全部原始 bytes（实际长度、SHA-256、Android 真图解码所得 MIME/尺寸）。任一失败不发布该 owner。
- 复用 store 的 `gate` 和媒体文件安全函数。新 owner 写入独占 `.pending`，fsync 文件，完成 manifest 后单次 `ATOMIC_MOVE` 发布完整目录；不降级为非原子复制。仅清理由本次调用创建的 staging。
- 已有 owner 按 id + 全部 metadata 比较，忽略引用顺序和原草稿出处；验证完整文件清单、已有 manifest、每张图的原 bytes/摘要/实际图像。相同则只读返回，绝不重写 manifest/图；冲突、缺失、损坏或额外未知文件拒绝，绝不“修复”。原 prepare manifest/草稿出处保留。
- 新 restore manifest 使用由消息 owner 派生的合成草稿身份以兼容 v1 resolver；它不引用、恢复或删除草稿文件。prepare 的既有 source/order 幂等约定不改变。
- symlink/path 拒绝沿用现有媒体安全边界。已有 `.pending` 或 `.deleting` 不由恢复清理。跨 owner / SQLite 不提供原子文件事务；后续 owner 或 SQL 失败时，先前发布的完整 owner 作为安全孤儿保留，已有资产和其他孤儿均不删除。
- 与现有 prepare 一样，前提是每 root 一个 app-scoped store + 宿主串行化；不是抵御同 UID 外部文件写入者的沙箱，也不是跨进程锁。文件 fsync 不等于目录 fsync 或断电事务。

## 验证来源及状态

新增 `app/src/androidTest/kotlin/app/daiyu/android/backup/MessageImageBackupAssetsTest.kt`：使用真实 Bitmap PNG、默认 Android inspector、真实 MessageImageAssetStore、生产 adapter 和（整包场景）真实 SQLite/NeutralBackupService；不用 fake store 充当生产验证。

覆盖：新目标整包恢复并重新打开可读；实际 prepare 批次被同一备份幂等恢复、反序匹配且 manifest 不变；全部 metadata/非法实际图像/摘要/长度/数量限制；新 owner 后段非法不产生部分目录；已有文件冲突/缺失/损坏/未知项不覆盖；文件/manifest/owner/root/pending symlink、旧 pending/tombstone 保留；多 owner 晚冲突回滚 SQL 而保留早期完整孤儿、原有资产和无关孤儿，并覆盖重复冲突失败仍保留数据；不代表真实进程重启或解除冲突后成功恢复已验证。

本任务按限制仅源码、静态核验；**未执行构建、测试、CI 或安装**。现有 `NeutralImageBackupTest` 的 RecordingAssets 仍只是备份编排测试双，不是本实现的生产证据。
