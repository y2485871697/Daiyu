# P32 B — 豆包账户音色管理叶子

当前归属：用户明确确认本节语音业务实现为其 fork 后原创，保留代码与既有行为；历史输入只定位版本，不据以推定上游作者或要求重写。实际第三方网络库、引擎、JNI 主体及模型各按其真实来源与许可保留。以下阶段记录中的原创待核措辞不再适用于本项。

## 来源、范围与已实现行为

迁移来源均为冻结 `build/agent-input/fork/app/src/main/kotlin/[historical-package-omitted]/agent/voice/doubao/`：
`PersonalVoices.kt`、`DoubaoVoiceCatalog.kt`、`VoiceProjectCatalog.kt`、`VoiceCatalogPreferences.kt`、`VoiceCatalogSecretStore.kt`、`PersonalVoicePreview.kt`，并参考对应冻结测试（VoiceManagement、PersonalVoiceSlot、VoiceProjectCatalog、VoiceCatalogSigningTransport、VoiceCatalogSecretStore、PersonalVoicePreview）。未获取新网页资料或声称供应商在线验证。

新增仅限 `voice/account/**`、独立 `VoiceAccountPanel.kt`、专属测试和本文档。未修改 ControllerVoiceRuntime、VoiceSettingsPanel、Main、播放器、providers、network 或 gate。不是占位：已有音色查询导入、AK/SK 项目查询、购买槽位批量导入、单次训练、手动状态查询、首次使用接受、本机移除、额度/模型/状态展示均有实现。所有网络行为从显式操作的二次确认进入；加载/重组/保存/选音色/样本选择器返回不联网。

相对冻结实现的安全适配：

* 实例化存储、无全局后台 scope；取消真实 Call，不自动轮询/重试/重训。
* `VoiceConfiguration.speech()` 传入账户层；`VoiceAccountIdentity.from` 使用与 `DoubaoSpeech.request` 相同的 `headers()` 覆盖语义取得实际 X-Api-Key，并调用现 `providers.DoubaoPersonalVoice.accountFingerprint`。不是 provider 标签、MiMo referenceOwner 或聊天凭据。
* 原 AES/GCM、随机 IV、AAD 绑定保留，扩为加密整个 AK/SK/project envelope，并按实际语音账户指纹隔离；无明文 AK/SK preferences。整个明文 envelope 最大 4096 UTF-8 字节，含 IV/tag 的密文最大 4124 字节，Base64 最大 5500 字符。不可读或超限时明确报错，不静默采用空密钥。
* AtomicFile 写入本机音色身份及 `uncertain=true` **先于**上传。超时、取消、断线、全部 HTTP 错误、未验证语义的业务错误及缺失业务成功证据后保留原身份。失败后只可通过显式 get_voice 成功响应的状态 1..4 解除未知；HTTP 4xx 本身不证明训练未执行，绝不因此放行重训。未知状态禁止该账户发起新训练，避免改生成自定义 ID 变相重训；未知记录不能先移除而丢失恢复身份。
* 空闲购买槽位保留 quota，不伪造为可合成音色；模型 4/5、status 2/4、首次 accepted 和所属账户共同限制输出。训练清除旧 accepted。
* removeLocal 只改本机索引；不云删除、不释放槽位、不退款。墓碑阻挡当前实例晚回调恢复已移除项。
* 原 MediaPlayer 试听所有权适配为强制注入父端口；本叶子不创建播放器、麦克风、第二 gate 或合成客户端。

## review1f77368f 定向修复（基线 2b8a904e 账户 leaf）

本次仅修改账户 `PersonalVoices`、`VoiceCatalogSecretStore`、其专属测试和本文档，不改 Main/runtime/player/其他 leaf。未读取实际凭据，未获取新网页资料，也未运行供应商请求；不以旧实现或 fixture 的错误码推定供应商非执行语义。

