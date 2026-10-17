# P32 — 账户音色试听父 adapter（待父集成 / 未执行验证）

## 边界、来源与实际请求路径

任务基线声明为 `96a755e`、冻结账户叶子为 `2b8a904e`。本任务工具不提供 Git HEAD/提交操作，不能独立证明当前 HEAD 的 SHA；这里记录的是任务给定基线，而非伪造 Git 核验结果。已读 `docs/重构清单.md` 的 P32/P38、唯一进度清单和并行范围约束，以及 `docs/P32-voice-account.md`。

仅新增：

- `app/src/main/kotlin/app/daiyu/android/ActivityVoiceAccountPreviewHost.kt`
- `app/src/test/kotlin/app/daiyu/android/ActivityVoiceAccountPreviewHostTest.kt`
- `app/src/androidTest/kotlin/app/daiyu/android/ActivityVoiceAccountPreviewHostAndroidTest.kt`
- 本文档。

不修改账户叶子、MainActivity、ControllerVoiceRuntime、RemoteVoiceHostPlayer、VoiceSettingsPanel、provider 或 network。没有浏览旧 Eta、实际凭据或外网。实现依据为当前树的 `VoiceHostPlayer`/`VoiceHost`（MainActivity 内）、`ProductionSpeechOutput`、`RemoteVoiceHostPlayer`、`VoiceSession.kt`、`VoiceProviderApi.kt`、`DoubaoSpeech.kt`、`OkHttpVoiceTransport.kt` 与冻结账户端口。没有新增依赖、Manifest 或最低 API 要求，使用的 Android/Java API 均可在 API26 使用；这不是 API26 实测结论。

**本 adapter 不下载 request.demoUrl。** 它使用现有 `SpeechSynthesisClient(OkHttpVoiceTransport(...)).synthesize(configuration, PREVIEW_TEXT, voice.id, voice)`，明确传入第四个 `providers.DoubaoPersonalVoice`，文本固定为：

> 你好，这是当前账户音色的试听。

实际通过现 `DoubaoSpeech` 在线个人音色路径：`https://openspeech.bytedance.com/api/v3/tts/unidirectional`、`seed-icl-2.0`，不是任意 CDN 请求，不上传聊天内容、录音样本或 AK/SK 管理凭据。有效 API Key 遵循现 case-insensitive customHeaders 覆盖语义。现合成解码器/8 MiB 有界读取、现共享 OkHttp 客户端/连接池、one-shot/no-retry/禁止重定向策略均复用，没有第二合成网络。AccountPreviewCalls 只是现 Call.Factory 的取消及回调完成跟踪器，不构建客户端、不重写请求/解码器。

**每次显式试听可能产生供应商合成费用/消耗额度；取消不承诺免计费，失败或未知也不会自动重试。** demoUrl 仅保留端口兼容，不读取、不记日志。冻结叶子仍要求合法 HTTPS demo 才允许点击，即使本 adapter 不使用它；没有 demo 的声音仍不能从该叶子试听。saved model 含 seed-audio 时拒绝，不自动切换模型、不偷偷保存配置；需父引导用户保存 seed-tts 在线配置后重新进入。

### 冻结文案与计费同意的集成阻碍

账户叶子现确认文案是“读取此音色的云端示范音频…”。它被冻结，本任务不改动。**父不能把这个确认当作新合成计费授权而无提示直接上线。** 父挂载时必须在父页面/包装端口明确展示上述固定文本、实际合成路径与可能收费，并取得显式授权；admission 应包含该授权有效性。若父无法在不改冻结叶子的前提下提供真实授权，则暂不挂载此生产 adapter，不能宣称账户试听业务已完成。本文档说明并不替代面向最终用户的真实计费提示。

## 精确父挂载 API

所有 API 都在同 app module 内为 `internal`，公开方法/端口回调主线程调用，scope 必须使用主线程 dispatcher。一个实例绑定一个已保存配置 revision 和一个页面 incarnation；即便 A→B→A 配置内容相同，也必须通过父 revision/page token 失效旧实例，不能只比较字符串。构造、admissionChanged、页面加载、恢复前台都不建播放器/不联网。

```kotlin
val frozen = runtime.snapshot() // 保存后的不可变 VoiceConfiguration，不是草稿
val speech = frozen.speech()
val pageToken = currentPageToken
// 构造前无需请求网络；accounts.voices.load() 是叶子现有本机加载路径。
val host = ActivityVoiceAccountPreviewHost.forContext(context, mainScope, speech) { request ->
    runtime.current(frozen) &&
        currentPageToken === pageToken && accountPageVisible && activityResumed &&
        parentSynthesisPreviewConsentIsValid &&
        runCatching {
            // selected 是本机读取，不联网；防已删除、撤销 accepted 或训练状态变化。
            val selected = accounts.voices.selected(request.voice.id, request.configuration)
            selected != null && selected.account == request.voice.account &&
                selected.status == request.voice.status && selected.models == request.voice.models &&
                selected.accepted == request.voice.accepted
        }.getOrDefault(false)
}
// 在上文父计费提示/授权接通后，再作为 VoiceAccountPanel(previewPort = host, ...) 传入。
```

父还应复用相同 `runtime.current(frozen) && page/foreground` 规则作为叶子的 `isCurrent`。host 自行按保存的所有 SpeechProviderConfig 字段（engine/baseUrl/apiKey/model/providerId/customHeaders）精确匹配 request，再按实际认证 Key 检查个人音色所属账户、accepted、status 2/4、model 4/5、个人 ID 和在线模型。父不得把 admission 永久设为 true，不能仅检查 provider 标签或 fingerprint。

