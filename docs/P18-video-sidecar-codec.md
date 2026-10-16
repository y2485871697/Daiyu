# P18：严格视频 sidecar codec（父级待接线）

## 范围与依据

新增 `app/src/main/kotlin/app/daiyu/android/storage/MessageVideoInputCodec.kt`，只持久化原视频**引用元信息**。API 为同 app 模块内的：

```kotlin
internal object MessageVideoInputCodec {
    fun encode(message: Message, inputs: List<MessageVideoInput>): String
    fun decode(message: Message, encoded: String): List<MessageVideoInput>
}
```

此叶子不改 `ConversationDatabase`、Main、Controller、`MessageBlock` 或既有视频协议；不接网络、不读写媒体文件、不重新编码视频、不产生封面、data URL 或上传许可。codec 不是资产存在性、SHA 实际内容或账户授权证明。父级仍需使用真实授权映射和 `VideoAssetStore` 校验原文件。

已核对的本地源码依据：

- `model/videos/FrozenMessageVideoPlan.kt`：`MessageVideoInput(owner, references)` 对引用列表作不可变副本；冻结层要求唯一展开 user、无 protocol replay、授权 owner 和完整存储记录。视频顺序仍为该行文字/图片之后的引用列表顺序。
- `media/video/VideoAttachment.kt`：`MessageVideoOwner` 只有 conversation/message；`MessageVideoReference` 的八项属性为 owner、id、mime、bytes、sha256、durationMs、width、height。`VIDEO_ID` / `VIDEO_HASH` / `VIDEO_MIMES` 与 `VideoAttachmentLimits` 为校验依据。
- `media/video/VideoAssetStore.kt`：`prepare` 保留附件 UUID 与全部元数据，但创建新的 message owner；因此**相同 UUID 出现在不同 owner 下合法**。`validateReference`、`checkCapacity` 与 inspector 执行真实媒体/容量检查。
- `core-domain/.../Conversation.kt` 的 `MessageHistory.expand(history: List<Message>)`：并非 `expand(message)`；assistant 的非空 `historySegments` 被权威展开，继承其 conversation/run。整个历史里的对应 standalone user supplement 经校验后被去重。无 segment 的 legacy 行原样返回。
- `storage/SQLiteText.kt`：现有 BLOB 长度预检、64 KiB `substr` 分块、拼齐后严格 UTF-8 解码；当前 allowlist **尚无视频 sidecar 列**。
- `storage/ConversationDatabase.kt`：当前 `save` / `writeRuntimeMessage` / `values` 不认识视频 sidecar；现有文本备份封闭 schema 也没有视频。不能将此叶子的完成理解为数据库或端到端接收已完成。

未联网查询供应商能力；未执行构建、JVM 测试或 Android 测试。

## v1 闭合格式

以下只演示元数据结构，不代表可读取的资产：

```json
{
  "version": 1,
  "owner": {"conversation":"c","message":"assistant-row","run":"r"},
  "inputs": [
    {
      "owner": {"conversation":"c","message":"user-segment","run":"r"},
      "references": [
        {
          "id":"12345678-1234-1234-1234-123456789abc",
          "mime":"video/mp4",
          "bytes":91235,
          "sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "duration_ms":12346,
          "width":1920,
          "height":1080
        }
      ]
    }
  ]
}
```

- root `owner` 绑定传入的**物理持久化 Message**；每个 input 的 `owner` 绑定 `MessageHistory.expand(listOf(message))` 中唯一同 conversation/message 的行。该行必须是 `user` 且 `protocolItems` 为空。所有层级的 run 必须与真实 Message/展开行完全相等。
- Plain user 可以直接作为物理 owner；有权威 segments 的 assistant 可以承载一个或多个 user 的 sidecar。不能把 assistant 自身当视频 user。重复 user segments/错误 history 身份先由真实 domain API 拒绝；codec 不复制另一套展开算法。
- `MessageVideoOwner` 本身没有 run 字段：run 从 Message 派生写入 wire，decode 必须核对，不能信任 wire 再构造一个虚假的 Message 来验证。物理行 message/run/conversation 任一变化都会使旧 wire 失效。
- 每一对象的字段集合精确闭合，无可选字段、无 null、无未知字段；只接受 version 整数 `1`。引用 owner 由 input owner 无损继承，编码前也逐项检查 `ref.owner == input.owner`。
- inputs 和 references 均保留原序，不按 UUID 排序。空 `inputs` 是显式无视频；有 input 却空 references 拒绝。相同 input owner 重复拒绝，同一 owner 中 UUID 重复拒绝（即使元数据不同）；不同 owner 可保留同一 UUID，符合 `prepare` 行为。不按 sha256 合并视频。
- 所有元数据逐字段保留；原视频与 preview 严格分离。没有草稿 ID、文件路径、URI、字节内容、授权 token 或 data URL 字段。
- 编码顺序稳定；解码允许合法 JSON 的空白、键顺序、Unicode 转义和 `/` 转义，重新 encode 会收敛为稳定输出。身份按解码后的**精确字符串**匹配，不 trim、不做 Unicode normalization、不做大小写转换、不解 URI。

