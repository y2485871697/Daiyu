# P19/P20 专用公网生成资源 transport（生产接线已补，待统一验证）

## 范围与证据

本轮接线任务给定基线为 `fc6227387af60cab44def691bb059a2e7fd6cb21`，已预置 `7cfe138` 下载 leaf；没有重新搬运 leaf。提交及验证由父代理统一执行，本实现代理未创建提交、未运行验证。

- 既有 `GenerationPublicNetworkHttp.kt` 复用已安装的 OkHttp **4.12.0**，没有新增依赖、权限或 trust store；原 Resources/Session 的 paired binding、DNS 固定与 cancellation abort 保留不变。
- 本轮生产修改仅 `ChatController.kt` 的两处接缝，另补 `ControllerGenerationSessionTest.kt`、`ControllerGenerationIntegrationTest.kt` 和本文。没有改 `MainActivity.kt`、总清单、CI、依赖、旧 Eta、模型 schema、DB、付费审批或下载 owner 规则。
- 所有新增 URL/key fixture 都是合成值；没有访问凭据、真实外网或付费服务。没有把模型 POST 替换成 GET 下载。

## 已补的实际生产接线（两处必须同时存在）

1. 在 `ChatController.retainGenerationResources` 的 **唯一 retained account/DB Resources** 构造处显式提供：

   ```kotlin
   ControllerGenerationResources(
       accountScope,
       { database.writableDatabase },
       { generationScope },
       File(application.filesDir, "media-message-generation"),
       downloadTransport = GenerationPublicNetworkHttp(),
   )
   ```

   Resources 仍只在首次需要时构造，普通 Stop、reader close、import 和 deletion 均不替换；同一 transport 随 account/DB Resources 强保留。原 publication gate/撤权生命周期不变。构造不做网络或 DNS。

2. `ChatController` 的 `generationFactory -> session.openLeaf` 已删除显式的
   `networkPolicy = ControllerGenerationResources.DENY_DOWNLOADS`，让 Session 使用该 Resources 的成对 binding。没有添加 allow-all policy 或 boolean 开关；其它调用若显式传 `DENY_DOWNLOADS` 仍应拒绝，这是刻意保留的防误接行为。

实际链：`ChatController.retainGenerationResources` -> retained `ControllerGenerationResources(downloadTransport)` -> `RunCatalogTools.generationFactory` -> `ControllerGenerationSession.openLeaf` -> `generationNetwork()` -> `GenerationDownloadNetworkBinding.publicNetwork` -> `ConfirmedGenerationPreparation.openLeaf` -> `RunGenerationTools(downloadAuthorization, http)`（由 `GenerationCatalogLeaf` 包装保留在 catalog）-> `GenerationIo`。模型生成 POST 和后续下载 GET 都仍由原生成协议客户端决定；同一专用 adapter 不继承聊天模型 client。

`Resources.generationNetwork(additional)` 只返回一个 `GenerationDownloadNetworkBinding`（`authorization` + `http`）；Session 一次取得同一 binding，原样传进 token.openLeaf。没有注入专用 transport 时，即使额外策略返回 true，binding 仍拒绝所有 URL。默认拒绝没有偷偷放开。不要单独拿 adapter 的 structural policy 配旧 UrlConnection transport。

原 token 仍 AND：单次确认、冻结精确下载 host、原 owner/account/run、isCurrentOwner、撤权以及 `GenerationDownloadAuthorization`。下载 host 不是连接安全替代品；此 adapter 的授权回调不做 DNS，更不把预解析当作连通证明。

## 连接安全与有限资源

- 每次 execute 新建专用 OkHttp client 和空的零 idle pool，强制 HTTP/1.1、`Proxy.NO_PROXY`、无 cache/cookies/authenticator/interceptors 继承。不同执行不共享连接；不继承模型网络 client 的认证、日志、代理或定制 TLS。
- 只接受 HTTPS DNS hostname；拒绝 userinfo、fragment、IP literal、明显本地域/非规范 authority。原 URL/hostname 始终不改成 IP；保留平台证书信任、SNI 与 hostname verifier，没有宽松 verifier/trust manager。
- `GenerationPinnedPublicDns.lookup` 是 OkHttp 选择实际 socket route 时调用的 hook。只允许请求原 host；一次性验证 **全部** DNS 答案（非空、最多 64 个、任何私有/特殊地址混入均整组拒绝），复制保存原地址集合，仅向 OkHttp 返回固定首地址的不可变 singleton，避免地址 fallback；后续调用不能重新解析或 rebind。
- IPv4 拒绝私网、loopback、link-local/metadata、CGNAT、测试/文档、benchmark、多播与保留地址；IPv6 保守仅允许 2000::/3 global-unicast，额外拒绝 special 2001::/23、文档、6to4/6bone 等。ULA/link-local/mapped/NAT64 特殊前缀不能以 IPv6 绕过；JDK 已归一成 IPv4 的答案按 IPv4 规则验证。部分真实公网特殊用途地址也会保守拒绝，不能绕过安全性换兼容。
- 禁重定向、禁连接重试。**不只依赖 retryOnConnectionFailure=false**：network interceptor 在 RetryAndFollowUpInterceptor 之前 abort/close 所有非 2xx 并抛无敏感信息的状态异常；execute 转成原 HTTP status + 空 body，覆盖 GET 408/421/503（含 Retry-After:0）、30x、认证 follow-up。
- 30 秒 connect、120 秒 read/write、180 秒 call timeout。成功 body 使用 identity 编码，不透明解压；先查 Content-Length，再每次最多读到预算 + 1 字节，未知/chunked 长度同样有上限；请求上限不超过已有视频 100 MiB。仍遵守现有 GenerationHttpResponse 字节数组契约，不声称实现了磁盘流式资产协议；内存复制有限但视频内存开销仍需设备验证。
- 只有现有 generation 请求所需的 Accept/Content-Type/Authorization 可传入，没有 cookie/proxy header 入口。普通 CDN 下载的 headers 仍由原 GenerationIo 清空；仅原始已验证的 `/videos/{id}/content` 可以收到其原 token。没有新增跨 host 凭据传递，重定向不可能转发认证。
- URL、signed query、认证与 DNS 原始异常不写日志、不加入异常 cause；仅抛固定 redacted 信号。响应 MIME 有 256 字符上限，错误 body 不读入内存。

