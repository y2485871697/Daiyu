# P05 余额保留迁移：独立生产增量与宿主接缝

## 范围与验证状态

本轮在已有加密 store/runtime 基础上增量收口，不另起框架。修改仅限 `app/.../balance/`、该模块测试及本文；**未修改** MainActivity、ChatController、ModelSettings、数据库、共同 schema、总清单、依赖、CI 或冻结参考原件。父代理只应取这些生产文件，不把 `docs/reference-preserved/` 当生产源码提交。

本代理只有 UTF-8 工作树工具，没有 Shell、Git、构建/设备/网页工具。**没有执行编译、JVM、设备、Compose 渲染测试，没有提交 Git 或触发 CI**。以下为已落地实现和静态检查，不是“P05 已主界面接通/全部通过”的声明；最终由父代理统一验证并接入入口。

## 实际读取的冻结来源

本轮已能读取普通路径 `docs/reference-preserved/provenance.json`，以及下表中的全部源码、Fetcher/Coordinator/Indicator Kotlin 测试和 Python 样式合同；未读 `.agent/` 或历史自有源码 活树。provenance 各项均声明冻结提交 `f001fb82eb9941e5673970c656f5c4d5370fbf7f`。下列 blob 标识来自该清单，父代理已提供逐 blob 核验背景；本工具不能执行 hash，**未独立重新计算 blob/SHA256**。

路径均相对 `docs/reference-preserved/`：

| 冻结路径尾部 | git blob | 生产承接 |
| --- | --- | --- |
| `model/.../data/model/BalanceOption.kt` | `8fc01d7ca1a5cc3bb1bda815d35873ad66ba3c2e` | `balance/BalanceOption.kt` 四预设、模板填充、New API 固定值及切回恢复 |
| `model/.../data/repository/ProviderBalanceFetcher.kt` | `818004cdde6370b34b74b861b83b492bb3d04569` | `balance/ProviderBalanceFetcher.kt` URL、路径索引、表达式；已有 HTTP transport 继续复用 |
| `model/.../data/repository/ProviderBalanceCoordinator.kt` | `a134c670441593479c7339df43923248b0f94fd3` | 现有 Store/Runtime 内合流、成功缓存、delta、配置隔离；`ProviderBalancePoller` 生命周期逻辑 |
| `model/.../data/repository/ProviderBalanceStore.kt` | `780fa3ff2124fc76d93ec7326ec4686578fa1890` | 30 秒轮询、丰富状态和只暴露成功金额的投影，独立配置替代旧 ProviderRepository 依赖 |
| `ui/.../pages/providers/ProviderBalanceComponents.kt` | `7dc615f877722e3d3b5dce796e064742deb0f86f` | 独立页面内 `BasicComponent`、`WindowSpinnerPreference`、路径字段/密钥显隐/测试操作，`ProviderBalanceIndicator.kt` 静态合同 |
| `ui/.../pages/providers/ProviderBalanceAnimation.kt` | `ebcb4e63ed10aae0e3c424b043725a94a1cf79b0` | `balance/ui/ProviderBalanceAnimation.kt` 原三档绘制层动画、合并/过时/系统动画开关策略 |

参考测试：Coordinator `a442006c4954a40bc7836c361b331b5e06022320`，Fetcher `a3934105d9867224a035d7efbcf66abc02ca40f2`，Indicator `1e5b99c9681bcdd7b964b2ef1d00e001469ebd43`，Python 样式合同 `e4323c05a081ce7db0292f5729f40670a487cbf0`。生产测试按新包/独立配置承接其断言，不运行冻结文件。

## 保留策略与必要适配（不是保守禁用）