* **P1 / 训练结果未知**：移除“所有 4xx 都是确定拒绝”的判断。`408 + {}`、421、429 等 HTTP-only 错误必须保留持久化 `uncertain=true`；其他 HTTP 错误同样保守处理。原 `403 + {"code":45000030}` fixture 只证明返回了错误码，当前没有可引用的供应商“明确未执行”语义证据，因此它也不再解除 unknown，不增设猜测的拒绝码白名单。后续如要特判，必须先补可审查的非执行语义来源及专属测试。
* **重建恢复**：重建 store 不自动联网；同账户换新 custom ID 或另一槽位也不能训练，移除/接受/合成均不能绕过原未知身份。失败查询或成功业务响应但状态仍为 0 时继续保留 unknown。只有显式查询原 ID、成功业务码且状态 1..4 才恢复已知状态，首次 accepted 仍须用户确认。
* **凭据 envelope 上限**：固定 4 KiB 明文总预算用于 AK、SK、project 的短凭据 JSON，而非每字段 4 KiB；给通常仅数十至数百字符的凭据和 JSON/UTF-8 开销留余量，不宣称这是供应商协议上限。save 先检查字符数再检查 UTF-8 字节数，超限在取密钥/加密/写入前拒绝，原持久值不变。read 在 Base64 decode 前检查 5500 字符，再在取密钥/解密前检查完整 payload 为 29..4124 字节（12 字节 IV + 至少 1 字节明文 + 16 字节 tag）。解密输出因此最多 4096 字节；Base64 padding 使 4125 字节也可能编码成 5500 字符，故两级检查不可省。未改凭据 JSON codec、schema 或其他 leaf。
* **错误恢复**：超限或损坏的已存 envelope 抛相同脱敏读取错误，不自动清除或覆盖；仅显式保存/清除恢复。测试覆盖明文 4095/4096/4097 字节、多字节 UTF-8、过长字符串、有效最大密文/Base64、Base64 超限一字符、密文超限一字节但 Base64 未超限，以及短 payload；测试密钥回调计数验证拒绝发生于 key access/加解密之前。

以上为源码实现与静态检查结论，未执行测试/编译，不宣称测试通过或供应商在线验收。

## 父挂载稳定 API（均为同 app module 的 internal）

```kotlin
// 父持有同一实例，每个文件目录只建一份。构造/面板本机 load 均不联网。
val accounts = VoiceAccountResources.forContext(context)
// 可选第二参数 AccountHttpTransport 用于父注入/测试；不是 synthesis transport。

VoiceAccountPanel(
    frozenConfiguration = frozenSavedConfiguration, // runtime.snapshot()，不是草稿
    resources = accounts,
    previewPort = parentAccountPreviewPort,
    isCurrent = { runtime.current(it) && pageIsCurrentAndForeground() },
    onSelect = { frozen, personal ->
        // 只有父决定保存；先重新验证 current。叶子不修改 shared runtime。
        // runtime.save(frozen.copy(voice = personal.id, model = "seed-tts-2.0"))
    },
    onBack = { /* 父导航 */ },
)
```

`onSelect: (VoiceConfiguration, providers.DoubaoPersonalVoice) -> Unit` 是稳定选择回调；可能要通过父协程调用 suspend save。个人声音须在线 `seed-tts`（不能 `seed-audio` create）。配置保存生成新 revision 后应关闭/重新打开面板。

父冻结播放器/现合成客户端的第四参数：

```kotlin
val cfg = frozen.configuration
val personal: DoubaoPersonalVoice? = accounts.voices.selected(cfg.voice, cfg.speech())
// built-in voice 返回 null；个人 voice 缺失/未接受/状态或账户不符则失败，不回退。
client.synthesize(cfg.speech(), text, cfg.voice, personal)
// 或现父冻结播放器已有第四参数接口传 personal，不修改 provider 实现。
```

如果父在面板外需查询选择，先在 IO 调用 `accounts.voices.load()`；失败应阻止个人声音，而非返回可用默认值。selected/find 不联网；不要缓存它跨账户配置 revision 使用。普通内置声音也可以走 selected，返回 null。

### 试听端口给另行实现的 personal host

```kotlin
fun interface VoiceAccountPreviewPort {
    fun play(request: AccountPreviewRequest, listener: Listener): Lease
    fun interface Lease { fun stop() }
    interface Listener { fun started(); fun stopped(); fun failed() }
}
```

request 包含 `configuration: SpeechProviderConfig`、现 `providers.DoubaoPersonalVoice`、`demoUrl`。必须在父已有 gate 内完成准入、远程示例读取和输出；若父选择现合成试听，也应使用现 provider 路径与第四参数，而非另建合成网路。`started` 表示实际开始播放，不是收到字节；`stopped`/`failed` 为终态。父负责有界读取、取消、音频尾部 drain 与 gate 租约释放。`Lease.stop` 必须同时停止准备和播放，且清理失败不得伪报释放。回调与本叶子 preview 方法主线程调用。父需额外按当前配置、前台、页面准入拒绝过期请求；面板已在离页、切账户、ON_PAUSE/ON_STOP 时 stop。

