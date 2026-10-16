# 本地 TTS 播放/停止接线

## 范围和来源

- 本模块基于本工作树可读的 `build/agent-input/SOURCE.json` 所声明候选 `98f72d00ef44a66abbbbad4a78f7becedfbfa7d3`，已读取其中列出的 `AndroidSpeechPlayer.kt`、`SpeechPlayback.kt`、`INTEGRATION.md`、`SpeechPlaybackControllerTest.kt`。此处说明输入清单的来源声明，不声称已独立用 Git 核验该提交。
- 保留候选的 Android 本地离线 TTS 播放、停止、异步初始化、owner 隔离和错误状态功能；在控制器内补充同步 StateFlow 观察者重入防护，保留原假引擎测试并新增专门回归测试。
- 这里只提供 P38 选定内容朗读/试听的播放后端，不声称实现 P32 语音会话/听写、完整音色设置页或全 app 接通。宿主应复用现有消息操作栏，不另造自动朗读入口。
- 仅调用 Android SDK，自研生命周期/owner 控制，不搬入供应商代码、远程 TTS、模型、音频或凭据。本任务不修改宿主、Manifest、Gradle 或 docs 唯一清单；宿主接线和平台可见性由父代理另行核查。

## 宿主 API

所有命令在主线程调用；回调也串行到主线程。`state` 是只读 `StateFlow<SpeechPlaybackState>`，宿主可用 `collectAsStateWithLifecycle()` 或 lifecycle-aware collector 作为状态回调。

```kotlin
val player = AndroidSpeechPlayer(applicationContext)
// 每次进入会话/切换会话生成新的不复用 token；不要把可复用 conversationId 当唯一代际。
val owner = java.util.UUID.randomUUID().toString()
player.bindOwner(owner)
// 可选预热，不会播放：player.initialize(owner)

// 仅用户点击指定消息“朗读”或指定文本“试听”时调用。
player.play(owner, requestId = messageId, text = selectedText, locale = java.util.Locale.CHINA)
player.stop(owner) // 用户停止、宿主 ON_STOP、取消/隐藏当前会话时
player.close() // 宿主持有作用域真正销毁/dispose；此实例随后不可复用
```

- 宿主自行接现有消息操作栏，不在列表渲染、历史载入、流式 token 更新或收到新消息时调用 `play`。
- 新 owner 的 `bindOwner` 会停止并关闭旧引擎；同 owner 重绑幂等。`play/stop/initialize` 收到旧 owner 时直接返回 false、不改当前状态。宿主切换会话后必须更新捕获在点击事件中的 owner。
- `play/initialize` 返回 true 仅表示命令被当前 owner 接受，**不是播放成功**；初始化、语言检查、合成仍可能失败，观察者也可同步取消已接受命令。空文本会同步返回 false。超长文本按 Android 单次输入上限报告 `TEXT_TOO_LONG`，不偷偷截断、不自动拆成一串朗读。
- 初始化期间只保留最后一条显式请求。主动停止、切 owner 或 close 会清空待播文本；不会恢复或自动重播。
- `requestId` 用于标记具体消息或预览，不传给系统引擎。engineGeneration + 每次播放唯一 token 隔离旧 init/进度回调；同一条消息重复播放也不会串状态。另有命令代际隔离同步重入：即便 owner、requestId 或引擎未变，也不能恢复被取消/替换的旧命令。
- 状态 `INITIALIZING/QUEUED/SPEAKING` 可显示“停止”；`COMPLETED/STOPPED` 恢复播放按钮；`UNAVAILABLE/ERROR` 展示适合用户的提示。预热时 INITIALIZING 的 requestId 为 null。所有错误是枚举和可选平台错误码，不包含引擎原始异常、正文或路径。
- `ENGINE_UNAVAILABLE/INITIALIZATION_FAILED`：设备没有可用引擎或引擎无法初始化；`LANGUAGE_UNSUPPORTED/LANGUAGE_DATA_MISSING/OFFLINE_VOICE_UNAVAILABLE`：不支持该语言、数据未安装、没有已安装离线音色；`PLAYBACK_FAILED/STOP_FAILED`：引擎命令/进度失败。下一次显式播放可重试，停止失败会关闭该引擎。

