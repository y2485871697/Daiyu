# P32：旧语音供应商协议迁入（独立、待父层接线）

## 来源与范围

本实现不是“全原创重写”，也不把旧项目归因为某个未经证实的 RikkaHub 固定版本。
它是**旧 `f001fb82eb9941e5673970c656f5c4d5370fbf7f` 授权冻结源码迁入／混合保留**：
保留真实供应商 wire builder/decoder 和 MiMo 本地声音语义；新写配置、transport 边界及少量防御校验。

读取依据：工作树内 `build/agent-input/SOURCE.json`、`frozen/snapshot-manifest.json`，以及下列
`frozen/app/src/main/kotlin/io/github/mangi/eta/agent/voice/` 文件。
下列 SHA-256 是 SOURCE.json **记录的值**（本次未通过 shell 重新计算）：

|冻结源相对 voice 路径|记录 SHA-256|新位置（`app/src/main/kotlin/app/daiyu/android/voice/` 下）|
|---|---|---|
|`doubao/DoubaoAsrProtocol.kt`|`6cd71453fef9190a8431307d2781216f14d963c2c3a891c3f67c6a8dc6e065fb`|`providers/DoubaoAsrProtocol.kt`|
|`tts/DoubaoSpeech.kt`|`69ea7940e12b6e3f528e67b3d467406505a81f85ef64351622009251bce0874e`|`providers/DoubaoSpeech.kt`|
|`tts/SpeechProtocols.kt`|`fea86a5637261c11754d13e5a1c950a2f3d4de69fb82a77529ec9254cfd25059`|`providers/SpeechProtocols.kt`|
|`mimo/MimoPersonalVoices.kt`|`368771574870f4699e28535233ee5631c68c9ec7b16f79b4671add126acaeb20`|`personal/MimoPersonalVoices.kt`|

配套阅读／部分保留：同一冻结树的 `tts/CloudSpeechSynthesizer.kt`（豆包 HTTP response 解码、
响应大小上限）、`tts/SpeechEngine.kt`（引擎枚举）、`doubao/DoubaoAsrSession.kt`（握手头）、
`doubao/PersonalVoices.kt`（账户指纹、ready/model/accepted 判断）。
专属测试参考 `fork/.../voice/tts/{CloudSpeechSynthesizerTest,DoubaoSpeechTest}.kt`、
`fork/.../voice/doubao/DoubaoAsrProtocolTest.kt`、`fork/.../voice/mimo/MimoCloneTest.kt`，
并补充新注入边界、错误和持久化测试。测试并非供应商在线可用性证明。

旧文件无逐文件版权头可供迁入；保留本记录和仓库已有 LICENSE／第三方通知，不抹除旧项目、
原项目和贡献者的适用权利。`THIRD_PARTY/rikkahub/NOTICE.md` 中另一项 Responses 复用的
固定提交不是这四个语音文件的来源证明。本次没有访问工作树外或网页。

没有复制完整输入快照、SecretStore、旧 AgentModelClient、ProviderSetting、全局 settings、
HTTP 单例、旧日志器、麦克风循环、UI 或播放器。没有将 `build/agent-input` 注册进 sourceSet，
也未改该目录、Main、ChatController、AndroidSpeechPlayer、共享 gate、Gradle。

## 保留的真实协议

* **豆包 ASR**：`wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async`；显式
  `X-Api-Key` / `X-Api-Resource-Id` / request ID。四种原有 seed/bigasr resource；
  binary v1 JSON config、16kHz/16bit/mono PCM、末包 `0x22`；response 序号、gzip、
  full hypothesis、最终包和 definite utterance 分别解码。补齐头／序号截断检查和 1 MiB 解压上限。
* **豆包 TTS**：seed-audio `/api/v3/tts/create` 的 `text_prompt/references/audio_config`；
  seed-tts `/unidirectional` 的 `user/req_params/audio_params` 和 resource 选择。
  非 OpenAI `/audio/speech`；保留 MP3/WAV/Ogg/PCM 包装以及相邻 JSON、SSE base64 解码。
  个人豆包 speaker 必须有同账户、ready(2/4)、model(4/5)、accepted 的明确凭据，使用 `seed-icl-2.0`；
  本任务未迁入训练／控制面 catalog 网络服务或其存储。
