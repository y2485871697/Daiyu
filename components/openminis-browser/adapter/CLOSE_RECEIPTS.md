# R10 / P34：关闭回执接缝（候选，未执行构建/测试）

先核对 `docs/重构清单.md`：R10 为待集成，P34 / T01 为待迁移。本增量不改变这些状态，不修改注册器、Controller、Main、设置、权限、固定源或网络算法。读取的是分配工作树源码；未通过 Git 元数据独立验证用户提供的主线 `6ae9adb`。

## 给 RunCatalogCloseResult 的 API

```kotlin
val first = browserRunHost.closeWithResult() // 请求关闭，立即返回快照
val later = browserRunHost.closeStatus()    // 只读取状态，不重放操作、不等待
```

返回 `app.daiyu.android.browser.network.BrowserCloseResult`：

* `closeRequested: Boolean`：所有被聚合的宿主已开始关闭。
* `pending: Set<String>`：尚无真实退出证据的阶段；不是 Future 的 done 状态。
* `failures: Set<String>`：固定、不含 URL / 路径 / Cookie / Throwable 消息的失败码。
* `drained` = 已请求关闭且 pending 为空。
* `successful` = drained 且 failures 为空。**drained 不等于成功。**

父接线可在自己的有界关闭预算内轮询 `closeStatus()`；到期仍有 pending 应保留未排空/UNKNOWN，不能因为 `close()` 已返回、命令 Future 已完成或取消而记成功。API 不创建轮询线程、不泵 Looper、不在锁中等待 I/O。`closeWithResult()` 可再调以重试被拒绝的 UI 入队或 cookie 文件删除；普通 `closeStatus()` 没有这些副作用。

阶段码：`BROWSER_RUN_INVALIDATION`、`WEBVIEW_CREATION`、`WEBVIEW_CLEANUP`、`WEBVIEW_EXECUTION`、`COOKIE_WORK_OR_FILES`、`FETCH_CALLER_OR_FILES`、`HTTP_RAW_IO_OR_CLEANUP`。失败码来自对应阶段（例如 `WEBVIEW_DESTROY`、`COOKIE_FILE_DELETE`、`HTTP_NATIVE_CLEANUP`），而不是原始异常文本。

`BrowserRunConstructionException.closeWithResult()` 保留构造中已取得的 web/cookie/HTTP/fetch 对象，可继续请求并观察半成品清理；包含 `BROWSER_RUN_CONSTRUCTION` 失败码，不能当作成功构造。此前的 root/grant 预检仍按原异常失败，尚未取得这些资源。异常 cause 只给可信宿主，不应序列化到模型/日志。

## 退出证据与保留的不变量

* `LeaseBrowserHost` 的 `created` Future 可能在取消时提早结束；另有 `making` 只在实际 `makeBlank` 的 finally 清零。cleanup closure 实际执行完成及执行 coroutine 的 finally 独立计数。关停仍留 allocation 墓碑，不允许复用旧 lease，不将 UNKNOWN 改成确认执行。
* `AndroidOpenMinisBrowserHost` 只增加代理 API，并把已分配 WebView 的布局初始化移到受管 `initialize()`：初始化抛错时 Lease 仍拥有可销毁的资源。未重写 WebView / manager /导航算法。底层 WebView 构造器自身在返回对象前抛出的内部资源，无法由 Kotlin 拿到句柄；不声称能证明这类平台内部回收。
* destroy 尝试仅一次，错误保留；只重试被拒绝的排队，不重放 native destroy。`WEBVIEW_DESTROY` 沿用原 cleanupFailure 聚合，也可包含该 closure 的订阅/取消失败。
* fetch caller 的 finally 返回前不释放请求；HTTP 沿用原算法的引用计数，caller、raw action、disconnect、stream.close 全部真实返回才移除 operation。没有 `Future.done` / cancel 冒充 drain；原连接/清理线程若永久不返回，pending 永久保留，不扩线程绕过。
* cookie 引用计数覆盖排队 start、文件 writer、晚到 UI publication 和 native setCookie 回调；所有引用退出并且私有 `.part`/`.env` 删除成功才移除请求。删除失败保留请求可重试；已观察到的失败码保留。CookieManager 已提交的全局副作用不可撤销，永不重放 setCookie。
* `successful` 只证明本宿主追踪的调用/清理结束，**不证明 WebView 操作系统进程已退出、不证明全局 CookieManager 持久化/撤销，也不删除已成功交付的 fetch 下载文件**。

## 回归源码（本次未运行）

* `LeaseBrowserHostTest` 新增 4 项：已完成命令不等于 cleanup 执行、工厂帧未返回、半成品 initialize/destroy 失败不重放、拒绝入队及显式重试。
* `BrowserCookieBackendTest` 新增 4 项：取消 Future 后排队 start 仍在、真实文件删除与迟到 Cookie callback、writer 中 close、删除失败保留/重试且不重放 Cookie。
* `BrowserFetchBackendTest` 新增 2 项：忽略中断的 raw worker 与 disconnect 分别滞留、native cleanup 错误在 drain 后保留且不重放。

以上为确定性假连接/队列源码，未执行构建、测试、网络或 GUI。既有真实 HTTP/Android 测试不因本次源码变动自动获得通过证据；由主代理统一构建与接线核验。