**必须主动撤销**：配置 revision/账户变化、导航离页、页面 token 变化、ON_PAUSE/ON_STOP/后台、同意失效、本机声音撤销等发生时，先更新父准入状态，然后在主线程立即调用 `host.admissionChanged()`；离页/销毁调用 `host.close()`。不能只依赖“下一次网络完成时再检查”。`host.stop()` 停止当前连接或播放；返回的 `VoiceAccountPreviewPort.Lease.stop()` 只作用于自己的请求。close 永久禁止该 host 再次 play；后台恢复不自动播放，应由父创建/挂载适当新实例。host 不拥有父 scope，close 不取消整个父 scope。

如父已有支持第四参数、并具有真实同步严格 close/drain 的 player，可优先注入：

```kotlin
val host = ActivityVoiceAccountPreviewHost(
    scope = mainScope,
    configuration = speech,
    admission = { request -> parentAdmissionForFrozenPageAndAccount(request) },
    factory = { request -> parentFreshPersonalVoiceHostPlayer(request) },
)
```

factory 仅在 gate 获准且旧 owner 严格清理完毕后运行。它返回全新、无自有 gate 的 `VoiceHostPlayer`，必须使用传入 request 的个人音色第四参数；不能直接用忽略该参数的现 RemoteVoiceHostPlayer 替代。host 固定传 PREVIEW_TEXT。factory/bind 抛异常、play 返回 false 也执行 strict close。生产构造不传 gate，默认就是唯一 `ActivityVoiceAudio.gate`；gate 参数只用于 JVM 隔离故障测试，**不能在父生产接线中创建或传入另一 gate**。

## 所有权、真实开始和严格结束

- 每次点击独立 VoiceOwner、playerOwner、requestId。collector 同时检查实例、gate lease、owner、requestId 和父 admission。旧 lease.stop、旧 flow 事件和旧延迟任务不会停止/启动新试听。
- `started()` 只来自匹配的真实 `SpeechPhase.SPEAKING`。默认 player 仅在 `MediaPlayer.start()` 返回后发布 SPEAKING。factory/Boolean/QUEUED/网络返回字节不构成 started。StateFlow 可能合并极短 SPEAKING，因此不会为了“显示成功”伪造回调；这也不证明扬声器物理可闻。
- 连接期 worker 使用独立 IO dispatcher，主线程严格取消等待不需要该 worker 回主线程完成。已有 HTTP transport 取消真实 Call；额外跟踪其 onResponse/onFailure 的 finally，不能把协程取消当作真实 HTTP callback drain。
- stop/preempt/close 先 fence 请求及 collector/tail，再 cancel worker/Call，等待 HTTP 回调和 worker，取消主线程 delivery，reset/release MediaPlayer、删除本次临时文件。HTTP+worker 共享 2 秒严格等待预算；没有无限 main join。平台 reset/release 自身是同步系统调用，不能承诺故障设备的绝对执行上界。
- 自然 COMPLETED 后保留 gate 150ms 有界尾部，再走相同严格 teardown；期间可 stop/preempt，延迟不阻止新 owner 的同步撤销。不是根据未知时长臆测已播放。强制停止不等待自然音频尾部，直接 reset/release；没有硬件扬声器缓冲清零的物理保证。
- 只有 strict close 成功，gate 才允许新 owner。连接/worker 等待超时、reset/release/文件清理异常都会抛失败到现 gate 的 releaseAfter/cancel；它保持 releaseFailed 禁止后续所有 owner，跨 Activity 重建也不重置。host 不调用 bare release、不用 best-effort close 冒充释放。失败只给 failed，不能给 stopped。默认 player 严格清理失败后再次 close 也不能伪报成功。
- terminal 回调异步等到 gate release barrier 返回之后才发布；新 play 后旧 terminal 被 serial 屏蔽。父不要在清理完成前取消回调 scope，否则 UI 可能收不到 terminal（但清理仍已同步执行）。

## 提供的验证源码 / 未运行

按集中策略，**未运行编译、测试、Shell、供应商请求或设备操作**。源码审阅不是通过证据；以下均待父统一验证：

- JVM `ActivityVoiceAccountPreviewHostTest`：无自动构造/联网；foreground/page/revision admission；全部配置与实际认证覆盖账户匹配；只有真实匹配 SPEAKING；连接期及播放期 stop；旧 lease/late flow 隔离；父主动撤销；共享 gate 撤销顺序；自然尾部与抢占；bind/play 拒绝严格清理；close/drain 失败后 gate 永久拒绝下一 owner；HTTP continuation 取消不足以证明 callback 已 drain；晚 enqueue 禁止。
- Android `ActivityVoiceAccountPreviewHostAndroidTest`：现 InstrumentationTestRunner/InstrumentationTestCase，无新增 runner 依赖；fake Call.Factory 返回本机生成的一秒静音 WAV 包装 JSON，走现第四参数/个人 resource/固定文本合成请求、真实 Android MediaPlayer prepare/start/completion/cache 清理；另测连接期真实 Call.cancel/回调 drain 和同一 singleton 重新准入。无真实 HTTP/凭据/费用。设备 codec/输出、回调调度和 API26 行为尚未验证。

父集中验证建议目标：JVM 类 `app.daiyu.android.ActivityVoiceAccountPreviewHostTest`；Android 类 `app.daiyu.android.ActivityVoiceAccountPreviewHostAndroidTest`（API26 和当前目标 API），另跑现 gate/VoiceHost/account preview 回归。不要把此建议记成已执行命令或成功报告。

交付为非空工作树源码/docs diff，工具无 Git 提交能力，提交 SHA/patch artifact 由主代理集成时生成和核验。本任务不编辑并行共享的重构清单；请父在同次集成按 P32/P38 记录“父 adapter 与测试源码就绪，待挂载/计费提示/集中验证”，不能标整个语音业务完成。