* **SpeechProtocols**：完整保留 OPENAI/GROQ/COSYVOICE/MOSS、STEP、MIMO、MINIMAX、
  QWEN、XAI、GEMINI、ELEVENLABS、FISH 各自请求字段、auth、响应 decoder。
  包括 Step camelCase、MiniMax hex SSE、DashScope SSE 头、Gemini inlineData PCM、
  ElevenLabs xi-api-key、Fish model header；不把这些供应商伪装成通用 demo。
* **MiMo**：普通合成用 assistant message、`audio.format=pcm16`、`stream=true`、
  api-key + bearer；SSE `choices[0].delta.audio.data` 按事件拼接后加 24kHz WAV 头。
  个人声音使用固定 `mimo-v2.5-tts-voiceclone`、空 user + assistant 正文、data URI、
  `format=wav/stream=false`；从 `choices[0].message.audio.data` 解码且不再加 WAV 头。
* **个人声音**：app-private `.audio` + AtomicFile index；providerId 归属、UUID 本地 ID、
  名称 trim/80 字、真实媒体时长、MP3/WAV sniff、7,500,000 bytes / 10,000,000 base64
  上限、加载失败可重试且禁止导入覆盖损坏索引、删除文件和记录、reference 重新验证。
  改为一目录一实例，保留 StateFlow；不保存密钥。默认时长读取器仍为 MediaMetadataRetriever。

与旧层区别：明确传入 SpeechEngine，不从聊天配置／URL 猜测；不迁入 extraModels/catalog UI。
原 ProviderUrls/ProviderRequestHeaders 不在四文件冻结定义中，也未搬入其混合依赖：使用本地显式
HTTPS URL 拼接、无 userinfo/fragment 的校验和大小写不敏感 customHeaders 合并。
父层应传包含 API 前缀的 baseUrl：OpenAI/MiMo `.../v1`、Gemini `.../v1beta`；Step 自动补 `/v1`，
ElevenLabs/Fish 传 origin（它们的 builder 带 `/v1/...`）。不替父层猜测 endpoint。
固定豆包 URL 和旧 DashScope 官方路由仍保留。customHeaders 可明确替换 auth，但不允许 Host、
Content-Length 等传输头；父层不能混入聊天或另一供应商的认证头。

SSE 是**整段 HTTP body 的真实事件解码**，不是增量播放接口；与旧层一样先取完再返回音频。
新增解码大小界限、明确业务 error 不播放部分结果、`[DONE]` 后停止、路径 component 转义和
无效 HTML/JSON 音频响应拒绝。HTTP transport 的原始异常／正文不进入合成对外错误。

## 父层可调用 API（均为 public）

包 `app.daiyu.android.voice.providers`：

```kotlin
SpeechProviderConfig(engine, baseUrl, apiKey, model, providerId, customHeaders = emptyMap())
SpeechSynthesisClient(transport: VoiceHttpTransport, personalVoices: PersonalVoiceReference? = null)
    .synthesize(config, text, voice, doubaoPersonalVoice: DoubaoPersonalVoice? = null): ByteArray // suspend

SpeechProtocols.request(config, text, voice): VoiceHttpRequest
SpeechProtocols.decode(engine, contentType, bytes): ByteArray
SpeechProtocols.mimoClone(config, text, reference): VoiceHttpRequest
SpeechProtocols.decodeMimoClone(bytes): ByteArray
DoubaoSpeech.request(config, text, speaker, personal = null): VoiceHttpRequest
DoubaoSpeech.decodeResponse(contentType, bytes): ByteArray

DoubaoAsrConfig(apiKey, resource, requestId = UUID.randomUUID().toString())
DoubaoAsrProtocol.open(config, transport: VoiceSocketTransport): DoubaoAsrConnection // suspend
connection.sendPcm(pcm16Mono16k, last = false) // suspend
connection.receive(): DoubaoAsrProtocol.Result? // suspend, null means socket EOF
connection.close()
```

`SpeechProtocols` 直接 builder/decoder 用于父层已有自定义调度的场景；推荐通过 client 获取
校验／错误隔离。返回 ByteArray 是音频（含容器头或原编码），不是“已经播放”的事件。

包 `app.daiyu.android.voice.personal`：

```kotlin
val voices = MimoPersonalVoices.forContext(applicationContext) // 一目录仅一个活动实例
voices.load() // IO 线程，损坏索引会抛错，不吞错变成空列表
voices.state // StateFlow<List<Voice>>
voices.import(context, uri, name, providerId) // suspend
voices.importSample(bytes, name, providerId) // suspend, 生产默认仍检查真实时长
voices.reference(localId, providerId) // blocking IO，通常由 client 在 IO dispatcher 调用
voices.remove(localId) // blocking IO
MimoPersonalVoices.supports(config, speechEndpointAllowed = true)
```