## 严格边界与真实约束

实现不使用 JVM/Android 行为不同且可能宽松/隐式转换的 `JSONTokener.getLong` 等。局限于本 schema 的有界 JSON reader 同时检查 JSON 文法与类型：

- 整数仅接受 JSON 十进制整数词法和 `Long` 精确范围。小数（包括 `1.0`）、指数、引号数字、布尔值、null、NaN、Infinity、十六进制、前导 `+`/多余 `0` 全拒绝；width/height 在转 Int **之前**检查范围。负数/0 虽可能是整数，不能用于正数媒体字段。
- 包括转义后同名的重复 key 在所有层级拒绝；单引号、裸键、注释、尾逗号、分号、尾部非空白数据拒绝。不通过先 parse 再 stringify 抹去重复 key 或数值精度证据。
- 整个 wire 最多 **1,048,576 UTF-8 字节**，encode/decode 均检查。直接计数 Unicode 标量，不创建同尺寸 UTF-8 临时数组；未配对 UTF-16 surrogate（包括 `\u` 转义产生的）拒绝。限制是字节而非 String.length；合法多字节身份可 roundtrip。
- 最多 256 个 inputs、每个 owner 最多 8 个 references、合计最多 1024 references。reader 还设对象字段最多 8、任意数组最多 1024、深度最多 6、节点最多 16,000；每个字符串最多 200 UTF-16 单元 / 800 UTF-8 字节，遇到上限即停止。schema 层继续收紧字段/数组用途。
- owner 的 conversation/message/run 必须非空白、最多 200 UTF-16 单元、无前后空白、无控制字符、无 `/`、`\`、`:`，且不是 `.` / `..`。这刻意比旧 domain ID 的非空白检查更严；不符合条件的旧 ID 要明确拒绝，不能静默改名。由此绝对路径、Windows 路径、URI 和 data URL 不能冒充 owner。
- 引用 ID 必须满足既有小写 UUID regex；sha256 必须 64 位小写十六进制；MIME 必须精确属于既有 `VIDEO_MIMES`：mp4、quicktime、3gpp、3gpp2、webm、x-matroska。路径/URI/base64 无法塞入这些字段，额外 path/url 字段同样拒绝。
- bytes 为 `1..256 MiB`，同 owner 合计最多 `512 MiB`；durationMs 为 `1..86,400,000`（24h）；宽高正 Int，使用 Long 乘积保证像素不超过 `40,000,000`。这些是 `VideoAttachmentLimits` **支持的可配置硬上限**，不是默认值（默认 64 MiB/128 MiB/4 个/1h），也不是某次模型上传预算。codec 不拒绝原本用合法较高配置导入的资产，但不会提高当前 store 或模型预算。
- 无法仅从元数据验证容器真实性、SHA 与原文件相符或资产是否存在；必须由父读取时的 store 校验。保留 webm 等存储 MIME 不代表既有仅支持 MP4 的 Responses 上传协议接受它们；不得自动转码或降级封面。

## 失败码及日志

`MessageVideoCodecException : IllegalArgumentException` 只携带 `failure` 枚举，message 固定为 `Message video sidecar: <CODE>`，不附带 cause：

| code | 类别 |
| --- | --- |
| `TOO_LARGE` | wire/字符串/集合/解析预算，或 owner 总字节超限 |
| `INVALID_JSON` | 文法、重复 key、非整数数值、Long 溢出、破损 Unicode |
| `INVALID_FIELDS` | 缺失/多余字段或 JSON 类型错误 |
| `UNSUPPORTED_VERSION` | 合法整数但非 v1 |
| `INVALID_IDENTITY` | owner 标识不安全/不规范 |
| `INVALID_MESSAGE` | 物理 Message role 或真实 history 校验失败 |
| `OWNER_MISMATCH` | 物理绑定、run、唯一展开 user 或引用 owner 不匹配，含 protocol replay user |
| `DUPLICATE_OWNER` / `DUPLICATE_REFERENCE` | input owner / 同 owner 引用 UUID 重复 |
| `INVALID_REFERENCE` | 空引用列表、UUID/SHA/MIME/逐项媒体数值约束错误 |

多项错误不保证先报某一项；仅将码用于诊断/安全 UI，禁止将原始 encoded、Message 内容、引用、异常链或文件路径追加到日志。损坏数据不能 catch 后返回 emptyList，也不能改为 text-only 发送。用户可见提示应为通用安全描述。

## 父级数据库接线要求（本叶子未实施）

1. **单一权威存储位置。** 推荐新增 `messages.video_inputs` 非空 TEXT 列或等价 sidecar 表，随真实物理 Message 原子提交。迁移时，既有确实无视频的行可用该行 `encode(message, emptyList())` 产生绑定后的 v1 值；不要填裸 `[]` 并交给此 codec，也不要在读失败时补空。若选 nullable legacy 标记，必须明确区分“迁移前确认无视频”与“遗漏/损坏 sidecar”。不能根据当前 store 扫描猜测历史附件顺序。
2. **接收事务。** 先 prepare 原视频，再以真实 user/assistant history 身份编码；Message 和全部 sidecar 在既有接收事务中一起成功或回滚。提交结果未知时不得当失败擅自删已准备资产，应沿用父级恢复/fencing 机制。添加事务回滚、进程恢复、取消、重复回执测试。
3. **所有读取入口使用 SQLiteText 分块。** 父需先把新列/表加入 `SQLiteText.read` 的精确 allowlist，并对 sidecar 使用 1 MiB BLOB 长度预算（现有 helper 通用上限为 64 MiB；不能仅依赖 decode 的事后限制）。执行 `length(CAST(column AS BLOB))` 预检，按 `substr(CAST(column AS BLOB), start, 65536)` 拼字节，最后严格 UTF-8 解码，再调用 codec；同一读事务中读取 Message 和 sidecar，防止竞态换绑。不要在主列表、恢复、备份或 identical-save 分支偷偷使用大列 `Cursor.getString`，也不要逐块单独解码 UTF-8。游标主查询只取身份/标量，不把多个大列放进同一 CursorWindow。
4. **save 不清 sidecar。** 当前 Message 没有视频字段：普通 `save(message)`、`writeRuntimeMessage` 或 receipt 更新没有 sidecar 参数，必须保持原值，不能重新 encode(emptyList())，不能 `REPLACE` 造成列默认值重置。若 history 变动，应先 decode 旧值，再用新 Message 验证/重编码，任何保留引用失去唯一 user 归属即原子拒绝。需要独立的受授权显式删除/替换流程；无变化的幂等 save 与终态回执也必须检查保留绑定。
5. **全历史加载与去重。** codec 每次只验证一个物理 Message；父仍须先 `MessageHistory.validate/expand` 完整历史，处理 standalone supplement 与 assistant segment 的同一 user。当同一 user 的数据出现两个权威 sidecar 来源时拒绝或在明确一致性规则下合并，不能把两份直接传入 freeze，也不能遗漏 segment 里的视频。构建全展开历史输入时，不只取最后行；codec 无法发现父漏传了 sidecar。
6. **备份/恢复。** 当前文本备份没有视频能力。父在未完成版本化、有界、带完整元数据和原资产的备份/恢复前，发现待导出会话含视频应明确拒绝，不能输出“成功”但丢视频。新格式应保留 codec 字符串或经过此严格 codec 的值，不先经宽松 JSON 重写；纳入整体字节预算/完整性摘要，恢复验证身份、重复 owner、全部资产及元数据后再事务提交。只有 sidecar 没有原文件不是成功的视频备份。旧客户端不识别新版本须失败关闭。
7. **分支、编辑、压缩、归档。** 保留物理身份且保留全部 user segments 时，可验证后原样保留 wire。更换 conversation/run/message 或拆分物理 owner 时，旧 wire 按设计拒绝；必须由真实授权流程复制/重归属资产并用新 Message/owner 重新 encode，不能字符串替换 JSON 假冒归属。如果当前功能尚未支持无损保留视频，就显式拒绝该操作。编辑文字不意味着允许删附件；压缩不能把原视频变成文字摘要后静默清 sidecar。
8. **删除与授权。** 显式删除会话/用户输入的事务应移除正确 sidecar，原资产清理由父在引用/未知提交状态可判定后进行，不根据此 codec 的成功返回擅自删除。真实账户/会话/run 授权仍来自父的可信状态，不来自此字符串或备份里的 owner。

## 新增测试与执行状态

- JVM：`app/src/test/kotlin/app/daiyu/android/storage/MessageVideoInputCodecTest.kt`。覆盖全部元数据与顺序、所有存储 MIME、空 sidecar、真实 history 展开/绑定、重放拒绝、重复 owner/reference、闭合字段、每个数字字段的小数/指数/溢出/隐式转换、严格 JSON、存储硬上限及 owner 总容量、集合边界（1024 引用）、UTF-8 多字节/坏 surrogate、安全身份和无泄漏异常。
- Android：`app/src/androidTest/kotlin/app/daiyu/android/storage/MessageVideoInputCodecAndroidTest.kt`。覆盖 Android 上的同一严格文法/数值/绑定契约，以及真实 SQLite 的 `SQLiteText` 64 KiB 边界拆开 Unicode 后仍完整读取。测试仅用隔离内存表的**既有 allowlist 列名**演示 reader，不是将 sidecar 写进生产 `history_segments`，也不证明新列已接线。

测试仅已编写并静态检查，**未运行**。父级可按项目环境运行对应 JVM/Android 测试，并补完整 DB 迁移/原子接收/普通 save 保留/备份与分支拒绝/全历史加载集成测试；这些不由纯 codec 的测试替代。