## 同步观察者和资源所有权

- StateFlow 的观察者可能在状态发布返回之前执行 `stop/close/bindOwner`，主线程串行本身不能防止这种重入。
- 发布 INITIALIZING 后重新检查命令/引擎代际，再调用工厂。工厂返回后再次检查：若创建期间已取消或关闭，则直接停止并 shutdown 尚未挂接的实例，不初始化、不遗留资源。
- 发布 QUEUED 后检查命令代际、引擎身份和 token，只有仍有效的请求可以调用 `speak`。SPEAKING 观察者触发停止/关闭/换 owner 后，旧 `speak` 的同步失败返回或异常不能覆盖较新的取消状态。
- 引擎调用返回后同样重新检查有效性；先清理旧命令再发布失败状态，避免失败观察者立即重试时被旧命令清空。
- close 幂等，停止/关闭先失效 token 与引擎身份，再触碰引擎；即使 stop 抛异常也仍尝试 shutdown。不因同步回调再次释放已移交或已关闭的实例。

## Android 平台边界

- 保留 applicationContext，不保留 Activity。初始化异步，监听器在成功后安装，构造期同步回调也先排队。
- 只选择同语言且 `isNetworkConnectionRequired == false`、未标记 NOT_INSTALLED/NETWORK_SYNTHESIS 的音色；不使用可能落到网络音色的 `setLanguage`，不触发安装页面。提交前核对 setVoice 的实际音色，并传 embedded-only hint。不接远程/付费 TTS，不含网络客户端或自动下载。
- 这是对 Android 引擎公开能力标记的约束，不是对第三方/厂商 TTS 进程的网络沙箱；不能保证虚报“离线”的系统引擎不联网。缺少离线音色时宁可显示不可用，不降级远程引擎。
- Android 11+ 的 TTS 服务发现通常需要宿主 Manifest 的 `<queries><intent><action android:name="android.intent.action.TTS_SERVICE" /></intent></queries>`。本任务不拥有 Manifest，未修改；请父代理核查并决定是否补齐。此模块不申请麦克风/存储权限，不修改 Gradle。
- 未主动申请 audio focus、后台服务、锁屏控制；宿主必须在退后台时 stop。没有 init 超时重试循环：系统不回调时保留 INITIALIZING，用户 stop 或宿主 close 仍可取消并释放。

## 验证与交接

- 保留 `app/src/test/kotlin/app/daiyu/android/voice/SpeechPlaybackControllerTest.kt`：显式触发、异步初始化/替换、停止、owner 切换、旧/重复回调、语言不可用、引擎错误/重试、长文本、关闭与资源释放。
- 新增同目录 `SpeechPlaybackReentrancyTest.kt`：使用真实 StateFlow + Unconfined 同步观察者及假引擎，覆盖 INITIALIZING/QUEUED/SPEAKING 的 stop/close/bindOwner、预热/异步/同步初始化、工厂创建期间取消后的资源释放（含 stop 抛异常）、同步 speak/init 返回失败或异常不得覆盖取消、同消息替换、owner 往返、失败观察者重试。不访问 TTS 服务/网络/录音。
- 本次子代理只做源码和测试静态复核，**没有执行编译、单测、仪器测试、真机验证、CI、Git 提交或 push**。父代理需统一编译并运行上述两个测试类；测试存在不代表已经通过。
- 真实设备引擎支持、离线音色标记、Manifest 可见性和宿主生命周期接线仍待父代理/后续设备验证。新增文件可能不在默认 tracked diff 中，请一并纳入最终变更。
