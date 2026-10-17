# R13：浏览器已完成下载原件的显式另存为

## 范围与原事实

R10/P34 的 `fetch` 成功只表示原下载器将文件写到 app-private `downloads/UUID.download`，返回 path/size/SHA-256；不是用户已经保存到文档目录。此次只补完成产物的用户保存动作，不重写浏览器下载、网络策略或路径映射。

不修改唯一任务清单、更新说明或备份 VIEW 域；不自动公开下载，不增加存储权限、MediaStore 发布、FileProvider 或共享 mount。交换目录是另一项明确配置需求，本次没有实现。

## 生产接线

1. `BrowserDownloadCoordinator.write` 在原 `sessions.publish(ticket.lease)` 成功核验点内，登记完成记录并发出 `BrowserCompletedDownload`。对象保留原 ticket ID、完整原 lease（owner kind/session 与 session/tab generation）、size、SHA-256，但不公开可选择的源路径。
2. `AndroidBrowserRunHost` 将完成观察器接在原 Coordinator 上；`CatalogBrowserBinding.open` 只透传可信宿主观察器。
3. MAIN：`ChatController.sendBrowser -> ControllerBrowserPreparation.openOnWorker` 用预先冻结的 conversation ID 和 browser owner 取得 shelf recorder。CHILD：`ControllerChildBrowserPlan -> CatalogChildBrowserSession` 同样用独立 child grant 的原 owner 取得 recorder。模型参数、工具回执文本和历史消息均不参与授权。
4. Main 的“保存本会话浏览器下载原件”列出 controller shelf 中的 opaque ref。点击“另存为原文件”才启动 `ACTION_CREATE_DOCUMENT + CATEGORY_OPENABLE`，使用保守的 `application/octet-stream` 和 UUID 建议名；不相信 URL 推断的文件名/MIME。
5. Main 收到用户选择的 `content:` URI 后，调用 `ChatController.saveBrowserDownload -> reference.saveOriginal -> Coordinator.saveCompleted`。复用现有 `dataTask` worker 排他及 ContentResolver `openOutputStream(uri, "wt")` 模式。URI 仅交 resolver，不映射或猜测成 host path。

## 完成引用与 owner 边界

- Coordinator 按 ref **对象身份**及原完成 ledger 查找源；构造相同 ID/hash/size 的另一个对象不能通过。没有接受任意路径、反序列化模型回执为 ref、按目录扫描恢复 ref 的入口。
- shelf recorder 对照原 browser owner；保存再核 owning conversation、原会话仍存在和 controller 未关闭。Main 在选择、回调及复制每个分块前后核 conversation/editor generation/页面有效性。跨线程读取的非 Compose owner 字段使用 volatile。
- 原 lease 是完成来源证明，不是恢复执行授权。正常 run/tab 关闭后，可显式保存已完成原件；没有恢复浏览器授权、重新 fetch 或重放 ledger。已失效 lease 的迟到下载不能通过原 `sessions.publish`，不会产生合法完成记录。
- picker ref 只驻内存；每次使用不同 request code，取消/非法 URI/首次回调即消耗待选项。旧/重复回调不能命中新选择。next request code 写入 Activity state，但 ref 不恢复，重建时丢弃此域的 pending request；旧回调不会被猜配到新产物。
- Controller shelf 和每个 Coordinator 的完成库存均最多 128 项；驱逐后不可再凭旧 ref 保存。进程重启后不从历史 path/hash 回执恢复授权。文件仍留原私有目录，本次不增加清理/交换目录策略。

## 复制与失败语义

- 源仍经原 `BrowserPathMapper.recheckPrivate`、regular-file/NOFOLLOW 和 size 检查。
- 第一遍有界流式读取，核原 size/SHA-256，**通过后才打开或截断目标**；第二遍以 8192-byte buffer 原字节复制，最多原完成 size，且不超过原 Coordinator 下载上限，再核摘要与 owner。没有整件读入内存或重新编码。
- 目标 open/write/flush/close、源读/size/hash/符号链接、owner 撤销均传播失败，不返回成功文案；仅所有检查与 close 完成后才产生保存成功通知。
- 用户取消或旧 owner 回调不打开目标。文档 provider/写入中故障可能留下不完整文档，提示失败而不声称原子回滚。摘要校验的是源及交给输出流的原字节，不声称能强制不可信 provider 的落盘行为，也没有以读回 URI 扩大授权。

## 回归与当前证据

新增：

- `app/src/test/kotlin/app/daiyu/android/browser/BrowserCompletedDownloadTest.kt`：真实 Coordinator 完成记录及原字节保存、正常 run 关闭后的保存、伪造 ref 拒绝、错会话/原 owner 拒绝、有界库存、源缺失/篡改/改 size/符号链接、目标 open/write/flush/close 故障、第二遍源变化、复制中 UI owner 撤销、失败/取消下载不发布合法 ref。
- `app/src/androidTest/kotlin/app/daiyu/android/BrowserDownloadSaveActivityTest.kt`：真实 Main 生产列表/按钮、真实 ACTION_CREATE_DOCUMENT 启动/Activity 回调、真实 controller dataTask、真实 Coordinator-issued ref 和私有 in-process ContentProvider 输出流。覆盖准确字节/hash、展示不自动写、取消/缺 URI/非 content 不写、旧 editor owner、旧回调在新 picker outstanding 时拒绝、重复回调拒绝、源篡改与 provider 权限失效不报成功。测试使用真实私有完成文件作为前置条件，不伪造 exporter；不声称本测试重新覆盖 HTTP 下载算法或真实 DocumentsUI。

实现代理仅能读写工作树，未运行构建/JVM/Android 测试；上述是新增测试与静态接线证据，执行结果需主代理确认。设备 DocumentsUI 与第三方 provider 的实际交互也需后续执行验证。
