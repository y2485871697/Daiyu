# P32：豆包双工迁入（独立集成边界）

当前归属：用户明确确认本节语音业务实现为其 fork 后原创，保留代码与既有行为；历史输入只定位版本，不据以推定上游作者或要求重写。实际第三方网络库、引擎、JNI 主体及模型各按其真实来源与许可保留。以下阶段记录中的原创待核措辞不再适用于本项。

## 来源与范围

依据本工作树 `build/agent-input/SOURCE.json` 所声明的源版本
`f001fb82eb9941e5673970c656f5c4d5370fbf7f`，实际阅读了以下冻结源，而非根据协议名称重写 OpenAI 兼容层：

- `fork/app/src/main/kotlin/[historical-package-omitted]/agent/voice/DoubaoDuplexProtocol.kt`
  （清单 SHA-256 `b42833d994df797a926bb4e9013e2aed3e9baef5f3b76e59b5a35995660dafa4`）
- 同目录 `DoubaoDuplexSession.kt`
  （`244e72dc51897d9fe5ed814a77ea68363a7ba9f3b6d00e2f1fdd9eaa3a784ed8`）
- 同目录 `VoiceModeController.kt` 的 `startDuplex` / generation / stop 路径。
- 同目录 `DuplexInterruptionGate.kt`、`DuplexAsrHypothesis.kt`、`DuplexTextStream.kt`、
  `DoubaoRealtimeVoices.kt` 以及相应协议/文本/打断测试。

以上 hash 为输入清单中的值；本次没有运行 hash 计算、编译、测试或联网验证。
输入目录未修改、未复制到交付目录。生产改动只在
`app/src/main/kotlin/app/daiyu/android/voice/duplex/`；测试只在相应的 `src/test/.../voice/duplex/`。
没有修改 Main、ChatController、已有 gate 或其他语音实现。没有增加依赖。

## 保留及调整

- 固定豆包官方域名 WebSocket 路径，`X-Api-Key` + 随机 `X-Api-Connect-Id`，
  不混入旧 TTS app/access/resource 头，也不添加 OpenAI Authorization。
- `session.create` 保持原 JSON：model `1.2.6.1`、顶层
  `extension.asr.extra.enable_asr_twopass=true`、16k mono PCM16 输入、24k
  `pcm_s16le` 输出、显式 voice / instructions。默认音色仍是独立实时音色小何。
- 原控制器的 provider repository、Prefs、旧包、日志器与普通 TTS 依赖改成构造参数。
  `voice` 是调用方明确授权的实时/个人音色 ID；不会自动从普通 TTS 配置猜测或回退。
- 只在 `session.created` 后采集；输入事件仍是 `input_audio_buffer.append`。
  文本按四个原字段优先级读取；ASR 是整句替换，文本 delta 保留空格，done 是整句快照。
- speculative started / 空白 / 标点事件不清空正在说的话；有字母或数字的短命令
  （包括“停”）才确认一次打断。原有二遍识别标点防闪保留。
- Android 端继续使用 `VOICE_COMMUNICATION` 采集和 speech/media AudioTrack，
  对设备可用的 AEC/降噪效果做尽力启用。**没有保证消除所有回声，也没有用字符串相似度
  把用户重复助手的话误判为回声。**
- 原来队列清空 + pause/flush 扩展为 playback epoch：每次部分非阻塞 write 前校验 epoch，
  confirmed interruption / response.canceled / close 清空尾帧与硬件缓冲，防止旧 chunk 的
  未写完部分重新入声卡。旧 response_id 的后续包被丢弃。
- 输入事件、音频和未知扩展事件均不重发。`DuplexSendResult.UNKNOWN`、连接中断、
  连接超时输出 `DuplexPhase.UNKNOWN` 并关闭/排空，不自动重连、不向 chat 补交同一句。
  原冻结代码没有可照搬的 UNKNOWN 模型；这是本次显式增加的保守发送语义。
- **未捏造服务端取消/截断 API**：冻结协议确认的只有 session.close；打断是本地播放
  截断及后续旧响应过滤。正常退出尽力发送一次 session.close，UNKNOWN 路径连此事件也不发送。
- 默认构造参数直接引用 `ActivityVoiceAudio.gate`。没有生产用的新 gate。每个回调校验该
  lease；先撤销会话，再同步排空/release，最后交还 gate。释放失败由已有 gate 维持禁止准入。

## 父调用 API（准确签名）

