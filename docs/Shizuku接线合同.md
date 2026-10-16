# Shizuku 后端与界面接线合同

本批仅实现后端。授权按钮、状态展示、后端选择和任务执行入口由界面接线方实现；未接好前不得展示“Shizuku 已完整可用”。不改 minSdk 26，不把 Shizuku 授权当成 Root 授权或任务写操作授权。

## 固定依赖与真实接口

- `dev.rikka.shizuku:api:13.1.5`、`provider:13.1.5`，MIT；provider 按官方 authority/跨用户权限保护配置，未申请该系统签名权限。
- api/provider/shared/aidl 制品 SHA-256 分别为 `4def9bde498ef8626614c2fc5db9af4749c86f16f6c33e3f5658d35e70bab59b`、`b0f18cd9812464ec171c53cac93a819fe411718a3965c311f01eb4de265381b3`、`4659642c9339be0a26e9c65bb8648f7ad6d8f4a465f557993ccbc78802381635`、`33fe7191cdd69fcb66d649264f3b0c47acb2f3d6343afc05b98dbbff6f221963`。
- 版本只取 SDK 握手元数据；实时权限直接调用当前 `IShizukuService.checkSelfPermission()`，不使用 SDK 的授权成功缓存判断撤销。
- 应用单例入口为 `(application as ChatApplication).shizuku`。读取 `state` 无 I/O；`refresh/requestPermission/connect/openBackend/execute/poll/cancel` 均在工作线程调用，不能阻塞 Compose/主线程。

## 授权界面

1. 进入页面后工作线程 `refresh()`，将不可变返回值交回 UI；不能把“已安装 Shizuku”当作服务运行或已授权。
2. `UNAVAILABLE`：提示服务不可用；`UNSUPPORTED`：版本低于 13 或身份不受支持；`PERMISSION_REQUIRED`：显示明确授权按钮；`AUTHORIZED`：显示观测身份；`CLOSED`：管理器已关闭。
3. 只有用户明确点击授权按钮才调用 `requestPermission()`。SDK 显示系统授权对话框；回调会使旧状态失效。界面重新获得焦点/用户刷新时再次 `refresh()`，不能靠原缓存显示授权成功。没有自动轮询或 UI 订阅 API。
4. 授权成功不自动执行命令或连接 UserService。用户明确选择执行后端后才 `connect(expectedUid)`：`2000` 为 ADB shell，`0` 为 root。expectedUid 无默认值，必须由可信宿主显式传入；实际 UID 与选择不一致即拒绝，不自动改选、不降级到普通 shell/root/Linux。
5. `state.connected` 仅代表当前连接仍在本地有效范围，不代表设备命令成功；身份显示取已观测 `connection.identity.uid/kind`。

## 执行与任务边界

- 获得连接后由可信宿主创建 `connection.openBackend(hostScope)`；scope 必须是 `[A-Za-z0-9_-]{1,128}`，同一连接只能绑定一个 scope。宿主必须把真实 task/run owner、精确写效果授权与取消信号接入后再向模型开放工具，本批没有注册模型命令工具或新增默认写权限。
- `hostScope` 是 journal 命名和连接绑定键，不是授权凭证；UserService 的 owner Binder 是客户端存活令牌，不是 task/run owner。真实 owner 和精确授权须由可信宿主逐次新派发核验，不能由模型提供的 scope、UID 或脚本文本自行声明。run 取消、owner 失效或授权撤销时须封口关闭所属连接，不跨 run 转交后端或旧 handle。
- `ShizukuCommand(requestId, script, timeoutMillis)`；requestId 必须是宿主稳定的操作 ID，超时默认 30 秒、范围 1..120000 ms；脚本最多 131072 UTF-8 字节、拒绝 NUL。不能收到 Unknown 后换 ID 重试同一操作。
- `execute` 的 `Dispatched` 只表示已接收派发，不是执行完成；保留原 handle，调用 `poll`。`Completed(exitCode)` 才是进程退出与输出收尾已确认，非零退出码按真实值处理。`NotExecuted` 与 `Unknown` 分别呈现，未知不可当作失败后安全重放。
- `poll(handle, offset, limit)` 返回原始字节，limit 最大 65536；使用 `nextOffset` 继续读；UTF-8 解码方必须处理跨页多字节。总保留 262144 字节，`truncated` 表示保留截断，`outputComplete` 表示实际输出 EOF，两者不同。
- 每服务代最多留存 64 个 ID，最多 4 个真实在途任务；达到上限拒绝，不驱逐旧 ID 重跑。同 ID 不同参数冲突；OperationJournal 在派发前持久预留，同 scope 的 UID/service/permission 绑定变化拒绝重用。
- 每次派发/poll/cancel 均重新查询服务端权限。Binder 断开、撤销、服务代变化或 host close 后，旧连接/handle 不复活，不自动重连重试。
- `cancel` 返回 Unknown，只请求停止本后端拥有的 shell，不声称回滚副作用或清理已脱离的后代进程。`close()` 立即封口，异步请求解绑/销毁，不是物理回收确认；SDK/服务不可用时回收未确认。宿主如需严格资源关闭回执，仍须接中央 run 生命周期，不能提前释放写操作责任。
- 连接内的后端发布与关闭使用同一状态机：同 scope 并发打开只构建一个后端；关闭不等待构造中的 journal I/O，立即封口并唤醒等待者。关闭后才完成构造的候选由原构造路径关闭，不发布、不转交新 scope；构造失败不能复活已关闭连接。已发布后端仍按自身 close 语义收尾，不新增物理回收确认。
- 这是无 PTY、无 daemon 的 Android Shizuku 执行接口，不是 Linux 后端；不能支持交互终端、常驻服务或跨环境自动重放。

## 验证范围与界面验收剩余项

- 本批增加纯 JVM 的 gate/engine 测试；Android 仪器覆盖真实 Bundle/持久 journal、假 endpoint 回执契约、真实应用 UID shell 输出/退出/超时、Provider 配置及拒绝应用 UID 冒充特权服务。
- 假 endpoint 用于可控失败场景，不是实际 Shizuku 服务；应用 UID shell 测试不是特权执行证据。真实服务授权、拒绝、撤销、ADB/root 两种身份、服务重启和跨进程绑定，需要界面入口接好后在有 Shizuku 的真机验收。
- 后续 UI 交付需同时确认：授权按钮真实触发；拒绝不显示成功；服务停止状态及时刷新；显示真实身份；后端选择参数传到底；撤销后旧 scope 不执行；停任务时关闭连接；Unknown 不自动重放；没有新增默认写权限。
