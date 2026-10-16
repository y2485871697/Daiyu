# P09 非视觉页面 owner 接线

`CompressionSettingsEditor` 只拥有一次页面实例的准入和结果身份，不持有 Compose 状态、线程池或路由。构造不打开 preferences；所有 store 构造、配置读取、凭据解密、保存及回读都在 `Work.runWorker()` 内。这里只使用 v2 压缩 store 和独立摘要 profile store，不读主模型。

## 最小 API

- `CompressionSettingsEditor(context)`：父页面用 application context 构造，每次进入页面新建，不能复用已 revoke 的实例。
- `prepareLoad(): Work?`
- `prepareSave(SaveRequest(settings: ProductionCompressionSettings, profile: SummaryProfileSettings? = null, protocolChangeAuthorized: Boolean = false, requestOptionsReplacementAuthorized: Boolean = false)): Work?`
- `work.runWorker(): Result`：一次性，仅在父 worker 执行。`prepare*` 返回 null 表示忙或已撤销，不应再排任务。
- `revoke()`：单向、幂等、不阻塞；离页时立即调用。
- `isCurrent(result)`：在 UI 真正消费结果时调用。校验页面身份、操作身份、未撤销；不要只在投递回调前检查。

示意（线程池、UI 投递、忙状态均由父提供）：

```kotlin
val work = editor.prepareLoad() ?: return
worker.execute {
    val result = work.runWorker()
    postToMain {
        if (editor.isCurrent(result)) {
            when (val outcome = result.outcome) {
                is CompressionSettingsEditor.Outcome.Loaded -> applySnapshot(outcome.snapshot)
                is CompressionSettingsEditor.Outcome.Saved -> showConfirmedSave(outcome.snapshot)
                is CompressionSettingsEditor.Outcome.Failed -> showFailure(outcome.stage)
                CompressionSettingsEditor.Outcome.Revoked -> Unit
            }
        }
    }
}
```

Compose 可 `remember` owner，并在 `DisposableEffect(owner)` 的 `onDispose` 中 `owner.revoke()`。父的返回按钮应先 revoke 再导航，不能仅等待 dispose。保存与加载采用同一种投递方式。页面忙时禁用重复加载/保存；加载失败后仍可明确提交修复草稿。若父线程池拒绝执行，父负责报错并撤销该 owner（或重建页面 owner），不要留下排队忙状态。离页不要通过 shutdownNow 或取消已准入 work 中断持久化。

## 草稿、身份和结果

字符串解析及显示属于 UI；将显式数值构成 `ProductionCompressionSettings` / `SummaryProfileSettings`，捕获其参数校验错误，不要把空白输入转换成猜测窗口/budget。`automatic=false` 或 `contextWindowTokens=null` 均支持，不产生隐式启用策略。

已有 profile 的 `binding` 必须保留。新 profile 由父在**新草稿创建时**生成一次 opaque `SummaryModelBinding`，随后保存在草稿并同时用于 `settings.summaryModel`、`profile.binding`；不要在每次点击保存时生成。两个 binding 不同会在访问 store 前失败。不保存/修改摘要 profile 时传 `profile=null`；这不清除既有 profile，取消选择应显式写 `settings.summaryModel=null`。

协议切换、损坏请求策略替换的授权位默认都是 false。仅当用户明确选择对应动作后传 true，普通保存按钮不自动授权；传入的是完整、独立、关闭工具的摘要 profile，不是主模型的局部覆盖。所有数值/协议/tool 校验继续由现有类型和 store 实施。

`Result.identity` 是 opaque 页面+操作身份。新页面永远不能接收旧 owner 的结果；同页准备了新操作后也不能接收旧操作结果。`Loaded.snapshot` 包含 settings、可选 profile、可选 `profileUnavailableReason`：选中 profile 损坏时仍可显示压缩设置供修复，不回退主模型。

保存先写可选 profile，再写压缩设置，最后回读并比较。仅 `Saved` 可显示已确认保存；选中 profile 回读不可用、内容改变、写入未确认都会是 `Failed`，即使压缩设置已经写入。两个 store 之间**不是跨 store 事务**：`Failed.writesMayHaveOccurred` 为 true 时可能部分写入，勿承诺回滚、勿自动重试。失败不暴露原始异常文本；不要记录 draft、profile、凭据或 provider 错误。

worker 从 queued 到 admitted 与 revoke 使用同一个 CAS 状态机。revoke 先赢：任务不触及任何 store；worker 先赢：允许整次保存及回读跨离页完成，但其结果不再可用于 UI。

## 测试状态

新增 `app/src/test/kotlin/app/daiyu/android/context/CompressionSettingsEditorTest.kt`：使用真实配置 store、Robolectric preferences、合成独立凭据和确定性 queue，覆盖 queued/revoke/admitted、身份隔离、默认/显式授权传递、无窗口/关闭保存、部分失败和回读拒绝等。实现代理未运行测试/构建；由父代理按需执行该测试类，不构建 APK 或运行全量测试。