```kotlin
class DoubaoDuplexConfig(
    val apiKey: String,
    val voice: String = DoubaoDuplexProtocol.DEFAULT_VOICE,
    val instructions: String = "你是代鱼，一个简洁、自然、友善的中文语音助手。",
)

class DoubaoDuplexSession(
    owner: app.daiyu.android.voice.VoiceOwner,
    config: DoubaoDuplexConfig,
    transport: DuplexTransport,
    audio: DuplexAudio,
    gate: app.daiyu.android.voice.AudioOwnerGate = ActivityVoiceAudio.gate,
) : AutoCloseable {
    val state: StateFlow<DuplexState>
    fun start(admission: () -> Boolean = { true })
    override fun close()
}
```

示意调用（不是已修改 Main 的声明）：

```kotlin
val call = DoubaoDuplexSession(
    owner = VoiceOwner(conversationId, runId),
    config = DoubaoDuplexConfig(apiKey = explicitKey, voice = explicitRealtimeVoice),
    transport = newHostDoubaoWebSocketTransport, // 必须为本次调用新建
    audio = AndroidDuplexAudio(),               // 必须为本次调用新建
    // 不传 gate 即使用 ActivityVoiceAudio.gate；不能 new AudioOwnerGate()
)
call.start { stillAdmitted.get() } // 准入判断不能反取宿主/注册表锁
// collect call.state，仅渲染当前 call/owner；内容只用于 UI，不写诊断日志
// 用户停止、切换会话、生命周期退出：call.close()
```

`start` 单次使用；需要再次通话必须由明确的新用户操作创建新 session，不能根据 UNKNOWN 自动重试。
初始化/关闭会进行音频设备操作与最多有界的 worker join，宿主宜在 IO 执行，不在 UI 帧中阻塞。
权限由宿主在调用前取得；本模块不弹权限、不修改全局 AudioManager mode/音量。

`DuplexState` 包含 `owner, phase, transcript, reply, reason`。phase 为
`IDLE, CONNECTING, LISTENING, THINKING, SPEAKING, COMPLETE, CANCELLED, FAILED, UNKNOWN`。
reason 是固定安全代码，不能当服务端原文；UNKNOWN 不代表请求没发出去。

### 必须实现的 transport 端口

当前基线没有 WebSocket 库，本次也未增加库。**本交付没有附带具体网络 WebSocket 引擎，
宿主必须注入真正的 TLS WebSocket transport 才能联网。** 不是把 HTTP SSE 冒充双工。

```kotlin
interface DuplexTransport {
    interface Listener {
        fun opened()
        fun message(json: String)
        fun failed()
        fun closed()
    }
    fun connect(request: DuplexRequest, listener: Listener)
    fun send(json: String): DuplexSendResult // ACCEPTED / REJECTED / UNKNOWN
    fun closeAndDrain()
}
```

- connect/send 不等待回调；listener 回调不能在 connect/send 栈内同步触发。
- 每条 WebSocket text 或 UTF-8 JSON binary 完整消息交给 message；fragment 由引擎拼接。
- 请求使用传入 endpoint/headers，校验证书，不附加旧 provider 头。不要记录密钥、PCM、文本。
- ACCEPTED 仅表示本地入队；写入结果不确定必须 UNKNOWN。禁止网络层自动重连和消息重播。
- closeAndDrain 必须幂等、撤销队列/网络 I/O、解除回调；允许从 listener 所在线程关闭自己，
  不能 join 自己或等待正在关闭自身的 callback。不能确认资源释放时抛错，不能谎报成功。
- 如宿主不能提供符合该契约的 transport，不能声称此功能已可进行线上通话。

`DuplexAudio` 也是公开可注入端口；已提供实际 Android 实现。自定义实现须满足接口的
异步 callback、epoch 清空部分帧、closeAndDrain 同步释放契约，而非只清一份应用队列。

## 测试源码与未验证项

`DoubaoDuplexFixtureTest` 使用合成 wire fixture + fake audio / 真实 AudioOwnerGate：覆盖真实
session.create 字段和认证、只启动一次、采集时机、PCM append、回声候选不截断、短命令
打断/旧响应丢弃、二遍 ASR、空格和 done 快照、UNKNOWN 不重发、共享 gate 抢占、旧回调
失效、排空失败保留 gate 禁止准入、错误脱敏。测试源码交由主代理统一运行。

没有运行编译/测试；没有实机路由/权限测试、线上服务测试或可听性证据。fixture 不证明
AudioTrack 的物理可听/真正离线，也不证明设备 AEC 效果。缺少 response_id 时，打断后的
late delta 不能可靠归属；实现保守丢弃直到明确的新 audio.started 边界（或新的已标识响应），
不能承诺服务器错误重排的无 ID 边界绝不夹带旧音频。服务端未提供的截断协议不在交付范围。
