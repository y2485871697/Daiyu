# P32 voice/network：真实网络边界

## 交付范围 / 宿主契约

新增 `app.daiyu.android.voice.network.VoiceNetworkAdapters`：

```kotlin
fun http(): VoiceHttpTransport
fun asr(): VoiceSocketTransport
fun duplex(): DuplexTransport
```

每次调用都返回**新 adapter**。HTTP 仅复用不含 Cookie、缓存、凭据或日志拦截器的不可变
OkHttp 客户端/连接池；每个 ASR / duplex 连接拥有独立 WebSocket 和队列。
未改 providers、duplex 协议/会话叶子、Controller、Main、UI 或音频 gate。
这里不做权限申请、麦克风/播放设备、provider 猜测、凭据存取或 gate admission。
宿主仍需按现有接口负责调用生命周期和超时，不把本地 send ACCEPTED 当作服务端确认。

## 为什么有两个成熟客户端

* `com.squareup.okhttp3:okhttp:4.12.0`：HTTPS POST。官方固定发布版 README 声明
  Android API21+ / Java8+，Apache-2.0。使用平台信任链与 TLS，不自造 HTTP/TLS。
* `org.java-websocket:Java-WebSocket:1.6.0`：WSS。官方 release 为正式版
  (`prerelease=false`，2024-12-15 发布)，MIT。固定 tag 的 POM 基础 class 使用
  `release=7`，传递依赖 SLF4J 2.0.13 使用 Java8。
* `org.slf4j:slf4j-nop:2.0.13`（runtimeOnly）：MIT，与 Java-WebSocket 官方 POM 匹配。
  这是隐私约束所必需的绑定，不是日志功能。Java-WebSocket 的 TRACE 能输出完整帧，
  ERROR 能输出原始异常，因此 WSS 构造前还校验 `NOPLoggerFactory`，非 NOP 则安全失败，
  不允许宿主后来加其他日志后端使音频/头/内容进入日志。

没有用 OkHttp 的 WebSocket 来做入站限额：该版本公开 WebSocket API 在完整消息回调
之前没有可配置的帧/累计分片内存上限；回调里仅检查 `ByteString.size` 太晚。
Java-WebSocket 的 `Draft_6455(emptyList(), limit)` 会在**长度头解析后、分配 payload 前**
检查单帧大小，并在累计分片时执行 `checkBufferLimit()`。`BoundedDraft` 只在官方握手解析器
入口增加 32 KiB 有限输入检查并保留 copy 上限，不自行解析协议；避免未完成 Upgrade 无限累计。
不启用压缩扩展，避免解压膨胀。
因此第二个客户端不是冗余自造协议；OkHttp 用于具有明确 one-shot 禁重放语义的 HTTP，
Java-WebSocket 用于具有解码层入站限额的 WSS。

API26 兼容性是官方 API/源码审查结论，**不是已经在 API26 设备上验证的声明**。
Java-WebSocket 的 `onSetSSLParameters` 设置 `endpointIdentificationAlgorithm="HTTPS"`；
Android 官方标明该 setter 从 API24 可用，本项目 minSdk26。使用默认 SSLSocketFactory、
系统信任根和带真实 hostname 的标准 TLS socket；未安装 permissive trust manager，未关闭
主机名校验。连接前发布原始 TCP Socket 引用，使 DNS/connect/TLS/握手中的取消也能阻止后续 I/O。
DNS 解析本身可能不能立即中断，但取消后关闭的 socket 不会再次发送或重新连接。

## 行为约束

### HTTPS

* 固定 POST / UTF-8 JSON。RequestBody `isOneShot()=true`，禁自动重试、HTTP/HTTPS 重定向、
  authentication followup、Cookie、缓存。没有 logging interceptor。
* `retryOnConnectionFailure(false)` **单独不足以禁止** 503 + `Retry-After: 0` 的 followup。
  已核对固定版本 `RetryAndFollowUpInterceptor`：one-shot body 在 followup 前直接返回原响应，
  也抑制 421 等 POST 的重放。原始 3xx/4xx/5xx 状态由现有 provider 处理，不跟随 Location。