PersonalVoicePreview 保留 generation 防旧回调、可停止 loading、同步拒绝后的 lease 释放；保留 `drainDelayMs` 原有界策略供父参考，但不自行持有第二音频输出。父应不依赖叶子忽略回调作为音频撤销手段，必须真正撤销旧 lease。

## 账户 HTTP 与现网络复用

`OkHttpAccountTransport` 复用现 `voice.network.OkHttpVoiceTransport.client` 的 connectionPool、dispatcher、TLS、超时、cookie/auth/cache/redirect/no-retry 设置。没有新的 `OkHttpClient.Builder`、独立连接池或 synth transport。账户只做业务 JSON、签名及有界读适配；允许固定三域名 openspeech/open.volcengineapi/iam.volcengineapi，响应最大 2 MiB；get_voice/voice_clone 最大 1 MiB。样本读取最大 10 MiB，仅用户确认后读取并上传；样本字节 finally 清零。

POST body 包装 `isOneShot=true`，不改签名的 UTF-8 bytes、Content-Type 或 Host。由于现 JSON-only transport 不能表达 IAM GET/签名 Host，账户使用现客户端的派生 adapter（共享 pool/dispatcher，未修改现客户端），唯一 network interceptor 将单次请求 408/421/503 暂时变为 599 屏蔽 OkHttp 对 GET 的透明 follow-up，再恢复原业务 HTTP 状态。派生适配仅为签名控制面/管理请求服务，不重做合成网络。

项目 IAM GET 按原冻结协议 `ListProjects/2021-08-01`；槽位 POST 按 `BatchListMegaTTSTrainStatus/2025-05-21`，Unknown/Training/Success/Active 每状态最多 20 页，每页 100；不完整项目页/超限直接报错，不用半列表冒充完整结果。

## 专属测试源码与未验证事项

`app/src/test/kotlin/app/daiyu/android/voice/account/`：

* PersonalVoicesTest：实际认证覆盖、输出消费、身份先写、取消/重启恢复、未知只查、不合成未接受音色、账户隔离、空槽 quota、模型/状态、removeLocal 晚回调、索引损坏；408+{}、421/429 和未验证 403/45000030 等错误后持久化 unknown、重建阻止新 ID/槽位训练及移除、失败/未知查询不放行、仅查询原 ID 确认恢复。
* VoiceCatalogTest：保留冻结独立 IAM 签名向量、UTF-8 签名 body、项目权限/分页完整性/上限、槽位查询、脱敏错误。
* VoiceCatalogSecretStoreTest：整个 envelope 加密、随机 IV、重建读取、AAD 跨账户复制拒绝、损坏显式恢复、清除隔离；整个 plaintext/encrypted/Base64 envelope 固定上限、UTF-8 字节边界、解码/解密前拒绝与超限保存不覆盖。
* PersonalVoicePreviewTest：强制注入、loading/playing stop、旧回调、同步 gate 拒绝、安全 URL、尾部时限。
* AccountSamplePickerTest：STARTED 收到结果等待 RESUMED、单次消费、账户 revision 变化、晚回调和取消。
* AccountHttpTransportTest：复用现 pool/dispatcher、安全配置、one-shot POST、签名 GET、GET 错误重试屏障、字节界限、取消真实 Call、晚响应关闭、错误脱敏。

按任务要求**未运行编译、测试、Shell 或供应商请求**；测试仅已提供源码，最终由父统一批次验证，不能声称通过。未创建分支、未 push。

实际局限：尚需父挂载页面、保存回调及另一个 personal host 接通唯一 gate，叶子本身不会自动挂主界面；无云删除/购买/退款；无服务端 live 权限或签名验收；控制面项目权限不代表复刻权限；不迁移旧应用 namespace 的实际用户私有文件/旧 Keystore alias；无法解密凭据必须明确保存/清除，损坏音色索引不自动覆盖；未知训练可能长期需要在供应商控制台处理，本叶子保守阻止绕过未知身份重训。录音格式采用原 MIME 白名单，未增加声纹验证、转码或时长算法。Android Keystore 生产可用性需设备验证；文件和 preferences 位于 app 私有目录，没有新增备份/导出入口，整应用备份策略仍由父现策略决定。
