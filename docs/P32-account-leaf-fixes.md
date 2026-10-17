# P32 账户 leaf 三项修复

## 范围与来源

本次仅修改 `VoiceAccountPanel.kt`、`voice/account/DoubaoVoiceCatalog.kt`、对应测试与本文。未修改 Main、Controller、runtime、control、唯一清单或旧 Eta；未推送。依据是分配工作树中的上述源码，以及只读核对的 `PersonalVoicePreview.kt`、`ActivityVoiceAccountPreviewHost.kt`、`PersonalVoices.kt` 和既有账户测试；未取得网页资料，也未进行付费网络请求。

## 1. 任何 execute 操作先严格停试听，失败保持拒绝

- 所有既有 `execute` 入口统一经过 leaf 的 `AccountLeafPreviewGuard.execute`，在 `Dispatchers.Main.immediate` 上依次检查当前配置/前台、调用 `preview.stop()`、核对严格停止证明、再次检查当前配置，然后才进入操作 block。训练样本读取、创建、查询、导入等都在此屏障之后；不等下一次播放事件，也不依赖旧试听的异步终止回调。
- 不仅训练/查询/导入，保存/清除本机凭据、首次使用接受、本机移除以及已确认的试听启动也保守地使用同一屏障。
- `PersonalVoicePreview.stop()` 会吞 lease 的停止异常，因此 leaf 包装返回的 lease，锁存停止失败；状态被清空或旧回调迟到，都不会恢复准入。host 返回 false/抛异常同样锁存失败；本次挂载期间后续 execute 拒绝，新试听也不能绕过。

### 必须由父 host 接通的最小 seam（默认拒绝）

`VoiceAccountPanel` 尾部增加可选参数：

```kotlin
stopPreviewStrictly: () -> Boolean = { false }
```

父宿主接线时传入其**同步、主线程**严格停播/健康证明能力。该回调必须覆盖准备中请求、输出关闭与排空，以及之前被 host 自己吞掉的 strict-close/gate.cancel 失败；没有停止证明、仍在停止、输出/gate poisoned 时返回 false 或抛异常。不能仅检查 leaf 的 active=false、host active=null、异步回调是否到达，也不能无条件传 `{ true }`。没有试听时，仍须能证明宿主无未确认输出且健康，才能返回 true。

原因：只读核对的基线 host `stop(entry)` 会捕获 `gate.cancel` 异常并异步报告失败；leaf 无法从其 Unit lease.stop 单独证明物理输出停止。本次不越界修改 host，由父代理在另一路接通。未接线时默认 false，操作被明确拒绝；此默认不代表功能已完成宿主集成。失败后的 leaf 重开也不能清除宿主自身应保留的 poisoned/失败状态。

## 2. 每次试听确认都明确是在线新合成及计费风险

每次点击“试听…”均使用现有 pending confirmation，显示：

> 使用当前账户在线新合成固定文本：你好，这是当前账户音色的试听。本次试听可能收费或消耗额度；取消也未必免计费。确认仅授权本次请求。

- 不再称“读取云端示范音频”；这不是下载云端 demo，也不是本机离线回放。
- 固定文本与只读核对的基线 host `PREVIEW_TEXT` 一致：`你好，这是当前账户音色的试听。` 父 host 集成仍须保持一致。
- 每次“确认本次操作”会先清除 pending confirmation，然后才进入 execute/新请求；没有持久化免确认开关。取消仅清除本次 pending confirmation，不请求合成；已提交请求取消未必免计费。
- 保持现有 `preview.toggle` 在确认 block 内创建新 `AccountPreviewRequest` 对象的身份链。没有新增授权布尔参数；父 host 负责将本次明确授权与该请求身份纳入 admission。现有 demo 元数据/HTTPS 校验没有改成下载路径；leaf 不新增网络/播放器/gate。

## 3. 槽位列表完整性必须可证明

`DoubaoVoiceCatalog.list` 现在要求：

1. 每页显式提供 `TotalCount`；原始 JSON 值必须是 Int/Long 表示的非负整数，拒绝缺失、null、字符串、布尔、浮点数和超出范围的数字，不再 optInt 转换/默认。
2. 总数仍受既有每状态 20 页、每页 100 条（2000 条）的边界限制。超过限制立即失败，不返回部分成功。
3. 每个查询状态分别保持自己的 TotalCount，后续页不能改变。不同状态可以拥有不同总数。
4. 每页条数必须恰好等于 `min(100, total - offset)`；总数为 0 仅接受空页；非零总数的空页、提前短页、过长页、越界/多余条目均失败。
5. 槽位 ID 必须有效；同页、跨页或跨状态重复 ID 都明确失败，不再覆盖后把缩水结果当成完整列表。
6. 只有每个状态全部验证完成后才返回。任何中途异常不会返回部分成功列表，因此 `importPurchased` 在 list 失败时尚未开始循环写入本机槽位。

原测试 fixture 让 Training/Success/Active 返回空页但 TotalCount=2，且含被静默过滤的 invalid ID；已改为真实一致的 total=0/1，并另测无效 ID 必须失败。

## 对应离线测试（新增/调整，未执行）

- `VoiceAccountPanelLeafTest`：准备中/播放中，训练/查询/导入 block 之前主线程 stop→host proof；无终止回调也先停止；lease.stop 被吞失败锁存；host false/throw/默认拒绝；host 吞错但 proof=false；停止引发配置失效；每次确认 block 都产生不同请求身份；计费/固定文本确认文案。
- `VoiceCatalogTest`：缺失/null/负数/浮点/字符串/布尔/大整数 total，原始 JSON 的 0.0/0e0，空页 total>0、短页/过长页/总数矛盾、分页总数变化、同页/跨页/跨状态重复、无效 ID，正常 101 条跨两页、多状态不同 total，以及 0/100/200/2000 的精确页边界。
- 保留既有 signing/响应错误测试。未改 `PersonalVoices` 的 unknown 训练保护、凭据加密 envelope 上限或 HTTP 4xx 训练结果分类，未将 HTTP 4xx 恢复为已知训练失败。

## 验证与交付限制

本实现代理只有 UTF-8 文件工具，**没有执行 shell、Git 提交、构建、下载、网络调用或任何测试**；上面是已添加的测试覆盖，不是通过结果。已静态核对允许范围内的非空变更。父代理需审核严格停止 seam 接线、按允许的本地离线能力选择定向验证，并完成实际非空提交/合并；不得据本文宣称宿主集成或测试已通过。没有请求付费服务，不能证明真实账户权限、服务端总数快照稳定性或取消免计费。