* 使用 `enqueue` 异步读取；协程取消调用真实 `Call.cancel()`。所有响应分支 `use` 关闭 body，
  包括取消之后才到达的响应、声明长度过大、未知长度和读取错误。
* 请求 UTF-8 最多 4 MiB；调用者响应上限必须在 1..32 MiB。声明 content-length 提前检查，
  实际流式累计到调用者上限，多一字节即失败；不调用无界 `bytes()/string()/readBytes()`。
  底层 Okio 允许一个固定大小的预读 segment，不会为未知长度响应一次性分配整个 body。
  透明 gzip 由成熟客户端处理，**解压后**字节仍经实际累计检查。
* 连接/读/写/整次调用超时分别 15/30/30/60 秒。不会在失败后发第二次生成请求。

### ASR WSS

* `connect` 等握手成功才返回。握手取消、send/receive 取消都会关闭实际 socket。
  已成功握手但尚未交给协程的资源有 continuation cancellation cleanup。
* 仅接受完整二进制消息；text、超限或入站队列溢出安全失败并中止。单帧和累计消息均受
  调用者 `maxFrameBytes` 限制，允许值 1..1 MiB；不把 TCP fragment 当消息。
* 入站最多 8 条，出站最多 8 条排队消息，另有至多一个已被 writer 取走的消息。
  出站发送 ACCEPTED 仅代表本地排队，不等待服务器确认；UNKNOWN 不重试。
* EOF 可读完已在有界队列中的二进制消息后返回 null；主动 close 丢弃队列、唤醒等待者，
  幂等、非抛错，不发会排空音频队列的优雅关闭握手。

### Duplex JSON WSS

* 每实例一次连接；重复 connect 不创建另一个计费连接。send 不等待 listener，结果为
  ACCEPTED / REJECTED / UNKNOWN；UNKNOWN 异步失败并中止，从不重放。
* 完整 text 原样交付；binary 采用严格 UTF-8 解码（非法字节失败，不能 replacement 解码）。
  JSON 事件结构仍由现有协议叶子解析，adapter 不把服务端错误正文变成异常文本。
* 每消息最多 1 MiB，回调队列最多 8 条；失败/溢出清除陈旧消息，仅异步报告 failed。
  正常 EOF 在已经排队的消息之后报告 closed；异常 WebSocket 关闭码报告 failed。
* **所有 listener 方法只在独立串行 daemon worker 上调用**，不 inline 于 connect/send，
  包括配置失败、溢出和真实客户端失败。回调抛出的普通异常也不流入 uncaught 日志。
* `closeAndDrain` 先 detach listener 和清除排队回调，再 fence 当前执行的回调并关闭实际 I/O。
  可从回调重入，不 `shutdownNow()` 中断宿主回调；并发 close 也要等待 fence/abort。
  返回后不再启动 listener 回调。网络 worker 随 socket 关闭退出，不等不可中断的 DNS；
  没有自动 reconnect，无法在取消后发起新连接。
* 底层原始 TCP 先于 SSL 关闭，再清理客户端队列，使用 force-close 而非 flush close handshake。
  自动 ping timer 已关闭，服务端 ping 的自动 pong 也受相同排队数量上限约束。

所有网络错误均使用无 cause 的固定安全消息或参数为空的 `failed()`。不打印原始 URL、头、
body、transcript、音频或 transport exception。URL 最多 8192 字符；最多 64 个头、头总长度
16 KiB；拒绝传输/代理/Cookie/WebSocket 握手保留头与 CR/LF 注入。URL 只接受 HTTPS/WSS、
无 userinfo / fragment。Query 可用于显式签名 URL，但不会出现在错误中。

## 官方资料（实现前核实，固定版本，不声称“最新”）