- **四预设保留**：自定义、New API、Sub2API、DeepSeek；New API 固定 `api/user/self` 与 `data.quota / 500000`，保留原自定义路径以便切回；另两种只在留空时填模板，仍可手改。旧 `userId` 字段按原样保留，原 Fetcher 并未把它自动拼成请求头，生产也不发明这一行为；页面另提供明确的可选 `New-Api-User` 请求头。
- **原查询算法保留**：完整 URL 覆盖、origin/OpenAI 后缀处理、用户接口 origin-relative 规则；点路径、`[index]`、点号数字索引、带空格 `+ - * /`。保留原右递归和 Double 算术语义（例如 `100 / 2 / 2` 得 100），未擅自换成有优先级的新表达式语言。New API quota 是服务端额度的明确单位换算，**不是 token 用量估算**。
- **真实金额边界**：直接 JSON 数字/数字字符串保留十进制原文并转 BigDecimal；只有有限数值能进入金额槽。null/缺少金额不造 0；`N/A`/任意服务端文字/错误正文不再冒充金额。此项是独立金额投影边界，不禁止预设/表达式。展示沿原 locale 分组、两位小数（使用 BigDecimal 避免金额精度丢失）；`project().balanceText` 另给宿主精确、不估算的带币种文本。
- **缓存/变化保留**：刷新、取消、网络/HTTP 失败不清空最后成功金额；只有成功推进检查时间。两次同配置成功数值比较产生递增 seq 和新值减旧值的 delta；相等值保留原事件，失败不丢比较基线。切账户/任何显式重存/删除清空金额和差值，不跨账户动画。恢复磁盘缓存不重放旧事件。
- **轮询保留且可用**：`runtime.start(ownerScope)` 明确启动原 30 秒前台轮询，首次立即查询；重复 start 不产生第二 poller，owner 取消/stop 后可重启；配置 revision 变化跟随新配置，禁用/删除停止请求。没有“永久只准手动”的替代政策。构造、读盘、收集 state、页面加载不偷偷联网；手动与轮询共用一条准入门禁，避免重叠请求。
- **原 UI/动画保留**：静态指示器只有成功金额才占位，失败/刷新不插状态词、不按年龄变色、不响应点击；Miuix `onSurfaceVariantSummary`、`footnote1` 与原 12dp 图标尺寸。宿主已有 `DaiyuIcons.Balance` 代替冻结快照未提供的 `MoneyBag02` 图标定义。顶部可选择动画：四舍五入后不足 0.01 不提示，扣费小于 1 为轻档，达到 1 为滚数/抖图标/短红/加粗弹出；增加只绿色飘字；5 秒过时不播、系统关闭动画不播、首次进入不重播、打断后合并 delta、飘字零尺寸不改测量。仅滚数绘制使用 Float，实际金额/差值均 BigDecimal。
- **原型向后兼容**：原加密 v1 记录没有 option 时，继续按精确 URL + RFC6901 读取，页面明确标注并要求用户点击后才切换到预设/表达式；不把 `/balance` 静默改成点路径。新增 option/headers/lastSuccess 仍在整条加密记录内，无 schema/DB 变更。

安全适配：不再依赖模型 AgentHttpClient、ProviderRepository 或聊天 Key，配置内独立 `apiKey` 和可选独立 `accessToken` 的覆盖次序保留；两者明确留空时沿原行为不加 Authorization（支持无需鉴权的接口），绝不回退到聊天密钥。可选自定义请求头显式随配置加密，拒绝 Host/Authorization/Cookie/连接控制等覆盖。生产 HTTPS-only、无日志、无缓存、无 cookie、无认证续发、无重定向/连接重试；保留原 15 秒总时限与 256 KiB 解压后上限，继续使用原型 one-shot 网络门禁防 OkHttp 503/408 内建 follow-up；协程取消到 Call.cancel，读取完正文前不脱离取消。

测试按钮查询**已明确保存的独立配置**，不是从编辑草稿复用聊天 provider。脏草稿必须先保存，避免界面显示账号 B 却发出账号 A 凭据；页面使用 composition scope，离开取消其手动请求，不沿 Context 链悄悄借长生命周期作用域。长生命周期刷新改由父明确拥有的 poller scope。页面加载按 revision 取消旧账户读取，并在新 revision 草稿加载完前不展示旧凭据。

## 保留的加密与迟到结果保护

`EncryptedBalancePersistence` 继续使用 Android Keystore AES-GCM + AtomicFile + `noBackupFilesDir`，配置、账户、URL、Key、token、headers、金额、错误种类整条加密；读不创建密钥；明确写入专属新 alias，读回后清理旧 alias。缺钥/损坏是 STORAGE，不是余额 0。没有任何备份导出接口。

Store ticket 绑定进程实例 identity、UUID revision、具体 pending 对象；完成前校验，过时/重复/foreign/cancelled 回包不写盘。保存/删除/reload 均撤销旧 ticket，Runtime 同时取消 active Job。即便测试假 query 不配合取消，在新账号成功后才返回旧账号金额，也不能写入或产生跨账号 delta。保存失败当前实例 fail closed，须明确重存；不偷偷用旧凭据。失败记录和最后成功缓存都在加密包中恢复；缓存带原检查时间，不宣称实时。

**单进程每 namespace 唯一实例**，保留跨 Activity 重建；不支持多个 Store 同时写同一 namespace。当前独立页面管理一个明确选定的独立余额账户，不枚举/读取模型 provider；如宿主未来需要多独立账户，各用单独 namespace/runtime/owner，互不等待、不得共享同一盘文件。

## 父代理最小接线（本轮不改受限宿主）

包和入口均已存在：

