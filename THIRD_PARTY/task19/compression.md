# T06：压缩移植与方法参考分开记录

基线ae6e834；旧冻结发布f001fb82，上游比较c16a5873。子任务fe0c2e5f核对冻结引入补丁与现源码，未改算法或执行测试。

## 已核自研小单元

`conversationCompressionBlocksSend`来自旧ui/app/ConversationCompressionSendPolicy.kt及12项测试，首次引入94ffe9322cf8596b87eec26cccdba7c92a556bdf，迁移记录docs/reuse-compression-policy.md、dc3971f。新core-domain同名类仅包名/必要public适配。它是会话限定发送门禁，不是整个预算器/压缩器原创证明。null不是全会话通配；无关会话不被共享队列阻塞。

## 已有本地引入线索（不是第三方source commit）

| 单元 | 本地引入证据 | 原源缺项 |
|---|---|---|
| AgentContextBudget.kt | ca61aec3c4d3d1038775b273f4c6556fcc3de25f明写ported from OpenMinis，codepoint/3、图片网格、从新到旧裁剪、8k预留 | OpenMinis实际预算器source commit/path/license；不能借浏览器来源 |
| 中文估算 | d2b37f0a5220be7ff9f842613746363d4c43193a按Operit方式，CJK约1.5/其它约0.25 | 原始出处/版本/片段与许可；不能断言逐字移植 |
| 保尾/摘要结构 | a3b4f67c489160b9695b189b72bb49d909e3df33，AgentCompressionStrategy/Boundary记录DeepSeek harness、retainRatio0.16 | 具体官方仓库/固定版本/文字或代码对应及许可 |
| AgentContextCompactor.kt | 8ce4924cfb67bb1925c5218a8bd756bb75d2ba6b标题明示ported from RikkaHub | 拟保留片段、独立RikkaHub固定源路径/许可；现地址改编NOTICE不覆盖 |

完整压缩/统计功能由用户要求保留；以上线索不把整文件全部增量归第三方，也不支持整模块全自研表述。fork独有提交或符号未命中不是完整原创证明。

现ContextPolicy默认UTF-8估算与keepTurns fallback不是旧OpenMinis/Operit规则和完整工具调用ID边界/16%策略的原样迁移证明。ContextService compressionBoundary接缝可用；不能由默认实现声称旧策略获准替代。

## 5号最小交接

实际保留函数/常量/提示词分类（自研/代码移植/文字改编/方法参考）、旧发布完整SHA/path/blob或SHA256、本地引入差分、新目标path/候选SHA、第三方source commit/path/LICENSE/NOTICE、保留片段映射及原测试证据。缺原源可先交精确目标片段和线索，不猜官方仓库，不重写算法。

证据根 `/workspace/daiyu-origin-audit/`：policy/ConversationCompressionSendPolicy.94ffe932.patch、policy/compression-topology.json、frozen-preserve-f001fb82/context/git-evidence/AgentContextBudget.introduction.patch；full-detail-f001fb82/introductions/{d2b37f0a,a3b4f67c,8ce4924c}.patch及targeted-evidence/compression-strategy-introduction.patch。这些是本地引入证据，三方原源精度仍待补。