1. OkHttp 4.12.0 的平台要求、坐标、许可：
   <https://github.com/square/okhttp/blob/parent-4.12.0/README.md>
2. OkHttp followup / one-shot / recover 实现：
   <https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt>
3. Java-WebSocket 正式发布：
   <https://github.com/TooTallNate/Java-WebSocket/releases/tag/v1.6.0>
4. Java-WebSocket 固定 POM（Java7 base、SLF4J2.0.13）、README 日志说明与 MIT：
   <https://github.com/TooTallNate/Java-WebSocket/blob/v1.6.0/pom.xml>
   <https://github.com/TooTallNate/Java-WebSocket/blob/v1.6.0/README.markdown>
   <https://github.com/TooTallNate/Java-WebSocket/blob/v1.6.0/LICENSE>
5. 帧分配前及累计分片限制：
   <https://github.com/TooTallNate/Java-WebSocket/blob/v1.6.0/src/main/java/org/java_websocket/drafts/Draft_6455.java>
6. TLS hostname、异步 connect、出站队列、强制关闭的官方实现：
   <https://github.com/TooTallNate/Java-WebSocket/blob/v1.6.0/src/main/java/org/java_websocket/client/WebSocketClient.java>
   <https://github.com/TooTallNate/Java-WebSocket/blob/v1.6.0/src/main/java/org/java_websocket/WebSocketImpl.java>
7. Android API24 的 TLS endpoint identification：
   <https://developer.android.com/reference/javax/net/ssl/SSLParameters#setEndpointIdentificationAlgorithm(java.lang.String)>
8. SLF4J Java8 运行要求、MIT：
   <https://github.com/qos-ch/slf4j/blob/v_2.0.13/README.md>
   <https://github.com/qos-ch/slf4j/blob/v_2.0.13/LICENSE.txt>
9. OkHttp 固定 build 的 Okio3.6.0 传递依赖及 Apache-2.0：
   <https://github.com/square/okhttp/blob/parent-4.12.0/build.gradle>
   <https://github.com/square/okio/blob/parent-3.6.0/README.md>

完整适用许可及归属见 [P32-voice-network-LICENSES.md](P32-voice-network-LICENSES.md)。
最终 Gradle resolved graph（尤其已有 Kotlin 的统一版本）、APK 中 ServiceLoader/NOP 绑定与许可证
展示/打包仍由父层统一验证；此修改不以源码审查代替 APK 安装测试。

## 专属测试源码 / 实际验证状态

新增 `app/src/test/kotlin/app/daiyu/android/voice/network/`：

* `HttpVoiceNetworkTest`：新实例、禁止隐式 followup 的配置及 one-shot、UTF-8 POST、状态保留、
  已知/未知长度边界、实际 Call 取消与迟到响应关闭、原始错误脱敏、保留头拒绝。
* `SocketVoiceNetworkTest`：握手等待/取消、二进制/EOF、receive 取消、文本/超限/队列溢出拒绝、
  UNKNOWN 不重放、异步回调、严格 UTF-8、closeAndDrain 并发 fence 与回调内重入、迟到回调隔离。
* `WebSocketBoundsTest`：真实固定版本 codec 的无 payload 超大长度头拒绝、累计分片上限、
  Upgrade 累计输入上限与 copy 保留、严格 UTF-8 与 URL/头上限。不打开监听端口，不连接外部服务器。

**本实现代理未运行任何构建、测试、设备操作、业务网络请求或付费请求。**
仅使用官方公开资料研究和源码阅读。上述是已交付测试源码，不是测试通过报告。
HTTP 测试注入假 Call，socket 生命周期测试注入假 peer；真实 codec 测试也不建立网络连接。
仍待父层：编译/专属 JVM 测试、依赖与 APK 许可检查、API26 TLS 正/反向证书验证、
真实本地 HTTPS/WSS server 的无重定向/无重放、取消竞态与压力测试。未验证任何 provider
线上连通、认证、协议兼容性、音频播放、端到端通话或性能，不据此宣称功能已上线可用。