```kotlin
import app.daiyu.android.balance.BalanceRuntime
import app.daiyu.android.balance.BalanceConfiguration
import app.daiyu.android.balance.BalanceOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.daiyu.android.balance.ProviderBalanceStore
import app.daiyu.android.balance.EncryptedBalancePersistence
import app.daiyu.android.balance.ui.BalanceSettingsScreen
import app.daiyu.android.balance.ui.ProviderBalanceIndicator

// 在应用进程宿主保留一次；首次 Keystore/磁盘读取放 IO，不在 Composable 中反复构造。
val runtime = withContext(Dispatchers.IO) {
    BalanceRuntime(ProviderBalanceStore(EncryptedBalancePersistence(applicationContext)))
}

// 父将 P05 点击 route 接到真实 Composable；无新 Activity/manifest/模型参数。
BalanceSettingsScreen(runtime = runtime, onBack = { /* 返回父路由 */ })

// 观察 runtime.state。首选直接插入顶部栏金额槽；选择器 animateChanges=false。
ProviderBalanceIndicator(state = balanceState, animateChanges = true)
// 若现有 top bar 只能接受 String?：只给 balanceState.project().balanceText。
// statusText 仅专页状态区，绝不能把 controller.problem、token 或估计支出放入金额槽。
```

生命周期：父创建**可取消的前台 ownerScope**，在 owner 活跃时 `runtime.start(ownerScope)`，离开前台/owner 结束时 `runtime.stop()` 并取消该 ownerScope。不要传永久 GlobalScope；同一 runtime 不要有两个并行 owner。恢复前台用新 scope 调 start 即可；UI 重组不重启。手动刷新可用 `runtime.manualRefresh()` 或 `runtime.requestRefresh(ownerScope)`；构造/读盘本身不联网，**已 start 的轮询收到保存的新 revision 后会明确使用新配置查询**。

只需传 `runtime` 与 `onBack` 到独立页；没有 parent model/account key 参数。父不必改变数据库/模型 schema。若父需要程序化写入独立配置：

```kotlin
runtime.save(BalanceConfiguration(
    accountId = "explicit-balance-account", endpoint = "https://balance.example/v1",
    apiKey = "<independent balance key>", amountPointer = "", currency = "CNY",
    option = BalanceOption(enabled = true, preset = BalanceOption.PRESET_DEEPSEEK),
))
```

`endpoint` 在 option 非空时是本账户明确保存的 Base URL；`amountPointer` 只用于 v1 兼容模式，新的 option 使用 resolved.resultPath。`currency` 是对接口金额单位的明确配置，不能推断、换汇或从 token 成本推导。不要将配置/异常写日志或 saved-state。所有变更走 Runtime 的 save/clear/reload，不绕过 revision gate。

**入口仍待父接**：FeatureCatalog 旧 P05 route、Main 当前错误串占余额槽不在本代理修改范围，不能因存在独立 screen 就声称已接通主界面。Controller 没有必须新增的模型/凭据/schema 参数；若主线保留生成结束后的主动刷新事件，父在该事件桥调用既有 `runtime.requestRefresh(ownerScope)` 即可，不把 Controller 错误或 token 用量输入金额投影。本叶子未接 Main/Controller，也不包含主线近期记忆/消息赋时改动。

## 离线测试产物与待验收项

- 原 `ProviderBalanceStoreTest`：0/Unknown、重开、重存 revision、切账号、删除/reload/cancel、重复/foreign ticket、持久化 fail-closed。
- 新 `PreservedBalancePolicyTest`：冻结四预设/URL/路径/算术与结合规则、成功缓存与时间、delta/seq/恢复不重播/跨账号重置、option/headers/token 回读。
- 新 `BalanceRuntimeTest`：构造/保存/收集不联网、合流、取消可再查、不配合取消的旧账号迟到结果零写盘、虚拟时间 30秒轮询/配置跟随/禁用/幂等/重启、owner 取消确实终止请求。
- 新 `BalanceHttpQueryTest`：内存 Call.Factory 请求/鉴权/响应夹具；实际十进制精度、0/unknown/非金额、非法/重复 JSON/UTF8/超限、HTTP 安全错误种类、Call 取消和迟到 callback、客户端无重定向/重试/缓存及超时配置。**不访问真实网络或付费接口**。
- 新 `ProviderBalanceIndicatorTest`：冻结只成功金额占位、失败/刷新继续显示金额、原动画阈值和 delta 格式、中性只读源码合同、独立 screen 最小签名/无 saveable/无隐式 start 合同。这不是 Compose 渲染测试。
- 扩展 Android `EncryptedBalancePersistenceTest`：随机专属 namespace；整条加密、旧 v1 恢复、缺钥读取不建钥、篡改拒收，以及 option/token/headers/失败缓存全加密恢复。不读写用户/模型 alias。

父统一运行编译和上述 JVM/设备测试；仍需实际 Compose 测量/主题/字体/减弱动画/合并事件行为、Activity 重建与后台前台 owner 生命周期、父 route/topbar 接线验证。内存 HTTP 夹具并非真实 TLS/超时集成测试；不得据此宣称已验证真实服务或进行付费探测。