## 取消与真实 drain 的边界

- execute 在任何 DNS/connect 前 `cancellation.begin()` 并注册 sticky `Call.cancel`，成功 response/非 2xx 关闭都走原 execution cleanup 计数；finally 才 finish/evict pool。显式先关闭 `body.source()`，不能仅用 OkHttp ResponseBody.close 的 closeQuietly 吞掉 IOException；正常 close 异常传播，cleanupFailures 继续使原叶子的 awaitClose 返回 UNKNOWN；不能把 cancel 当 Drained。
- DNS 返回后再检查取消；取消期间不得返回 socket route，测试使用不会真正 connect 的 SocketFactory 观察零 socket。
- **平台 DNS 可能不可中断**。没有把它移到后台后虚报 drain：lookup 尚未返回时执行仍 active，父的有界 awaitClose 可返回 UNKNOWN 并保留原 run/resources。OkHttp call timeout 无法保证 native DNS 按 180 秒返回；因此不承诺 DNS 的严格墙钟上限。DNS 返回后取消路径零内容请求，父不得自动重试/复活该 run。
- 公网地址分类不等于能证明设备/VPN/运营商把数据包物理路由到哪里；OS 路由与信任根仍属平台信任边界。无代理/固定公网目的地址能阻断应用层任意内网目标，不扩大原设备或模型权限。

## 测试交付与未完成项

**本任务未运行编译、测试、lint、构建、模拟器、真实 TLS 或外网请求；以下仅为测试源码，不是通过结果。**

本轮新增的离线测试源码：

- `ControllerGenerationIntegrationTest.testProductionRetainedResourcePairsSamePublicTransportAndKeepsReviewedHostOwnerGate`：由真实 ChatController 构造 Resources，经真实 Runner/catalog、Session、token 到 RunGenerationTools，断言 transport/leaf/owner/task 的对象身份；冻结精确 host 可以通过，但子域、其它 host、IP、异 account 和未持久化调用不获执行 owner 权限；Stop 后授权撤回而 Resources/transport 身份保留。模型保持等待，不发 tool event，不执行网络；此测试同时捕获漏注入与遗留显式 DENY 两种回归。
- `ControllerGenerationSessionTest.sessionPairsRealTransportWithExactReviewedHostOwnerAndOneShotToken`：真实 adapter（非 GenerationHttp 假实现）成对传递；精确 host、异 run/account、isCurrentOwner、重复 open 和 revoke；构造零 DB I/O。
- `sessionWithoutTransportCannotBeEnabledByAllowPolicyAndExplicitDenyStillNarrows`：无 transport 的 Resources 即使传 allow policy，经 Session 到真实 leaf 的授权仍拒绝；注入后显式 DENY 仍收窄。
- `stoppedUnknownSessionRetainsRealPairedLeafAndRejectsDispatchWithoutResend`：Stop + 父持久化/清理 UNKNOWN 后，用原 call ID 和新 call ID 经真实 dispatcher 调用都返回 `generation_closed`；不触达 DB/网络，保留 token/Resources/leaf/transport，cleanup 不重复、UNKNOWN 不升级，也不能 reopen。这里的 UNKNOWN 是父 receipt/drain 不确定，不冒称模拟了已发出的在途 HTTP。

既有下载 leaf 测试源码覆盖（本轮未改动、未重跑）：

- IPv4/IPv6 非公网范围、混合答案整组拒绝、空/过多/失败 DNS、失败 sticky、固定首地址与不可变 snapshot。
- 真正 OkHttp route 选择流程（假 Dns + 覆盖 connect 且立即抛出的 Socket）：无预解析、目的地址来自验证结果、仅一次 connect、混合答案零 socket。
- 专用 client 的 NO_PROXY/HTTP1/cookies/auth/cache/timeout/pool/默认 verifier 设置。
- 预算 + 1 的流式读取、已经取消时零读取、非法 URL 的脱敏异常与 drain。
- 全部非 2xx（尤其 408/421/503 与 Retry-After:0）在 follow-up 返回前关闭并抛固定状态异常。
- Resources 默认拒绝，不能用独立 allow-policy 打开；额外策略只收窄，异常 fail closed。
- 通用 abort 跨执行先于 stream close、late stream 再 abort、cleanup failure 保留、DNS 阻塞取消时真实 active 与返回后零 socket。

父仍须：审阅 diff、创建提交并统一集成；运行新/原 GenerationCancellation、Preparation、Session、GenerationIo 和媒体/DB 定向回归；补真实 TLS 正反向、超时、在途读写取消和严格 close 设备验证。完整单测/Lint/APK/API26 与最终业务验收均未由本实现代理运行；此清单只是尚未验证项，不要求本子任务构建 APK 或跑全量测试。没有把 P19/P20 协议支持范围、历史媒体迁移或 UI 展示判为整体完成。

来源：专属 adapter 来自已预置下载 leaf；网络主体复用项目已有 OkHttp/Okio 依赖，其既有许可证/NOTICE 沿用。没有复制其它仓库网络实现，也没有声称 OkHttp 是自研。