`providerId` 必须是父层稳定账户／提供商身份，不是可随意复用的 UI 标签。更换账户时父层需
更换 ID 或重新明确导入。仅因 URL 相同不能授权向新账户发送私有录音。录音／索引属于敏感
用户资料，父层负责备份排除、保留期限、用户确认和安全目录；不要把 data URI 写入日志。
本层不包含跨进程文件锁：多个实例同时写同一目录不受支持。与旧层一致，文件删除成功而
后续 index 写入失败时会报错，可能留下指向已删文件的记录；之后 reference 必须失败，不能
回落其他声音。索引损坏由父层提示／恢复，不自动清空。

## 最小接线步骤（本次未修改父层）

1. 父层只从明确的**语音**账户配置构造 `SpeechProviderConfig`，不使用当前聊天模型／systemPrompt。
   取得凭据的动作留在父层；配置和 request 的 toString 已 redacted，但不要记录其字段。
2. 实现 `VoiceHttpTransport.execute(request, maxResponseBytes)`：POST UTF-8 JSON，使用 request
   URL 和 headers；在读取过程中累计检查 max（含无 Content-Length 的 SSE）；有限连接／读取／
   总超时；取消协程必须取消真实 call 并关闭流；禁止跨 origin 重定向／自动重试付费请求。
   transport 不得缓存／打印 body、auth、私有录音或原始供应商错误。当前没有默认 HTTP 单例，
   因现有 app 无 OkHttp 依赖且此任务禁止改 Gradle；父层用自身网络栈适配即可，不需引入旧项目。
3. 若启用个人声音，创建上述单一 store（IO load），并在用户明确选中、确认上传参考录音后
   将它注入 `SpeechSynthesisClient(transport, voices)`。调用
   `client.synthesize(config, speakableText, selectedVoiceId)`；本地 ID 自动走真实 clone 分支。
   普通声音 ID 必须来自相应供应商目录，不会补一个“万能 voice”。
4. 父层在自身 owner/epoch/cancellation 保护之内消费音频，再交给已有受控播放端。
   **不得**把 HTTP 200、合成完成、缓存完成映射为 shared gate 的 started；实际播放证据继续由
   父层播放设备回调提供。此迁入没有绕过或改写语音安全 gate。
5. ASR 的 `VoiceSocketTransport.connect(request, maxFrameBytes)` 应握手成功后返回 socket；
   限制入站 binary frame，拒绝 text frame，send/receive 取消要取消真实 socket；close 幂等不抛错。
   父层先取得麦克风权限，再 `open`（自动发送 config），把 PCM16/16k/mono 送入 sendPcm，末包
   `last=true`（允许空末包）；**替换**显示 `Result.text` 而非累加，`utteranceDone` 和 `final` 区分。
   EOF 之前没有 final 由父层判为不完整；父层设置收音/等待 final 的超时，并在 finally close。
   此模块未创建 AudioRecord、没有静默开启麦克风，也不改变现有输入入口。

## 验证状态／父层统一验证清单

本次只写源文件和进行静态阅读，按任务要求**没有编译、没有执行测试、没有供应商在线调用**。
新专属测试：

* `app/src/test/kotlin/app/daiyu/android/voice/providers/SpeechProtocolsTest.kt`
* `app/src/test/kotlin/app/daiyu/android/voice/providers/DoubaoProtocolsTest.kt`
* `app/src/test/kotlin/app/daiyu/android/voice/personal/MimoPersonalVoicesTest.kt`

覆盖各供应商真实字段／URL／认证头，MiMo 多行 CRLF SSE 与 clone WAV，MiniMax/Qwen/Gemini，
豆包 JSON/音频/ASR gzip/截断/末包、同账户与 consent、transport cancellation、个人索引损坏／
重载／归属／删除／时长／大小。请父层统一运行这些 host tests 和现有 voice gate 回归；
Robolectric 用于 AtomicFile/媒体依赖，transport 的真实取消和网络上限仍需父适配器契约测试。
非本任务完成项：UI 选择器接线、网络 adapter、设备音频播放、真机录音、供应商账号权限／额度验证。
